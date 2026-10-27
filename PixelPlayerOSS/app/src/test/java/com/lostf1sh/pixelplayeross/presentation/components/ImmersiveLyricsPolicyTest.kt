package com.lostf1sh.pixelplayeross.presentation.components

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B40-F5: the rules for when the lyrics page folds (the pure half; the page that obeys them is in `LyricsImmersiveRenderTest`).
 */
class ImmersiveLyricsPolicyTest {
    private fun may(
        featureOn: Boolean = true,
        syncedLinesShown: Boolean = true,
        playing: Boolean = true,
        switchedOffOnce: Boolean = false,
        covered: Boolean = false,
    ) = ImmersiveLyricsPolicy.mayEngage(featureOn, syncedLinesShown, playing, switchedOffOnce, covered)

    @Test
    fun `it may fold only when everything allows it`() {
        assertThat(may()).isTrue()
    }

    @Test
    fun `each reason on its own keeps the page from folding`() {
        assertThat(may(featureOn = false)).isFalse()
        assertThat(may(syncedLinesShown = false)).isFalse()
        assertThat(may(playing = false)).isFalse()
        assertThat(may(switchedOffOnce = true)).isFalse()
        assertThat(may(covered = true)).isFalse()
    }

    @Test
    fun `a touch that begins on a folded page is not a request to seek`() {
        val gate = ImmersiveTouchGate()
        gate.touchBegan(immersive = true)
        assertThat(gate.lineTapSeeks()).isFalse()
    }

    @Test
    fun `a touch that begins on an open page seeks`() {
        val gate = ImmersiveTouchGate()
        gate.touchBegan(immersive = false)
        assertThat(gate.lineTapSeeks()).isTrue()
    }

    @Test
    fun `the answer is given once per touch, so a click without a touch of its own is a plain tap`() {
        val gate = ImmersiveTouchGate()
        gate.touchBegan(immersive = true)
        assertThat(gate.lineTapSeeks()).isFalse()
        assertThat(gate.lineTapSeeks()).isTrue()
    }

    @Test
    fun `a finger is touching from its going down until it is lifted`() {
        val gate = ImmersiveTouchGate()
        assertThat(gate.isTouching).isFalse()
        gate.touchBegan(immersive = false)
        assertThat(gate.isTouching).isTrue()
        gate.touchEnded()
        assertThat(gate.isTouching).isFalse()
    }

    private val sheet by lazy {
        listOf("src/main/java/com/lostf1sh/pixelplayeross", "app/src/main/java/com/lostf1sh/pixelplayeross")
            .map(::File).first { it.isDirectory }
            .let { File(it, "presentation/components/LyricsSheet.kt").readText() }
    }

    @Test
    fun `the page asks the policy and counts its options sheet and its dialogs as other surfaces`() {
        assertThat(sheet).contains("ImmersiveLyricsPolicy.mayEngage(")
        assertThat(sheet).contains("coveredByAnotherSurface = showMoreSheet || showFetchLyricsDialog || showSaveLyricsDialog")
    }

    @Test
    fun `the page no longer keeps its interaction clock in Compose state`() {
        // a timestamp written to state on every pointer event recomposed the whole page and was never written by a scroll
        assertThat(sheet).doesNotContain("lastInteractionTime")
        assertThat(sheet).doesNotContain("System.currentTimeMillis()")
    }
}
