package com.lostf1sh.pixelplayeross.data.volume

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.AppliedSignalFacts
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.MappingPlayer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import timber.log.Timber

/**
 * B40-fix3: the play the volume question holds, as one owner on the main thread with an attachment generation. The outside review of
 * B40-fix2 (ported in [B40Fix3ReviewTest]) found a settle that outlived its attachment, a new DAC's play consumed by the old DAC's wait, and a
 * play already posted to Main that a Pause could not stop. These tests run the coordinator as the service does: a test main dispatcher on
 * the same virtual clock as the scope that waits, the real Main handler that the resume is posted to, and the real [MappingPlayer] in
 * front of a mocked player, with the gate and the pause hook wired as `MusicService` wires them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B40Fix3HeldPlayTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val a = FixedOutputDac("fixture-a", "Fixture dongle A")
    private val b = FixedOutputDac("fixture-b", "Fixture dongle B")
    private var currentDac: FixedOutputDac? = a

    // the signal path as the engine reports it: under the lock, no gain stage, full level
    private val signal = MutableStateFlow(AppliedSignalFacts(true, true, false))
    private val gain = MutableStateFlow(DualPlayerEngine.GainModel(1f, null, 1f))
    private val heldOff = MutableStateFlow(false)
    private val lowered = mutableListOf<Float>()
    private val player = mockk<Player>(relaxed = true)
    private lateinit var coordinator: FixedOutputNoticeCoordinator
    private lateinit var mapped: MappingPlayer
    private val warnings = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        context.getSharedPreferences(DeviceVolumeLedger.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        ShadowToast.reset()
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { if (priority >= android.util.Log.WARN) warnings += message }
        })
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
        val pristine = mockk<PristineModeController>(relaxed = true)
        coEvery { pristine.enable(any()) } returns PristineToggleResult.Enabled(0f)
        coordinator = FixedOutputNoticeCoordinator(context, profiles, engine, prefs, dagger.Lazy { pristine }, scope)
        scheduler.runCurrent()
        // wired as MusicService wires the session's player: the engine's pause hook, the coordinator's cancellation, the coordinator's gate
        mapped = MappingPlayer(
            player, context,
            onPause = engine::notifyExternalPauseInitiated,
            onPlaybackCancelled = coordinator::cancelOwedPlay,
            playGate = { currentDac?.let(coordinator::allowPlayFor) ?: true },
        )
        coordinator.resumePlayback = { mapped.play() }
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun answered(dac: FixedOutputDac, answer: String = DeviceVolumeAnswer.NO_CONTROL) {
        context.getSharedPreferences(DeviceVolumeLedger.PREFS, Context.MODE_PRIVATE).edit().putString(dac.key, answer).commit()
    }

    /** The protection arrives in the signal path (the lock is lifted, a gain stage is there, the level is at the safe start). */
    private fun protectionArrives() {
        signal.value = AppliedSignalFacts(true, false, true)
        gain.value = DualPlayerEngine.GainModel(DeviceVolumePolicy.safeStartGain, null, DeviceVolumePolicy.safeStartGain)
    }

    private fun protectionGoesAway() {
        signal.value = AppliedSignalFacts(true, true, false)
        gain.value = DualPlayerEngine.GainModel(1f, null, 1f)
    }

    /** The virtual clock runs what waits; Main then runs what was posted to it. */
    private fun settleTheWorld() {
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun pass(ms: Long) {
        scheduler.advanceTimeBy(ms)
        settleTheWorld()
    }

    /** The listener presses Play on [currentDac]: through the real session player and its gate. */
    private fun press() = mapped.play()

    // ── a settle that outlives its attachment ────────────────────────────────────────────────────

    @Test
    fun a_detach_during_the_settle_ends_the_generation_so_the_reconnection_waits_again() {
        answered(a)
        press()                                   // generation 1: the protection is applied, the play is wanted
        assertThat(heldOff.value).isTrue()
        currentDac = null
        coordinator.onDacChanged(null)            // detach: the settle is cancelled
        protectionArrives()                       // the old request completes after the detach
        settleTheWorld()
        verify(exactly = 0) { player.play() }
        protectionGoesAway()                      // later, another output at full level; the same DAC is plugged again
        currentDac = a
        coordinator.onDacChanged(a)
        press()
        verify(exactly = 0) { player.play() }     // the first play of the new attachment waits for its own safe start
        protectionArrives()
        settleTheWorld()
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun a_to_nothing_to_a_with_the_old_confirmation_arriving_late_gives_one_play_from_the_new_generation_only() {
        answered(a)
        press()                                   // generation 1 settling, play wanted
        coordinator.onDacChanged(null)            // generation 2 (nothing)
        coordinator.onDacChanged(a)               // generation 3
        protectionGoesAway()
        press()                                   // generation 3's own play, its own settle
        verify(exactly = 0) { player.play() }
        protectionArrives()                       // the confirmation everyone was waiting for, arriving late for generation 1
        settleTheWorld()
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun a_cancelled_settle_does_not_time_out_afterwards_or_announce_anything() {
        answered(a)
        press()
        coordinator.onDacChanged(null)
        pass(DeviceVolumePolicy.PROTECTION_BOUND_MS + 1_000)
        assertThat(warnings.none { "were not in effect within" in it }).isTrue()
        assertThat(ShadowToast.getLatestToast()).isNull()
    }

    // ── a new DAC while another settles ──────────────────────────────────────────────────────────

    @Test
    fun a_new_dacs_protection_is_applied_at_once_even_while_the_old_settle_is_still_being_cancelled() {
        answered(a)
        answered(b)
        press()                                   // A settles: its lock and level asked for
        assertThat(lowered).hasSize(1)
        heldOff.value = false                     // something put the flag back; B's generation must set it itself
        currentDac = b
        coordinator.onDacChanged(b)
        press()                                   // B's first play, before the scheduler has even processed the cancellation of A's job
        assertThat(heldOff.value).isTrue()
        assertThat(lowered).hasSize(2)
        protectionArrives()
        settleTheWorld()
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun a_play_for_the_new_dac_is_not_consumed_by_the_old_dacs_settle() {
        answered(a)
        answered(b)
        press()
        currentDac = b
        coordinator.onDacChanged(b)
        press()
        protectionArrives()
        settleTheWorld()
        verify(exactly = 1) { player.play() }
        // and nothing is owed to A afterwards
        pass(5_000)
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun a_dac_the_flow_has_not_announced_yet_is_attached_by_the_gate_itself() {
        answered(a)
        // no onDacChanged: the gate reads the attachment from the play
        press()
        assertThat(heldOff.value).isTrue()
        assertThat(lowered).hasSize(1)
        protectionArrives()
        settleTheWorld()
        verify(exactly = 1) { player.play() }
    }

    // ── a play already posted to Main ────────────────────────────────────────────────────────────

    @Test
    fun a_dac_change_between_the_post_and_the_run_means_no_play() {
        answered(a)
        press()
        protectionArrives()
        scheduler.runCurrent()                    // confirmed: the resume is posted, Main has not run it
        coordinator.onDacChanged(null)            // the DAC is unplugged
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun a_reconnection_between_the_post_and_the_run_means_no_play_for_the_old_attachment() {
        answered(a)
        press()
        protectionArrives()
        scheduler.runCurrent()
        coordinator.onDacChanged(null)
        coordinator.onDacChanged(a)               // the same DAC again: a new generation
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 0) { player.play() }
    }

    // ── plays and pauses during one settle ───────────────────────────────────────────────────────

    @Test
    fun two_plays_during_one_settle_make_one_play() {
        answered(a)
        press()
        press()
        press()
        protectionArrives()
        settleTheWorld()
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun a_pause_during_the_settle_and_then_a_play_waits_for_the_same_protection_and_plays() {
        answered(a)
        press()
        mapped.pause()
        press()
        verify(exactly = 0) { player.play() }
        assertThat(lowered).hasSize(1)            // the same settle, not a second one
        protectionArrives()
        settleTheWorld()
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun a_pause_after_the_last_play_leaves_nothing_wanted() {
        answered(a)
        press()
        mapped.pause()
        protectionArrives()
        settleTheWorld()
        verify(exactly = 0) { player.play() }
        // the protection went ahead all the same: the next play just plays
        press()
        verify(exactly = 1) { player.play() }
    }

    // ── the bound ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_bound_expires_with_the_play_still_wanted_so_there_is_no_play_a_log_line_and_the_toast() {
        answered(a)
        press()
        pass(DeviceVolumePolicy.PROTECTION_BOUND_MS + 500)
        verify(exactly = 0) { player.play() }
        assertThat(warnings.any { "were not in effect within" in it }).isTrue()
        assertThat(ShadowToast.getTextOfLatestToast().toString()).isEqualTo(context.getString(R.string.fixed_output_toast_not_confirmed))
    }

    @Test
    fun the_bound_expires_after_a_pause_so_there_is_no_play_and_no_toast_but_the_log_line() {
        answered(a)
        press()
        mapped.pause()
        pass(DeviceVolumePolicy.PROTECTION_BOUND_MS + 500)
        verify(exactly = 0) { player.play() }
        assertThat(warnings.any { "were not in effect within" in it }).isTrue()
        assertThat(ShadowToast.getLatestToast()).isNull()
    }

    @Test
    fun after_the_bound_a_new_play_starts_a_new_settle_that_can_succeed() {
        answered(a)
        press()
        pass(DeviceVolumePolicy.PROTECTION_BOUND_MS + 500)
        press()
        assertThat(lowered).hasSize(2)
        protectionArrives()
        settleTheWorld()
        verify(exactly = 1) { player.play() }
    }

    // ── the question ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_pause_while_the_question_is_open_means_answering_it_does_not_play() {
        press()                                   // no answer yet: the question is raised and holds the play
        assertThat(coordinator.pending.value?.holdsPlayback).isTrue()
        mapped.pause()
        assertThat(coordinator.pending.value?.holdsPlayback).isFalse()
        coordinator.answerOwnControl(false)
        scheduler.runCurrent()
        assertThat(heldOff.value).isTrue()         // the protection goes ahead
        protectionArrives()
        settleTheWorld()
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun answering_the_question_plays_once_the_protection_is_confirmed() {
        press()
        coordinator.answerOwnControl(false)
        scheduler.runCurrent()
        verify(exactly = 0) { player.play() }
        protectionArrives()
        settleTheWorld()
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun a_dac_that_leaves_while_the_question_is_open_takes_the_question_and_the_play_with_it() {
        press()
        coordinator.onDacChanged(null)
        assertThat(coordinator.pending.value).isNull()
        coordinator.answerOwnControl(false)
        protectionArrives()
        settleTheWorld()
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun a_purity_answer_posts_its_play_and_a_pause_before_it_runs_cancels_it() {
        press()
        coordinator.answerOwnControl(true)
        coordinator.choose(DeviceVolumeMode.PURITY)
        scheduler.runCurrent()                    // the answer is applied on Main, the resume posted, not yet run
        mapped.pause()
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 0) { player.play() }
        // and without the pause it plays once, and applies no protection (Purity adds nothing)
        press()
        coordinator.answerOwnControl(true)
        coordinator.choose(DeviceVolumeMode.PURITY)
        settleTheWorld()
        assertThat(heldOff.value).isFalse()
        assertThat(lowered).isEmpty()
    }

    // ── the design, pinned in the source ─────────────────────────────────────────────────────────

    private fun source(path: String) = File("src/main/java/com/lostf1sh/pixelplayeross/$path").readText().replace("\r\n", "\n")

    @Test
    fun boundary_the_resume_is_called_in_one_place_and_checks_the_intent_and_the_generation_when_it_runs() {
        val notice = source("data/volume/FixedOutputNotice.kt")
        assertThat(notice.split("resumePlayback?.invoke()").size - 1).isEqualTo(1)
        val post = notice.substringAfter("private fun postResume(ticket: Wanted) {").substringBefore("\n    }\n")
        assertThat(post).contains("mainHandler.post {")
        assertThat(post).contains("if (wanted === ticket && ticket.epoch == epoch) {")
        // the call is inside the check, which is inside the posted Runnable
        assertThat(post.indexOf("resumePlayback?.invoke()")).isGreaterThan(post.indexOf("if (wanted === ticket && ticket.epoch == epoch) {"))
        assertThat(post.indexOf("if (wanted === ticket && ticket.epoch == epoch) {")).isGreaterThan(post.indexOf("mainHandler.post {"))
        // the service's callback is the bare action: it posts nothing and plays through the session's player (and its gate)
        val service = source("data/service/MusicService.kt")
        assertThat(service).contains("fixedOutputNotice.get().resumePlayback = { mediaSession?.player?.play() }")
        assertThat(service).doesNotContain("resumePlayback = { publicationHandler.post")
    }

    @Test
    fun boundary_the_held_play_is_main_thread_state_with_no_atomics_and_the_generation_changes_in_one_place() {
        val notice = source("data/volume/FixedOutputNotice.kt")
        // the code, not the comments that explain why there are none
        val code = notice.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//.*"), "")
        for (forbidden in listOf("AtomicReference", "AtomicLong", "AtomicBoolean", "compareAndSet", "@Volatile", "resumeOwed", "startConfirmedFor", "settling")) {
            assertThat(code).doesNotContain(forbidden)
        }
        // the only shared collection left is the descriptor cache, and it says why
        assertThat(notice).contains("private val answers = ConcurrentHashMap<String, Boolean>()")
        assertThat(notice.split("epoch++").size - 1).isEqualTo(1)
        assertThat(notice.substringAfter("internal fun onDacChanged(dac: FixedOutputDac?) {").substringBefore("\n    }\n")).contains("epoch++")
        // the DAC collector and the settle job run on Main
        assertThat(notice).contains("appScope.launch(Dispatchers.Main.immediate) {\n            fixedOutputDac.collect { dac -> onDacChanged(dac) }")
        assertThat(notice).contains("appScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {")
        // the settle belongs to its generation: cancelled when it ends, its result dropped if the generation changed
        assertThat(notice.substringAfter("internal fun onDacChanged(dac: FixedOutputDac?) {").substringBefore("\n    }\n")).contains("settle?.job?.cancel()")
        assertThat(notice).contains("if (epoch != generation) return@launch")
        // a confirmation is made in two places only: the gate seeing the protection in effect now, and the settle job of that generation
        assertThat(notice.split("confirmedEpoch = ").size - 1).isEqualTo(3)   // the declaration, the gate, the job
        // the protection of a generation is applied before its settle starts, with nothing that can skip it
        val begin = notice.substringAfter("private fun beginSettle(dac: FixedOutputDac) {").substringBefore("\n    }\n")
        assertThat(begin.indexOf("preferences.purityHeldOffForDevice.value = true")).isLessThan(begin.indexOf("engine.lowerUserDigitalGainTo("))
        assertThat(begin.indexOf("engine.lowerUserDigitalGainTo(")).isLessThan(begin.indexOf("appScope.launch("))
    }

    @Test
    fun boundary_a_pause_and_a_stop_from_any_controller_clear_the_intent() {
        val mapping = source("data/service/player/MappingPlayer.kt")
        for (head in listOf("override fun pause() {", "override fun stop() {", "override fun setPlayWhenReady(")) {
            assertThat(mapping.substringAfter(head).substringBefore("\n    }\n")).contains("onPlaybackCancelled?.invoke()")
        }
        assertThat(source("data/service/MusicService.kt")).contains("onPlaybackCancelled = { fixedOutputNotice.get().cancelOwedPlay() },")
        val cancel = source("data/volume/FixedOutputNotice.kt").substringAfter("fun cancelOwedPlay() {").substringBefore("\n    }\n")
        assertThat(cancel).contains("wanted = null")
        assertThat(cancel).contains("copy(holdsPlayback = false)")
    }
}
