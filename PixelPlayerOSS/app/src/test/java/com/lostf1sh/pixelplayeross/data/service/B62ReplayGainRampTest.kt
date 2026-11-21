package com.lostf1sh.pixelplayeross.data.service

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.media.ReplayGainManager
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.FadingPlayer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class B62ReplayGainRampTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private var volume = 1f
    private var playing = false
    private lateinit var processor: ReplayGainProcessor
    private val inner = mockk<Player>(relaxed = true).also {
        every { it.volume } answers { volume }
        every { it.setVolume(any()) } answers {
            volume = firstArg()
            if (!FadingPlayer.isRampWrite) processor.onPlayerVolumeChanged(volume)
        }
        every { it.isPlaying } answers { playing }
        every { it.play() } answers { playing = true }
        every { it.pause() } answers { playing = false }
    }
    private val engine = mockk<DualPlayerEngine>(relaxed = true).also {
        every { it.masterPlayer } returns inner
        every { it.isTransitionRunning() } returns false
    }

    @BeforeEach fun setup() {
        Dispatchers.setMain(dispatcher)
        processor = ReplayGainProcessor(engine, mockk<ReplayGainManager>(relaxed = true), scope) { null }
        processor.captureUserVolume(0.8f)
        processor.setDiagnosticReplayGainFactor(0.5f)
        processor.setProfileCompensation(0.9f)
    }
    @AfterEach fun teardown() { scope.advanceUntilIdle(); Dispatchers.resetMain() }

    @Test fun `reapplying ReplayGain during play cannot jump above the ramp step`() {
        val fading = FadingPlayer(inner, scope)
        fading.play(); scope.advanceTimeBy(24); scope.runCurrent()
        val step = volume
        assertThat(step).isLessThan(0.36f)
        processor.reapplyLastAppliedVolume(inner)
        assertThat(volume).isAtMost(step + 0.00001f)
        scope.advanceUntilIdle()
        assertThat(volume).isWithin(1e-6f).of(0.36f)
    }

    @Test fun `changing composed gain mid-ramp lands on the new product`() {
        val fading = FadingPlayer(inner, scope)
        fading.play(); scope.advanceTimeBy(24); scope.runCurrent()
        processor.setDiagnosticUserGain(0.4f)
        scope.advanceUntilIdle()
        assertThat(volume).isWithin(1e-6f).of(0.4f * 0.5f * 0.9f)
    }

    @Test fun `rapid pause play pause with RG does not ratchet the composed gain`() {
        val fading = FadingPlayer(inner, scope)
        repeat(8) {
            fading.play(); scope.advanceTimeBy(18); scope.runCurrent()
            processor.reapplyLastAppliedVolume(inner)
            fading.pause(); scope.advanceTimeBy(12); scope.runCurrent()
        }
        scope.advanceUntilIdle()
        fading.play(); scope.advanceUntilIdle()
        assertThat(volume).isWithin(1e-6f).of(0.36f)
    }
    @Test fun `the Direct transport keeps its previous half-second timing`() {
        val fading = FadingPlayer(inner, scope, smoothAndroid = { false })
        fading.play(); scope.advanceTimeBy(100); scope.runCurrent()
        assertThat(volume).isLessThan(0.36f)
        scope.advanceUntilIdle()
        assertThat(volume).isWithin(1e-6f).of(0.36f)
    }

    @Test fun `Android finishes the low start at 72 ms and pause is not lengthened`() {
        val fading = FadingPlayer(inner, scope)
        fading.play(); scope.advanceTimeBy(72); scope.runCurrent()
        assertThat(volume).isWithin(1e-6f).of(0.36f)
        fading.pause(); scope.advanceTimeBy(72); scope.runCurrent()
        assertThat(playing).isFalse()
        assertThat(volume).isWithin(1e-6f).of(0.36f)
    }

    @Test fun `a redundant pause preserves a composed gain changed while paused`() {
        val fading = FadingPlayer(inner, scope)
        processor.setDiagnosticUserGain(0.4f)
        fading.pause()
        assertThat(volume).isWithin(1e-6f).of(0.4f * 0.5f * 0.9f)
    }

}
