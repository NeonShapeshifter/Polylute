package com.lostf1sh.pixelplayeross.data.volume

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.AppliedSignalFacts
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * B40-fix1 (item 1): an answer to the volume question flips two safety states (the Pure Direct lock lifts, the level starts low), and
 * the play the question held may start only after the signal path has them. The world here is the production one in miniature: the
 * lock and the level reach the signal path through collectors and the main looper, after a delay of their own, so a play that starts
 * the moment the answer is written is heard under the old state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FixedOutputProtectionOrderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))

    // the signal path as the engine reports it: under the lock, no gain stage, full level
    private val signal = MutableStateFlow(AppliedSignalFacts(known = true, lockInForce = true, digitalVolume = false))
    private val gain = MutableStateFlow(DualPlayerEngine.GainModel(1f, null, 1f))
    private val heldOff = MutableStateFlow(false)

    private val events = mutableListOf<String>()
    private val atResume = mutableListOf<String>()
    private var lockAppliedAfterMs = 50L
    private var levelAppliedAfterMs = 120L
    private var collectorsAlive = true

    @Before
    fun clean() {
        // B40-fix3: the held play lives on the main thread; the test's main shares the scope's virtual clock
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        context.getSharedPreferences(DeviceVolumeLedger.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        ShadowToast.reset()
    }

    @After
    fun close() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    /** The time passes and the main thread runs what was posted to it (the play is posted, and checked when it runs). */
    private fun pass(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun coordinator(): FixedOutputNoticeCoordinator {
        val profiles = mockk<DacProfileRepository>(relaxed = true)
        every { profiles.connected } returns MutableStateFlow(emptyList())
        every { profiles.profiles } returns MutableStateFlow(emptyList())
        val engine = mockk<DualPlayerEngine>(relaxed = true)
        every { engine.audioRouteState } returns MutableStateFlow(mockk<AudioRouteState>(relaxed = true))
        every { engine.appliedSignal } returns signal
        every { engine.gainModel } returns gain
        every { engine.lowerUserDigitalGainTo(any()) } answers {
            // the lock has to be lifted by the time the level is asked for
            events += "lower(held-off=${heldOff.value})"
            val target = firstArg<Float>()
            if (collectorsAlive) scope.launch {
                delay(levelAppliedAfterMs)
                gain.value = DualPlayerEngine.GainModel(minOf(gain.value.userDigitalGain, target), null, target)
                delay(20)
                signal.value = signal.value.copy(digitalVolume = true)
            }
        }
        val preferences = mockk<UserPreferencesRepository>(relaxed = true)
        every { preferences.purityHeldOffForDevice } returns heldOff
        // the service's collector: the lock reaches the signal path a moment after the flag changes
        scope.launch {
            heldOff.collect { held ->
                events += "held-off=$held"
                if (collectorsAlive) {
                    delay(lockAppliedAfterMs)
                    signal.value = signal.value.copy(lockInForce = !held)
                }
            }
        }
        val pristine = mockk<PristineModeController>(relaxed = true)
        coEvery { pristine.enable(any()) } returns PristineToggleResult.Enabled(0f)
        val made = FixedOutputNoticeCoordinator(context, profiles, engine, preferences, dagger.Lazy { pristine }, scope)
        scheduler.runCurrent()
        events.clear()
        made.resumePlayback = {
            events += "resume"
            atResume += "held-off=${heldOff.value} lockInForce=${signal.value.lockInForce} digitalVolume=${signal.value.digitalVolume} gain=${gain.value.userDigitalGain}"
        }
        return made
    }

    private fun hold(made: FixedOutputNoticeCoordinator, stage: VolumeQuestionStage = VolumeQuestionStage.ASK_CONTROL) {
        val pending = FixedOutputNoticeCoordinator::class.java.getDeclaredField("_pending").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (pending.get(made) as MutableStateFlow<FixedOutputNotice?>).value =
            FixedOutputNotice(FixedOutputDac("dac-a", "Dongle A"), holdsPlayback = true, stage = stage)
    }

    private fun assertProtectedAtResume() {
        assertThat(atResume).hasSize(1)
        assertThat(atResume.single()).contains("held-off=true")
        assertThat(atResume.single()).contains("lockInForce=false")
        assertThat(atResume.single()).contains("digitalVolume=true")
        val level = atResume.single().substringAfter("gain=").toFloat()
        assertThat(level).isAtMost(DeviceVolumePolicy.safeStartGain + 1e-4f)
    }

    @Test
    fun a_no_control_answer_starts_the_held_play_only_after_the_lock_and_the_level_are_in_effect() {
        val made = coordinator()
        hold(made)
        made.answerOwnControl(false)
        // the answer is written and the play must still be waiting: the signal path is under the lock at full level
        scheduler.runCurrent()
        assertThat(events).doesNotContain("resume")
        assertThat(signal.value.lockInForce).isTrue()
        pass(1_000)
        assertThat(events.last()).isEqualTo("resume")
        assertProtectedAtResume()
        // in order: the level is asked for only when the lock flag is already lifted (the flag is read at the call)
        assertThat(events).contains("lower(held-off=true)")
        assertThat(events).doesNotContain("lower(held-off=false)")
    }

    @Test
    fun a_knob_answer_of_resonance_waits_the_same_way() {
        val made = coordinator()
        hold(made)
        made.answerOwnControl(true)
        made.choose(DeviceVolumeMode.RESONANCE)
        scheduler.runCurrent()
        assertThat(events).doesNotContain("resume")
        pass(1_000)
        assertThat(events.last()).isEqualTo("resume")
        assertProtectedAtResume()
    }

    @Test
    fun a_play_that_cannot_be_confirmed_within_the_bound_is_not_started_and_the_listener_is_told() {
        collectorsAlive = false
        val made = coordinator()
        hold(made)
        made.answerOwnControl(false)
        scheduler.advanceTimeBy(DeviceVolumePolicy.PROTECTION_BOUND_MS + 500)
        scheduler.runCurrent()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertThat(events).doesNotContain("resume")
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(context.getString(R.string.fixed_output_toast_not_confirmed))
    }

    @Test
    fun a_knob_answer_of_purity_keeps_its_own_flow_and_does_not_wait_for_a_gain_stage() {
        val made = coordinator()
        hold(made)
        made.answerOwnControl(true)
        made.choose(DeviceVolumeMode.PURITY)
        scheduler.runCurrent()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        // Purity adds nothing: no lock lifted, no level asked for, and the play it held is released as before
        assertThat(events).doesNotContain("held-off=true")
        assertThat(events.none { it.startsWith("lower") }).isTrue()
        assertThat(events).contains("resume")
    }

    @Test
    fun the_first_play_on_a_later_connection_of_an_answered_device_waits_for_the_same_things() {
        val dac = FixedOutputDac("dac-a", "Dongle A")
        context.getSharedPreferences(DeviceVolumeLedger.PREFS, Context.MODE_PRIVATE).edit()
            .putString(dac.key, DeviceVolumeAnswer.NO_CONTROL).commit()
        val made = coordinator()
        // a fresh attachment: under the lock, full level, collectors still to deliver
        assertThat(made.answeredPlayMayStart(dac)).isFalse()
        pass(1_000)
        assertThat(events.last()).isEqualTo("resume")
        assertProtectedAtResume()
        // now in effect: the next play passes, and so does one after the listener raised Polylute's own volume
        assertThat(made.answeredPlayMayStart(dac)).isTrue()
        gain.value = DualPlayerEngine.GainModel(0.5f, null, 0.5f)
        assertThat(made.answeredPlayMayStart(dac)).isTrue()
    }

    @Test
    fun a_device_that_answered_purity_is_never_held_by_this_wait() {
        val dac = FixedOutputDac("dac-a", "Dongle A")
        context.getSharedPreferences(DeviceVolumeLedger.PREFS, Context.MODE_PRIVATE).edit()
            .putString(dac.key, DeviceVolumeAnswer.KNOB_PURITY).commit()
        val made = coordinator()
        assertThat(made.answeredPlayMayStart(dac)).isTrue()
        assertThat(events).isEmpty()
    }

    @Test
    fun protection_is_in_effect_only_with_the_lock_lifted_a_gain_stage_present_and_the_level_at_or_below_the_safe_start() {
        val safe = DeviceVolumePolicy.safeStartGain
        val ok = AppliedSignalFacts(known = true, lockInForce = false, digitalVolume = true)
        assertThat(DeviceVolumePolicy.protectionInEffect(ok, safe)).isTrue()
        assertThat(DeviceVolumePolicy.protectionInEffect(ok, safe / 2)).isTrue()
        assertThat(DeviceVolumePolicy.protectionInEffect(ok, safe * 2)).isFalse()
        assertThat(DeviceVolumePolicy.protectionInEffect(ok.copy(known = false), safe)).isFalse()
        assertThat(DeviceVolumePolicy.protectionInEffect(ok.copy(lockInForce = true), safe)).isFalse()
        assertThat(DeviceVolumePolicy.protectionInEffect(ok.copy(digitalVolume = false), safe)).isFalse()
    }
}
