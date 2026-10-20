package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B25: where the changes are wired, read at the source. What each one does is measured by its own test; this pins that the real
 * code goes through them, in the order that matters, and that what the batch must not touch is where it was.
 */
class B25WiringBoundaryTest {
    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")
    private val wrapper = File("../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3")

    private fun text(base: File, path: String) = File(base, path).readText().replace("\r\n", "\n")

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

    // ── S1: unplugging the DAC pauses ────────────────────────────────────────────────────────────

    private val engine get() = code(text(main, "data/service/player/DualPlayerEngine.kt"))
    private val sink get() = code(text(wrapper, "UsbAudioSink.kt"))

    @Test
    fun `S1 a dead Direct stream holds the speaker delegate silent before it releases anything, and never falls through to it`() {
        val dead = sink.substringAfter("var stream = usbAudioStream").substringBefore("if (config.directEnabled && stream?.isAlive == true)")
        val hold = dead.indexOf("directFailureHold = true")
        val mute = dead.indexOf("muteDelegateIfNeeded()")
        val release = dead.indexOf("releaseUsbStream()")
        assertThat(hold).isAtLeast(0)
        assertThat(mute).isAtLeast(0)
        assertThat(release).isAtLeast(0)
        // `releaseUsbStream` ends in `unmuteDelegateIfNeeded`, which is a no-op only while the hold stands
        assertThat(hold).isLessThan(release)
        assertThat(mute).isLessThan(release)
        assertThat(dead).contains("DirectStreamLoss.reasonFor(deviceGone)")
        // and the hold really does keep the un-mute from running
        assertThat(body(sink, "private fun unmuteDelegateIfNeeded()")).contains("if (directFailureHold) return")
    }

    @Test
    fun `S1 the engine observes the bus first in the refresh, the sink report and every route evaluation`() {
        val refresh = body(engine, "fun refreshUsbAudioDiagnostics()")
        assertThat(refresh).contains("observeUsbPresence(usbDevice != null")
        // before the diagnostics are published and before a reevaluation can rebuild with the play intent
        assertThat(refresh.indexOf("observeUsbPresence(")).isLessThan(refresh.indexOf("publishUsbDiagnostics("))
        assertThat(refresh.indexOf("observeUsbPresence(")).isLessThan(refresh.indexOf("reevaluateAutomaticRoute("))

        val publish = body(engine, "private fun publishUsbDiagnostics(")
        assertThat(publish).contains("DirectStreamLoss.isDeviceGone(diagnostics.errorReason)")
        assertThat(publish.indexOf("observeUsbPresence(false")).isLessThan(publish.indexOf("_audioRouteState.value ="))
        // a DAC that left is not a failed claim: nothing is latched against it
        assertThat(publish).contains("!dacGone")

        val reevaluate = body(engine, "private fun reevaluateAutomaticRouteNow(")
        assertThat(reevaluate).contains("observeUsbPresence(device != null, logMessage)")
        assertThat(reevaluate.indexOf("observeUsbPresence(")).isLessThan(reevaluate.indexOf("reconcileHandoffWithDecision("))
        assertThat(reevaluate.indexOf("observeUsbPresence(")).isLessThan(reevaluate.indexOf("applyAndroidUsbRouteGuard("))
        assertThat(reevaluate.indexOf("observeUsbPresence(")).isLessThan(reevaluate.indexOf("rebuildPlayersPreservingMasterState("))
    }

    @Test
    fun `S1 the pause tells the service first, pauses both players, and ends what could restart playback by itself`() {
        val pause = body(engine, "private fun pauseForOutputLoss(")
        assertThat(pause.indexOf("outputLossListener?.onPlaybackPausedForOutputLoss(reason)"))
            .isLessThan(pause.indexOf("playerA.playWhenReady = false"))
        assertThat(pause).contains("playerB?.playWhenReady = false")

        val observe = body(engine, "private fun observeUsbPresence(")
        assertThat(observe).contains("isFocusLossPause = false")
        assertThat(observe).contains("pausedByDuck = false")
        assertThat(observe).contains("androidUsbRouteGuard.onDeviceLost()")
        // the rebuild captures `playWhenReady` at its start, so the pause above must already have happened by then
        assertThat(body(engine, "private fun reassembleMasterPlayer(")).contains("val desiredPlayWhenReady = playerA.playWhenReady")
    }

