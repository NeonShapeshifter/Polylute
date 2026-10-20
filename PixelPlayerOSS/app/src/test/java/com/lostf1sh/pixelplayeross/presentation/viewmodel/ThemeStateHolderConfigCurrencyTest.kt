package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.net.Uri
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtColorAccuracy
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtPaletteStyle
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreference
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B30-fix1 H4 (auditor R29-F1-01): **the newest configuration wins.** The global palette's currency used to be the cover and a request
 * generation that only moves with the cover. A forced regeneration of the **same** cover that began under the old extraction style
 * kept a valid cover and a valid generation, so when it ended after the new style's palette was published it published the old
 * style's colours over it. A result may now become the global palette only while the cover, the request, the style, the accuracy, the
 * settings epoch and the cover's regeneration are all still the ones it began under; what an older configuration computed stays in the
 * processor's own cache entry for it and replaces nothing that is shown.
 *
 * Every case here is a pair of operations completing in **both** orders, for the same cover, and for A → B → A.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ThemeStateHolderConfigCurrencyTest {

    private fun uri(text: String): Uri = Uri.parse(text)
    private fun pair(seed: Int) = ColorSchemePair(
        light = lightColorScheme(primary = Color(seed)),
        dark = darkColorScheme(primary = Color(seed)),
    )

    private val tonal = AlbumArtPaletteStyle.default
    private val vibrant = AlbumArtPaletteStyle.VIBRANT
    private val styleFlow = MutableStateFlow(tonal)
    private val accuracyFlow = MutableStateFlow(AlbumArtColorAccuracy.DEFAULT)
    private val processor = mockk<ColorSchemeProcessor>()

    /** Cover A's palettes, by the settings they were computed under and whether they were forced. */
    private val aTonal = pair(0xFF000001.toInt())
    private val aTonalForced = pair(0xFF000002.toInt())
    private val aVibrant = pair(0xFF000003.toInt())
    private val aVibrantForced = pair(0xFF000004.toInt())
    private val aVibrantForcedAgain = pair(0xFF000005.toInt())
    private val bTonal = pair(0xFF000006.toInt())

    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun stopCollectors() = scopes.forEach { it.cancel() }

    private fun TestScope.holder(): ThemeStateHolder {
        val preferences = mockk<ThemePreferencesRepository>(relaxed = true)
        every { preferences.playerThemePreferenceFlow } returns MutableStateFlow(ThemePreference.ALBUM_ART)
        every { preferences.albumArtPaletteStyleFlow } returns styleFlow
        every { preferences.albumArtColorAccuracyFlow } returns accuracyFlow
        coEvery { processor.invalidateScheme(any()) } returns Unit
        val collectors = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }
        return ThemeStateHolder(processor, preferences).also { it.initialize(collectors) }
    }

    /**
     * What the processor answers: [gate] may hold a call (cover, style, accuracy, forced, ordinal of the call) until a test completes
     * it; any call it does not hold gets the cover's palette for those settings at once.
     */
    private fun answers(gate: (String, AlbumArtPaletteStyle, Int, Boolean, Int) -> CompletableDeferred<ColorSchemePair?>?) {
        val calls = AtomicInteger()
        // The processor's cache, as far as these tests need it: a forced regeneration replaces the entry for its settings, and an
        // ordinary call that is not held answers from it (a held call answers what the test completes it with, as a slow one reads the
        // cache when it begins and hands it back when it ends).
        val cache = java.util.concurrent.ConcurrentHashMap<Triple<String, AlbumArtPaletteStyle, Int>, ColorSchemePair>()
        coEvery { processor.getOrGenerateColorScheme(any(), any(), any(), any()) } coAnswers {
            val cover = firstArg<String>()
            val style = secondArg<AlbumArtPaletteStyle>()
            val accuracy = thirdArg<Int>()
            val forced = arg<Boolean>(3)
            val key = Triple(cover, style, accuracy)
            val result = gate(cover, style, accuracy, forced, calls.incrementAndGet())?.await() ?: when {
                !forced && cache[key] != null -> cache.getValue(key)
                cover != "content://a" -> bTonal
                forced && style == tonal -> aTonalForced
                forced -> aVibrantForced
                style == tonal -> aTonal
                else -> aVibrant
            }
            if (forced && result != null) cache[key] = result
            result
        }
    }

    // ── The auditor's case, and its mirror ───────────────────────────────────────────────────────────────

    @Test
    fun `a regeneration begun under the old style that ends after the new style's palette does not replace it`() = runTest(StandardTestDispatcher()) {
        val oldRegeneration = CompletableDeferred<ColorSchemePair?>()
        answers { _, style, _, forced, _ -> if (forced && style == tonal) oldRegeneration else null }
        val holder = holder()
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(aTonal)

        launch { holder.forceRegenerateColorScheme("content://a") }   // under the old style, and slow
        advanceUntilIdle()
        styleFlow.value = vibrant                                      // the new style's refresh publishes at once
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(aVibrant)

        oldRegeneration.complete(aTonalForced)                         // the old regeneration ends last
        advanceUntilIdle()

        assertThat(holder.currentAlbumArtUri.value).isEqualTo("content://a")
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(aVibrant)
    }

    @Test
    fun `the new style's refresh that ends after a regeneration under that style does not replace the regeneration`() = runTest(StandardTestDispatcher()) {
        val slowRefresh = CompletableDeferred<ColorSchemePair?>()
        answers { _, style, _, forced, _ -> if (!forced && style == vibrant) slowRefresh else null }
        val holder = holder()
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()

        styleFlow.value = vibrant                                      // the refresh begins and waits
        advanceUntilIdle()
        launch { holder.forceRegenerateColorScheme("content://a") }   // a regeneration under the new style, at once
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(aVibrantForced)

        slowRefresh.complete(aVibrant)                                 // the older refresh ends last
        advanceUntilIdle()

        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(aVibrantForced)
    }

    @Test
    fun `an older regeneration of the same cover that ends after a newer one does not replace it, and the other order is the same`() = runTest(StandardTestDispatcher()) {
        // Order 1: the first regeneration is slow and the second is quick; the second's palette must stay.
        val firstSlow = CompletableDeferred<ColorSchemePair?>()
        answers { _, _, _, forced, call -> if (forced && call == 2) firstSlow else if (forced && call == 3) null else null }
        val one = holder()
        advanceUntilIdle()
        launch { one.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()
        launch { one.forceRegenerateColorScheme("content://a") }     // call 2: waits
        advanceUntilIdle()
        launch { one.forceRegenerateColorScheme("content://a") }     // call 3: answers at once with the forced palette
        advanceUntilIdle()
        assertThat(one.currentAlbumArtColorSchemePair.value).isEqualTo(aTonalForced)
        firstSlow.complete(aTonal)                                    // the older one ends last, with something else
        advanceUntilIdle()
        assertThat(one.currentAlbumArtColorSchemePair.value).isEqualTo(aTonalForced)
    }

    @Test
    fun `two regenerations that end in the order they began leave the newest, too`() = runTest(StandardTestDispatcher()) {
        val first = CompletableDeferred<ColorSchemePair?>()
        val second = CompletableDeferred<ColorSchemePair?>()
        answers { _, _, _, forced, call -> if (forced && call == 2) first else if (forced && call == 3) second else null }
        val holder = holder()
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()
        launch { holder.forceRegenerateColorScheme("content://a") }
        advanceUntilIdle()
        launch { holder.forceRegenerateColorScheme("content://a") }
        advanceUntilIdle()
        first.complete(aTonal)
        advanceUntilIdle()
        second.complete(aTonalForced)
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(aTonalForced)
    }

    // ── An ordinary request that the settings overtake ───────────────────────────────────────────────────

    @Test
    fun `an ordinary request begun under the old style, finished after the style changed, is computed again under the new one`() = runTest(StandardTestDispatcher()) {
        val slowOrdinary = CompletableDeferred<ColorSchemePair?>()
        answers { _, style, _, forced, _ -> if (!forced && style == tonal) slowOrdinary else null }
        val holder = holder()
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }   // slow, under the old style
        advanceUntilIdle()
        styleFlow.value = vibrant                                                              // nothing published yet: no refresh to do
        advanceUntilIdle()

        slowOrdinary.complete(aTonal)                                                          // it ends with the old style's palette
        advanceUntilIdle()

        // Not the old style's colours, and not nothing: the request went round again under the style that is set now.
        assertThat(holder.currentAlbumArtUri.value).isEqualTo("content://a")
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(aVibrant)
        coVerify { processor.getOrGenerateColorScheme("content://a", vibrant, any(), any()) }
    }

    @Test
    fun `an ordinary request overtaken by a regeneration of its own cover is computed again after it, not published stale`() = runTest(StandardTestDispatcher()) {
        val slowOrdinary = CompletableDeferred<ColorSchemePair?>()
        val regenerationGate = CompletableDeferred<ColorSchemePair?>()
        answers { _, _, _, forced, call -> if (!forced && call == 2) slowOrdinary else if (forced) regenerationGate else null }
        val holder = holder()
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }   // call 1: the cover is published
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(aTonal)
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }   // call 2: the same song again, slow
        advanceUntilIdle()
        launch { holder.forceRegenerateColorScheme("content://a") }                           // call 3: a regeneration, waits
        advanceUntilIdle()

        regenerationGate.complete(aTonalForced)                                                // the regeneration ends first and publishes
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(aTonalForced)
        slowOrdinary.complete(aTonal)                                                          // the older, un-regenerated palette ends last
        advanceUntilIdle()

        // It went round again after the regeneration and got the regenerated palette from the cache; what it was holding never replaced anything.
        assertThat(holder.currentAlbumArtUri.value).isEqualTo("content://a")
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(aTonalForced)
    }

    // ── A, B, A ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `A then B then A again with the style changed in between, the first request of A never replaces the last`() = runTest(StandardTestDispatcher()) {
        val firstA = CompletableDeferred<ColorSchemePair?>()
        answers { cover, style, _, forced, call -> if (cover == "content://a" && !forced && style == tonal && call == 1) firstA else null }
        val holder = holder()
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }   // the first A: slow
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://b"), "content://b") }
        advanceUntilIdle()
        styleFlow.value = vibrant
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }   // A again, under the new style
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(aVibrant)

        firstA.complete(aTonal)                                                                // the first A ends last
        advanceUntilIdle()

        assertThat(holder.currentAlbumArtUri.value).isEqualTo("content://a")
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(aVibrant)
    }

    // ── The accuracy has the same key as the style ───────────────────────────────────────────────────────

    @Test
    fun `a regeneration begun under the old accuracy does not replace the palette the new accuracy published`() = runTest(StandardTestDispatcher()) {
        val oldRegeneration = CompletableDeferred<ColorSchemePair?>()
        answers { _, _, accuracy, forced, _ -> if (forced && accuracy == AlbumArtColorAccuracy.DEFAULT) oldRegeneration else null }
        val holder = holder()
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()
        launch { holder.forceRegenerateColorScheme("content://a") }
        advanceUntilIdle()
        accuracyFlow.value = 5                                          // the new accuracy's refresh publishes at once (the default palette)
        advanceUntilIdle()
        val published = holder.currentAlbumArtColorSchemePair.value
        assertThat(published).isEqualTo(aTonal)

        oldRegeneration.complete(aTonalForced)
        advanceUntilIdle()

        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(published)
    }

    // ── The per-cover flows follow the same rule ─────────────────────────────────────────────────────────

    @Test
    fun `a regeneration under settings that are gone does not fill a cover's own palette flow, one under the current settings does`() = runTest(StandardTestDispatcher()) {
        val oldRegeneration = CompletableDeferred<ColorSchemePair?>()
        answers { cover, style, _, forced, _ -> if (cover == "content://b" && forced && style == tonal) oldRegeneration else null }
        val holder = holder()
        advanceUntilIdle()
        val flow = holder.getAlbumColorSchemeFlow("content://b", eager = false)   // a list item's flow, for a cover that is not playing
        launch { holder.forceRegenerateColorScheme("content://b") }               // under the old style: waits
        advanceUntilIdle()
        styleFlow.value = vibrant
        advanceUntilIdle()
        oldRegeneration.complete(bTonal)
        advanceUntilIdle()
        assertThat(flow.value).isNull()

        launch { holder.forceRegenerateColorScheme("content://b") }               // under the current style: answers at once
        advanceUntilIdle()
        assertThat(flow.value).isEqualTo(bTonal)
    }

    // ── What the settings and the covers already did stays as it was ─────────────────────────────────────

    @Test
    fun `a style change on the song that keeps playing still refreshes its palette, and a song with no cover stays without one`() = runTest(StandardTestDispatcher()) {
        val refresh = CompletableDeferred<ColorSchemePair?>()
        answers { _, style, _, forced, _ -> if (!forced && style == vibrant) refresh else null }
        val holder = holder()
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()
        styleFlow.value = vibrant
        advanceUntilIdle()
        refresh.complete(aVibrant)
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(aVibrant)

        launch { holder.extractAndGenerateColorScheme(null, null) }
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isNull()
        assertThat(holder.currentAlbumArtUri.value).isNull()
    }
}
