package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.AlbumArtThemeDao
import com.lostf1sh.pixelplayeross.data.database.AlbumArtThemeEntity
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtColorAccuracy
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtPaletteStyle
import com.lostf1sh.pixelplayeross.presentation.components.RecentlyPlayedSection
import com.lostf1sh.pixelplayeross.presentation.model.RecentlyPlayedSongUiModel
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B42-fix3, item 3: **a cover's palette is computed once, and never asked for from composition.**
 *
 * The reviewer's trace had `computePalette` 742 times in 35 s on the main thread, in bursts of 40-60 at each song change, and read it as the Library's
 * rows asking for palettes. It is not ours: the name is not in the debug APK's dex, the slices sit directly under `AndroidOwner:draw` (the draw phase, not
 * composition) and cost 0.0067 ms each (5 ms in the 35 s); it is the platform's, called as views are drawn. Our own palette is the theme holder's, made off
 * the main thread by [ColorSchemeProcessor] under a single flight and cached by cover (memory, then Room).
 *
 * What the investigation did find in our code is the same shape in a smaller place: **Home's "Recently played" pills each asked the theme holder for
 * their album's colour scheme from composition, once per pill, and threw the answer away** (the pill has been flat graphite since B29; nothing is
 * drawn with it). Each pill on screen started a palette request for an album that was not the song's. Gone.
 *
 * Held here: the pills ask for nothing; and the processor under a song change computes the new cover once, however many callers (the song change, a sheet,
 * a preload) ask, and a cover already made is not made again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h900dp-xxhdpi")
class B42Fix3PaletteOncePerCoverTest {
    @get:Rule
    val compose = createComposeRule()

    private fun song(i: Int) = Song(
        id = "id-$i", title = "Cancion $i", artist = "Mägo de Oz", artistId = 7L, album = "Album $i", albumId = 100L + i, path = "/m/$i.flac",
        contentUriString = "content://$i", albumArtUriString = "content://art/album$i", duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    @Test
    fun home_s_recently_played_pills_ask_for_no_palette_from_composition() {
        // six pills, six different albums: the old pill asked the holder for each album's colour scheme
        val holder = mockk<ThemeStateHolder>(relaxed = true)
        every { holder.getAlbumColorSchemeFlow(any(), any()) } returns MutableStateFlow<ColorSchemePair?>(null)
        val items = (0 until 6).map { RecentlyPlayedSongUiModel(song(it), lastPlayedTimestamp = 1_000L + it) }.toImmutableList()
        compose.setContent {
            DapTheme {
                RecentlyPlayedSection(
                    songs = items,
                    onSongClick = {},
                    onOpenAllClick = {},
                    /*THEME_HOLDER*/
                    currentSongId = "id-2",
                )
            }
        }
        compose.waitForIdle()
        verify(exactly = 0) { holder.getAlbumColorSchemeFlow(any(), any()) }
        verify(exactly = 0) { holder.ensureAlbumColorScheme(any()) }
    }

    // ── the processor: one computation per cover ────────────────────────────────────────────────────

    private class MemoryDao : AlbumArtThemeDao {
        val rows = ConcurrentHashMap<Pair<String, String>, AlbumArtThemeEntity>()
        override suspend fun insertTheme(theme: AlbumArtThemeEntity) {
            rows[theme.albumArtUriString to theme.paletteStyle] = theme
        }
        override suspend fun getThemeByUriAndStyle(uriString: String, paletteStyle: String): AlbumArtThemeEntity? = rows[uriString to paletteStyle]
        override suspend fun deleteThemesByUris(uriStrings: List<String>) {
            rows.keys.removeAll { it.first in uriStrings }
        }
    }

    private fun pair(seed: Int) = ColorSchemePair(
        light = androidx.compose.material3.lightColorScheme(primary = androidx.compose.ui.graphics.Color(seed)),
        dark = androidx.compose.material3.darkColorScheme(primary = androidx.compose.ui.graphics.Color(seed)),
    )

    private fun <T> blocking(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(20_000) { block() } }

    @Test
    fun a_song_change_computes_one_palette_for_the_new_cover_however_many_ask_and_none_for_the_rest() = blocking {
        val processor = ColorSchemeProcessor(ApplicationProvider.getApplicationContext(), MemoryDao())
        val computed = ConcurrentHashMap<String, AtomicInteger>()
        processor.generator = { uri, _, _, _ ->
            computed.getOrPut(uri) { AtomicInteger() }.incrementAndGet()
            kotlinx.coroutines.delay(30)
            pair(uri.hashCode() or (0xFF shl 24))
        }
        val style = AlbumArtPaletteStyle.default
        val accuracy = AlbumArtColorAccuracy.DEFAULT

        // the song starts: the holder's request, a sheet's own and a preload all ask for the same cover at once
        val first = (0 until 8).map { async(Dispatchers.Default) { processor.getOrGenerateColorScheme("content://art/a", style, accuracy) } }.awaitAll()
        assertThat(first.all { it != null }).isTrue()
        assertThat(computed["content://art/a"]!!.get()).isEqualTo(1)

        // the next song: one palette, for its cover only; the first cover is not touched
        val second = (0 until 8).map { async(Dispatchers.Default) { processor.getOrGenerateColorScheme("content://art/b", style, accuracy) } }.awaitAll()
        assertThat(second.all { it != null }).isTrue()
        assertThat(computed["content://art/b"]!!.get()).isEqualTo(1)
        assertThat(computed["content://art/a"]!!.get()).isEqualTo(1)
        assertThat(computed.keys).containsExactly("content://art/a", "content://art/b")

        // back to the first: made already, so not made again (memory, then Room)
        processor.getOrGenerateColorScheme("content://art/a", style, accuracy)
        processor.clearMemoryCache()
        processor.getOrGenerateColorScheme("content://art/a", style, accuracy)
        assertThat(computed["content://art/a"]!!.get()).isEqualTo(1)
    }
}
