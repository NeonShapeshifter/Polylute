package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B8b at the sink boundary: the decisions are proven where they can run (`PipelineThreadTransitionTest`,
 * `NaturalJoinPauseTest`, `tools/natural-join-test.cpp`); this pins that the sink uses them where the
 * defects were. Source-level, because the sink needs Media3 and usbfs.
 */
class PipelineThreadWiringBoundaryTest {

    private val root = "../../decent-player/libs/"
    private val sink = File(root + "decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt")
        .readText().replace("\r\n", "\n")
    private val output = File(root + "decent-usb-audio-driver/src/main/jni/usb-audio-output.cpp")
        .readText().replace("\r\n", "\n")

    private fun function(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced $signature")
    }

    // ── AUD-143 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a pipeline that stays the producer keeps its running thread and does not count a start`() {
        val start = function(sink, "private fun startPipeline(")
        val keep = start.indexOf("PipelineThread.keepOrStart(runningThread)")
        val kept = start.indexOf("if (thread === runningThread) return")
        val counted = start.indexOf("DirectPathTelemetry.streamingThreadStarts.incrementAndGet()")
        assertThat(keep).isAtLeast(0)
        assertThat(kept).isGreaterThan(keep)
        assertThat(counted).isGreaterThan(kept)
        assertThat(start.substring(kept, counted)).contains("return")
    }

    @Test
    fun `a rebuild of the stream plays the ending item's tail out before it releases the stream`() {
        val configure = function(sink, "private fun configureUsbBitPerfect(") + function(sink, "private fun rebuildUsbStream(")
        val playOut = configure.indexOf("playOutPipelineTailBeforeRebuild()")
        val release = configure.indexOf("releaseUsbStream()", playOut)
        assertThat(playOut).isAtLeast(0)
        assertThat(release).isGreaterThan(playOut)
        // The cached stream's early return, which keeps the thread, comes before either.
        assertThat(configure.indexOf("USB stream cached for rate")).isLessThan(playOut)
    }

    @Test
    fun `the pipeline clock latches a buffer where it will be written, not where the writer is`() {
        assertThat(sink).contains("pipelineClock.accept(presentationTimeUs, usbAudioStream?.framesWritten ?: 0L, pipelineQueueLeadUs())")
        // The thread's frames are counted in the stream's own channels.
        assertThat(sink).contains("channelCount = currentChannelCount.takeIf { it > 0 } ?: STEREO")
    }

    @Test
    fun `a flush still empties the thread - the seek path is untouched`() {
        val flush = function(sink, "override fun flush()")
        assertThat(flush).contains("thread.flush()")
    }

    @Test
    fun `the pipeline's natural end lets the ring play out before a teardown, and the next write clears the mark`() {
        val drain = function(output, "Java_com_decent_usbaudio_UsbAudioStream_nativeDrainEndOfStream(")
        assertThat(drain).contains("ctx->playOutBeforeDrain = true")
        // B9: both writes go through one helper (it ramps what a gesture owes and otherwise is the plain
        // submit); the mark is cleared there, after the submit.
        for (name in listOf("Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWrite(", "Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWriteRaw(")) {
            assertThat(function(output, name)).contains("submitPipelinePcm(ctx,")
        }
        val helper = function(output, "bool submitPipelinePcm(")
        // (B12a-6: the plain submit is the same one with a pause request to look for between URBs)
        val plain = helper.indexOf("submitPcmToUrbsImpl(ctx, data, totalBytes, interruptible, &consumedBytes)")
        val cleared = helper.indexOf("ctx->playOutBeforeDrain = false")
        assertThat(plain).isAtLeast(0)
        assertThat(cleared).isGreaterThan(plain)
    }

    // ── AUD-144 ─────────────────────────────────────────────────────────────────────────────────

    /**
     * B10-4 (logging only): the decision a `pause()` takes is unchanged; it now says why it was not held, and every
     * place the join mark is set, left alone or cleared says what the sink knew. The next native `rate` run settles
     * AUD-144 from these lines.
     */

    // ── B9 ──────────────────────────────────────────────────────────────────────────────────────

    private val streamingThread = File(root + "decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbStreamingThread.kt")
        .readText().replace("\r\n", "\n")
    private val streamKotlin = File(root + "decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioStream.kt")
        .readText().replace("\r\n", "\n")

    @Test
    fun `a stop from Media3 reaches the pipeline's thread through the same join policy as the engine`() {
        val pause = function(sink, "override fun pause()")
        assertThat(pause).contains("pauseStreamingThread()")
        assertThat(pause).doesNotContain("usbStreamingThread?.pauseStreaming()")

        val policy = function(sink, "private fun pauseStreamingThread()")
        assertThat(policy).contains("NaturalJoinPause.onMedia3Stop(userWantsPlayback, sinceJoinMs)")
        assertThat(policy).contains("thread.pauseStreaming()")
        // Held for a bounded time, and only for the thread that was running when it was held.
        assertThat(policy).contains("NaturalJoinPause.MAX_HOLD_MS")
        assertThat(policy).contains("usbStreamingThread === thread")
    }

    @Test
    fun `play ends a held thread stop before it resumes the thread, and a user's pause or a release settles it`() {
        val play = function(sink, "override fun play()")
        val cancel = play.indexOf("cancelHeldThreadPause(\"play()\")")
        val resume = play.indexOf("usbStreamingThread?.resumeStreaming()")
        assertThat(cancel).isAtLeast(0)
        assertThat(resume).isGreaterThan(cancel)

        val listener = sink.substringAfter("private inner class PlayerIntegrationListener : Player.Listener {")
            .substringBefore("override fun onMediaItemTransition(")
        assertThat(listener).contains("applyHeldThreadPauseNow()")
        assertThat(function(sink, "private fun releaseUsbStream()")).contains("cancelHeldThreadPause(\"stream release\")")
    }

    @Test
    fun `Media3's own advance marks the pipeline's join on the playback thread, in order with a pause that may follow`() {
        val listener = sink.substringAfter("private inner class PlayerIntegrationListener : Player.Listener {")
            .substringBefore("val uri = mediaItem.localConfiguration?.uri")
        assertThat(listener).contains("reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO")
        assertThat(listener).contains("playbackHandler?.post { markPipelineJoin() }")
        assertThat(function(sink, "private fun markPipelineJoin()")).contains("if (usbStreamingThread != null)")
    }

    @Test
    fun `a flush hands what is queued to the thread as the continuation - it neither stops the thread nor resets the stream`() {
        val flush = function(sink, "override fun flush()")
        assertThat(flush).contains("thread.flush()")
        assertThat(flush).doesNotContain("usbStreamingThread?.stop()")
        val threadFlush = function(streamingThread, "fun flush() {")
        // B48-3: drained through the counted helper (the running frame count moves with it), which is audioQueue.drainTo
        assertThat(threadFlush).contains("drainQueueCounted(")
        assertThat(streamingThread.substringAfter("private fun drainQueueCounted(").substringBefore("\n    }\n")).contains("audioQueue.drainTo(")
        assertThat(threadFlush).doesNotContain("audioQueue.clear()")
        // The packetizer reset the old flush did dropped the old audio's last partial frame before the ramp.
        assertThat(threadFlush).doesNotContain("usbStream.flush()")
        // A flush found while a seek is owed keeps the first continuation (the ring still ends in it).
        assertThat(threadFlush).contains("if (seekRequest == null)")
    }

    @Test
    fun `the writer looks at a seek before a pause, and no buffer of an older generation is written as audio`() {
        val loop = streamingThread.substringAfter("thread = Thread({").substringBefore("}, \"UsbStreamingThread\")")
        val seek = loop.indexOf("takeSeekRequest()")
        val pause = loop.indexOf("if (paused || usbStream.consumePauseApplied()) {")
        val poll = loop.indexOf("audioQueue.poll(POLL_TIMEOUT_MS")
        assertThat(seek).isAtLeast(0)
        assertThat(pause).isGreaterThan(seek)
        assertThat(poll).isGreaterThan(pause)
        // A buffer polled just before a flush is old-position audio: it goes to the continuation, not the DAC.
        val staleCheck = loop.indexOf("if (isStale(buf))")
        val write = loop.indexOf("writeBuffer(buf)")
        assertThat(staleCheck).isAtLeast(0)
        assertThat(write).isGreaterThan(staleCheck)
        assertThat(streamingThread).contains("offerWithinHardLimit(AudioBuffer.RawBuffer(rawBytes, encoding, streamGeneration)")   // B46-B2: stamped, then bounded by time
        assertThat(streamingThread).contains("offerWithinHardLimit(AudioBuffer.FloatBuffer(floatBuf, processAtWrite, streamGeneration)")
    }

    @Test
    fun `a signal-mode change with the pipeline as the producer keeps the thread, the queue and the clock`() {
        val realign = function(sink, "private fun realignPipelineForSignalMode()")
        assertThat(realign).contains("realignWithinPipeline(")
        for (teardown in listOf("usbStreamingThread?.stop()", "attachedPlayer?.seekTo(", "pipelineClock.arm(")) {
            assertThat(realign).doesNotContain(teardown)
        }
        val kept = function(sink, "private fun realignWithinPipeline(")
        for (forbidden in listOf("stop()", "seekTo(", "pipelineClock", "usbStreamingThread = null", "flush()", "usbStartMediaTimeNeedsInit")) {
            assertThat(kept).doesNotContain(forbidden)
        }
        assertThat(kept).contains("ProcessingCrossfade(")
        assertThat(kept).contains("DirectPathTelemetry.pipelineRealignsKept.incrementAndGet()")
    }

    @Test
    fun `the stage a queued buffer still runs through is not wiped at the change`() {
        val refresh = function(sink, "private fun refreshSignalPath()")
        assertThat(refresh).contains("val keepsQueue = previous != null && previous !== requestedStage && usbStreamingThread != null")
        assertThat(refresh).contains("if (previous != null && previous !== requestedStage && !keepsQueue) previous.reset()")
        // It is reset once the queue it was for is gone.
        assertThat(function(sink, "override fun flush()")).contains("resetRetiredStage()")
        assertThat(function(sink, "private fun releaseUsbStream()")).contains("resetRetiredStage()")
        // The first buffers after the change are crossfaded, and only on the plain stereo path.
        val enqueue = sink.substringAfter("val crossfade = pipelineCrossfade?.takeIf { !it.isDone }")
            .substringBefore("// Advance buffer and return true")
        assertThat(enqueue.indexOf("enqueueDownmixed(")).isLessThan(enqueue.indexOf("enqueueProcessed(snapshot, crossfade.stage, thread, crossfade)"))
    }

    @Test
    fun `a rebuild waits for the fade-out a skip left to write before it releases the stream`() {
        val tail = function(sink, "private fun playOutPipelineTailBeforeRebuild()")
        val wait = tail.indexOf("thread.awaitFadeOut(PipelineThread.FADE_WAIT_MS)")
        val playOut = tail.indexOf("PipelineThread.playOutTail(")
        assertThat(wait).isAtLeast(0)
        assertThat(playOut).isGreaterThan(wait)
        // And that is before the stream is released.
        val configure = function(sink, "private fun configureUsbBitPerfect(") + function(sink, "private fun rebuildUsbStream(")
        assertThat(configure.indexOf("releaseUsbStream()", configure.indexOf("playOutPipelineTailBeforeRebuild()")))
            .isGreaterThan(configure.indexOf("playOutPipelineTailBeforeRebuild()"))
    }

    @Test
    fun `every gesture entry point the Kotlin side declares exists natively under the same name`() {
        val names = Regex("""private external fun (nativePipeline\w+)\(""").findAll(streamKotlin).map { it.groupValues[1] }.toList()
        assertThat(names).containsExactly(
            "nativePipelineRequestPause", "nativePipelineCancelPauseRequest", "nativePipelineConsumePauseApplied",
            "nativePipelineBeginPause", "nativePipelineBeginSeek", "nativePipelineFadingOut",
            "nativePipelineEndFadeOut", "nativePipelineResume", "nativePipelineFeedSilence", "nativePipelineStats",
        )
        for (name in names) assertThat(output).contains("Java_com_decent_usbaudio_UsbAudioStream_$name(")
    }

    @Test
    fun `the pipeline's ramps and silence are the engine's own functions, not a second implementation`() {
        for (name in listOf("Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWrite(", "Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWriteRaw(")) {
            assertThat(function(output, name)).contains("submitPipelinePcm(ctx,")
        }
        val helper = function(output, "bool submitPipelinePcm(")
        assertThat(helper).contains("fadeOutFramesOfBlock(")
        assertThat(helper).contains("pcmApplyGainRamp(")
        // B46-A5: the lengths are the stream's plan's (usb-pcm-packetizer.h), the same numbers seek-barrier.cpp's helpers give the ramp test
        assertThat(function(output, "static void armFadeOut(")).contains("ctx->plan.fadeOutFrames")
        val begin = function(output, "int pipelineBeginSeek(")
        assertThat(begin).contains("seekCutFor(")
        assertThat(begin).contains("ctx->plan.fadeInFrames")
        assertThat(function(output, "void pipelineResume(")).contains("ctx->plan.fadeInFrames")
        val feed = function(output, "bool pipelineFeedSilence(")
        assertThat(feed).contains("seekFeedSilenceStep(")
        assertThat(feed).contains("ctx->plan.silenceLeadFrames")
        assertThat(feed).contains("ctx->plan.silenceChunkFrames")
        // The loop the engine runs is the same step.
        val barrier = File(root + "decent-usb-audio-driver/src/main/jni/seek-barrier.cpp").readText()
        assertThat(function(barrier, "bool seekFeedSilenceUntilDone(")).contains("seekFeedSilenceStep(")
    }

    // ── AUD-004 / B8b-2 ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `every un-mute of the delegate respects the failure hold`() {
        val unmute = function(sink, "private fun unmuteDelegateIfNeeded()")
        val guard = unmute.indexOf("if (directFailureHold) return")
        val applied = unmute.indexOf("super.setVolume(pendingVolume)")
        assertThat(guard).isAtLeast(0)
        assertThat(applied).isGreaterThan(guard)
        // The hold's own exit: configure clears it before choosing between mute and un-mute.
        val configure = function(sink, "override fun configure(")
        assertThat(configure.indexOf("directFailureHold = false"))
            .isLessThan(configure.indexOf("if (directFailureHold) {"))
    }

    // ── AUD-145 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the USB writer waits through one helper, and the reap itself is unchanged`() {
        val reap = function(output, "static int reapOldestUrb(")
        assertThat(reap).contains("waitForCompletion(ctx,")
        assertThat(reap).doesNotContain("usleep(")
        // The reap, the feedback URB, the quarantine and every observer stay where they were.
        for (kept in listOf(
            "ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c)",
            "observeReapedUrb(ctx, site, c)",
            "urbQuarantineTake(c)",
            "handleFeedbackCompletion(ctx)",
            "checkIsoCompletion(ctx, c)",
            "reportUsbGlitch(USB_GLITCH_REAP_SLOW",
        )) {
            assertThat(reap).contains(kept)
        }
        val helper = function(output, "static bool waitForCompletion(")
        assertThat(helper).contains("POLLOUT")
        assertThat(helper).contains("poll(&pfd, 1,")
        // Only the helper sleeps for 125 µs in the steady-state path.
        assertThat(helper).contains("usleep(125)")
    }

    // ── B12a-6 ──────────────────────────────────────────────────────────────────────────────────

    private val streamingThreadSource = File(root + "decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbStreamingThread.kt")
        .readText().replace("\r\n", "\n")

    @Test
    fun `a pause is told to the stream at once and a resume takes the request back`() {
        val pause = function(streamingThreadSource, "fun pauseStreaming()")
        assertThat(pause.indexOf("paused = true")).isLessThan(pause.indexOf("usbStream.requestPause()"))
        val resume = function(streamingThreadSource, "fun resumeStreaming()")
        assertThat(resume.indexOf("paused = false")).isLessThan(resume.indexOf("usbStream.cancelPauseRequest()"))
        // handled like a pause the thread saw itself: counted, idle announced, the kept rest replayed at the resume
        val performPause = function(streamingThreadSource, "private fun performPause()")
        assertThat(performPause).contains("val appliedInBlock = usbStream.consumePauseApplied()")
        assertThat(performPause).contains("} else if (appliedInBlock) {")
        assertThat(performPause).contains("usbStream.markIdle()")
        assertThat(performPause).contains("usbStream.resumeAfterPause()")
    }

    @Test
    fun `the stream looks for a pause between URBs only where splitting a block is harmless`() {
        val impl = function(output, "static bool submitPcmToUrbsImpl(")
        // only between two URBs, never before the previous call's residual is behind us, and only on request
        assertThat(impl).contains("if (interruptible && offset >= residualPrefix && ctx->pauseRequested.load(std::memory_order_relaxed)) {")
        assertThat(impl.indexOf("if (interruptible && offset >= residualPrefix")).isLessThan(impl.indexOf("planUsbPcmUrb("))
        // what is handed back is not saved as a residual
        assertThat(impl.indexOf("if (interrupted) {")).isLessThan(impl.indexOf("int leftover = dataLen - offset;"))
        // the plain entry point is the same function with no interruption: the engine and the silence feed are untouched
        assertThat(output).contains(
            "bool submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int totalBytes) {\n" +
                "    return submitPcmToUrbsImpl(ctx, pcmData, totalBytes, false, nullptr);\n}",
        )
        val helper = function(output, "bool submitPipelinePcm(")
        // audio already playing, and no fade-in under way (the kept rest would be ramped twice)
        assertThat(helper).contains("const bool interruptible = frameBytes > 0 && g.hasAudio && g.fadeInRemaining <= 0;")
        // the fade starts through the same function a pause at a block's end uses, and the rest becomes its continuation
        assertThat(helper).contains("if (pipelineBeginPause(ctx)) {")
        assertThat(helper).contains("return submitPipelinePcm(ctx, rest, restFrames * frameBytes, restFrames);")
        // the frames of what was written before the pause are counted, the rest when it is written or replayed
        assertThat(helper).contains("ctx->framesWritten += consumedFrames;")
        // the request is dropped wherever a pause or seek is handled some other way
        for (handled in listOf("bool pipelineBeginPause(", "int pipelineBeginSeek(", "void pipelineResume(")) {
            assertThat(function(output, handled)).contains("ctx->pauseRequested.store(false")
        }
    }

    @Test
    fun `nothing about the ring, the fades or the joins changes`() {
        // the ring is the same 80 slots (now the plan's: one definition in usb-pcm-packetizer.h) and the fades the engine's own lengths
        assertThat(File(root + "decent-usb-audio-driver/src/main/jni/usb-pcm-packetizer.h").readText()).contains("#define USB_TRANSPORT_RING_URBS 128")
        assertThat(File(root + "decent-usb-audio-driver/src/main/jni/usb-audio-output.h").readText()).contains("#define USB_AUDIO_NUM_URBS USB_TRANSPORT_RING_URBS")
        assertThat(function(output, "static void armFadeOut(")).contains("ctx->plan.fadeOutFrames")
        assertThat(function(output, "void pipelineResume(")).contains("ctx->plan.fadeInFrames")
        // a natural join never asks for a pause: the thread's end-of-stream and the sink's held pause are untouched
        assertThat(function(sink, "private fun pauseStreamingThread()")).contains("NaturalJoinPause.onMedia3Stop(")
    }
}
