package com.lostf1sh.pixelplayeross.data.service

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.media.ReplayGainManager
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.TransitionVolumePolicy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Test

/**
 * AUD-001 / AUD-099: the user's digital gain is owned by the user.
 *
 * Every user-facing control reaches the processor through `setUserDigitalGain` (the contributor).
 * A write to `player.volume` from anywhere else — the engine's `cancelNext` restoring "full" after a
 * transition, a rebuild, a crossfade abort — is not the user choosing a level, and adopting it put
 * the digital gain back to 100 % ~1.5 s into every track.
 *
 * The fake player echoes each write synchronously to the processor, the way `MusicService`'s
 * `onVolumeChanged` listener does on the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayGainUserGainOwnershipTest {

    private var volume = 1f
    private val writes = mutableListOf<Float>()
    private var transitionRunning = false
    private lateinit var processor: ReplayGainProcessor

    private val player = mockk<Player>(relaxed = true).also { player ->
        every { player.volume } answers { volume }
        every { player.setVolume(any()) } answers {
            volume = firstArg()
            writes += volume
            processor.onPlayerVolumeChanged(volume)
        }
    }

    private val engine = mockk<DualPlayerEngine>(relaxed = true).also { engine ->
        every { engine.masterPlayer } returns player
        every { engine.isTransitionRunning() } answers { transitionRunning }
    }

    init {
        processor = ReplayGainProcessor(
            engine = engine,
            replayGainManager = mockk<ReplayGainManager>(relaxed = true),
            scope = TestScope(StandardTestDispatcher()),
            currentSessionMediaItem = { null },
        )
    }

    @Test
    fun `a foreign write to player volume does not become the user's gain`() {
        processor.setDiagnosticUserGain(0.3f)
        assertThat(volume).isWithin(1e-6f).of(0.3f)

        // What cancelNext used to do 1.5 s after every track start.
        player.volume = 1f

        assertThat(processor.userGain).isWithin(1e-6f).of(0.3f)
        assertThat(volume).isWithin(1e-6f).of(0.3f)
    }

    @Test
    fun `a foreign write during a transition is ignored and the gain survives it`() {
        processor.setDiagnosticUserGain(0.3f)
        transitionRunning = true
        player.volume = 1f
        transitionRunning = false
        processor.onTransitionFinished()

        assertThat(processor.userGain).isWithin(1e-6f).of(0.3f)
        assertThat(volume).isWithin(1e-6f).of(0.3f)
    }

    @Test
    fun `cancelling with no transition in flight leaves the volume alone`() {
        // Crossfade is off by default, so this is the branch taken after almost every track start.
        assertThat(TransitionVolumePolicy.volumeAfterCancel(transitionWasRunning = false, composedGain = 0.3f))
            .isNull()
    }

    @Test
    fun `cancelling a running fade restores the composed gain, never unity`() {
        assertThat(TransitionVolumePolicy.volumeAfterCancel(transitionWasRunning = true, composedGain = 0.3f))
            .isWithin(1e-6f).of(0.3f)
    }

    @Test
    fun `a track change with the user below unity never writes unity`() {
        processor.setDiagnosticUserGain(0.3f)
        writes.clear()

        // Manual skip / playSongs: cancelNext first, then the ReplayGain application.
        TransitionVolumePolicy.volumeAfterCancel(transitionWasRunning = false, composedGain = processor.combinedGain)
            ?.let { player.volume = it }
        processor.reapplyLastAppliedVolume(player)

        assertThat(writes).isNotEmpty()
        assertThat(writes.max()).isAtMost(0.3f + 1e-6f)
    }
}
