package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B23: where the performance changes are wired, read at the source. What each one does is measured by its own test
 * (`ForegroundCatchUpTest`, `ActivityDayCheckTest`, `StreamingThreadPriorityTest`, `usb-glitch-test.cpp`, `ParkedWhileCollapsedTest`,
 * `AlbumCarouselResyncTest`, `HiddenQueueSheetTest`, `SnapshotRemapMemoTest`, `UsbRouteFactsCacheTest`, `ArtworkGrantLedgerTest`);
 * this pins that the real code goes through them and that what the batch must not touch is where it was.
 */
class B23WiringBoundaryTest {
    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")
    private val jni = File("../../decent-player/libs/decent-usb-audio-driver/src/main/jni")
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
        // the function's closing brace is the first line that is just its indentation + "}"
        val indent = source.substring(source.lastIndexOf('\n', start) + 1, start).takeWhile { it == ' ' }
        return source.substring(start, source.indexOf("\n$indent}\n", start))
    }

    // ── P1 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the activity builds no controller at start, and the view model's one lives as long as it did`() {
        val activity = code(text(main, "MainActivity.kt"))
        for (gone in listOf("MediaController.Builder", "SessionToken(", "MediaController.releaseFuture", "mediaControllerFuture", "buildAsync")) {
            assertThat(activity).doesNotContain(gone)
        }
        assertThat(body(activity, "override fun onStart()")).doesNotContain("MediaController")
        assertThat(body(activity, "override fun onStop()")).doesNotContain("MediaController")
        // the app's controller: built with the ViewModel, released with it, and nowhere in between
        val vm = text(main, "presentation/viewmodel/PlayerViewModel.kt")
        assertThat(vm).contains("private val mediaControllerFuture: ListenableFuture<MediaController> =\n        mediaControllerFactory.create(context, sessionToken, mediaControllerListener)")
        val cleared = body(vm, "override fun onCleared()")
        assertThat(cleared.indexOf("controllerToRelease?.release()")).isAtLeast(0)
        assertThat(cleared).contains("mediaControllerFuture.cancel(true)")
        assertThat(vm.split("mediaControllerFactory.create(").size - 1).isEqualTo(1)
        // the activity's lifecycle hooks never reach the controller
        assertThat(body(vm, "fun onMainActivityStart()")).doesNotContain("mediaController")
        assertThat(body(vm, "fun onMainActivityResume()")).doesNotContain("mediaController")
        // and the only builders in the app are the factory's (the looper test pins that it pins the looper)
        val builders = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("MediaController.Builder(") }.map { it.name }.toList()
        assertThat(builders).containsExactly("MediaControllerFactory.kt")
    }

    @Test
    fun `the foreground catch-up and the day check go through the settle gate`() {
        val sync = text(main, "data/worker/SyncManager.kt")
        val observer = body(sync, "private fun observeAppForeground()")
        assertThat(observer).contains("foregroundCatchUp.onForeground()")
        assertThat(sync).doesNotContain("maybeRunForegroundCatchUpSync")
        assertThat(sync).doesNotContain("lastForegroundSyncTime")
        assertThat(sync).contains("ForegroundCatchUp(")
        assertThat(sync).contains("cooldownMs = FOREGROUND_SYNC_COOLDOWN_MS")
        val vm = text(main, "presentation/viewmodel/PlayerViewModel.kt")
        assertThat(body(vm, "fun onMainActivityStart()")).contains("activityDayCheck.onActivityStart()")
        assertThat(body(vm, "fun onMainActivityStart()")).doesNotContain("checkAndUpdateDailyMixIfNeeded()")
        assertThat(body(vm, "fun onMainActivityResume()")).contains("activityDayCheck.onActivityResume()")
        // the midnight timer and the date broadcasts are not wakes: they still ask at once
        val mix = text(main, "presentation/viewmodel/DailyMixStateHolder.kt")
        assertThat(body(mix, "private fun scheduleMidnight(")).contains("checkAndUpdateIfNeeded()")
        // what the lock screen's wake cycle and the library's trim restore do is not this batch's
        assertThat(text(main, "PixelPlayerApplication.kt")).contains("libraryStateHolder.get().restoreAfterTrimIfNeeded()")
    }

    // ── P2 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the writer raises its own priority from inside its thread, before its loop, and keeps the Java priority as the fallback`() {
        val thread = code(text(wrapper, "UsbStreamingThread.kt"))
        val start = body(thread, "fun start()")
        val lambda = start.substringAfter("thread = Thread({").substringBefore("}, \"UsbStreamingThread\")")
        assertThat(lambda.indexOf("writerPriority.raiseCurrentThread()")).isAtLeast(0)
        assertThat(lambda.indexOf("writerPriority.raiseCurrentThread()")).isLessThan(lambda.indexOf("while (running)"))
        assertThat(start).contains("priority = Thread.MAX_PRIORITY")
        // the priority is applied nowhere else in the wrapper, and the system call is the audio priority, not a realtime class
        assertThat(thread.split("Process.setThreadPriority(").size - 1).isEqualTo(1)
        assertThat(thread).contains("Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)")
        assertThat(wrapper.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "UsbStreamingThread.kt" }
            .none { code(it.readText()).contains("Process.setThreadPriority(") }).isTrue()
    }

    @Test
    fun `the output gap model reads clocks at the submit and changes nothing that is sent`() {
        val output = text(jni, "usb-audio-output.cpp")
        // hooked where the kernel accepted the URB, and nowhere in the reap itself (the reap is pinned unchanged by another test)
        val submit = body(output, "static int submitRingUrb(")
        assertThat(submit.indexOf("observeOutputGap(ctx, numPackets)")).isGreaterThan(submit.indexOf("ctx->urbSubmits.fetch_add"))
        assertThat(body(output, "static int reapOldestUrb(")).doesNotContain("outputGap")
        // the line is built only when a gap was found, never per URB
        val observe = body(output, "static void observeOutputGap(")
        assertThat(observe).contains("if (usbOutputGapSubmit(")
        // B24-Q3: the report also takes the writer's scheduler delta (read only when a gap was found)
        assertThat(observe.indexOf("reportOutputGap(ctx, gap, ")).isGreaterThan(observe.indexOf("if (usbOutputGapSubmit("))
        for (forbidden in listOf("malloc", "new ", "std::string", "snprintf", "LOGI", "LOGW", "LOGE", "memcpy")) {
            assertThat(observe).doesNotContain(forbidden)
        }
        assertThat(output).contains("\"FEEDBACK_STUCK\", \"FEEDBACK_UNSTUCK\", \"OUTPUT_GAP\"")
        // the packet plan, the copy into the slot and the submit keep their order: gap model aside, the loop is the loop it was
        val loop = body(output, "static bool submitPcmToUrbsImpl(")
        assertThat(loop.indexOf("memcpy(slot->buffer, data + offset, plan.totalBytes);"))
            .isLessThan(loop.indexOf("submitRingUrb(ctx, plan.packetSizes, plan.packetCount, plan.totalBytes)"))
        assertThat(loop).contains("const int64_t reapStartUs = monotonicUs();")
        assertThat(loop).contains("usbOutputGapAddReap(&ctx->outputGap, monotonicUs() - reapStartUs);")
        // the ring is as deep as it was
        assertThat(text(jni, "usb-audio-output.h")).contains("#define USB_AUDIO_NUM_URBS 80")
    }

    // ── P3 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the full player is parked while collapsed, with the same test as its offscreen offset`() {
        val layers = text(main, "presentation/components/UnifiedPlayerSheetLayers.kt")
        assertThat(layers).contains("ParkedWhileCollapsed(parked = parked, modifier = Modifier.fillMaxSize())")
        assertThat(layers).contains("currentSheetContentState != PlayerSheetState.EXPANDED &&")
        assertThat(layers).contains("playerContentExpansionFraction.value <= 0.01f")
        // the same fraction decides the offscreen offset
        assertThat(layers).contains("if (playerContentExpansionFraction.value <= 0.01f) IntOffset(0, 10000)")
        // what it is given is held; the live song never reaches it directly
        val full = layers.substringAfter("FullPlayerContent(").substringBefore("onFavoriteToggle = onFavoriteToggle")
        for (held in listOf("currentSong = heldSong,", "currentPlaybackQueue = currentPlaybackQueue,", "currentQueueSourceName = heldQueueSourceName,",
            "currentMediaItemIndex = heldInfrequentState.currentMediaItemIndex,")) {
            assertThat(full).contains(held)
        }
        assertThat(full).doesNotContain("currentSongNonNull")
        assertThat(full).doesNotContain("infrequentPlayerState.")
        assertThat(layers).contains("val currentPlaybackQueue = rememberHeldWhile(parked, liveQueue)")
        // the warm latch is untouched
        assertThat(text(main, "presentation/components/scoped/FullPlayerCompositionPolicy.kt")).contains("collapsedWarmDelayMs: Long = 650L")
        // the carousel jumps off screen, and is told by the cover surface
        assertThat(text(main, "presentation/components/player/PlayerCoverSurface.kt"))
            .contains("isOnScreen = { expansionFractionProvider() >= CAROUSEL_ON_SCREEN_FRACTION }")
        val carousel = text(main, "presentation/components/AlbumCarouselSelection.kt")
        assertThat(carousel).contains("if (isShiftOnly || !isOnScreen()) {")
        assertThat(carousel).contains("if (resyncing || !isOnScreen()) return@collect")
    }

    @Test
    fun `the queue sheet gets the held song while it is closed`() {
        val surface = text(main, "presentation/components/QueueSurface.kt")
        assertThat(surface).contains("val song = rememberQueueSheetSong(")
        val sheetCall = surface.substringAfter("QueueBottomSheet(").substringBefore("onDismiss = onDismissQueue")
        assertThat(sheetCall).contains("currentSongId = song.currentSongId,")
        assertThat(sheetCall).contains("currentMediaItemIndex = song.currentMediaItemIndex,")
        assertThat(sheetCall).contains("isPlaying = song.isPlaying,")
        assertThat(sheetCall).doesNotContain("infrequentPlayerState.")
        assertThat(surface).contains("held = !visible,")
    }

    // ── P4 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the snapshot capture takes its remaps from the memo, and the grants go through the ledger`() {
        val service = text(main, "data/service/MusicService.kt")
        val capture = body(service, "private fun capturePlaybackSnapshotNow(")
        assertThat(capture).contains("snapshotRemapMemo.currentIndex(keptIndices, sourceIndex)")
        assertThat(capture).contains("snapshotRemapMemo.savedOrder(order, sourceCount, keptIndices)")
        assertThat(capture).doesNotContain("ShuffleOrderPersistence.orderToSave(")
        assertThat(capture).doesNotContain("QueueIndexMapping.remap(")
        // the restore keeps its own, exact mapping
        assertThat(service).contains("QueueIndexMapping.remap(keptIndices, snapshot.currentIndex)")
        assertThat(service).contains("ShuffleOrderPersistence.orderToRestore(")

        val grant = body(service, "private fun grantArtworkUriPermissions(")
        assertThat(grant.indexOf("artworkGrants.needsGrant(targetPackage, uriString)")).isLessThan(grant.indexOf("grantUriPermission("))
        assertThat(grant.indexOf("artworkGrants.recordGranted(targetPackage, uriString)")).isGreaterThan(grant.indexOf(".onSuccess {"))
        assertThat(body(service, "private fun systemUiArtworkGrantTarget()")).contains("if (systemUiTargetResolved) return systemUiTarget")
        // the controller's own connection still grants the current item
        assertThat(service).contains("grantArtworkUriPermissions(\n                    controller.packageName,")
    }

    @Test
    fun `the route evaluation goes through the facts cache, a diagnostics refresh drops it, and the Android-route fact is lazy`() {
        val engine = text(main, "data/service/player/DualPlayerEngine.kt")
        val now = body(engine, "private fun reevaluateAutomaticRouteNow(")
        assertThat(now).contains("usbRouteFacts.evaluate(")
        assertThat(now).doesNotContain("findUsbAudioDevice()")
        assertThat(now).doesNotContain("androidUsbRouteAvailable()")
        assertThat(now).contains("if (!factsReused) lastLiveRouteDecision = evaluation.decision")
        // the eager read is gone from the facts; the policy asks for it only for an Android decision with a DAC
        val evaluate = body(engine, "private fun evaluateRoute(")
        assertThat(evaluate).contains("androidUsbRouteAvailable = ::androidUsbRouteAvailable,")
        assertThat(evaluate).doesNotContain("androidUsbRouteAvailable = androidUsbRouteAvailable()")
        assertThat(body(engine, "fun refreshUsbAudioDiagnostics()")).startsWith("fun refreshUsbAudioDiagnostics() {\n        // B23-P4")
        assertThat(body(engine, "fun refreshUsbAudioDiagnostics()")).contains("usbRouteFacts.invalidate()")
        // failed-claim, permission and unplug handling are the triggers that always probe: none of them may reuse
        val cache = text(main, "data/service/player/UsbRouteFactsCache.kt")
        assertThat(cache).contains("const val SOURCE_CHANGED = \"Source changed\"")
        assertThat(cache).contains("const val SOURCE_FORMAT_CHANGED = \"Source format changed\"")
        assertThat(cache).contains("fun triggerMayReuse(trigger: String): Boolean = trigger == SOURCE_CHANGED || trigger == SOURCE_FORMAT_CHANGED")
        assertThat(engine).contains("reevaluateAutomaticRoute(\"Direct claim failed\")")
        assertThat(engine).contains("reevaluateAutomaticRoute(\"USB availability changed\")")
    }

    @Test
    fun `what the batch must not touch is where it was`() {
        // the bit-perfect path: the packet plan, the slot copy and the ring depth
        val output = text(jni, "usb-audio-output.cpp")
        assertThat(output).contains("UsbPcmUrbPlan plan = planUsbPcmUrb(")
        assertThat(output).contains("memcpy(slot->buffer, data + offset, plan.totalBytes);")
        // the safety / Purity / queue-policy / lock-screen sources of this batch's "do not touch" list do not mention it
        for (path in listOf(
            "presentation/lockscreen/WakeCycle.kt",
            "presentation/lockscreen/DapLockScreenPolicy.kt",
            "data/service/SessionQueuePolicy.kt",
        )) {
            val source = File(main, path)
            if (source.isFile) assertThat(source.readText()).doesNotContain("B23")
        }
    }
}
