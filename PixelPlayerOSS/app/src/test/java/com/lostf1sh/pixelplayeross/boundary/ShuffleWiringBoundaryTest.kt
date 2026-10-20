package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B17-H1: shuffle is Media3's shuffle mode, and everything that used to assume "the timeline is the play order" says so.
 *
 * Source assertions, like the rest of this suite: the engine and the service need a device (no Robolectric in this project), so
 * these pin *where each decision is made*. The pure parts (the order arithmetic, the window plan, the saved order) are executed in
 * `PlayOrderTest`, `AuxiliaryQueuePlanTest`, `ShuffleOrderPersistenceTest` and `PlaybackSnapshotStoreTest`, and the toggle's
 * "publishes nothing" in `ShuffleToggleTest`; the behaviour on the HONOR is the reviewer's check in `B17-resultado.md`.
 */
class ShuffleWiringBoundaryTest {

    private val main = "src/main/java/com/lostf1sh/pixelplayeross"

    private fun source(relative: String): String {
        val direct = File(relative)
        val file = if (direct.isFile) direct else File("app/$relative")
        check(file.isFile) { "Could not locate $relative from ${File(".").absolutePath}" }
        return file.readText()
    }

    private val vm get() = source("$main/presentation/viewmodel/PlayerViewModel.kt")
    private val holder get() = source("$main/presentation/viewmodel/PlaybackStateHolder.kt")
    private val engine get() = source("$main/data/service/player/DualPlayerEngine.kt")
    private val service get() = source("$main/data/service/MusicService.kt")

    private fun block(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        check(start >= 0) { "missing: $from" }
        val end = text.indexOf(to, start + from.length)
        check(end > start) { "missing end: $to" }
        return text.substring(start, end)
    }

    // --- the toggle -----------------------------------------------------------------------------------

    @Test
    fun `the app's toggle is the engine's flag and nothing else`() {
        val toggle = block(vm, "    fun toggleShuffle()", "    /** Shuffle to a given state")
        assertThat(toggle).contains("playbackStateHolder.toggleShuffle()")
        assertThat(toggle).doesNotContain("setMediaItems")
        assertThat(toggle).doesNotContain("replaceMediaItems")

        val setShuffle = block(holder, "    fun setShuffleEnabled(", "    fun toggleShuffle()")
        assertThat(setShuffle).contains("dualPlayerEngine.setShuffleEnabled(enabled)")
    }

    @Test
    fun `the old physical shuffle is gone from the app`() {
        listOf("reorderQueueInPlace", "replacePlayerQueuePreservingCurrent", "buildQueueReplacement", "buildQueueSegments", "shuffleToggleJob")
            .forEach { assertThat(holder).doesNotContain(it) }
        assertThat(vm).doesNotContain("syncShuffleStateWithSession")
        assertThat(vm).doesNotContain("playerCtrl.shuffleModeEnabled = false")
        assertThat(vm).doesNotContain("updateQueueCallback")
    }

    @Test
    fun `the player's report is how the app learns shuffle changed`() {
        val listener = block(vm, "override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean)", "override fun onRepeatModeChanged")
        assertThat(listener).contains("isShuffleEnabled = shuffleModeEnabled")
        assertThat(listener).contains("updateCurrentPlaybackQueueFromPlayer(playerCtrl)")
        assertThat(listener).doesNotContain("toggleShuffle()")
    }

    @Test
    fun `the service no longer asks the app to rebuild anything when shuffle changes`() {
        val update = block(service, "    private fun updateManualShuffleState(", "    private fun setCurrentSongFavoriteState(")
        assertThat(update).contains("engine.setShuffleEnabled(enabled)")
        assertThat(update).doesNotContain("broadcastCustomCommand")
        assertThat(update).doesNotContain("session.player.shuffleModeEnabled")
    }

    @Test
    fun `the player's flag is the truth, the service's copy and the stored preference follow it`() {
        val listener = block(service, "override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean)", "override fun onRepeatModeChanged")
        assertThat(listener).contains("isManualShuffleEnabled = shuffleModeEnabled")
        assertThat(listener).contains("userPreferencesRepository.setShuffleOn(shuffleModeEnabled)")
        // A restore sets the flag from what was saved; it must not write that back as the user's preference.
        assertThat(listener).contains("!isRestoringPlaybackSnapshot")
    }

    // --- the engine -----------------------------------------------------------------------------------

    @Test
    fun `the engine flips the flag and, for a master that holds the whole queue, does nothing else`() {
        val setShuffle = block(engine, "    fun setShuffleEnabled(", "    fun setPlayOrder(")
        assertThat(setShuffle).contains("player.shuffleModeEnabled = enabled")
        // The single exception, a window of the queue, is widened first and only then flipped. B18-R4: that decision, and the
        // one pending intent while a fade runs, live in ShuffleRequestGate; the engine is the player behind it.
        assertThat(setShuffle).contains("shuffleGate.request(enabled")
        assertThat(setShuffle).contains("override val windowed: Boolean get() = activePlayerUsesWindowedQueue")
        assertThat(setShuffle).contains("unwindowQueue()")
        assertThat(setShuffle).doesNotContain("setMediaItems")
    }

    @Test
    fun `a crossfade picks the next song from the shuffle order, not from the next index`() {
        val target = block(engine, "    fun getNextTransitionTarget(", "    fun onUsbAudioDeviceReady")
        assertThat(target).contains("playerA.shuffleModeEnabled")
        assertThat(target).contains("getNextWindowIndex(")
        assertThat(target).contains("!activePlayerUsesWindowedQueue")
    }

