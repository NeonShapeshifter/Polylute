package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.MainCoroutineExtension
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * B12a-7: the sequence of states the play/pause button is drawn from, through the real [PlaybackStateHolder].
 *
 * The owner saw **pause → play → pause** when tapping pause. Here the session's echoes arrive after the tap exactly
 * as they do on the device (the controller's optimistic state, then the session reporting the state from before
 * the pause once per volume ramp step, then the pause itself), and the glyph's `isPlaying` must go
 * `true → false` and stay there: no intermediate "playing".
 */
@ExtendWith(MainCoroutineExtension::class)
class PlayPauseButtonSequenceTest {

    private val dualPlayerEngine: DualPlayerEngine = mockk(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)
    private val powerManager: PowerManager = mockk(relaxed = true)

    init {
        every { appContext.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { powerManager.isInteractive } returns true
    }

    private class Rig(val holder: PlaybackStateHolder) {
        var now = 0L
        val glyph = mutableListOf<Boolean>()      // `isPlaying` after every write, as the button would see it
        init { holder.guardClockMs = { now } }

        fun at(ms: Long, block: () -> Unit) {
            now = ms
            block()
            glyph += holder.stablePlayerState.value.isPlaying
        }
        fun report(isPlaying: Boolean, playWhenReady: Boolean, source: String = "controller.onIsPlayingChanged") =
            holder.updateStablePlayerState(source) { it.copy(isPlaying = isPlaying, playWhenReady = playWhenReady) }
    }

    private fun rig(): Rig {
        val holder = PlaybackStateHolder(
            dualPlayerEngine = dualPlayerEngine,
            userPreferencesRepository = mockk<UserPreferencesRepository>(relaxed = true),
            queueStateHolder = mockk(relaxed = true),
            appContext = appContext,
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            dispatchers = TestDispatcherProvider(Dispatchers.Unconfined),
        )
        return Rig(holder).also { it.holder.updateStablePlayerState { s -> s.copy(isPlaying = true, playWhenReady = true) } }
    }

    @Test
    fun `tapping pause never shows playing again, with the session's stale echoes in between`() {
        val r = rig()
        r.at(0) { r.holder.noteUserPlayPause(playbackWanted = false) }                       // the tap: paused, at once
        r.at(2) { r.report(isPlaying = false, playWhenReady = false) }                         // the controller's own optimistic step agrees
        r.at(25) { r.report(isPlaying = true, playWhenReady = true) }                          // echo after the first ramp step
        r.at(50) { r.report(isPlaying = true, playWhenReady = true, source = "controller.onPlayWhenReadyChanged") }
        r.at(75) { r.report(isPlaying = true, playWhenReady = true) }                          // and the last
        r.at(160) { r.report(isPlaying = false, playWhenReady = false) }                       // the pause itself
        r.at(220) { r.report(isPlaying = true, playWhenReady = true, source = "controller.mediaItemSync") }   // a late sync of the old state
        assertThat(r.glyph).containsExactly(false, false, false, false, false, false, false).inOrder()
        // and the state is settled: paused, intent paused
        assertThat(r.holder.stablePlayerState.value.isPlaying).isFalse()
        assertThat(r.holder.stablePlayerState.value.playWhenReady).isFalse()
    }

    @Test
    fun `a player that really resumes after the tap is shown once the window is over`() {
        val r = rig()
        r.at(0) { r.holder.noteUserPlayPause(false) }
        r.at(160) { r.report(false, false) }
        r.at(400) { r.report(true, true) }                                                     // inside the window: indistinguishable from an echo
        r.at(PlayPauseCommandGuard.HOLD_MS + 100) { r.report(true, true) }                     // focus gain, another controller's play(): shown
        assertThat(r.glyph).containsExactly(false, false, false, true).inOrder()
    }

    @Test
    fun `a contradiction that outlasts the hold is believed - nothing is hidden for good`() {
        val r = rig()
        r.at(0) { r.holder.noteUserPlayPause(false) }
        r.at(300) { r.report(true, true) }                                                     // still inside the hold: an echo
        r.at(PlayPauseCommandGuard.HOLD_MS + 50) { r.report(true, true) }                      // the pause never came: the player plays
        assertThat(r.glyph).containsExactly(false, false, true).inOrder()
    }

    @Test
    fun `a pause from outside - the notification, another controller - is not a tap and is shown at once`() {
        val r = rig()
        r.at(0) { r.report(false, false) }
        assertThat(r.glyph).containsExactly(false)
    }

    @Test
    fun `a play tap keeps the glyph on playing through the paused echo of its first ramp step`() {
        val r = rig()
        r.at(0) { r.report(false, false) }                                                     // paused
        r.at(10) { r.holder.noteUserPlayPause(playbackWanted = true) }                         // the tap (no optimistic write for play)
        r.at(30) { r.report(true, true) }                                                      // the controller's optimistic: playing
        r.at(55) { r.report(false, false) }                                                    // echo of the paused state, after setVolume(0)
        r.at(120) { r.report(true, true) }                                                     // the play itself
        assertThat(r.glyph).containsExactly(false, false, true, true, true).inOrder()
    }

    @Test
    fun `the app's own writes - a new queue, the external VIEW - are not held and end the command`() {
        val r = rig()
        r.at(0) { r.holder.noteUserPlayPause(false) }
        r.at(100) { r.holder.updateStablePlayerState { it.copy(isPlaying = true, playWhenReady = true) } }   // external VIEW: playing
        r.at(120) { r.report(true, true) }                                                     // nothing pending any more
        assertThat(r.glyph).containsExactly(false, true, true).inOrder()
    }

    @Test
    fun `the held write is logged with its source so the device log says who it was`() {
        val holder = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/PlaybackStateHolder.kt").readText()
        assertThat(holder).contains("PlayPause: held a stale %s=%s from %s %d ms after the tap (the user asked for playing=%s)")
        val viewModel = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/PlayerViewModel.kt").readText()
        for (source in listOf("controller.onIsPlayingChanged", "controller.onPlayWhenReadyChanged", "controller.mediaItemSync")) {
            assertThat(viewModel).contains("updateStablePlayerState(\"$source\")")
        }
        // the tap is told to the holder before the controller is asked
        val playPause = viewModel.substringAfter("    fun playPause() {").substringBefore("    fun seekTo(position: Long) {")
        assertThat(playPause.indexOf("noteUserPlayPause(playbackWanted = false)")).isLessThan(playPause.indexOf("controller.pause()"))
        assertThat(playPause.indexOf("noteUserPlayPause(playbackWanted = true)")).isLessThan(playPause.lastIndexOf("controller.play()"))
    }

    @Test
    fun `every surface reads the one state - mini player, full player, lock screen and the external overlay`() {
        // They all draw from the holder's `stablePlayerState`; the guard sits at its source, so none needs its own.
        val sheetLayers = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/UnifiedPlayerSheetLayers.kt").readText()
        val external = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/external/ExternalPlayerOverlay.kt").readText()
        val lock = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/lockscreen/DapLockScreenHost.kt").readText()
        assertThat(sheetLayers).contains("infrequentPlayerState.isPlaying")
        assertThat(external).contains("stablePlayerState.isPlaying")
        assertThat(lock).contains("state.isPlaying")
        for (surface in listOf(sheetLayers, external, lock)) assertThat(surface).doesNotContain("PlayPauseCommandGuard")
    }
}
