package com.lostf1sh.pixelplayeross.data.volume

import android.app.Application
import android.content.Context
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CompletableDeferred
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
 * B41-fix1 (A2), the open fault of the outside review of B40-fix3 (`B40-fix3-revision-codex.md`, R1): an asynchronous Purity result
 * must respect what happened while it was suspended. The activation (`PristineModeController.enable`) suspends on the lock's own
 * preferences; meanwhile a Pause or Stop clears the play the question holds, a DAC that leaves ends the attachment generation, and a
 * new Play starts another question. The coordinator kept the question as it was when the listener chose Purity and applied it when the
 * activation ended: a stale copy still said "holds a play", so it created an owed play again (even after a Pause, Stop or detach), a
 * rise confirmation asked after a Pause came back with the play intent on, and a detach re-registered the old DAC as the current one.
 *
 * The first five tests are the review's, ported (the first four **fail before**, the fifth is its positive control). They run the real
 * [MappingPlayer] in front of a mocked player and the real coordinator, wired as `MusicService` wires them; the Purity dependency
 * answers through a [CompletableDeferred], so the suspension is the one the real operation has, with no sleeps and nothing injected by
 * reflection. The rest are this batch's: the other ways a stale result can reach a question that is not its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B41Fix1PurityActivationTest {
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

    /** One deferred per call of `enable`, in the order they are made: the first call takes the first, and so on. */
    private val purityResults = ArrayDeque<CompletableDeferred<PristineToggleResult>>().apply { repeat(4) { addLast(CompletableDeferred()) } }
    private val handedOut = mutableListOf<CompletableDeferred<PristineToggleResult>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        context.getSharedPreferences(DeviceVolumeLedger.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        ShadowToast.reset()
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
        coEvery { pristine.enable(any()) } coAnswers {
            val next = purityResults.removeFirst()
            handedOut += next
            next.await()
        }
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

    private fun beginPurity() {
        mapped.play()
        coordinator.answerOwnControl(true)
        coordinator.choose(DeviceVolumeMode.PURITY)
        scheduler.runCurrent()
        assertThat(coordinator.pending.value?.stage).isEqualTo(VolumeQuestionStage.CHOOSE_MODE)
        verify(exactly = 0) { player.play() }
    }

    /** The [n]th activation (0 based) ends with [result]. */
    private fun finishPurity(n: Int = 0, result: PristineToggleResult = PristineToggleResult.Enabled(0f)) {
        handedOut[n].complete(result)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun answerOf(dac: FixedOutputDac): String? =
        context.getSharedPreferences(DeviceVolumeLedger.PREFS, Context.MODE_PRIVATE).getString(dac.key, null)

    // ── the review's five ───────────────────────────────────────────────────────────────────────

    @Test
    fun pause_while_purity_is_suspended_must_cancel_the_original_play() {
        beginPurity()
        mapped.pause()
        assertThat(coordinator.pending.value?.holdsPlayback).isFalse()
        finishPurity()
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun stop_while_purity_is_suspended_must_cancel_the_original_play() {
        beginPurity()
        mapped.stop()
        assertThat(coordinator.pending.value?.holdsPlayback).isFalse()
        finishPurity()
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun detach_while_purity_is_suspended_must_not_resume_on_another_output() {
        beginPurity()
        currentDac = null
        coordinator.onDacChanged(null)
        assertThat(coordinator.pending.value).isNull()
        finishPurity()
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun a_confirmation_arriving_after_pause_must_preserve_the_cancelled_intent() {
        beginPurity()
        mapped.pause()
        finishPurity(result = PristineToggleResult.NeedsConfirmation(6f, PristineModePolicy.RiseReason.DAC_RANGE))
        assertThat(coordinator.pending.value?.stage).isEqualTo(VolumeQuestionStage.CONFIRM_RISE)
        assertThat(coordinator.pending.value?.holdsPlayback).isFalse()
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun positive_control_a_current_uncancelled_purity_choice_resumes_once() {
        beginPurity()
        finishPurity()
        verify(exactly = 1) { player.play() }
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 1) { player.play() }
    }

    // ── the other ways a stale result could reach a question that is not its own ──────────────────

    @Test
    fun a_detach_leaves_no_trace_of_the_old_dacs_answer_and_does_not_register_it_as_attached() {
        beginPurity()
        currentDac = null
        coordinator.onDacChanged(null)
        finishPurity()
        assertThat(answerOf(a)).isNull()
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
        // and a later play for the other DAC is its own question, from the start
        currentDac = b
        mapped.play()
        assertThat(coordinator.pending.value?.dac).isEqualTo(b)
        assertThat(coordinator.pending.value?.stage).isEqualTo(VolumeQuestionStage.ASK_CONTROL)
    }

    @Test
    fun a_pause_and_a_new_play_start_a_new_question_that_the_old_activation_cannot_answer_or_close() {
        beginPurity()
        mapped.pause()
        mapped.play()   // the question comes back from its first stage, holding this play
        assertThat(coordinator.pending.value?.stage).isEqualTo(VolumeQuestionStage.ASK_CONTROL)
        assertThat(coordinator.pending.value?.holdsPlayback).isTrue()
        finishPurity()
        assertThat(coordinator.pending.value?.stage).isEqualTo(VolumeQuestionStage.ASK_CONTROL)
        assertThat(coordinator.pending.value?.holdsPlayback).isTrue()
        assertThat(answerOf(a)).isNull()
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun a_failure_after_the_dac_changed_does_not_toast_or_close_the_other_dacs_question() {
        beginPurity()
        currentDac = b
        coordinator.onDacChanged(b)
        mapped.play()   // B's own question, held
        assertThat(coordinator.pending.value?.dac).isEqualTo(b)
        finishPurity(result = PristineToggleResult.Failed("simulated"))
        assertThat(coordinator.pending.value?.dac).isEqualTo(b)
        assertThat(coordinator.pending.value?.holdsPlayback).isTrue()
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }

    @Test
    fun a_failure_for_the_question_that_is_still_there_is_told_and_closes_it() {
        beginPurity()
        finishPurity(result = PristineToggleResult.Failed("simulated"))
        assertThat(coordinator.pending.value).isNull()
        assertThat(ShadowToast.shownToastCount()).isEqualTo(1)
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun a_rise_confirmation_is_bound_the_same_way_a_pause_during_it_means_no_play() {
        beginPurity()
        finishPurity(0, PristineToggleResult.NeedsConfirmation(6f, PristineModePolicy.RiseReason.DAC_RANGE))
        assertThat(coordinator.pending.value?.stage).isEqualTo(VolumeQuestionStage.CONFIRM_RISE)
        assertThat(coordinator.pending.value?.holdsPlayback).isTrue()   // not paused yet: the play is still wanted
        coordinator.confirmRise()
        scheduler.runCurrent()
        mapped.pause()                                                   // while the second activation is suspended
        finishPurity(1)
        verify(exactly = 0) { player.play() }
        assertThat(answerOf(a)).isNotNull()                              // the answer itself is the listener's and stands
    }

    @Test
    fun a_rise_confirmation_for_a_dac_that_left_is_dropped() {
        beginPurity()
        finishPurity(0, PristineToggleResult.NeedsConfirmation(6f, PristineModePolicy.RiseReason.DAC_RANGE))
        coordinator.confirmRise()
        scheduler.runCurrent()
        currentDac = null
        coordinator.onDacChanged(null)
        finishPurity(1)
        assertThat(coordinator.pending.value).isNull()
        assertThat(answerOf(a)).isNull()
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun two_taps_on_purity_make_one_answer_and_one_play() {
        mapped.play()
        coordinator.answerOwnControl(true)
        coordinator.choose(DeviceVolumeMode.PURITY)
        coordinator.choose(DeviceVolumeMode.PURITY)   // the same stage, before the first activation ended
        scheduler.runCurrent()
        assertThat(handedOut).hasSize(2)
        finishPurity(0)
        finishPurity(1)
        verify(exactly = 1) { player.play() }
        assertThat(ShadowToast.shownToastCount()).isEqualTo(1)
    }

    // ── the design, pinned in the source ────────────────────────────────────────────────────────

    private fun source(path: String) = File("src/main/java/com/lostf1sh/pixelplayeross/$path").readText().replace("\r\n", "\n")

    @Test
    fun boundary_the_activation_keeps_a_generation_a_stage_and_an_identity_never_the_question_it_began_from() {
        val notice = source("data/volume/FixedOutputNotice.kt")
        // B41-fix2 added the question's identity to what it keeps (a stage alone does not say which question it was asked from)
        assertThat(notice).contains("private class PurityRequest(val epoch: Long, val dacKey: String, val stage: VolumeQuestionStage, val question: Long)")
        // the suspended function is given the request and a name for the log, not a copy of the question
        assertThat(notice).contains("private suspend fun enterPurity(request: PurityRequest, dacName: String, confirmRise: Boolean) {")
        val enter = notice.substringAfter("private suspend fun enterPurity(").substringBefore("\n    }\n")
        assertThat(enter).doesNotContain("notice")
        // the result is applied in one place, on Main, after the generation and the question are checked, and to the question as it is then
        val finish = notice.substringAfter("private fun finishPurity(").substringBefore("\n    }\n")
        assertThat(finish).contains("request.epoch != epoch")
        assertThat(finish).contains("current.dac.key != request.dacKey")
        assertThat(finish).contains("current.stage != request.stage")
        assertThat(finish).contains("current.question != request.question")
        assertThat(finish.indexOf("return")).isLessThan(finish.indexOf("apply(current,"))
        assertThat(finish).contains("apply(current, VolumeQuestion.afterModeChoice(current, DeviceVolumeMode.PURITY) { outcome })")
        assertThat(notice.split("withContext(Dispatchers.Main.immediate) { finishPurity(request, dacName, outcome) }").size - 1).isEqualTo(1)
        // both entries take it from the main thread, where the generation lives
        assertThat(notice.substringAfter("fun choose(mode: DeviceVolumeMode) {").substringBefore("\n    }\n")).contains("if (!onMainThread()) {")
        assertThat(notice.substringAfter("fun confirmRise() {").substringBefore("\n    }\n")).contains("if (!onMainThread()) {")
    }
}
