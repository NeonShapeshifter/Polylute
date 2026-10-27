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

/**
 * B40-fix3, ported as it came from the outside review of B40-fix2 (`ReviewB40Fix2Test`): the held play across attachments and across the
 * two queues the service really has (the coordinator's own dispatcher, and the Main handler `resumePlayback` posts to). Four of its five
 * cases failed on fix2's code; the fifth is its positive control (a play that was not cancelled starts exactly once).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B40Fix3ReviewTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val signal = MutableStateFlow(AppliedSignalFacts(true, true, false))
    private val gain = MutableStateFlow(DualPlayerEngine.GainModel(1f, null, 1f))
    private val dac = FixedOutputDac("fixture-a", "Fixture dongle A")
    private var currentDac: FixedOutputDac? = dac
    private val player = mockk<Player>(relaxed = true)
    private lateinit var coordinator: FixedOutputNoticeCoordinator
    private lateinit var mapped: MappingPlayer

    @Before fun setup() {
        // B40-fix3: the coordinator keeps the held play on the main thread (`Dispatchers.Main.immediate`); the test's main is the same virtual
        // clock as the scope it is given, so one `scheduler` drives everything that waits.
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        context.getSharedPreferences(DeviceVolumeLedger.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        val profiles = mockk<DacProfileRepository>(relaxed = true)
        every { profiles.connected } returns MutableStateFlow(emptyList())
        every { profiles.profiles } returns MutableStateFlow(emptyList())
        val engine = mockk<DualPlayerEngine>(relaxed = true)
        every { engine.audioRouteState } returns MutableStateFlow(mockk<AudioRouteState>(relaxed = true))
        every { engine.appliedSignal } returns signal
        every { engine.gainModel } returns gain
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.purityHeldOffForDevice } returns MutableStateFlow(false)
        coordinator = FixedOutputNoticeCoordinator(context, profiles, engine, prefs, dagger.Lazy { mockk(relaxed = true) }, scope)
        scheduler.runCurrent()
        mapped = MappingPlayer(player, context, onPause = engine::notifyExternalPauseInitiated,
            onPlaybackCancelled = coordinator::cancelOwedPlay,
            playGate = { currentDac?.let(coordinator::allowPlayFor) ?: true })
        // The callback MusicService installs, as it is now: the action that starts the playback (the coordinator posts it, and checks it when it
        // runs). In fix2 it posted by itself and returned, which is what the review exploited.
        coordinator.resumePlayback = { mapped.play() }
        val pending = FixedOutputNoticeCoordinator::class.java.getDeclaredField("_pending").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (pending.get(coordinator) as MutableStateFlow<FixedOutputNotice?>).value = FixedOutputNotice(dac, true)
        coordinator.answerOwnControl(false)
        scheduler.runCurrent()
    }

    @After fun cleanup() { scope.cancel(); Dispatchers.resetMain() }

    private fun confirmWithoutRunningMain() {
        signal.value = AppliedSignalFacts(true, false, true)
        gain.value = DualPlayerEngine.GainModel(DeviceVolumePolicy.safeStartGain, null, DeviceVolumePolicy.safeStartGain)
        scheduler.runCurrent()
    }

    @Test fun confirming_the_current_attachment_starts_one_play_when_it_was_not_cancelled() {
        confirmWithoutRunningMain()
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 1) { player.play() }
    }

    @Test fun pause_cancels_a_resume_already_posted_to_main() {
        confirmWithoutRunningMain()
        // The wait completed, but Main has not performed the posted Play yet. Pause is the newer intent.
        mapped.pause()
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 1) { player.pause() }
        verify(exactly = 0) { player.play() }
    }

    @Test fun stop_cancels_a_resume_already_posted_to_main() {
        confirmWithoutRunningMain()
        mapped.stop()
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 1) { player.stop() }
        verify(exactly = 0) { player.play() }
    }

    @Test fun a_wait_from_an_unplugged_attachment_cannot_confirm_a_later_attachment() {
        // Detach while its protection is still pending. The owed Play is cancelled by fix2.
        currentDac = null
        coordinator.onDacChanged(null)
        // The old lowering request completes after detach. It must not mark a disconnected attachment confirmed.
        confirmWithoutRunningMain()
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 0) { player.play() }
        // Much later, another output was used at a higher level. Reconnect the same DAC: a new attachment needs a safe start.
        signal.value = AppliedSignalFacts(true, true, false)
        gain.value = DualPlayerEngine.GainModel(1f, null, 1f)
        currentDac = dac
        coordinator.onDacChanged(dac)
        assertThat(coordinator.allowPlayFor(dac)).isFalse()
    }

    @Test fun a_new_dac_play_is_not_consumed_by_the_old_dac_wait() {
        val other = FixedOutputDac("fixture-b", "Fixture dongle B")
        context.getSharedPreferences(DeviceVolumeLedger.PREFS, Context.MODE_PRIVATE).edit()
            .putString(other.key, DeviceVolumeAnswer.NO_CONTROL).commit()
        currentDac = other
        coordinator.onDacChanged(other)
        assertThat(coordinator.allowPlayFor(other)).isFalse()
        confirmWithoutRunningMain()
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 1) { player.play() }
    }
}