    @Test
    fun `S1 the service arms the same resume rule for it, and the engine's listener is cleared with the others`() {
        val service = code(text(main, "data/service/MusicService.kt"))
        assertThat(service).contains("engine.outputLossListener = OutputLossListener")
        assertThat(service).contains("headsetReconnectRule.onOutputLostPause(")
        assertThat(service).contains("engine.outputLossListener = null")
        // the old copies of the rule are gone
        assertThat(service).doesNotContain("shouldResumeAfterHeadsetReconnect")
        assertThat(service).doesNotContain("isReconnectableHeadsetOutput")
    }

    @Test
    fun `S1 plugging in while playing is not touched, the handoff and the Direct claim still run on an attach`() {
        // the observation only ever acts on a DAC that left; an attach (present = true) returns before any pause
        val guard = code(text(main, "data/service/player/OutputLossGuard.kt"))
        assertThat(guard).contains("if (!wasPresent || present || !playWhenReady) return null")
        assertThat(engine).contains("maybeStartDirectHandoff(decision, previousRoute)")
    }
    // ── S6: back from both edges ─────────────────────────────────────────────────────────────────

    @Test
    fun `S6 both back gestures read the edge and mirror the move, and nothing excludes a system gesture`() {
        val gesture = code(text(main, "presentation/navigation/DapEdgeBackGesture.kt"))
        assertThat(gesture).contains("EdgeBackGesturePolicy.edgeAt(")
        assertThat(gesture).contains("edge.inward(delta.x)")
        val bridge = code(text(main, "presentation/components/ModalInputBridge.kt"))
        assertThat(bridge).contains("EdgeBackGesturePolicy.edgeAt(event.rawX, screenWidthPx(), edgeWidthPx)")
        assertThat(bridge).contains("RightEdgeCentrePopupPositionProvider")
        // the activity's indicator follows the side too
        assertThat(code(text(main, "MainActivity.kt"))).contains("onEdge = { edgeBackEdge = it }")
        // no screen carves a hole out of the system's gesture area (the right edge's would have shadowed the system's Back there)
        main.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach {
            assertThat(code(it.readText())).doesNotContain("systemGestureExclusion")
        }
    }

    @Test
    fun `S6 the dedicated Lock Task mask still blocks Home and Recents, and the batch did not touch it`() {
        val features = com.lostf1sh.pixelplayeross.dap.admin.DapKeyguardPolicy
        val mask = features.APPLIANCE_WAKE_FEATURES or features.SYSTEM_KEYGUARD_IN_WAKE_FEATURES
        assertThat(mask and android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_HOME).isEqualTo(0)
        assertThat(mask and android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW).isEqualTo(0)
        assertThat(mask and android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS).isEqualTo(0)
    }
    // ── S2: the flick ────────────────────────────────────────────────────────────────────────────

    private val lockScreen get() = code(text(main, "presentation/lockscreen/DapLockScreen.kt"))

    @Test
    fun `S2 the lock surface decides from the policy and feeds the speed tracker from the touch-down`() {
        assertThat(lockScreen).contains("LockDismissPolicy.shouldOpen(travelDp, upwardDpPerS)")
        assertThat(lockScreen).doesNotContain("DISMISS_FLING_DP_PER_S")
        assertThat(lockScreen).doesNotContain("DISMISS_TRAVEL_DP")
        // the observer sees every event of the gesture before the drag detector, whatever it consumes, and consumes none itself
        assertThat(lockScreen).contains("awaitPointerEvent(PointerEventPass.Initial)")
        assertThat(lockScreen).contains("changedToDownIgnoreConsumed()")
        assertThat(lockScreen.substringAfter("detectVerticalDragGestures(").substringBefore("onVerticalDrag")).doesNotContain("resetTracking")
    }

