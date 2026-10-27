package com.lostf1sh.pixelplayeross.data.volume

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.AppliedSignalFacts
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.MappingPlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * B40-fix2 item 2: a pause (or a stop) does not start the play that was waiting for the protection when the protection arrives.
 * `pause_cancels_a_play_waiting_for_fixed_output_protection` is the outside review's case of B40-fix1 (`ReviewB40Fix1Test`), ported: it
 * failed on the fix1 code (a pause through the service's player left the owed resume alone). It wires the player the way `MusicService`
 * does (a boundary test below pins that wiring). The others: a stop, a DAC that leaves, another DAC that arrives, and a play asked for
 * again after the pause, which waits for the same protection and then plays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B40Fix2PauseCancelsProtectedPlayTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scopes = mutableListOf<CoroutineScope>()
    private val scheduler = TestCoroutineScheduler()
    private val dac = FixedOutputDac("fixture", "Fixture dongle")

    // the signal path as the engine reports it: under the lock, no gain stage, full level
    private val signal = MutableStateFlow(AppliedSignalFacts(true, true, false))
    private val gain = MutableStateFlow(DualPlayerEngine.GainModel(1f, null, 1f))
    private val heldOff = MutableStateFlow(false)
    private val lowered = mutableListOf<Float>()
    private lateinit var coordinator: FixedOutputNoticeCoordinator
    private val underlying = mockk<Player>(relaxed = true)
    private lateinit var mapped: MappingPlayer

    @Before
    fun clean() {
        // B40-fix3: the held play lives on the main thread; the test's main shares the scope's virtual clock
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        context.getSharedPreferences(DeviceVolumeLedger.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        ShadowToast.reset()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler)).also(scopes::add)
        val profiles = mockk<DacProfileRepository>(relaxed = true)
        every { profiles.connected } returns MutableStateFlow(emptyList())
        every { profiles.profiles } returns MutableStateFlow(emptyList())
        val engine = mockk<DualPlayerEngine>(relaxed = true)
        every { engine.audioRouteState } returns MutableStateFlow(mockk<AudioRouteState>(relaxed = true))
        every { engine.appliedSignal } returns signal
        every { engine.gainModel } returns gain
        every { engine.lowerUserDigitalGainTo(any()) } answers { lowered += firstArg<Float>() }
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.purityHeldOffForDevice } returns heldOff
        coordinator = FixedOutputNoticeCoordinator(context, profiles, engine, prefs, dagger.Lazy { mockk(relaxed = true) }, scope)
        scheduler.runCurrent()
        // This is the pause hook MusicService installs on the session's player: the engine's, and the coordinator's own.
        mapped = MappingPlayer(
            underlying, context,
            onPause = engine::notifyExternalPauseInitiated,
            onPlaybackCancelled = coordinator::cancelOwedPlay,
        )
        // the callback MusicService installs: the action that starts the playback, through the session's player (and its gate); the
        // coordinator posts it to Main and checks it when it runs (B40-fix3)
        coordinator.resumePlayback = { mapped.play() }
        val pending = FixedOutputNoticeCoordinator::class.java.getDeclaredField("_pending").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (pending.get(coordinator) as MutableStateFlow<FixedOutputNotice?>).value = FixedOutputNotice(dac, true)
    }

    @After
    fun close() { scopes.forEach { it.cancel() }; Dispatchers.resetMain() }

    /** The protection arrives: the lock is lifted, a gain stage is in the signal, the level is at the safe start. */
    private fun protectionArrives() {
        signal.value = AppliedSignalFacts(true, false, true)
        gain.value = DualPlayerEngine.GainModel(DeviceVolumePolicy.safeStartGain, null, DeviceVolumePolicy.safeStartGain)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun answerNoControl() {
        coordinator.answerOwnControl(false)
        scheduler.runCurrent()
    }

    // ── the review's case ────────────────────────────────────────────────────────────────────────

    @Test
    fun pause_cancels_a_play_waiting_for_fixed_output_protection() {
        answerNoControl()
        mapped.pause()
        protectionArrives()
        verify(exactly = 1) { underlying.pause() }
        verify(exactly = 0) { underlying.play() }
    }

    // ── the other ways the listener says "not now" ───────────────────────────────────────────────

    @Test
    fun a_stop_cancels_it_too() {
        answerNoControl()
        mapped.stop()
        protectionArrives()
        verify(exactly = 0) { underlying.play() }
    }

    @Test
    fun a_set_play_when_ready_false_from_a_controller_cancels_it() {
        answerNoControl()
        mapped.playWhenReady = false
        protectionArrives()
        verify(exactly = 0) { underlying.play() }
        verify(exactly = 0) { underlying.playWhenReady = true }
    }

    @Test
    fun the_protection_still_applies_when_the_play_was_cancelled() {
        answerNoControl()
        mapped.pause()
        // the lock flag is lifted and the level asked for at the answer, whatever happens to the play
        assertThat(heldOff.value).isTrue()
        assertThat(lowered).containsExactly(DeviceVolumePolicy.safeStartGain)
        protectionArrives()
        assertThat(coordinatorHolds()).isFalse()
    }

    private fun coordinatorHolds(): Boolean = coordinator.allowPlayFor(dac).not()

    @Test
    fun a_dac_that_leaves_while_the_protection_settles_gets_no_play() {
        answerNoControl()
        coordinator.onDacChanged(null)
        protectionArrives()
        verify(exactly = 0) { underlying.play() }
    }

    @Test
    fun another_dac_arriving_cancels_the_play_owed_to_the_first() {
        answerNoControl()
        coordinator.onDacChanged(FixedOutputDac("another", "Another dongle"))
        protectionArrives()
        verify(exactly = 0) { underlying.play() }
    }

    @Test
    fun the_same_dac_announced_again_does_not_cancel_it() {
        answerNoControl()
        coordinator.onDacChanged(dac.copy(name = "Fixture dongle, renamed"))
        protectionArrives()
        verify(exactly = 1) { underlying.play() }
    }

    @Test
    fun without_a_pause_the_play_starts_when_the_protection_arrives() {
        answerNoControl()
        // not before
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 0) { underlying.play() }
        protectionArrives()
        verify(exactly = 1) { underlying.play() }
    }

    @Test
    fun a_play_asked_for_again_after_the_pause_waits_for_the_same_protection_and_then_plays() {
        answerNoControl()
        mapped.pause()
        // the listener presses Play again while the protection is still settling: held, and owed again
        assertThat(coordinator.allowPlayFor(dac)).isFalse()
        verify(exactly = 0) { underlying.play() }
        protectionArrives()
        verify(exactly = 1) { underlying.play() }
    }

    @Test
    fun a_play_asked_for_again_after_the_protection_arrived_just_plays() {
        answerNoControl()
        mapped.pause()
        protectionArrives()
        verify(exactly = 0) { underlying.play() }
        assertThat(coordinator.allowPlayFor(dac)).isTrue()
    }

    @Test
    fun a_cancelled_play_whose_protection_never_arrives_is_not_announced() {
        answerNoControl()
        mapped.pause()
        scheduler.advanceTimeBy(DeviceVolumePolicy.PROTECTION_BOUND_MS + 500)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        // no one is waiting for a play, so there is nothing to say "it did not start" about
        assertThat(ShadowToast.getTextOfLatestToast().toString()).doesNotContain("could not confirm")
        verify(exactly = 0) { underlying.play() }
    }

    // ── wiring ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun wiring_the_service_player_tells_the_coordinator_on_a_pause_a_stop_and_a_play_when_ready_false() {
        val service = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/MusicService.kt").readText().replace("\r\n", "\n")
        assertThat(service).contains("onPlaybackCancelled = { fixedOutputNotice.get().cancelOwedPlay() },")
        val mapping = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/player/MappingPlayer.kt").readText().replace("\r\n", "\n")
        val pause = mapping.substringAfter("override fun pause() {").substringBefore("\n    }\n")
        val stop = mapping.substringAfter("override fun stop() {").substringBefore("\n    }\n")
        val ready = mapping.substringAfter("override fun setPlayWhenReady(").substringBefore("\n    }\n")
        for (body in listOf(pause, stop, ready)) assertThat(body).contains("onPlaybackCancelled?.invoke()")
        val coordinator = File("src/main/java/com/lostf1sh/pixelplayeross/data/volume/FixedOutputNotice.kt").readText().replace("\r\n", "\n")
        assertThat(coordinator).contains("fixedOutputDac.collect { dac -> onDacChanged(dac) }")
        // B40-fix3: the held play is main-thread state, not atomics (the design is pinned in B40Fix3HeldPlayTest)
        assertThat(coordinator).doesNotContain("AtomicReference")
        assertThat(coordinator).doesNotContain("resumeOwed")
    }
}
