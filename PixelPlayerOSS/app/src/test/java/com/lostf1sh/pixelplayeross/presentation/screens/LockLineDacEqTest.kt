package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.decent.usbaudio.media3.DirectSignalMode
import com.decent.usbaudio.media3.UsbDirectSinkState
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabel
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DapAudioRouteKind
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B30-P1.4: the lock screen's output line follows the DAC's EQ live. Purity (the app leaves the PCM alone and the DAC's EQ is closed or
 * flat, or cannot be asked) keeps the line it always had; the DAC's EQ on adds "Resonance · DAC EQ"; an EQ the app could not read
 * adds "DAC EQ: unknown". The dot (`untouched`) is `purityHolds`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class LockLineDacEqTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun route(mode: DirectSignalMode) = AudioRouteState(
        actualBackend = AudioBackend.USB_DIRECT, desiredBackend = AudioBackend.USB_DIRECT, transition = AudioRouteTransition.DIRECT_ACTIVE,
        reason = if (mode == DirectSignalMode.PRISTINE) AudioRouteReason.DIRECT_PRISTINE else AudioRouteReason.DIRECT_PROCESSED,
        usbDacPresent = true, signalMode = mode,
    )

    private fun device(mode: DirectSignalMode, eq: DacEqLabel) = DeviceControlUiState(
        audio = AudioStatus(route = route(mode), sinkDirectActive = true, dacEq = eq),
        audioRouteKind = DapAudioRouteKind.USB_DIRECT, audioOutputName = "SNOWSKY Melody",
        usbDirectState = UsbDirectSinkState.USB_DIRECT_ACTIVE, usbDirectRate = 96_000, usbDirectBitDepth = 24,
    )

    private fun line(state: DeviceControlUiState): String {
        var text = ""
        compose.setContent { text = dapOutputSignalLine(state); Text(text) }
        compose.waitForIdle()
        return text
    }

    private val base get() = "SNOWSKY Melody · ${s(R.string.dap_output_usb_direct)}"
    private val tail get() = "${s(R.string.dap_bits, 24)} · ${s(R.string.dap_rate_khz, "96")}"

    @Test
    fun `Purity keeps the line it always had, with or without a DAC EQ the app can read`() {
        assertEquals("$base · $tail", line(device(DirectSignalMode.PRISTINE, DacEqLabel.OFF_OR_FLAT)))
    }

    @Test
    fun `a DAC the app cannot ask keeps the line it always had`() {
        assertEquals("$base · $tail", line(device(DirectSignalMode.PRISTINE, DacEqLabel.NOT_APPLICABLE)))
    }

    @Test
    fun `the DAC's EQ on says Resonance and DAC EQ`() {
        assertEquals("$base · ${s(R.string.dap_output_resonance_dac)} · $tail", line(device(DirectSignalMode.PRISTINE, DacEqLabel.ON)))
    }

    @Test
    fun `the app's DSP says Resonance and App DSP`() {
        assertEquals("$base · ${s(R.string.dap_output_resonance_app)} · $tail", line(device(DirectSignalMode.PROCESSED, DacEqLabel.OFF_OR_FLAT)))
    }

    @Test
    fun `the app's DSP and the DAC's EQ at once say both`() {
        assertEquals("$base · ${s(R.string.dap_output_resonance_both)} · $tail", line(device(DirectSignalMode.PROCESSED, DacEqLabel.ON)))
    }

    @Test
    fun `an EQ that could not be read is said, and is not Purity`() {
        val state = device(DirectSignalMode.PRISTINE, DacEqLabel.UNKNOWN)
        assertEquals("$base · ${s(R.string.dap_output_dac_eq_unknown)} · $tail", line(state))
        assertEquals(false, state.audio.purityHolds)
    }

    @Test
    fun `the lock screen's dot is Purity's, so it is off while the DAC's EQ is on or unknown`() {
        assertEquals(true, device(DirectSignalMode.PRISTINE, DacEqLabel.OFF_OR_FLAT).audio.purityHolds)
        assertEquals(true, device(DirectSignalMode.PRISTINE, DacEqLabel.NOT_APPLICABLE).audio.purityHolds)
        assertEquals(false, device(DirectSignalMode.PRISTINE, DacEqLabel.ON).audio.purityHolds)
        assertEquals(false, device(DirectSignalMode.PRISTINE, DacEqLabel.UNKNOWN).audio.purityHolds)
        val source = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/presentation/lockscreen/DapLockSurface.kt").readText()
        assertEquals(true, source.contains("audio?.purityHolds == true"))
    }
}
