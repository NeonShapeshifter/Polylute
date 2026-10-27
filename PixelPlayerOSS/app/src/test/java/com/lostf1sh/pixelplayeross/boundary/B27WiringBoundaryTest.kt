package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B27: where the changes are wired, read at the source. What each one does is measured by its own test (the engine and the
 * controller run for real in `PendingTransitionOutputLossTest` and `SafetyVolumeWriteResultTest`, the output in the native gate);
 * this pins that the real code goes through them and that the debug instruments exist in debug builds only.
 */
class B27WiringBoundaryTest {
    private val app = File("src")
    private val main = File(app, "main/java/com/lostf1sh/pixelplayeross")

    private fun text(file: File) = file.readText().replace("\r\n", "\n")

    /** The source without its comments: what a scan for a call should read (a comment may name what it explains). */
    private fun code(source: String) = source.lines()
        .map { it.substringBefore("//") }
        .filterNot { it.trim().let { line -> line.startsWith("*") || line.startsWith("/*") } }
        .joinToString("\n")

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val indent = source.substring(source.lastIndexOf('\n', start) + 1, start).takeWhile { it == ' ' }
        return source.substring(start, source.indexOf("\n$indent}\n", start))
    }

    // ── A1: a pending crossfade does not play after the music was paused under it ──────────────────

    private val engine get() = code(text(File(main, "data/service/player/DualPlayerEngine.kt")))

    @Test
    fun `A1 the crossfade shows its ticket and the play intent before it waits, after it waits and right before the first play`() {
        val overlap = body(engine, "private suspend fun performOverlapTransition(settings: TransitionSettings, ticket: Long)")
        val before = overlap.indexOf("overlapMayStart(ticket, outgoingPlayer, auxiliaryPlayer, \"before waiting")
        val wait = overlap.indexOf("awaitPlayerReady(")
        val after = overlap.indexOf("overlapMayStart(ticket, outgoingPlayer, auxiliaryPlayer, \"after waiting")
        val firstPlay = overlap.indexOf("playerA.play()")
        val secondPlay = overlap.indexOf("auxiliaryPlayer.play()")
        assertThat(before).isAtLeast(0)
        assertThat(wait).isAtLeast(0)
        assertThat(after).isAtLeast(0)
        assertThat(firstPlay).isAtLeast(0)
        assertThat(before).isLessThan(wait)
        assertThat(wait).isLessThan(after)
        // nothing that can suspend sits between the last check and the first play
        assertThat(after).isLessThan(firstPlay)
        assertThat(overlap.substring(after, firstPlay)).doesNotContain("delay(")
        assertThat(overlap.substring(after, firstPlay)).doesNotContain("await")
        assertThat(firstPlay).isLessThan(secondPlay)
        // and no play() is issued ahead of the first check
        assertThat(overlap.substring(0, before)).doesNotContain(".play()")
    }

    @Test
    fun `A1 the output loss, a pause, a focus loss and a rebuild each invalidate a pending crossfade`() {
        assertThat(body(engine, "private fun pauseForOutputLoss(reason: OutputLossReason, trigger: String)"))
            .contains("invalidatePendingTransition(\"output lost")
        assertThat(body(engine, "private fun pauseForTransientFocusLoss()")).contains("invalidatePendingTransition(")
        assertThat(engine).contains("invalidatePendingTransition(\"audio focus lost\")")
        assertThat(engine).contains("invalidatePendingTransition(\"playback paused")
        assertThat(body(engine, "private fun reassembleMasterPlayer(logMessage: String)")).contains("abandonTransitionForRebuild()")
        assertThat(body(engine, "fun initialize()")).contains("abandonTransitionForRebuild()")
        assertThat(body(engine, "fun cancelNext()")).contains("transitionBarrier.invalidate()")
        // the pause the crossfade itself arms at the end of an item is not a reason to drop it
        assertThat(engine).contains("reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM")
    }

    // ── A3: Safety Max publishes what the DAC reported ─────────────────────────────────────────────

    private val safety get() = code(text(File(main, "data/volume/SafetyVolumeController.kt")))

    @Test
    fun `A3 no write publishes the level that was asked for`() {
        val setRaw = body(safety, "fun setRawLevel(requested: Int, origin: String = \"ui\")")
        assertThat(setRaw).contains("write.publishedStep(")
        assertThat(setRaw).contains("sameOutput(")
        // the old shape: the requested level written into the state unconditionally
        assertThat(setRaw).doesNotContain("copy(rawLevel = raw)")
        assertThat(safety).contains("private fun setBackendRaw(manager: AudioManager, backend: VolumeBackendSnapshot, raw: Int, origin: String): VolumeWrite")
        assertThat(safety).doesNotContain("backend.copy(rawLevel = clamp)")
        assertThat(safety).contains("write.publishedStep(backend.rawLevel)")
    }

    @Test
    fun `A3 the resume verdict waits for a level the DAC has not confirmed, before it looks at the ceiling`() {
        val verdict = code(text(File(main, "data/volume/HandoffResumeSafety.kt")))
        val unverified = verdict.indexOf("!backend.levelVerified -> HandoffVolumeVerdict.WAIT")
        val ceiling = verdict.indexOf("ceiling != null && backend.rawLevel > ceiling")
        assertThat(unverified).isAtLeast(0)
        assertThat(unverified).isLessThan(ceiling)
    }

    @Test
    fun `A3 the controller does not retry a write from reconcile`() {
        // one write per call of the clamp and nothing that calls reconcile again from the failure path
        val clamp = safety.substringAfter("val write = setBackendRaw(manager, backend, clamp, \"clamp\")").substringBefore("} else {")
        assertThat(clamp).doesNotContain("reconcile(")
        assertThat(clamp).doesNotContain("setBackendRaw(")
    }

    // ── A5: the instruments are in debug builds only ───────────────────────────────────────────────

    @Test
    fun `A5 the noise mark is in the main source set, so the release has it, and the debug source set has no copy of it or a provider for it`() {
        assertThat(File(main, "data/diagnostics/NoiseMark.kt").isFile).isTrue()
        // B39-S2: the one thing of the debug bridge that moved to the release. Nothing of it is left behind in the debug source set.
        for (name in listOf("NoiseMark.kt", "DebugHooksProvider.kt")) {
            val names = File(app, "debug").walkTopDown().filter { it.isFile }.map { it.name }.toList()
            assertThat(names).doesNotContain(name)
        }
        assertThat(File(main, "data/diagnostics/DebugHooks.kt").exists()).isFalse()
        for (variant in listOf("release", "benchmark")) {
            val names = File(app, variant).walkTopDown().filter { it.isFile }.map { it.name }.toList()
            assertThat(names).doesNotContain("NoiseMark.kt")
        }
    }

    @Test
    fun `A5 the broadcast that runs it from a shell is declared in the debug manifest only, and no manifest has a provider for it`() {
        val debug = text(File(app, "debug/AndroidManifest.xml"))
        assertThat(debug).contains("com.lostf1sh.pixelplayeross.debug.MARK_NOISE")
        for (variant in listOf("debug", "main", "release", "benchmark")) {
            val manifest = File(app, "$variant/AndroidManifest.xml")
            if (!manifest.isFile) continue
            assertThat(text(manifest)).doesNotContain("DebugHooksProvider")
            if (variant != "debug") assertThat(text(manifest)).doesNotContain("MARK_NOISE")
        }
    }

    @Test
    fun `A5 the More menu and Maintenance offer the row in every build, the More menu only in Now Playing`() {
        val sheet = code(text(File(main, "presentation/components/SongInfoBottomSheet.kt")))
        val guard = sheet.indexOf("if (onViewTrackDetails != null) {")
        val row = sheet.indexOf("R.string.noise_mark)")
        assertThat(guard).isAtLeast(0)
        assertThat(row).isGreaterThan(guard)
        assertThat(sheet).doesNotContain("BuildConfig.DEBUG")
        assertThat(sheet).doesNotContain("DebugHooks")
        val maintenance = code(text(File(main, "presentation/screens/MaintenanceScreen.kt")))
        assertThat(maintenance).contains("stringResource(R.string.noise_mark)")
        assertThat(maintenance).contains("NoiseMarkDialog(noiseMark)")
        // the debug receiver is the only caller outside the two rows, and it lives in the debug source set
        val callers = File(main, "").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { code(text(it)).contains("NoiseMark.mark(") || code(text(it)).contains("noiseMark.mark(") || code(text(it)).contains("nativeGetSecondSummaries") }
            .map { it.name }.toList()
        // B40-F7: the Detailed log reads the same ring every ten seconds while the listener has it on (DetailedLoggingService)
        assertThat(callers).containsExactly("NoiseMarkAction.kt", "MaintenanceScreen.kt", "SongInfoBottomSheet.kt", "NoiseMark.kt", "DetailedLoggingService.kt")
    }

    @Test
    fun `A5 the output records the seconds where it already looks at the reading and the packets, and nowhere on the PCM path`() {
        val jni = File("../../decent-player/libs/decent-usb-audio-driver/src/main/jni")
        val output = code(text(File(jni, "usb-audio-output.cpp")))
        assertThat(body(output, "static void handleFeedbackCompletion(UsbAudioContext *ctx)")).contains("usbSecondRingFeedback(&g_secondRing")
        assertThat(body(output, "static void checkIsoCompletion(UsbAudioContext *ctx, const struct usbdevfs_urb *c)")).contains("usbSecondRingIso(&g_secondRing")
        // nothing in the packetizer, the converters or the submit
        for (pcm in listOf("usb-pcm-packetizer.cpp", "usb-pcm-convert.cpp")) {
            assertThat(code(text(File(jni, pcm)))).doesNotContain("usbSecondRing")
        }
        val submit = output.substringAfter("static bool submitPcmToUrbsImpl(").substringBefore("bool submitPcmToUrbs(UsbAudioContext")
        assertThat(submit).doesNotContain("usbSecondRing")
    }
}
