package com.lostf1sh.pixelplayeross.data.service.player

import android.content.Context
import android.hardware.usb.UsbDevice
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode
import com.lostf1sh.pixelplayeross.data.audio.dsp.HeadphoneGraphicEqController
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideStore
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrides
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * B79 item 1: reproduces Sherlock's 1.4.0 Bluetooth / no-DAC sequence against the real [DualPlayerEngine]
 * (APP mode, default share switch, no USB DAC: play -> pause 3 s -> play).
 *
 * Fails on `f9ee0e6c` with `dacSharedWithOtherApps == true`, `allowPlayForDacShare() == false`, and after 10 s the
 * toast `dac_share_refused_dac_not_back` ("The DAC did not come back in time, so playback stayed paused.").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B79DacShareNoDacEngineTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val engines = mutableListOf<DualPlayerEngine>()

    @Before
    fun setUp() {
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        engines.forEach { runCatching { it.release() } }
    }

    private inner class Rig {
        var attachedDac: UsbDevice? = null
        private val usbFactsCache = UsbRouteFactsCache { UsbRouteFacts(attachedDac, permission = false) }
        var playWhenReady = true
        var isPlaying = true
        val player = mockk<ExoPlayer>(relaxed = true).also {
            every { it.playWhenReady } answers { playWhenReady }
            every { it.playWhenReady = any() } answers { playWhenReady = firstArg() }
            every { it.isPlaying } answers { isPlaying }
            every { it.playbackState } returns Player.STATE_READY
        }
        val engine: DualPlayerEngine

        init {
            val dacProfiles = mockk<DacProfileRepository>(relaxed = true)
            every { dacProfiles.connected } returns MutableStateFlow(emptyList())
            val overrides = mockk<MaintenanceOverrideStore>(relaxed = true)
            every { overrides.state } returns MutableStateFlow(MaintenanceOverrides())
            val scope = CoroutineScope(Job().apply { cancel() })
            engine = DualPlayerEngine(
                context,
                mockk<HeadphoneGraphicEqController>(relaxed = true),
                dacProfiles,
                overrides,
                scope,
            )
            engines += engine
            engine.effectiveProductModeProvider = { EffectiveProductMode.APP }
            set("playerA", player)
            set("usbRouteFacts", usbFactsCache)
            // Keep default dacShareChoice == null (on in APP mode).
            engine.updateProcessingRequirements(AudioProcessingRequirements(), pristineLock = false)
            idle()
        }

        private fun field(name: String) = DualPlayerEngine::class.java.getDeclaredField(name).apply { isAccessible = true }
        private fun set(name: String, value: Any?) = field(name).set(engine, value)
        fun listener(): Player.Listener = field("masterPlayerListener").get(engine) as Player.Listener
        fun dacShare(): DacShareController = field("dacShare").get(engine) as DacShareController

        fun evaluateRoute(device: UsbDevice?) {
            attachedDac = device
            usbFactsCache.invalidate()
            val method = DualPlayerEngine::class.java.declaredMethods.single { it.name == "evaluateRoute" }
            method.isAccessible = true
            method.invoke(engine, UsbRouteFacts(device, permission = false), engine.audioRouteState.value)
        }

        fun observeUsbPresence(present: Boolean) {
            val method = DualPlayerEngine::class.java.getDeclaredMethod(
                "observeUsbPresence",
                Boolean::class.javaPrimitiveType,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(engine, present, "test", false)
        }

        fun playEvent() {
            playWhenReady = true
            isPlaying = true
            listener().onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        }

        fun pauseEvent() {
            playWhenReady = false
            isPlaying = false
            listener().onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        }

        fun focus(change: Int) {
            val listener = field("focusChangeListener").get(engine) as AudioManager.OnAudioFocusChangeListener
            listener.onAudioFocusChange(change)
        }

        fun idle(durationMs: Long = 0) {
            val looper = shadowOf(Looper.getMainLooper())
            if (durationMs > 0) looper.idleFor(Duration.ofMillis(durationMs)) else looper.idle()
        }
    }

    @Test
    fun `APP mode, default switch, no DAC - play, pause 3 s, play goes straight through with no DAC-not-back toast`() {
        val rig = Rig()
        rig.evaluateRoute(null)
        rig.observeUsbPresence(false)

        // Playing on Bluetooth (no USB DAC), then listener pauses for 3 s, then presses play.
        rig.playEvent()
        rig.pauseEvent()
        rig.idle(DacShareController.PAUSE_GRACE_MS)

        assertThat(rig.engine.dacSharedWithOtherApps).isFalse()
        assertThat(rig.engine.allowPlayForDacShare()).isTrue()

        // Even after the 10 s claim timeout window, no refusal toast appears.
        rig.idle(DacShareController.CLAIM_TIMEOUT_MS)
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
        assertThat(ShadowToast.getTextOfLatestToast())
            .isNotEqualTo(context.getString(R.string.dac_share_refused_dac_not_back))
    }

    @Test
    fun `APP mode, default switch, usbDacPresentFact still null - pause 3 s does not release or refuse play`() {
        val rig = Rig()

        rig.playEvent()
        rig.pauseEvent()
        rig.idle(DacShareController.PAUSE_GRACE_MS)

        assertThat(rig.engine.dacSharedWithOtherApps).isFalse()
        assertThat(rig.engine.allowPlayForDacShare()).isTrue()

        rig.idle(DacShareController.CLAIM_TIMEOUT_MS)
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }

    @Test
    fun `APP mode, DAC released then detached - clears share state, cancels timeout, and lets play through`() {
        val rig = Rig()
        val dac = mockk<UsbDevice>(relaxed = true)
        rig.evaluateRoute(dac)
        rig.observeUsbPresence(true)

        rig.playEvent()
        rig.pauseEvent()
        rig.idle(DacShareController.PAUSE_GRACE_MS)
        assertThat(rig.engine.dacSharedWithOtherApps).isTrue()

        // DAC unplugged while released (nothing is playing).
        rig.evaluateRoute(null)
        rig.observeUsbPresence(false)
        rig.idle()

        assertThat(rig.engine.dacSharedWithOtherApps).isFalse()
        assertThat(rig.dacShare().claiming).isFalse()
        assertThat(rig.engine.allowPlayForDacShare()).isTrue()

        rig.idle(DacShareController.CLAIM_TIMEOUT_MS)
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }

    @Test
    fun `APP mode, DAC attached later - starts normal and next pause 3 s releases as before`() {
        val rig = Rig()
        rig.evaluateRoute(null)
        rig.observeUsbPresence(false)

        rig.playEvent()
        rig.pauseEvent()
        rig.idle(DacShareController.PAUSE_GRACE_MS)
        assertThat(rig.engine.dacSharedWithOtherApps).isFalse()

        val dac = mockk<UsbDevice>(relaxed = true)
        rig.evaluateRoute(dac)
        rig.observeUsbPresence(true)
        rig.playEvent()
        rig.pauseEvent()
        rig.idle(DacShareController.PAUSE_GRACE_MS)
        assertThat(rig.engine.dacSharedWithOtherApps).isTrue()
    }

    @Test
    fun `APP mode, claiming when DAC detached - clears claiming, cancels timeout, does not resume or pause`() {
        val rig = Rig()
        val dac = mockk<UsbDevice>(relaxed = true)
        rig.evaluateRoute(dac)
        rig.observeUsbPresence(true)

        rig.playEvent()
        rig.pauseEvent()
        rig.idle(DacShareController.PAUSE_GRACE_MS)
        assertThat(rig.engine.dacSharedWithOtherApps).isTrue()

        // Start a claim on play, then unplug the DAC before Direct comes back.
        assertThat(rig.engine.allowPlayForDacShare()).isFalse()
        assertThat(rig.dacShare().claiming).isTrue()
        assertThat(rig.playWhenReady).isFalse()

        rig.evaluateRoute(null)
        rig.observeUsbPresence(false)
        rig.idle()

        assertThat(rig.engine.dacSharedWithOtherApps).isFalse()
        assertThat(rig.dacShare().claiming).isFalse()
        assertThat(rig.playWhenReady).isFalse()

        rig.idle(DacShareController.CLAIM_TIMEOUT_MS)
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }
}