    // ── S3: the elapsed time ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S3 the lock surface reads the player itself and samples only while the screen is on`() {
        val surface = code(text(main, "presentation/lockscreen/DapLockSurface.kt"))
        assertThat(surface).contains("playerViewModel.samplePlaybackPositionNow()")
        assertThat(surface).doesNotContain("currentPlaybackPosition")
        // one read per call, no loop, no job of its own: nothing polls while the screen is off or the surface is not drawn
        val holder = code(text(main, "presentation/viewmodel/PlaybackStateHolder.kt"))
        val sample = holder.substringAfter("fun samplePlaybackPositionNow()").substringBefore("private fun shouldSampleLocalProgress")
        assertThat(sample).doesNotContain("while")
        assertThat(sample).doesNotContain("delay(")
        assertThat(sample).doesNotContain("launch")
        // the progress loop is the one that needs subscribers; this does not wait for any
        assertThat(sample).doesNotContain("subscriptionCount")
        // the sampler in the lock surface lives inside the screen's lifecycle, and the host draws the surface only while it is up
        assertThat(lockScreen).contains("repeatOnLifecycle(Lifecycle.State.STARTED)")
        val host = code(text(main, "presentation/lockscreen/DapLockScreenHost.kt"))
        assertThat(host).contains("if (surfaceVisible && displayedSong != null) {")
    }

    // ── S4: the wake's first frame ───────────────────────────────────────────────────────────────

    @Test
    fun `S4 the wake cycle holds the app from its arming, and the activity hands the signal to the shell`() {
        val host = code(text(main, "presentation/lockscreen/DapLockScreenHost.kt"))
        assertThat(host).contains("cover?.covered = WakeCycleCover.covers(machine.state, songPresent())")
        val sync = body(host, "fun sync()")
        assertThat(sync).contains("publishCover()")
        assertThat(host).contains("DisposableEffect(cover) { onDispose { cover?.covered = false } }")
        // the signal reaches the lifecycle cap through a listener, not through composition
        val freeze = code(text(main, "presentation/lockscreen/FreezeWhileCovered.kt"))
        assertThat(freeze).contains("cover?.attach { owner.coverHeld = it }")
        assertThat(freeze).contains("val ceiling = if (frozen || coverHeld)")
        val activity = code(text(main, "MainActivity.kt"))
        assertThat(activity).contains("hidden = (lockSurfaceVisible || lockCover.covered) && !lockRevealing")
        assertThat(activity).contains("cover = lockCover")
        // the wake cycle's own timing logic is what it was: the machine knows nothing of the cover
        assertThat(code(text(main, "presentation/lockscreen/WakeCycle.kt"))).doesNotContain("Cover")
    }

    // ── S5: the player's controls ────────────────────────────────────────────────────────────────

    @Test
    fun `S5 the portrait player's block starts under the header and the spare height is left below it`() {
        val layouts = code(text(main, "presentation/components/player/PlayerLayouts.kt"))
        val portrait = body(layouts, "internal fun FullPlayerPortraitContent(")
        assertThat(portrait).contains("Modifier.weight(1f, fill = false).fillMaxWidth(), contentAlignment = Alignment.TopCenter")
        assertThat(portrait).doesNotContain("contentAlignment = Alignment.Center")
        // the order and the spacings of the design are the ones that were there
        assertThat(portrait.indexOf("albumCoverSection(Modifier)")).isLessThan(portrait.indexOf("songMetadataSection()"))
        assertThat(portrait.indexOf("playerProgressSection()")).isLessThan(portrait.indexOf("controlsSection()"))
    }

    // ── S8: the statistics ───────────────────────────────────────────────────────────────────────

    @Test
    fun `S8 every ranking of the summary goes through one comparator and the screen has one switch for all of them`() {
        // B28 rebuilt the statistics: the rankings are ordered in one place, from the same rows, by the mode at the moment they are read.
        val report = code(text(main, "data/stats/StatsReport.kt"))
        // songs, genres, artists and albums: the four rankings, each ordered by the mode and nothing else
        assertThat(report.split("sortedWith(rankingComparator(mode").size - 1).isEqualTo(4)
        // a play is counted by the rule, never by the number of merged segments
        val ledger = code(text(main, "data/stats/ListeningLedger.kt"))
        assertThat(ledger).contains("PlayRule.isPlay(")
        assertThat(ledger).doesNotContain("segments.size")
        val screen = code(text(main, "presentation/screens/StatsScreen.kt"))
        assertThat(screen.split("onSelect = onMode").size - 1).isEqualTo(1)
        assertThat(screen).contains("SortRow(")
        assertThat(code(text(main, "presentation/viewmodel/StatsViewModel.kt"))).contains("fun onRankModeSelected(")
        // the history is read as it is stored: no event field or file format changed with this batch
        assertThat(code(text(main, "data/stats/PlaybackStatsRepository.kt"))).contains("val libraryItemId: String? = null")
    }
}
