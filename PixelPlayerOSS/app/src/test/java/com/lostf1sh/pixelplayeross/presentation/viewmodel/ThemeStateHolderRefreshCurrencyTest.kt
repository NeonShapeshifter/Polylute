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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * B29-fix1 F4 (auditor R29-03): the palette a change of extraction style or accuracy refreshes must not become the global palette of a
 * song that is no longer the one playing.
 *
 * The preferences collector looked up the current cover, waited for the refresh and published it unchecked; by then the listener could be
 * on another song, and the holder held song B's cover with song A's colours (a combination the consumers' own check by cover cannot see:
 * the cover is B's). Every publisher of the global palette now asks the same question as the ordinary request: is this still the
 * current request, for this cover. The per-cover cache still receives what the refresh computed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ThemeStateHolderRefreshCurrencyTest {

    private fun uri(text: String): Uri = Uri.parse(text)
    private fun pair(seed: Int) = ColorSchemePair(
        light = lightColorScheme(primary = Color(seed)),
        dark = darkColorScheme(primary = Color(seed)),
    )

    private val styleFlow = MutableStateFlow(AlbumArtPaletteStyle.default)
    private val accuracyFlow = MutableStateFlow(AlbumArtColorAccuracy.DEFAULT)
    private val processor = mockk<ColorSchemeProcessor>()

    private val a0 = pair(0xFF000001.toInt())
    private val a1 = pair(0xFF000002.toInt())
    private val b0 = pair(0xFF000003.toInt())
    private val b1 = pair(0xFF000004.toInt())

    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun stopCollectors() = scopes.forEach { it.cancel() }

    /**
     * The holder's collectors live in a scope of their own on the test's scheduler. Not `backgroundScope`: `advanceUntilIdle` stops when only
     * background tasks are left, so a collector there answered a change of setting after the steps that followed it.
     */
    private fun TestScope.holder(): ThemeStateHolder {
        val preferences = mockk<ThemePreferencesRepository>(relaxed = true)
        every { preferences.playerThemePreferenceFlow } returns MutableStateFlow(ThemePreference.ALBUM_ART)
        every { preferences.albumArtPaletteStyleFlow } returns styleFlow
        every { preferences.albumArtColorAccuracyFlow } returns accuracyFlow
        val collectors = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }
        return ThemeStateHolder(processor, preferences).also { it.initialize(collectors) }
    }

    /** What the processor answers: the palette of each cover for the default style at once; for another style, [gates] decides. */
    private fun answers(gates: (String, AlbumArtPaletteStyle) -> CompletableDeferred<ColorSchemePair?>?) {
        coEvery { processor.getOrGenerateColorScheme(any(), any(), any(), any()) } coAnswers {
            val cover = firstArg<String>()
            val style = secondArg<AlbumArtPaletteStyle>()
            gates(cover, style)?.await() ?: if (cover == "content://a") a0 else b0
        }
    }

    private val tonal = AlbumArtPaletteStyle.default
    private val vibrant = AlbumArtPaletteStyle.VIBRANT

    @Test
    fun `A then a style change then B, and A's refresh completes last, the cover and the palette stay B's`() = runTest(StandardTestDispatcher()) {
        val refreshOfA = CompletableDeferred<ColorSchemePair?>()
        answers { cover, style -> if (cover == "content://a" && style == vibrant) refreshOfA else null }
        val holder = holder()
        advanceUntilIdle()

        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(a0)

        styleFlow.value = vibrant            // the refresh of A starts and waits
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://b"), "content://b") }
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtUri.value).isEqualTo("content://b")
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(b0)

        refreshOfA.complete(a1)              // A's refresh, after the skip
        advanceUntilIdle()

        assertThat(holder.currentAlbumArtUri.value).isEqualTo("content://b")
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(b0)
    }

    @Test
    fun `a style change on the song that keeps playing still refreshes its palette`() = runTest(StandardTestDispatcher()) {
        val refreshOfA = CompletableDeferred<ColorSchemePair?>()
        answers { cover, style -> if (cover == "content://a" && style == vibrant) refreshOfA else null }
        val holder = holder()
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()

        styleFlow.value = vibrant
        advanceUntilIdle()
        refreshOfA.complete(a1)
        advanceUntilIdle()

        assertThat(holder.currentAlbumArtUri.value).isEqualTo("content://a")
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(a1)
    }

    @Test
    fun `a later accuracy change ends on the newest refresh, not the one before it`() = runTest(StandardTestDispatcher()) {
        val styleRefresh = CompletableDeferred<ColorSchemePair?>()
        val accuracyRefresh = CompletableDeferred<ColorSchemePair?>()
        coEvery { processor.getOrGenerateColorScheme(any(), any(), any(), any()) } coAnswers {
            val style = secondArg<AlbumArtPaletteStyle>()
            val accuracy = thirdArg<Int>()
            when {
                style == vibrant && accuracy == AlbumArtColorAccuracy.DEFAULT -> styleRefresh.await()
                style == vibrant -> accuracyRefresh.await()
                else -> a0
            }
        }
        val holder = holder()
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()

        styleFlow.value = vibrant                          // refresh 1 is waiting
        advanceUntilIdle()
        accuracyFlow.value = 5                             // refresh 2 is asked for behind it
        advanceUntilIdle()
        styleRefresh.complete(a1)
        advanceUntilIdle()
        accuracyRefresh.complete(b1)                       // the newest setting's palette (a made-up second palette for the same cover)
        advanceUntilIdle()

        assertThat(holder.currentAlbumArtUri.value).isEqualTo("content://a")
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(b1)
    }

    @Test
    fun `a song with no cover while a refresh is pending stays without a palette when the refresh arrives`() = runTest(StandardTestDispatcher()) {
        val refreshOfA = CompletableDeferred<ColorSchemePair?>()
        answers { cover, style -> if (cover == "content://a" && style == vibrant) refreshOfA else null }
        val holder = holder()
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()

        styleFlow.value = vibrant
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(null, null) }   // the next song has no cover
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isNull()
        assertThat(holder.currentAlbumArtUri.value).isNull()

        refreshOfA.complete(a1)
        advanceUntilIdle()

        assertThat(holder.currentAlbumArtColorSchemePair.value).isNull()
        assertThat(holder.currentAlbumArtUri.value).isNull()
    }

    @Test
    fun `A then B then A again while the first refresh of A is pending, the old refresh does not replace A's newer palette`() = runTest(StandardTestDispatcher()) {
        val oldRefreshOfA = CompletableDeferred<ColorSchemePair?>()
        answers { cover, style -> if (cover == "content://a" && style == vibrant && !oldRefreshOfA.isCompleted) oldRefreshOfA else null }
        val holder = holder()
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()

        styleFlow.value = vibrant            // refresh of A (request 1) is waiting
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://b"), "content://b") }
        advanceUntilIdle()
        // A comes back: its own request, under the new style, answers at once (the gate is still shut for the old refresh only)
        coEvery { processor.getOrGenerateColorScheme("content://a", vibrant, any(), any()) } returns a0
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(a0)

        oldRefreshOfA.complete(a1)           // the refresh that began before B
        advanceUntilIdle()

        assertThat(holder.currentAlbumArtUri.value).isEqualTo("content://a")
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(a0)
    }

    @Test
    fun `a forced regeneration of A that ends after the listener moved to B does not become the global palette`() = runTest(StandardTestDispatcher()) {
        val regenerationOfA = CompletableDeferred<ColorSchemePair?>()
        coEvery { processor.invalidateScheme(any()) } returns Unit
        coEvery { processor.getOrGenerateColorScheme(any(), any(), any(), any()) } coAnswers {
            val cover = firstArg<String>()
            val forced = arg<Boolean>(3)
            if (cover == "content://a" && forced) regenerationOfA.await() else if (cover == "content://a") a0 else b0
        }
        val holder = holder()
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()

        launch { holder.forceRegenerateColorScheme("content://a") }
        advanceUntilIdle()
        launch { holder.extractAndGenerateColorScheme(uri("content://b"), "content://b") }
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(b0)

        regenerationOfA.complete(a1)
        advanceUntilIdle()

        assertThat(holder.currentAlbumArtUri.value).isEqualTo("content://b")
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(b0)
    }
}
