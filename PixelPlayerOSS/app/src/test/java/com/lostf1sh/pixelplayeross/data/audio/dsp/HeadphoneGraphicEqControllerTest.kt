package com.lostf1sh.pixelplayeross.data.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverride
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideStore
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrides
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileRepository
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqualizerCoordinator
import com.lostf1sh.pixelplayeross.data.service.player.HeadphoneGraphicEqProcessor
import io.mockk.every
import io.mockk.mockk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Test

/**
 * What the audio thread sees when the user moves a slider or switches profile.
 *
 * The claims being pinned here are the ones a listener would notice if they broke: an EQ change must
 * take effect on the stream that is already playing, and it must do so without the processor being
 * torn down and rebuilt — because a rebuild is a click, a re-buffer, or a position loss.
 */
class HeadphoneGraphicEqControllerTest {

    private val stereo16 = AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

    private fun state(
        mode: HeadphoneEqMode = HeadphoneEqMode.GRAPHIC_10,
        enabled: Boolean = true,
        bands10: List<Int> = List(10) { 0 },
        bands15: List<Int> = List(15) { 0 },
    ) = HeadphoneEqState(
        enabled = enabled,
        presetName = "custom",
        bands10 = bands10,
        mode = mode,
        bands15 = bands15,
    )

    /** Runs [body] with an eagerly-collecting controller and disposes the scope afterwards. */
    private fun withController(
        states: StateFlow<HeadphoneEqState>,
        overrides: MaintenanceOverrides = MaintenanceOverrides(),
        body: (HeadphoneGraphicEqController) -> Unit,
    ) {
        val coordinator = mockk<HeadphoneEqualizerCoordinator>(relaxed = true)
        every { coordinator.effectiveStateFlow } returns states
        val overrideStore = mockk<MaintenanceOverrideStore>(relaxed = true)
        every { overrideStore.current() } returns overrides
        val profiles = mockk<HeadphoneProfileRepository> { every { state } returns MutableStateFlow(HeadphoneProfileState()) }
        val scope = CoroutineScope(UnconfinedTestDispatcher(TestCoroutineScheduler()))
        try {
            body(HeadphoneGraphicEqController(coordinator, overrideStore, scope, profiles))
        } finally {
            scope.cancel()
        }
    }

    private fun pcm16Buffer(samples: List<Int>): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        samples.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    private fun ByteBuffer.readShorts(): List<Int> {
        val view = duplicate().order(ByteOrder.nativeOrder())
        val out = ArrayList<Int>(view.remaining() / Short.SIZE_BYTES)
        while (view.remaining() >= Short.SIZE_BYTES) out.add(view.getShort().toInt())
        return out
    }

    private val ramp = (0 until 512).map { (it % 128) * 200 - 12_800 }

    // --- Publishing -----------------------------------------------------------------------

    @Test
    fun `the default profile state is a truthful bypass`() {
        withController(MutableStateFlow(HeadphoneEqState())) { controller ->
            assertThat(controller.settings().isBypass).isTrue()
        }
    }

    @Test
    fun `a published state reaches the audio thread without the controller being rebuilt`() {
        val states = MutableStateFlow(state(bands10 = List(10) { 0 }))

        withController(states) { controller ->
            assertThat(controller.settings().isBypass).isTrue()

            states.value = state(bands10 = List(10) { 0 }.toMutableList().also { it[5] = 9 })

            assertThat(controller.settings().isBypass).isFalse()
            assertThat(controller.settings().gainsDb[5]).isEqualTo(9f)
        }
    }

    @Test
    fun `switching to fifteen bands changes the rendered band count live`() {
        val states = MutableStateFlow(state(mode = HeadphoneEqMode.GRAPHIC_10, bands10 = List(10) { 3 }))

        withController(states) { controller ->
            assertThat(controller.settings().bandCount).isEqualTo(10)
            assertThat(controller.settings().frequenciesHz.toList())
                .isEqualTo(GraphicEqBands.TEN_BAND_HZ.toList())

            states.value = state(mode = HeadphoneEqMode.GRAPHIC_15, bands15 = List(15) { -3 })

            assertThat(controller.settings().bandCount).isEqualTo(15)
            assertThat(controller.settings().frequenciesHz.toList())
                .isEqualTo(GraphicEqBands.FIFTEEN_BAND_HZ.toList())
        }
    }

    @Test
    fun `switching profile mid-stream changes the curve with no fifteen band leftovers`() {
        val states = MutableStateFlow(
            state(mode = HeadphoneEqMode.GRAPHIC_15, bands15 = List(15) { 15 })
        )

        withController(states) { controller ->
            states.value = state(mode = HeadphoneEqMode.GRAPHIC_10, bands10 = List(10) { -7 })

            val rendered = controller.settings()
            assertThat(rendered.bandCount).isEqualTo(10)
            assertThat(rendered.gainsDb.toList()).isEqualTo(List(10) { -7f })
        }
    }

    // --- Live behaviour through the real processor ----------------------------------------

