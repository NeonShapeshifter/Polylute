package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.net.Uri
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.AlbumArtThemeDao
import com.lostf1sh.pixelplayeross.data.database.AlbumArtThemeEntity
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtColorAccuracy
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtPaletteStyle
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreference
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreferencesRepository
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B31 P1.7 (auditor R30-F1-03): **a regeneration that something newer has replaced must not leave its colours in the cache.**
 *
 * B30-fix1 stopped the *holder* from publishing a stale regeneration, but the processor wrote every result it computed into the
 * cover's cache entry (memory and Room), whatever had happened since. Two regenerations of the same cover and settings (a cover
 * replaced under a stable URI) that finished in the wrong order left the **older** one's colours in the entry, and the next ordinary
 * request read them back and published them as current.
 *
 * The real [ColorSchemeProcessor] runs here (its memory cache, its Room path through an in-memory DAO, its single flight and its
 * currency); only the computation of a palette from a cover is held and released by the test, so each pair completes in **both** orders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PaletteCacheCurrencyTest {
    private val cover = "content://cover/a"
    private val tonal = AlbumArtPaletteStyle.default
    private val accuracy = AlbumArtColorAccuracy.DEFAULT
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun stop() = scopes.forEach { it.cancel() }

    private fun pair(seed: Int) = ColorSchemePair(
        light = lightColorScheme(primary = Color(seed)),
        dark = darkColorScheme(primary = Color(seed)),
    )

    private val oldPair = pair(0xFF112233.toInt())
    private val newPair = pair(0xFF445566.toInt())

    private class MemoryDao : AlbumArtThemeDao {
        val rows = ConcurrentHashMap<Pair<String, String>, AlbumArtThemeEntity>()
        @Volatile var inserts = 0
        override suspend fun insertTheme(theme: AlbumArtThemeEntity) {
            rows[theme.albumArtUriString to theme.paletteStyle] = theme
            inserts++
        }
        override suspend fun getThemeByUriAndStyle(uriString: String, paletteStyle: String): AlbumArtThemeEntity? = rows[uriString to paletteStyle]
        override suspend fun deleteThemesByUris(uriStrings: List<String>) {
            rows.keys.removeAll { it.first in uriStrings }
        }
    }

    /** One call of the computation the test holds: it says when it began, and returns what the test hands it. */
    private class Held {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<ColorSchemePair?>()
    }

    private class Rig(val dao: MemoryDao, val processor: ColorSchemeProcessor, val held: List<Held>)

    private fun rig(calls: Int): Rig {
        val dao = MemoryDao()
        val processor = ColorSchemeProcessor(ApplicationProvider.getApplicationContext(), dao)
        val held = List(calls) { Held() }
        var next = 0
        processor.generator = { _, _, _, _ ->
            val mine = held[synchronized(held) { next++ }]
            mine.started.complete(Unit)
            mine.release.await()
        }
        return Rig(dao, processor, held)
    }

    private fun <T> blocking(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(20_000) { block() } }

    /** What an ordinary request gets from the cache now: from memory, then (memory cleared) from Room. */
    private suspend fun cachedNow(r: Rig): Pair<Color?, Color?> {
        val fromMemory = r.processor.getOrGenerateColorScheme(cover, tonal, accuracy)?.dark?.primary
        r.processor.clearMemoryCache()
        val fromRoom = r.processor.getOrGenerateColorScheme(cover, tonal, accuracy)?.dark?.primary
        return fromMemory to fromRoom
    }

    // ── Two regenerations of the same cover, in both orders ─────────────────────────────────────────────

    @Test
    fun `the later regeneration ends first and the older one ends after it so the cache keeps the later one, in memory and in Room`() = blocking {
        val r = rig(calls = 2)
        val older = async(Dispatchers.Default) { r.processor.getOrGenerateColorScheme(cover, tonal, accuracy, forceRefresh = true) }
        r.held[0].started.await()
        val newer = async(Dispatchers.Default) { r.processor.getOrGenerateColorScheme(cover, tonal, accuracy, forceRefresh = true) }
        r.held[1].started.await()

        r.held[1].release.complete(newPair)
        assertThat(newer.await()?.dark?.primary).isEqualTo(newPair.dark.primary)
        r.held[0].release.complete(oldPair)
        // Whoever asked for the older one still gets its own result back ...
        assertThat(older.await()?.dark?.primary).isEqualTo(oldPair.dark.primary)
        // ... but it did not go where the next ordinary request reads.
        val (memory, room) = cachedNow(r)
        assertThat(memory).isEqualTo(newPair.dark.primary)
        assertThat(room).isEqualTo(newPair.dark.primary)
        assertThat(r.dao.inserts).isEqualTo(1)
    }

    @Test
    fun `the older regeneration ends first and the later one after it so the later one is what is cached`() = blocking {
        val r = rig(calls = 2)
        val older = async(Dispatchers.Default) { r.processor.getOrGenerateColorScheme(cover, tonal, accuracy, forceRefresh = true) }
        r.held[0].started.await()
        val newer = async(Dispatchers.Default) { r.processor.getOrGenerateColorScheme(cover, tonal, accuracy, forceRefresh = true) }
        r.held[1].started.await()

        r.held[0].release.complete(oldPair)
        older.await()
        // A newer one has begun: the older one's result is not written, so nothing is cached yet.
        assertThat(r.dao.inserts).isEqualTo(0)
        r.held[1].release.complete(newPair)
        newer.await()
        val (memory, room) = cachedNow(r)
        assertThat(memory).isEqualTo(newPair.dark.primary)
        assertThat(room).isEqualTo(newPair.dark.primary)
    }

    // ── An ordinary request against a regeneration, in both orders ──────────────────────────────────────

    @Test
    fun `an ordinary request that began before a regeneration and ends after it does not replace the regenerated palette`() = blocking {
        val r = rig(calls = 2)
        val ordinary = async(Dispatchers.Default) { r.processor.getOrGenerateColorScheme(cover, tonal, accuracy) }
        r.held[0].started.await()
        val forced = async(Dispatchers.Default) { r.processor.getOrGenerateColorScheme(cover, tonal, accuracy, forceRefresh = true) }
        r.held[1].started.await()

        r.held[1].release.complete(newPair)
        forced.await()
        r.held[0].release.complete(oldPair) // read the cover before it was replaced
        assertThat(ordinary.await()?.dark?.primary).isEqualTo(oldPair.dark.primary)
        val (memory, room) = cachedNow(r)
        assertThat(memory).isEqualTo(newPair.dark.primary)
        assertThat(room).isEqualTo(newPair.dark.primary)
    }

    @Test
    fun `an ordinary request that began while a regeneration ran and ends after it does not replace it either`() = blocking {
        val r = rig(calls = 2)
        val forced = async(Dispatchers.Default) { r.processor.getOrGenerateColorScheme(cover, tonal, accuracy, forceRefresh = true) }
        r.held[0].started.await()
        val ordinary = async(Dispatchers.Default) { r.processor.getOrGenerateColorScheme(cover, tonal, accuracy) }
        r.held[1].started.await()

        r.held[0].release.complete(newPair)
        forced.await()
        r.held[1].release.complete(oldPair) // decoded the cover from the image cache the regeneration was bypassing
        ordinary.await()
        val (memory, room) = cachedNow(r)
        assertThat(memory).isEqualTo(newPair.dark.primary)
        assertThat(room).isEqualTo(newPair.dark.primary)
    }

    @Test
    fun `an ordinary request that ends before anything else began is cached, as it always was`() = blocking {
        val r = rig(calls = 1)
        val ordinary = async(Dispatchers.Default) { r.processor.getOrGenerateColorScheme(cover, tonal, accuracy) }
        r.held[0].started.await()
        r.held[0].release.complete(oldPair)
        ordinary.await()
        val (memory, room) = cachedNow(r)
        assertThat(memory).isEqualTo(oldPair.dark.primary)
        assertThat(room).isEqualTo(oldPair.dark.primary)
    }

    @Test
    fun `a generation that began before the cover's palette was invalidated cannot put it back`() = blocking {
        val r = rig(calls = 1)
        val running = async(Dispatchers.Default) { r.processor.getOrGenerateColorScheme(cover, tonal, accuracy) }
        r.held[0].started.await()
        r.processor.invalidateScheme(cover)
        r.held[0].release.complete(oldPair)
        running.await()
        assertThat(r.dao.rows).isEmpty()
        assertThat(r.dao.inserts).isEqualTo(0)
    }

    // ── Through the holder: what an ordinary request publishes afterwards ───────────────────────────────

    @Test
    fun `through the holder, after two regenerations ended in the wrong order the song publishes the newer palette`() = blocking {
        val r = rig(calls = 3)
        val preferences = mockk<ThemePreferencesRepository>(relaxed = true)
        every { preferences.playerThemePreferenceFlow } returns MutableStateFlow(ThemePreference.ALBUM_ART)
        every { preferences.albumArtPaletteStyleFlow } returns MutableStateFlow(tonal)
        every { preferences.albumArtColorAccuracyFlow } returns MutableStateFlow(accuracy)
        val holder = ThemeStateHolder(r.processor, preferences)
        val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        holder.initialize(collectors)

        val first = async(Dispatchers.Default) { holder.forceRegenerateColorScheme(cover) }
        r.held[0].started.await()
        val second = async(Dispatchers.Default) { holder.forceRegenerateColorScheme(cover) }
        r.held[1].started.await()
        r.held[1].release.complete(newPair)
        second.await()
        r.held[0].release.complete(oldPair)
        first.await()

        // The song is asked for afterwards, the way a skip back to it would: the cache answers, and the answer is the newer palette.
        holder.extractAndGenerateColorScheme(Uri.parse(cover), cover)
        assertThat(holder.currentAlbumArtColorSchemePair.value?.dark?.primary).isEqualTo(newPair.dark.primary)
    }
}
