package com.lostf1sh.pixelplayeross.data.service.player

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.audio.dsp.HeadphoneGraphicEqController
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideStore
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrides
import com.lostf1sh.pixelplayeross.data.model.TransitionSettings
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * B27-A1 (review R-01): a crossfade that is waiting for its incoming player must not start playing once the music was
 * paused under it. The review's probe, kept as a regression: the **real** [DualPlayerEngine] with two fake ExoPlayers.
 *
 * Only what exists in both B26 and B27 is used (the engine's own members through reflection, the fakes' state), so the same
 * file runs unchanged on the tree from before the fix, where the first tests fail with `paused both -> READY -> both playing`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PendingTransitionOutputLossTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val engines = mutableListOf<DualPlayerEngine>()

    @After
    fun tearDown() {
        engines.forEach { runCatching { it.release() } }
    }

    // ── the DAC leaves while the incoming player buffers ───────────────────────────────────────────

    @Test
    fun `the DAC leaving while the next song buffers keeps both players paused when it becomes ready`() {
        val rig = Rig().apply { dacWasPresent() }
        rig.fireTransition()
        assertThat(rig.incoming.listeners).isNotEmpty()      // it is waiting for READY

        rig.dacLeaves()
        rig.incoming.becomeReady()
        rig.idle()

        assertThat(rig.outgoing.playWhenReady).isFalse()
        assertThat(rig.incoming.playWhenReady).isFalse()
        assertThat(rig.outgoing.playCalls).isEqualTo(0)
        assertThat(rig.incoming.playCalls).isEqualTo(0)
        assertThat(rig.incoming.playWhenReadyEverTrue).isFalse()
        rig.assertNoCrossfadeRan()
    }

    @Test
    fun `the DAC leaving while Android plays to it is the same`() {
        val rig = Rig().apply { dacWasPresent(directActive = false) }
        rig.fireTransition()

        rig.dacLeaves(directActive = false)
        rig.incoming.becomeReady()
        rig.idle()

        assertThat(rig.outgoing.playWhenReady).isFalse()
        assertThat(rig.incoming.playWhenReady).isFalse()
        assertThat(rig.outgoing.playCalls + rig.incoming.playCalls).isEqualTo(0)
    }

    // ── the same barrier for a manual pause and for a focus loss ───────────────────────────────────

    @Test
    fun `a manual pause while the next song buffers is not undone when it becomes ready`() {
        val rig = Rig()
        rig.fireTransition()

        rig.outgoing.userPauses()
        rig.incoming.becomeReady()
        rig.idle()

        assertThat(rig.outgoing.playWhenReady).isFalse()
        assertThat(rig.incoming.playWhenReady).isFalse()
        assertThat(rig.outgoing.playCalls + rig.incoming.playCalls).isEqualTo(0)
        rig.assertNoCrossfadeRan()
    }

    @Test
    fun `a manual pause that raised no event is caught by the play intent`() {
        val rig = Rig(attachMasterListener = false)
        rig.fireTransition()

        rig.outgoing.userPauses()
        rig.incoming.becomeReady()
        rig.idle()

        assertThat(rig.outgoing.playWhenReady).isFalse()
        assertThat(rig.incoming.playWhenReady).isFalse()
        assertThat(rig.outgoing.playCalls + rig.incoming.playCalls).isEqualTo(0)
    }

    @Test
    fun `an audio focus loss while the next song buffers keeps both paused`() {
        val rig = Rig()
        rig.fireTransition()

        rig.focus(AudioManager.AUDIOFOCUS_LOSS)
        rig.incoming.becomeReady()
        rig.idle()

        assertThat(rig.outgoing.playWhenReady).isFalse()
        assertThat(rig.incoming.playWhenReady).isFalse()
        assertThat(rig.outgoing.playCalls + rig.incoming.playCalls).isEqualTo(0)
        rig.assertNoCrossfadeRan()
    }

    @Test
    fun `a transient focus loss while the next song buffers keeps both paused, and the gain resumes the song, not the crossfade`() {
        val rig = Rig()
        rig.fireTransition()

        rig.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        rig.incoming.becomeReady()
        rig.idle()
        assertThat(rig.outgoing.playWhenReady).isFalse()
        assertThat(rig.incoming.playWhenReady).isFalse()
        assertThat(rig.outgoing.playCalls + rig.incoming.playCalls).isEqualTo(0)

        rig.focus(AudioManager.AUDIOFOCUS_GAIN)
        rig.idle()

        assertThat(rig.outgoing.playWhenReady).isTrue()          // the focus gain owes the song its resume
        assertThat(rig.incoming.playWhenReady).isFalse()         // and the dropped crossfade does not start the next one
        assertThat(rig.incoming.playCalls).isEqualTo(0)
    }

    @Test
    fun `a crossfade asked for on a player that is already paused does not start it`() {
        val rig = Rig()
        rig.outgoing.userPauses()
        rig.incoming.state = Player.STATE_READY      // even with the next song ready, nothing is waiting to be played

        rig.fireTransition()
        rig.idle(durationMs = 2_000)

        assertThat(rig.outgoing.playWhenReady).isFalse()
        assertThat(rig.incoming.playWhenReady).isFalse()
        assertThat(rig.outgoing.playCalls + rig.incoming.playCalls).isEqualTo(0)
        rig.assertNoCrossfadeRan()
    }

    // ── a command that is meant to play still plays ────────────────────────────────────────────────

    @Test
    fun `an explicit play after the pause starts a new crossfade that completes`() {
        val rig = Rig().apply { dacWasPresent() }
        rig.fireTransition()
        rig.dacLeaves()
        rig.incoming.becomeReady()
        rig.idle()
        assertThat(rig.incoming.playCalls).isEqualTo(0)

        // the listener presses play, the next crossfade is asked for (the controller's job), its incoming player is ready
        rig.outgoing.userPlays()
        rig.incoming.state = Player.STATE_READY
        rig.fireTransition()
        rig.idle()

        assertThat(rig.incoming.playCalls).isEqualTo(1)
        assertThat(rig.incoming.playWhenReady).isTrue()
        rig.idle(durationMs = 2_000)
        assertThat(rig.master()).isSameInstanceAs(rig.incoming.player)   // the fade ended and the players swapped
    }

    @Test
    fun `a crossfade with nothing in between starts when the next song is ready and completes`() {
        val rig = Rig()
        rig.fireTransition()
        assertThat(rig.incoming.playCalls).isEqualTo(0)           // still waiting

        rig.incoming.becomeReady()
        rig.idle()

        assertThat(rig.incoming.playCalls).isEqualTo(1)
        assertThat(rig.incoming.playWhenReady).isTrue()
        assertThat(rig.outgoing.playWhenReady).isTrue()
        rig.idle(durationMs = 2_000)
        assertThat(rig.master()).isSameInstanceAs(rig.incoming.player)
        assertThat(rig.incoming.volume).isGreaterThan(0.9f)
    }

    @Test
    fun `a crossfade already fading is not stopped by a pause, and never plays again after it`() {
        val rig = Rig()
        rig.incoming.state = Player.STATE_READY
        rig.fireTransition()
        rig.idle()
        assertThat(rig.incoming.playCalls).isEqualTo(1)           // fading

        rig.outgoing.userPauses()
        rig.incoming.userPauses()
        rig.idle(durationMs = 2_000)

        assertThat(rig.incoming.playCalls).isEqualTo(1)           // no second play() from the transition
        assertThat(rig.outgoing.playCalls).isEqualTo(0)
        assertThat(rig.master()).isSameInstanceAs(rig.incoming.player)
        assertThat(rig.incoming.playWhenReady).isFalse()
    }

    @Test
    fun `review end of outgoing while incoming buffers does not stop playback`() {
        val rig = Rig()
        rig.fireTransition()
        rig.outgoing.setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        rig.incoming.becomeReady()
        rig.idle(durationMs = 2_000)
        assertThat(rig.incoming.playWhenReady).isTrue()
        assertThat(rig.master()).isSameInstanceAs(rig.incoming.player)
    }

    @Test
    fun `review end of outgoing with incoming timeout does not stop playback`() {
        val rig = Rig()
        rig.fireTransition()
        rig.outgoing.setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        rig.idle(durationMs = 4_000)
        assertThat(rig.outgoing.playWhenReady).isTrue()
        assertThat(rig.outgoing.pauseAtEnd).isFalse()
        assertThat(rig.outgoing.advancedAtEnd).isTrue()
        assertThat(rig.incoming.playCalls).isEqualTo(0)
    }

    @Test
    fun `an end pause followed by direct DAC loss never resumes at READY or timeout`() {
        assertEndPauseCancelled { it.dacLeaves() }
    }

    @Test
    fun `an end pause followed by Android DAC loss never resumes at READY or timeout`() {
        assertEndPauseCancelled { it.dacLeaves(directActive = false) }
    }

    @Test
    fun `a manual pause command while already false cancels end intent without another callback`() {
        assertEndPauseCancelled { rig -> rig.sessionPlayer().pause() }
    }

    @Test
    fun `setting play intent false while already false cancels end intent without another callback`() {
        assertEndPauseCancelled { rig -> rig.sessionPlayer().playWhenReady = false }
    }

    @Test
    fun `permanent focus loss after an end pause cancels pending play`() {
        assertEndPauseCancelled { it.focus(AudioManager.AUDIOFOCUS_LOSS) }
    }

    @Test
    fun `transient focus loss after an end pause cancels pending play`() {
        assertEndPauseCancelled { it.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) }
    }

    @Test
    fun `a rebuild after an end pause cancels the held players`() {
        assertEndPauseCancelled { rig ->
            DualPlayerEngine::class.java.getDeclaredMethod("abandonTransitionForRebuild").apply {
                isAccessible = true
                invoke(rig.engine)
            }
        }
    }

    @Test
    fun `an incoming queue cleared during buffering falls back to the masters next song`() {
        val rig = Rig()
        rig.fireTransition()
        rig.outgoing.setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        rig.incoming.items = 0
        rig.incoming.becomeReady()
        rig.idle(durationMs = 2_000)
        assertThat(rig.outgoing.playWhenReady).isTrue()
        assertThat(rig.outgoing.advancedAtEnd).isTrue()
        assertThat(rig.incoming.playCalls).isEqualTo(0)
    }

    private fun assertEndPauseCancelled(cancel: (Rig) -> Unit) {
        for (ready in listOf(true, false)) {
            val rig = Rig().apply { dacWasPresent() }
            rig.fireTransition()
            rig.outgoing.setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
            cancel(rig)
            if (ready) rig.incoming.becomeReady()
            rig.idle(durationMs = 4_000)
            assertThat(rig.outgoing.playWhenReady).isFalse()
            assertThat(rig.incoming.playWhenReady).isFalse()
            assertThat(rig.outgoing.playCalls + rig.incoming.playCalls).isEqualTo(0)
            rig.assertNoCrossfadeRan()
        }
    }

    @Test
    fun `gain after transient loss during an end pause continues the normal queue`() {
        assertEndPauseFocusResume(ready = true)
    }

    @Test
    fun `gain after transient loss during an end pause continues after the incoming timeout`() {
        assertEndPauseFocusResume(ready = false)
    }

    @Test
    fun `an explicit pause while transiently paused cancels resume without another callback`() {
        assertFocusResumeCancelled { it.sessionPlayer().pause() }
    }

    @Test
    fun `an explicit false play intent while transiently paused cancels resume without another callback`() {
        assertFocusResumeCancelled { it.sessionPlayer().playWhenReady = false }
    }

    @Test
    fun `Direct DAC detach while transiently paused cancels the owed resume`() {
        assertFocusResumeCancelled(dacPresent = true) { it.dacLeaves() }
    }

    @Test
    fun `Android DAC detach while transiently paused cancels the owed resume`() {
        assertFocusResumeCancelled(dacPresent = true) { it.dacLeaves(directActive = false) }
    }

    @Test
    fun `permanent focus loss while transiently paused cancels the owed resume`() {
        assertFocusResumeCancelled { it.focus(AudioManager.AUDIOFOCUS_LOSS) }
    }

    @Test
    fun `duplicated and late gains do not play again or undo a subsequent pause`() {
        for (ready in listOf(true, false)) {
            val rig = assertEndPauseFocusResume(ready)
            val requests = rig.outgoing.truePlayIntentRequests
            rig.focus(AudioManager.AUDIOFOCUS_GAIN)
            rig.idle()
            assertThat(rig.outgoing.truePlayIntentRequests).isEqualTo(requests)
            rig.sessionPlayer().pause()
            rig.idle(durationMs = 500)
            rig.focus(AudioManager.AUDIOFOCUS_GAIN)
            rig.focus(AudioManager.AUDIOFOCUS_GAIN)
            rig.idle(durationMs = 4_000)
            assertThat(rig.outgoing.playWhenReady).isFalse()
            assertThat(rig.outgoing.truePlayIntentRequests).isEqualTo(requests)
            assertThat(rig.incoming.truePlayIntentRequests).isEqualTo(0)
        }
    }

    @Test
    fun `repeated transient losses preserve the end pauses owed resume`() {
        val rig = endPausedDuringFocusLoss()
        rig.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        rig.incoming.becomeReady()
        rig.idle(durationMs = 4_000)
        rig.assertNothingStarted()
        rig.focus(AudioManager.AUDIOFOCUS_GAIN)
        rig.idle()
        assertThat(rig.outgoing.playWhenReady).isTrue()
        assertThat(rig.outgoing.advancedAtEnd).isTrue()
        assertThat(rig.outgoing.truePlayIntentRequests).isEqualTo(1)
        assertThat(rig.incoming.truePlayIntentRequests).isEqualTo(0)
        rig.assertNoCrossfadeRan()
    }

    @Test
    fun `explicit commands also cancel an ordinary transient resume when already paused`() {
        for (pauseCommand in listOf<(Player) -> Unit>({ it.pause() }, { it.playWhenReady = false })) {
            val rig = Rig()
            rig.fireTransition()
            rig.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            val callbacks = rig.outgoing.playIntentCallbacks
            pauseCommand(rig.sessionPlayer())
            assertThat(rig.outgoing.playIntentCallbacks).isEqualTo(callbacks)
            rig.incoming.becomeReady()
            rig.idle(durationMs = 4_000)
            rig.focus(AudioManager.AUDIOFOCUS_GAIN)
            rig.idle()
            rig.assertNothingStarted()
        }
    }

    private fun endPausedDuringFocusLoss(dacPresent: Boolean = false): Rig = Rig().apply {
        if (dacPresent) dacWasPresent()
        fireTransition()
        outgoing.setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertThat(engine.isTransitionRunning()).isFalse()
        assertThat(outgoing.pauseAtEnd).isFalse() // cancel releases the master's ordinary queue
        assertNothingStarted()
    }

    private fun assertEndPauseFocusResume(ready: Boolean): Rig {
        val rig = endPausedDuringFocusLoss()
        if (ready) rig.incoming.becomeReady()
        rig.idle(durationMs = 4_000) // also pass the cancelled wait's 3 s deadline
        rig.assertNothingStarted()
        rig.focus(AudioManager.AUDIOFOCUS_GAIN)
        rig.idle(durationMs = 2_000)
        assertThat(rig.outgoing.playWhenReady).isTrue()
        assertThat(rig.outgoing.advancedAtEnd).isTrue()
        assertThat(rig.outgoing.truePlayIntentRequests).isEqualTo(1)
        assertThat(rig.incoming.truePlayIntentRequests).isEqualTo(0)
        assertThat(rig.outgoing.playCalls + rig.incoming.playCalls).isEqualTo(0) // normal GAIN setter, no revived fade
        rig.assertNoCrossfadeRan()
        return rig
    }

    private fun assertFocusResumeCancelled(dacPresent: Boolean = false, cancel: (Rig) -> Unit) {
        for (ready in listOf(true, false)) {
            val rig = endPausedDuringFocusLoss(dacPresent)
            val callbacks = rig.outgoing.playIntentCallbacks
            cancel(rig)
            assertThat(rig.outgoing.playIntentCallbacks).isEqualTo(callbacks)
            if (ready) rig.incoming.becomeReady()
            rig.idle(durationMs = 4_000)
            rig.assertNothingStarted()
            repeat(2) { rig.focus(AudioManager.AUDIOFOCUS_GAIN) }
            rig.idle(durationMs = 4_000)
            rig.assertNothingStarted()
        }
    }

    // ── harness ────────────────────────────────────────────────────────────────────────────────────

    /** An ExoPlayer that keeps the state the engine reads and tells its listeners, as Media3 does, synchronously. */
    private class FakePlayer(var state: Int, var items: Int = 1) {
        var playWhenReady = false
        var playWhenReadyEverTrue = false
        var volume = 1f
        var playCalls = 0
        var truePlayIntentRequests = 0
        var playIntentCallbacks = 0
        var pauseAtEnd = false
        var ended = false
        var advancedAtEnd = false
        val listeners = mutableListOf<Player.Listener>()

        val player: ExoPlayer = mockk<ExoPlayer>(relaxed = true).also { p ->
            every { p.playWhenReady } answers { playWhenReady }
            every { p.playWhenReady = any() } answers { setPlayWhenReady(firstArg(), Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
            every { p.play() } answers {
                playCalls++
                setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            }
            every { p.pauseAtEndOfMediaItems } answers { pauseAtEnd }
            every { p.pauseAtEndOfMediaItems = any() } answers { pauseAtEnd = firstArg() }
            every { p.pause() } answers { setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
            every { p.isPlaying } answers { playWhenReady && state == Player.STATE_READY }
            every { p.playbackState } answers { state }
            every { p.volume } answers { volume }
            every { p.volume = any() } answers { volume = firstArg() }
            every { p.mediaItemCount } answers { items }
            every { p.currentMediaItem } returns MediaItem.Builder().setMediaId("song").build()
            every { p.addListener(any()) } answers { listeners += firstArg<Player.Listener>() }
            every { p.removeListener(any()) } answers { listeners -= firstArg<Player.Listener>() }
            every { p.repeatMode } returns Player.REPEAT_MODE_OFF
            every { p.shuffleModeEnabled } returns false
            every { p.audioSessionId } returns 1
        }

        fun setPlayWhenReady(value: Boolean, reason: Int) {
            val changed = value != playWhenReady
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) ended = true
            playWhenReady = value
            if (value) {
                truePlayIntentRequests++
                playWhenReadyEverTrue = true
                // ExoPlayer.play() delegates to setPlayWhenReady(true). With stop-at-end released,
                // either command lets the ordinary queue advance. This models intent, not decoding or DAC audio.
                if (ended && !pauseAtEnd) advancedAtEnd = true
            }
            if (changed) {
                playIntentCallbacks++
                listeners.toList().forEach { it.onPlayWhenReadyChanged(value, reason) }
            }
        }

        fun userPauses() = setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        fun userPlays() = setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

        /** The buffering player gets its first frames. */
        fun becomeReady() {
            state = Player.STATE_READY
            listeners.toList().forEach { it.onPlaybackStateChanged(Player.STATE_READY) }
        }
    }

    /** The real engine, playing [outgoing] with [incoming] prepared and still buffering. */
    private inner class Rig(attachMasterListener: Boolean = true) {
        val outgoing = FakePlayer(Player.STATE_READY).also { it.playWhenReady = true; it.playWhenReadyEverTrue = true }
        val incoming = FakePlayer(Player.STATE_BUFFERING).also { it.volume = 0f }   // prepareNext leaves the next song silent
        val engine: DualPlayerEngine

        init {
            val dacProfiles = mockk<DacProfileRepository>(relaxed = true)
            every { dacProfiles.connected } returns MutableStateFlow(emptyList())
            val overrides = mockk<MaintenanceOverrideStore>(relaxed = true)
            every { overrides.state } returns MutableStateFlow(MaintenanceOverrides())
            // an application scope that never runs: the engine's own collectors are not part of this
            val idleScope = CoroutineScope(Job().apply { cancel() })
            engine = DualPlayerEngine(context, mockk<HeadphoneGraphicEqController>(relaxed = true), dacProfiles, overrides, idleScope)
            engines += engine
            set("playerA", outgoing.player)
            set("playerB", incoming.player)
            if (attachMasterListener) call("addMasterPlayerListeners", outgoing.player)
        }

        private fun field(name: String) = DualPlayerEngine::class.java.getDeclaredField(name).apply { isAccessible = true }

        private fun set(name: String, value: Any?) = field(name).set(engine, value)

        private fun call(name: String, argument: Any) {
            val method = DualPlayerEngine::class.java.declaredMethods.first { it.name == name && it.parameterCount == 1 }
            method.isAccessible = true
            method.invoke(engine, argument)
        }

        fun sessionPlayer(): Player = MappingPlayer(
            // Same wrappers as MusicService; the pause command arrives before FadingPlayer's ramp.
            FadingPlayer(outgoing.player, CoroutineScope(Job())), context,
            onPause = engine::notifyExternalPauseInitiated,
        )

        fun master(): ExoPlayer = field("playerA").get(engine) as ExoPlayer

        private fun observeUsbPresence(present: Boolean, directActive: Boolean) {
            val method = DualPlayerEngine::class.java.getDeclaredMethod(
                "observeUsbPresence", Boolean::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(engine, present, "test", directActive)
        }

        fun dacWasPresent(directActive: Boolean = true) = observeUsbPresence(true, directActive)

        fun dacLeaves(directActive: Boolean = true) = observeUsbPresence(false, directActive)

        fun focus(change: Int) {
            val listener = field("focusChangeListener").get(engine) as AudioManager.OnAudioFocusChangeListener
            listener.onAudioFocusChange(change)
        }

        fun fireTransition() {
            engine.setPauseAtEndOfMediaItems(true) // TransitionController arms this before performTransition.
            engine.performTransition(TransitionSettings(durationMs = 800))
            idle()
        }

        fun idle(durationMs: Long = 0) {
            val looper = shadowOf(Looper.getMainLooper())
            if (durationMs > 0) looper.idleFor(Duration.ofMillis(durationMs)) else looper.idle()
        }

        fun assertNothingStarted() {
            assertThat(outgoing.playWhenReady).isFalse()
            assertThat(incoming.playWhenReady).isFalse()
            assertThat(outgoing.truePlayIntentRequests + incoming.truePlayIntentRequests).isEqualTo(0)
            assertThat(outgoing.playCalls + incoming.playCalls).isEqualTo(0)
            assertThat(outgoing.advancedAtEnd).isFalse()
            assertNoCrossfadeRan()
        }

        /** Nothing of the fade ran: the incoming player kept its silence and the outgoing its level. */
        fun assertNoCrossfadeRan() {
            assertThat(incoming.volume).isEqualTo(0f)
            assertThat(master()).isSameInstanceAs(outgoing.player)
        }
    }
}
