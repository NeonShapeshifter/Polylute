package com.lostf1sh.pixelplayeross.data.usb

import android.content.Context
import android.content.SharedPreferences
import android.hardware.usb.UsbManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.decent.usbaudio.media3.NaturalEndCoordinator
import com.decent.usbaudio.media3.UsbAudioSink
import com.decent.usbaudio.media3.UsbAudioSinkConfig
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B12a: with no native engine — the default now — the glue that exists for it must have nothing to act on
 * (it is deleted in B12b). Each piece is checked in the form it can be checked in:
 *
 * - `NativeEngineAwareLoadControl` holds Media3's loading back only while the sink says an engine is active,
 *   ready or near its end: all three are false for a sink that has no engine, so the loading and the start of
 *   playback are the delegate's, untouched. (Behavioural: a real sink with a mocked delegate.)
 * - the natural-end bookkeeping starts only from an engine's end of stream, so without one every decision of
 *   `NaturalEndCoordinator` is the no-engine one (no deferred configure, no dropped buffers, no next offset).
 * - bootstrap seeks, engine offsets, the drift watchdog and `ENGINE_ITEM_MISMATCH` all live in code that is
 *   handed an engine; the sink's only call site passes a non-null one. (Source pins.)
 */
@OptIn(UnstableApi::class)
class NativeEngineGlueIdleTest {

    private fun freshSink(): UsbAudioSink {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.USB_SERVICE) } returns mockk<UsbManager>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true)
        return UsbAudioSink(mockk<DefaultAudioSink>(relaxed = true), context, UsbAudioSinkConfig(directEnabled = true))
    }

    @Suppress("DEPRECATION")
    private val delegate = mockk<LoadControl>(relaxed = true).also {
        every { it.shouldContinueLoading(any<Long>(), any(), any()) } returns true
        every { it.shouldStartPlayback(any<Long>(), any(), any(), any()) } returns false
    }

    @Test
    fun `a sink with no engine reports no engine active, ready or near its end`() {
        val sink = freshSink()
        assertThat(sink.isNativeEngineActive).isFalse()
        assertThat(sink.isNativeEngineNearEnd).isFalse()
        assertThat(sink.isNativeEngineReadyForCurrentItem).isFalse()
        assertThat(sink.provocationEngineAbsent()).isTrue()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `the load control wired to a sink with no engine leaves Media3's loading and start to the delegate`() {
        val sink = freshSink()
        val control = UsbAudioSink.wrapLoadControl(
            delegate,
            isEngineReady = { sink.isNativeEngineReadyForCurrentItem },
            isEnginePreloadWindow = { sink.isNativeEngineNearEnd },
            isEngineActive = { sink.isNativeEngineActive },
        )
        // loading is not held back: the delegate's answer (true) is the answer
        assertThat(control.shouldContinueLoading(0L, 0L, 1f)).isTrue()
        // playback does not start on the engine's say-so: the delegate's buffering rule (false here) applies
        assertThat(control.shouldStartPlayback(0L, 1f, false, 0L)).isFalse()
        verify { delegate.shouldContinueLoading(0L, 0L, 1f) }
        verify { delegate.shouldStartPlayback(0L, 1f, false, 0L) }
    }

    @Test
    fun `the natural end never starts without an engine, so it never defers a configure or drops a buffer`() {
        // The only way into the pending state is onEngineFinished, called from the engine's end of stream.
        val naturalEnd = NaturalEndCoordinator()
        assertThat(naturalEnd.pending).isFalse()
        assertThat(naturalEnd.onConfigure(engineRunning = false, trackChanged = false))
            .isEqualTo(NaturalEndCoordinator.ConfigureAction.RECONFIGURE)
        assertThat(naturalEnd.onConfigure(engineRunning = false, trackChanged = true))
            .isEqualTo(NaturalEndCoordinator.ConfigureAction.RECONFIGURE)
        assertThat(naturalEnd.onHandleBuffer(engineExists = false, streamingThreadExists = true))
            .isEqualTo(NaturalEndCoordinator.BufferAction.NORMAL)
        assertThat(naturalEnd.onItemTransition(media3OffsetUs = 1_000L)).isEqualTo(NaturalEndCoordinator.NextOffset.None)
        assertThat(naturalEnd.overdue(nowMs = 10_000_000L, itemChanged = false)).isFalse()
        assertThat(naturalEnd.consumeEarlyTransition(nowMs = 0L)).isFalse()
    }

    // ── Source pins: the engine glue is only reachable with an engine in hand ─────

    private val sinkSource = File("../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt")
        .readText().replace("\r\n", "\n")

    @Test
    fun `the coherence watchdog (ENGINE_ITEM_MISMATCH, POSITION_DRIFT, engine offsets) takes an engine and has one caller`() {
        assertThat(sinkSource).contains("private fun checkEngineCoherence(engine: NativeAudioEngine, enginePositionUs: Long)")
        val callers = Regex("""checkEngineCoherence\(engine, enginePositionUs\)""").findAll(sinkSource).count()
        assertThat(callers).isEqualTo(1)
        // the caller is inside the branch that already holds a non-null, running engine
        val before = sinkSource.substring(0, sinkSource.indexOf("checkEngineCoherence(engine, enginePositionUs)"))
        val branch = before.substring(before.lastIndexOf("val engine = nativeEngine"))
        assertThat(branch).contains("if (engine != null")
    }

    @Test
    fun `bootstrap seeks are decided only from an engine`() {
        val block = sinkSource.substring(sinkSource.indexOf("val decision = engine?.let {"))
            .substringBefore("// Re-block LoadControl now that we have the position.")
        assertThat(block).contains("val decision = engine?.let {")
        // every branch that acts on the decision is guarded by a non-null engine
        for (acting in listOf("decision == BootstrapSeekPolicy.Decision.Refused", "decision != BootstrapSeekPolicy.Decision.None")) {
            assertThat(block).contains("engine != null && $acting")
        }
        assertThat(block).doesNotContain("seekEngine(engine!!")
    }

    @Test
    fun `nothing re-blocks Media3's loading unless an engine is running`() {
        assertThat(sinkSource).contains("if (nativeEngine?.isRunning == true) {\n                    isNativeEngineActive = true")
        // the only assignments that turn the flag on are guarded by an engine that exists
        val on = Regex("""isNativeEngineActive = true""").findAll(sinkSource).map { it.range.first }.toList()
        assertThat(on).isNotEmpty()
        for (at in on) {
            val context = sinkSource.substring((at - 400).coerceAtLeast(0), at)
            assertThat(context).containsMatch("""engine|Engine""")
        }
    }
}