    @Test
    fun `the auxiliary player is loaded from the plan and given the master's order`() {
        val prepare = block(engine, "    private suspend fun prepareNext(mediaItem: MediaItem, preferredAbsoluteIndex: Int", "    fun cancelNext()")
        assertThat(prepare).contains("AuxiliaryQueuePlan.build(")
        assertThat(prepare).contains("PlayOrder.of(playerA.currentTimeline, shuffleEnabled = true)")
        // After setMediaItems, which hands the player a fresh random order.
        assertThat(prepare.indexOf("setMediaItems(windowItems")).isLessThan(prepare.indexOf("setShuffleOrder("))
    }

    @Test
    fun `a window cut from the play order becomes the snapshot and what it was cut from is kept`() {
        val swap = block(engine, "        val windowedOrder = preparedWindowedPlayOrder", "        resetPreparedWindowState()\n\n        playerA.pauseAtEndOfMediaItems")
        assertThat(swap).contains("windowedBaseQueue = windowedBase")
        assertThat(swap).contains("queueSnapshot = List(windowedOrder.size)")
    }

    @Test
    fun `a rebuild of the master brings the shuffle order back`() {
        val reassemble = block(engine, "    private fun reassembleMasterPlayer(", "    private fun recordDirectCapability")
        assertThat(reassemble).contains("captureQueue()")
        assertThat(reassemble).contains("setShuffleOrder(")
        assertThat(reassemble.indexOf("setMediaItems(mediaItems")).isLessThan(reassemble.indexOf("setShuffleOrder("))
    }

    @Test
    fun `the crossfade reschedules when shuffle changes`() {
        val controller = source("$main/data/service/player/TransitionController.kt")
        val listener = block(controller, "override fun onShuffleModeEnabledChanged(", "        currentObservedPlayer = engine.masterPlayer")
        assertThat(listener).contains("engine.cancelNext()")
        assertThat(listener).contains("scheduleTransitionFor(it)")
    }

    // --- what controllers see -------------------------------------------------------------------------

    @Test
    fun `the session hands controllers a timeline that carries the shuffle order`() {
        val player = source("$main/data/service/player/MappingPlayer.kt")
        val timeline = block(player, "internal class MappedTimeline(", "\n}\n")
        listOf("getFirstWindowIndex", "getLastWindowIndex", "getNextWindowIndex", "getPreviousWindowIndex")
            .forEach { assertThat(timeline).contains("override fun $it(") }
    }

    @Test
    fun `the queue screen lists the songs in play order and finds its way back to the player's index`() {
        val update = block(vm, "    private fun updateCurrentPlaybackQueueFromPlayer(", "    private fun applyPreferredRepeatMode")
        assertThat(update).contains("PlayOrder.of(timeline, true)")
        assertThat(update).contains("currentMediaController.shuffleModeEnabled")

        val reuse = block(vm, "    private fun MediaController.resolveReusablePlaybackTargetIndex(", "    private fun playLoadedControllerItem")
        assertThat(reuse).contains("playbackStateHolder.playOrderOf(this)")

        val reorder = block(vm, "    fun reorderQueueItem(", "    fun togglePlayerSheetState")
        assertThat(reorder).contains("PlayOrder.move(")
        assertThat(reorder).contains("dualPlayerEngine.setPlayOrder(")
    }

    @Test
    fun `a song that only moved rows under shuffle is not taken for a new occurrence of it`() {
        val sync = block(vm, "    private fun syncDisplayedMediaItemIfChanged(", "        playbackStateHolder.onPlaybackOccurrenceTransition(mediaItem.mediaId)")
        // Same song, same player index, shuffle on: only the row is updated (no lyrics reset, no occurrence reset).
        assertThat(sync).contains("player.shuffleModeEnabled && currentSongId == mediaItem.mediaId")
        assertThat(sync).contains("lastSeenPlayerIndex == playerIndex")
        // Without shuffle the rule is what it always was: a different row for the same song is a repeat of that track.
        assertThat(sync.indexOf("player.shuffleModeEnabled")).isGreaterThan(sync.indexOf("currentIndex == expectedIndex"))
    }

    @Test
    fun `play next and add to queue put the song where the queue lists it`() {
        val add = block(vm, "    fun addSongToQueue(", "    private fun buildPlaybackMediaItem(")
        assertThat(add).contains("PlayOrder.placeAfter(")
        assertThat(add).contains("PlayOrder.placeLast(")
    }

    @Test
    fun `the widget's up next follows the shuffle order`() {
        assertThat(service).contains("PlayOrder.upcoming(PlayOrder.of(snapshotTimeline, shuffleEnabled = true), snapshotWindowIndex, 4)")
    }

    // --- the saved queue ------------------------------------------------------------------------------

    @Test
    fun `the snapshot saves the order next to the queue and the restore applies it before playback`() {
        val capture = block(service, "    private fun capturePlaybackSnapshotFromPlayer(", "    /** The play order read off the queue, once per revision")
        assertThat(capture).contains("shuffleOrder = savedOrder")
        assertThat(capture).contains("shuffleEnabled = shuffleOn")

        val restore = block(service, "                player.shuffleModeEnabled = snapshot.shuffleEnabled", "                isManualShuffleEnabled = snapshot.shuffleEnabled")
        assertThat(restore).contains("ShuffleOrderPersistence.orderToRestore(")
        assertThat(restore).contains("engine.setPlayOrder(restoredOrder)")
    }

    @Test
    fun `the restore no longer forces the player's shuffle off`() {
        assertThat(service).doesNotContain("player.shuffleModeEnabled = false")
    }
}
