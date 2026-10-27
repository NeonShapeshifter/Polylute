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
 * B41-fix2 (the outside review of B41-fix1, R1; ported from its `ReviewB41Fix1PurityQuestionTest`, the seven cases unchanged): an asynchronous
 * Purity result must respect later cancellation, attachment changes and a **question that was closed and opened again**.
 *
 * The session player (`MappingPlayer`) and the coordinator are real; the Purity dependency suspends at the same boundary as its real preference
 * operations; no device. `an_old_activation_must_not_answer_a_reopened_question_at_the_same_stage` and
 * `dismiss_and_reopen_must_not_let_the_old_activation_choose_purity` fail against the tree as B41-fix1 left it (one `Player.play()` where there
 * must be none); the other five, the controls, pass there and after.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B41Fix2PurityQuestionTest {
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
    private val purityResult = kotlinx.coroutines.CompletableDeferred<PristineToggleResult>()

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
        coEvery { pristine.enable(any()) } coAnswers { purityResult.await() }
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

    private fun finishPurity(result: PristineToggleResult = PristineToggleResult.Enabled(0f)) {
        purityResult.complete(result)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

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
        finishPurity(PristineToggleResult.NeedsConfirmation(6f, PristineModePolicy.RiseReason.DAC_RANGE))
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

    @Test
    fun an_old_activation_must_not_answer_a_reopened_question_at_the_same_stage() {
        beginPurity()
        mapped.pause()
        mapped.play()
        coordinator.answerOwnControl(true)
        // New question: listener has confirmed the knob, but has not chosen a mode again.
        assertThat(coordinator.pending.value?.stage).isEqualTo(VolumeQuestionStage.CHOOSE_MODE)
        finishPurity()
        verify(exactly = 0) { player.play() }
        assertThat(coordinator.pending.value?.stage).isEqualTo(VolumeQuestionStage.CHOOSE_MODE)
    }

    @Test
    fun dismiss_and_reopen_must_not_let_the_old_activation_choose_purity() {
        beginPurity()
        coordinator.dismiss()
        mapped.play()
        coordinator.answerOwnControl(true)
        finishPurity()
        verify(exactly = 0) { player.play() }
        assertThat(coordinator.pending.value?.stage).isEqualTo(VolumeQuestionStage.CHOOSE_MODE)
    }

    // ── added by B41-fix2: the identity itself, and what else an old activation must not do to a reopened question ──

    @Test
    fun a_question_keeps_its_identity_through_its_stages_and_a_pause_and_another_question_has_its_own() {
        mapped.play()
        val first = coordinator.pending.value!!.question
        assertThat(first).isGreaterThan(0L)
        coordinator.answerOwnControl(true)
        assertThat(coordinator.pending.value!!.stage).isEqualTo(VolumeQuestionStage.CHOOSE_MODE)
        assertThat(coordinator.pending.value!!.question).isEqualTo(first)
        // a Pause makes a valid copy of the same question: the identity is the question's, not the copy's
        mapped.pause()
        assertThat(coordinator.pending.value!!.holdsPlayback).isFalse()
        assertThat(coordinator.pending.value!!.question).isEqualTo(first)
        // another question for the same DAC is another identity
        mapped.play()
        assertThat(coordinator.pending.value!!.stage).isEqualTo(VolumeQuestionStage.ASK_CONTROL)
        assertThat(coordinator.pending.value!!.question).isNotEqualTo(first)
    }

    @Test
    fun an_old_failure_must_not_close_or_toast_over_a_reopened_question() {
        beginPurity()
        mapped.pause()
        mapped.play()
        coordinator.answerOwnControl(true)
        val reopened = coordinator.pending.value
        finishPurity(PristineToggleResult.Failed("DAC volume could not be lowered and verified"))
        assertThat(coordinator.pending.value).isEqualTo(reopened)
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun an_old_confirmation_must_not_replace_a_reopened_question_with_its_rise() {
        beginPurity()
        mapped.pause()
        mapped.play()
        coordinator.answerOwnControl(true)
        val reopened = coordinator.pending.value
        finishPurity(PristineToggleResult.NeedsConfirmation(6f, PristineModePolicy.RiseReason.DAC_RANGE))
        assertThat(coordinator.pending.value).isEqualTo(reopened)
        assertThat(coordinator.pending.value?.riseDb).isNull()
    }

    @Test
    fun the_reopened_question_can_still_be_answered_and_plays_once_when_it_is() {
        beginPurity()
        mapped.pause()
        mapped.play()
        coordinator.answerOwnControl(true)
        finishPurity()                                  // the old activation ends: dropped
        verify(exactly = 0) { player.play() }
        // the listener now chooses Purity in the new question; its own activation is the one that counts
        coordinator.choose(DeviceVolumeMode.PURITY)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 1) { player.play() }
    }
}
