package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.net.Uri
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreferencesRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B29-L11: a palette that arrives late for a song that is no longer the one playing must not become the current colour.
 *
 * Fast skips: A is asked for, B is asked for, B's palette is ready first and A's arrives after. Each request compared its cover with the
 * current song **as it was when the request began**, so A, whose request began while A was current, passed that check and replaced B's palette
 * with its own: the colour of the song before the one that plays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ThemeStateHolderStaleResultTest {

    private fun uri(text: String): Uri = Uri.parse(text)
    private fun pair(seed: Int) = ColorSchemePair(
        light = lightColorScheme(primary = Color(seed)),
        dark = darkColorScheme(primary = Color(seed)),
    )

    @Test
    fun `a late palette for the song before does not replace the one for the song that plays`() = runTest(StandardTestDispatcher()) {
        val processor = mockk<ColorSchemeProcessor>()
        val gateA = CompletableDeferred<ColorSchemePair?>()
        val gateB = CompletableDeferred<ColorSchemePair?>()
        val paletteA = pair(0xFF112233.toInt())
        val paletteB = pair(0xFF445566.toInt())
        coEvery { processor.getOrGenerateColorScheme("content://a", any(), any(), any()) } coAnswers { gateA.await() }
        coEvery { processor.getOrGenerateColorScheme("content://b", any(), any(), any()) } coAnswers { gateB.await() }
        val holder = ThemeStateHolder(processor, mockk<ThemePreferencesRepository>(relaxed = true))

        val askedForA = launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()
        val askedForB = launch { holder.extractAndGenerateColorScheme(uri("content://b"), "content://b") }
        advanceUntilIdle()

        gateB.complete(paletteB)
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(paletteB)

        gateA.complete(paletteA) // the slow one, after the skip
        advanceUntilIdle()
        askedForA.join(); askedForB.join()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(paletteB)
        assertThat(holder.currentAlbumArtUri.value).isEqualTo("content://b")
    }

    @Test
    fun `the colour stays the current song's until the new one is ready, and a song with no cover clears it`() = runTest(StandardTestDispatcher()) {
        val processor = mockk<ColorSchemeProcessor>()
        val gateB = CompletableDeferred<ColorSchemePair?>()
        val paletteA = pair(0xFF112233.toInt())
        coEvery { processor.getOrGenerateColorScheme("content://a", any(), any(), any()) } returns paletteA
        coEvery { processor.getOrGenerateColorScheme("content://b", any(), any(), any()) } coAnswers { gateB.await() }
        val holder = ThemeStateHolder(processor, mockk<ThemePreferencesRepository>(relaxed = true))

        launch { holder.extractAndGenerateColorScheme(uri("content://a"), "content://a") }
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(paletteA)

        // B starts: nothing is published for it yet, and the colour on screen is still A's, not a flash of the app's accent
        launch { holder.extractAndGenerateColorScheme(uri("content://b"), "content://b") }
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isEqualTo(paletteA)

        // the next song has no cover at all: the colour goes, and B's palette arriving late does not bring it back
        launch { holder.extractAndGenerateColorScheme(null, null) }
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isNull()
        gateB.complete(pair(0xFF445566.toInt()))
        advanceUntilIdle()
        assertThat(holder.currentAlbumArtColorSchemePair.value).isNull()
    }
}