    @Test
    fun `a moving slider changes what the playing stream hears, with no reconfigure`() {
        val states = MutableStateFlow(state(bands10 = List(10) { 0 }))

        withController(states) { controller ->
            val processor = HeadphoneGraphicEqProcessor { controller.settings() }
            processor.configure(stereo16)

            processor.queueInput(pcm16Buffer(ramp))
            val flat = processor.getOutput().readShorts()

            // The user drags the 1 kHz slider. Nothing is reconfigured, reset or recreated.
            states.value = state(bands10 = List(10) { 0 }.toMutableList().also { it[5] = 12 })

            processor.queueInput(pcm16Buffer(ramp))
            val boosted = processor.getOutput().readShorts()

            assertThat(boosted).hasSize(flat.size)
            assertThat(boosted).isNotEqualTo(flat)
        }
    }

    @Test
    fun `switching profile mid-stream keeps the same processor`() {
        val states = MutableStateFlow(state(mode = HeadphoneEqMode.GRAPHIC_10, bands10 = List(10) { 0 }))

        withController(states) { controller ->
            val processor = HeadphoneGraphicEqProcessor { controller.settings() }
            processor.configure(stereo16)

            processor.queueInput(pcm16Buffer(ramp))
            val before = processor.getOutput().readShorts()

            // A profile switch to a different band count, on the stream that is already running.
            states.value = state(mode = HeadphoneEqMode.GRAPHIC_15, bands15 = List(15) { 4 })
            processor.queueInput(pcm16Buffer(ramp))
            val after = processor.getOutput().readShorts()

            assertThat(after).hasSize(before.size)
            assertThat(after).isNotEqualTo(before)
            // Same object, still configured, still running: the change cost no restart.
            assertThat(processor.isActive()).isTrue()
        }
    }

    @Test
    fun `switching the eq off mid-stream restores byte-exact pass-through`() {
        val states = MutableStateFlow(state(bands10 = List(10) { 0 }.toMutableList().also { it[5] = 15 }))

        withController(states) { controller ->
            val processor = HeadphoneGraphicEqProcessor { controller.settings() }
            processor.configure(stereo16)

            processor.queueInput(pcm16Buffer(ramp))
            assertThat(processor.getOutput().readShorts()).isNotEqualTo(ramp)

            // Turn it off while the filter is still ringing from the previous buffer. Bypass is a
            // byte copy, so it does not matter what the delay lines hold.
            states.value = state(enabled = false, bands10 = List(10) { 0 }.toMutableList().also { it[5] = 15 })

            processor.queueInput(pcm16Buffer(ramp))
            assertThat(processor.getOutput().readShorts()).isEqualTo(ramp)
        }
    }

    // --- Phase 13: the Maintenance DSP kill -------------------------------------------------

    /**
     * The kill is applied at delivery to the audio thread, not by editing the profile. Bypass is a
     * byte copy, so the buffer must come back untouched even though the profile still has a curve.
     */
    @Test
    fun `the maintenance DSP kill bypasses an enabled equalizer`() {
        val states = MutableStateFlow(state(bands10 = List(10) { 0 }.toMutableList().also { it[5] = 15 }))
        val killed = MaintenanceOverrides().applied(MaintenanceOverride.DISABLE_DSP_GRAPH, active = true)

        withController(states, killed) { controller ->
            assertThat(controller.settings().enabled).isFalse()
            val processor = HeadphoneGraphicEqProcessor { controller.settings() }
            processor.configure(stereo16)
            processor.queueInput(pcm16Buffer(ramp))
            assertThat(processor.getOutput().readShorts()).isEqualTo(ramp)
        }
    }

    @Test
    fun `without the kill the profile curve is still published`() {
        val states = MutableStateFlow(state(bands10 = List(10) { 0 }.toMutableList().also { it[5] = 15 }))
        withController(states) { controller ->
            assertThat(controller.settings().enabled).isTrue()
        }
    }

    /**
     * The kill must not be able to turn an equalizer *on*. An override that could only ever be
     * additive would be a way for a diagnostic switch to change the user's sound by accident.
     */
    @Test
    fun `the kill cannot enable a disabled equalizer`() {
        val states = MutableStateFlow(state(enabled = false))
        val killed = MaintenanceOverrides().applied(MaintenanceOverride.DISABLE_DSP_GRAPH, active = true)
        withController(states, killed) { controller ->
            assertThat(controller.settings().enabled).isFalse()
        }
    }

    /** Removing the override must restore the profile's own sound with nothing re-entered. */
    @Test
    fun `removing the kill restores the profile curve unchanged`() {
        val states = MutableStateFlow(state(bands10 = List(10) { 0 }.toMutableList().also { it[5] = 15 }))
        val killed = MaintenanceOverrides().applied(MaintenanceOverride.DISABLE_DSP_GRAPH, active = true)
        val restored = killed.applied(MaintenanceOverride.DISABLE_DSP_GRAPH, active = false)

        withController(states, killed) { controller ->
            assertThat(controller.settings().enabled).isFalse()
        }
        withController(states, restored) { controller ->
            assertThat(controller.settings().enabled).isTrue()
        }
    }
}
