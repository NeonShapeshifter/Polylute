package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.DirectSignalMode
import com.decent.usbaudio.media3.NativeEnginePolicy
import com.decent.usbaudio.media3.PipelineThread
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B12a: the ExoPlayer pipeline is the default PRISTINE producer and the native engine is plan B behind a
 * debug switch. Who produces is decided by two pure functions, `NativeEnginePolicy.allowed` (signal mode,
 * stream shape, the switch) and `PipelineThread.wantsEngine` (the file is a local FLAC), and the whole
 * matrix is checked here: mode × source shape × file × switch.
 *
 * "Debug and release" is the same matrix: the only thing that differs between the builds is whether the
 * switch can ever be true (`NativeEngineDebugSwitchTest`), and with it false nothing below can create an engine.
 */
class NativeEnginePolicyTest {

    private val modes = DirectSignalMode.values().toList()
    private val downmixChannels = listOf(0, 1, 2, 6, 8)
    private val upmix = listOf(false, true)
    private val files = listOf(
        "/storage/emulated/0/Music/a.flac", "/storage/emulated/0/Music/B.FLAC", "/sdcard/a.mp3",
        "/sdcard/a.m4a", "/sdcard/a.wav", "/sdcard/a.flac.part", null,
    )

    /** `UsbAudioSink.nativeEngineAllowed()` as it was before B12a, with the switch absent. */
    private fun before(mode: DirectSignalMode, downmixSourceChannels: Int, upmixMonoSource: Boolean): Boolean =
        mode == DirectSignalMode.PRISTINE && downmixSourceChannels == 0 && !upmixMonoSource

    private fun producerIsEngine(
        mode: DirectSignalMode, channels: Int, mono: Boolean, planB: Boolean, file: String?,
    ): Boolean = PipelineThread.wantsEngine(NativeEnginePolicy.allowed(mode, channels, mono, planB), file)

    @Test
    fun `by default no input at all gets the engine - the pipeline is the producer for every format, FLAC included`() {
        for (mode in modes) for (channels in downmixChannels) for (mono in upmix) {
            assertThat(NativeEnginePolicy.allowed(mode, channels, mono, debugForceNativeEngine = false)).isFalse()
            for (file in files) assertThat(producerIsEngine(mode, channels, mono, planB = false, file = file)).isFalse()
        }
    }

    @Test
    fun `by default an ordinary stereo pristine local FLAC goes through the pipeline`() {
        assertThat(NativeEnginePolicy.allowed(DirectSignalMode.PRISTINE, 0, false, false)).isFalse()
        assertThat(producerIsEngine(DirectSignalMode.PRISTINE, 0, false, planB = false, file = "/music/a.flac")).isFalse()
    }

    @Test
    fun `plan B on - the decision is exactly the pre-B12a one for every input`() {
        for (mode in modes) for (channels in downmixChannels) for (mono in upmix) {
            assertThat(NativeEnginePolicy.allowed(mode, channels, mono, debugForceNativeEngine = true))
                .isEqualTo(before(mode, channels, mono))
        }
    }

    @Test
    fun `plan B on - a pristine stereo local FLAC gets the engine and nothing else does`() {
        for (mode in modes) for (channels in downmixChannels) for (mono in upmix) for (file in files) {
            val expected = mode == DirectSignalMode.PRISTINE && channels == 0 && !mono &&
                file != null && file.lowercase().endsWith(".flac")
            assertThat(producerIsEngine(mode, channels, mono, planB = true, file = file)).isEqualTo(expected)
        }
        assertThat(producerIsEngine(DirectSignalMode.PRISTINE, 0, false, planB = true, file = "/music/a.flac")).isTrue()
    }

    @Test
    fun `plan B on - a processed signal still gets no engine, so a running stage is never bypassed`() {
        assertThat(NativeEnginePolicy.allowed(DirectSignalMode.PROCESSED, 0, false, true)).isFalse()
    }

    @Test
    fun `plan B on - a downmixed or upmixed source still gets no engine, which cannot do either`() {
        assertThat(NativeEnginePolicy.allowed(DirectSignalMode.PRISTINE, 6, false, true)).isFalse()
        assertThat(NativeEnginePolicy.allowed(DirectSignalMode.PRISTINE, 0, true, true)).isFalse()
    }

    @Test
    fun `the switch only ever removes or allows - nothing is allowed that the old rules refused`() {
        for (mode in modes) for (channels in downmixChannels) for (mono in upmix) for (planB in listOf(false, true)) {
            if (NativeEnginePolicy.allowed(mode, channels, mono, planB)) {
                assertThat(before(mode, channels, mono)).isTrue()
                assertThat(planB).isTrue()
            }
        }
    }

    @Test
    fun `the pipeline keeps its thread across a mode change when no engine will take over (the realign needs no new producer)`() {
        // With the default there is no engine, so PRISTINE <-> PROCESSED stays inside the pipeline: B9-3's realign.
        for (mode in modes) {
            val wantsEngine = producerIsEngine(mode, 0, false, planB = false, file = "/music/a.flac")
            assertThat(
                PipelineThread.realignFor(engineExists = false, threadRunning = true, wantsEngine = wantsEngine),
            ).isEqualTo(PipelineThread.RealignAction.KEEP_PIPELINE)
            assertThat(PipelineThread.actionFor(wantsEngine, threadRunning = true)).isEqualTo(PipelineThread.Action.KEEP)
            assertThat(PipelineThread.actionFor(wantsEngine, threadRunning = false)).isEqualTo(PipelineThread.Action.START)
        }
    }
}
