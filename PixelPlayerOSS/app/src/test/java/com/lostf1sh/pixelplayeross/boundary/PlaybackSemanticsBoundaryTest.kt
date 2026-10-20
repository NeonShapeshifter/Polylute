package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/** B6 wiring: AUD-016, 017, 019, 050, 066/067, 103. Rules are tested in their own JVM tests. */
class PlaybackSemanticsBoundaryTest {
    private val java = "src/main/java/com/lostf1sh/pixelplayeross"
    private fun read(path: String) = File(path).readText().replace("\r\n", "\n")
    private val sink = read("../../decent-player/libs/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt")
    private val service = read("$java/data/service/MusicService.kt")

    /**
     * AUD-016 as reworked for AUD-128 (written after the code): no seek at the natural end; the clock
     * runs on, the next item's configure waits for Media3's own transition, and so does the cleanup.
     */
    @Test
    fun `the native end of file is resolved by Media3's own transition, without a seek`() {
        assertThat(sink).contains("naturalEnd.onEngineFinished(engine.getPositionUs(), windowOffsetUs, now)")
        assertThat(sink).contains("directResult = windowOffsetUs + naturalEnd.positionUs(SystemClock.elapsedRealtime())")
        assertThat(sink).contains("if (nativeEngine?.isRunning == true || naturalEnd.pending) {")
        assertThat(sink).contains("if (nativeEngine?.isRunning == false && !naturalEnd.pending) {")
        val cleanup = sink.substringAfter("private fun cleanUpFinishedEngineForItem() {").substringBefore("\n    }\n")
        // B7-fix (AUD-129): resolution moved to createPendingEngine, which applies the exact offset.
        assertThat(cleanup).contains("val natural = naturalEnd.pending")
        assertThat(cleanup).contains("if (natural) return")
        val create = sink.substringAfter("private fun createPendingEngine(reason: Int) {").substringBefore("\n    }\n")
        // B7-fix2 (AUD-136): Media3's offset for the new period, adopted via adoptItemOffset.
        assertThat(create).contains("val next = naturalEnd.onItemTransition(offsetLedger.playingOffsetUs())")
        assertThat(create).contains("adoptItemOffset(next.offsetUs, reportedBeforeUs, \"NATURAL_END_MEDIA3\")")
        assertThat(sink).doesNotContain("p.seekTo(plan.itemIndex, plan.endPositionMs)")
    }

    /** AUD-017: one end-of-track timer, the service's; the UI arms it and only reports. */
    @Test
    fun `the UI arms the service end-of-track timer and no longer pauses by itself`() {
        val holder = read("$java/presentation/viewmodel/SleepTimerStateHolder.kt")
        assertThat(holder).contains("MusicNotificationProvider.CUSTOM_COMMAND_SET_SLEEP_TIMER_END_OF_TRACK")
        val viewModel = read("$java/presentation/viewmodel/PlayerViewModel.kt")
        val eot = viewModel.substringAfter("val activeEotSongId = EotStateHolder.eotTargetSongId.value")
            .substringBefore("cancelSleepTimer(suppressDefaultToast = true)")
        assertThat(eot).doesNotContain("playerCtrl.pause()")
        assertThat(eot).doesNotContain("playerCtrl.seekTo(0L)")
    }

    /** AUD-050: elapsed-time alarms, immune to wall-clock and time-zone changes. */
    @Test
    fun `sleep timers use ELAPSED_REALTIME_WAKEUP`() {
        for (path in listOf("$java/data/service/PlaybackTimerController.kt", "$java/presentation/viewmodel/SleepTimerStateHolder.kt")) {
            val source = read(path)
            assertThat(source).doesNotContain("RTC_WAKEUP")
            assertThat(source).contains("ELAPSED_REALTIME_WAKEUP")
        }
        assertThat(read("$java/data/service/PlaybackTimerController.kt")).contains("SystemClock.elapsedRealtime()")
    }

    /** AUD-066/067: the app and the widget share one decision. */
    @Test
    fun `play pause in the app and the widget go through PlayPauseDecision`() {
        assertThat(read("$java/presentation/viewmodel/PlaybackStateHolder.kt")).contains("PlayPauseDecision.apply(controller)")
        val widget = service.substringAfter("PlayerActions.PLAY_PAUSE -> {").substringBefore("PlayerActions.NEXT -> {")
        assertThat(widget).contains("PlayPauseDecision.apply(player)")
        assertThat(widget).doesNotContain("player.playWhenReady = !player.playWhenReady")
    }

    /** AUD-103: the widget carries the media id itself. */
    @Test
    fun `the widget queue tap carries the full media id`() {
        assertThat(service).contains("WidgetQueueSelection.indexOf(")
        assertThat(read("$java/ui/glancewidget/PlayerControlActionCallback.kt")).contains("mediaIdKey")
    }

    /**
     * AUD-019 — verified in B6, not reimplemented: AUD-117 already routes every session seek
     * (notification, lock screen, Bluetooth/AVRCP, widget) through MappingPlayer to
     * notifyExternalSeekInitiated (behaviour tested in SeekAuthorityTest). This pins that the session
     * is built on that wrapper and that every player swap re-wraps. Green from the start.
     */
    @Test
    fun `every player the session exposes is wrapped with the seek authority`() {
        assertThat(service).contains("MediaSession.Builder(this, wrapFadingPlayer(engine.masterPlayer))")
        assertThat(service).contains("val wrappedPlayer = wrapFadingPlayer(player)")
        assertThat(service).contains("onPositionSeek = engine::notifyExternalSeekInitiated")
    }
}
