package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B18: where each decision of the batch is made. Source assertions, like the rest of this suite: the engine, the service and the
 * view model need a device (no Robolectric here), so these pin the wiring around the pure parts that the other tests execute
 * (`ShuffleRequestGateTest`, `ShuffleRedrawTest`, `QueueLoadStagesTest`, `QueueUndoStateHolderTest`, `QueueOccurrenceTest`,
 * `RandomModeTest`). The behaviour on the HONOR is the reviewer's checks in `B18-resultado.md`.
 */
class QueueAndShuffleModeWiringBoundaryTest {

    private val main = "src/main/java/com/lostf1sh/pixelplayeross"
    private val res = "src/main/res"

    private fun source(relative: String): String {
        val direct = File(relative)
        val file = if (direct.isFile) direct else File("app/$relative")
        check(file.isFile) { "Could not locate $relative from ${File(".").absolutePath}" }
        return file.readText()
    }

    private fun block(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        check(start >= 0) { "missing: $from" }
        val end = text.indexOf(to, start + from.length)
        check(end > start) { "missing end: $to" }
        return text.substring(start, end)
    }

    private val vm get() = source("$main/presentation/viewmodel/PlayerViewModel.kt")
    private val holder get() = source("$main/presentation/viewmodel/PlaybackStateHolder.kt")
    private val engine get() = source("$main/data/service/player/DualPlayerEngine.kt")
    private val service get() = source("$main/data/service/MusicService.kt")
    private val mapping get() = source("$main/data/service/player/MappingPlayer.kt")

    // --- R1: classic / fast ---------------------------------------------------------------------------

    @Test
    fun `classic mode deals the queue again with one order publication and replaces nothing`() {
        val deal = block(engine, "    private fun dealQueueAgain()", "    /**\n     * Shuffle requests that cannot be applied yet")
        assertThat(deal).contains("ShuffleRedraw.redraw(")
        assertThat(deal).contains("setPlayOrder(order)")
        listOf("setMediaItems", "replaceMediaItems", "replaceMediaItem(", "addMediaItems", "moveMediaItem", "removeMediaItem", "clearMediaItems")
            .forEach { assertThat(deal).doesNotContain(it) }

        // One publication: setPlayOrder is the only place that writes an order, and it writes it once.
        val setPlayOrder = block(engine, "    fun setPlayOrder(order: IntArray)", "    /** The order the master plays in over its own timeline")
        assertThat(setPlayOrder.split("setShuffleOrder(").size - 1).isEqualTo(1)
    }

    @Test
    fun `the new order is built off the main thread and published on it`() {
        val redraw = source("$main/data/service/player/ShuffleRedraw.kt")
        assertThat(redraw).contains("withContext(Dispatchers.Default) { PlayOrder.randomPermutation(count, source) }")
        // The engine's job runs on the engine scope (Main), so the publication is on the thread the player belongs to.
        assertThat(engine).contains("redrawJob = scope.launch {")
    }

    @Test
    fun `only a switch ON by a listener in classic mode deals again, a load and fast mode do not`() {
        val setFlag = block(engine, "        override fun setFlag(enabled: Boolean, freshOrder: Boolean)", "        override fun watchTransitionEnd()")
        assertThat(setFlag).contains("if (enabled && freshOrder && randomMode == RandomMode.CLASSIC)")
        assertThat(setFlag).contains("dealQueueAgain()")
        // Fast keeps B17's wording, which the reviewer greps for: a flag and no timeline change.
        assertThat(setFlag).contains("Shuffle %s: flag only, %d items, no timeline change")

        assertThat(engine).contains("shuffleGate.request(enabled, freshOrder = true)")
        assertThat(engine).contains("shuffleGate.request(enabled, freshOrder = false)")
        assertThat(holder).contains("dualPlayerEngine.setShuffleForQueueLoad(enabled)")
        // The queue load is the only caller that says "this load brings its own order".
        assertThat(vm).contains("playbackStateHolder.setShuffleForQueueLoad(enabled)")
    }

    @Test
    fun `classic is the engine's starting mode and the service keeps the stored choice in step`() {
        assertThat(engine).contains("var randomMode: RandomMode = RandomMode.DEFAULT")
        val collector = block(service, "userPreferencesRepository.randomModeFlow.collect", "replayGainEnabledFlow")
        assertThat(collector).contains("engine.randomMode = mode")
    }

    @Test
    fun `persistent random deals a new order on every load whatever the random mode is`() {
        // The load never reads the random mode: a new list with persistent random is always a fresh draw (B17), in both modes.
        assertThat(vm).doesNotContain("RandomMode")
        val playSongs = block(vm, "    fun playSongs(\n        songsToPlay: List<Song>,", "    fun playSongsShuffled(")
        assertThat(playSongs).contains("QueueUtils.buildAnchoredShuffleQueueSuspending(")
        assertThat(playSongs).contains("playSequence = playSequence")
    }

    @Test
    fun `the setting is in the Audio page's Queue section with one row, in three languages`() {
        // B22: random mode (and the persistent mix) moved from Behavior to Audio, Queue. Same preference, same action.
        val screen = source("$main/presentation/settings/AudioSettings.kt")
        val queueSection = block(screen, "private fun QueueSection(", "audio_persistent_mix_title")
        assertThat(queueSection).contains("RandomMode.entries")
        assertThat(queueSection).contains("actions::onRandomModeChange")
        assertThat(queueSection).contains("setcat_random_mode_title")
        // ...and it is no longer on Behavior.
        assertThat(source("$main/presentation/settings/BehaviorSettings.kt")).doesNotContain("RandomMode")
        // ...and the action is the one writer of the random mode.
        assertThat(source("$main/presentation/settings/SettingsActionsWiring.kt")).contains("vm.setRandomMode(value)")

        for (locale in listOf("values", "values-es", "values-sv")) {
            val strings = source("$res/$locale/strings_settings.xml")
            for (name in listOf("setcat_random_mode_title", "random_mode_classic", "random_mode_fast")) {
                val match = Regex("""<string name="$name">([^<]+)</string>""").find(strings)
                assertThat(match).isNotNull()
                assertThat(match!!.groupValues[1].trim()).isNotEmpty()
            }
            // The design's Queue rows carry no per-mode explanation: those strings are gone.
            assertThat(strings).doesNotContain("random_mode_classic_hint")
            assertThat(strings).doesNotContain("random_mode_fast_hint")
        }
    }

    // --- R2: the first load brings its neighbours, a skip is never lost --------------------------------

    @Test
    fun `a queue load starts with the start song and its neighbours, and the rest is attached off the main thread`() {
        val load = block(vm, "    private suspend fun internalPlaySongs(", "    private suspend fun buildResolvedPlaybackMediaItem")
        assertThat(load).contains("QueueLoadPlan.of(")
        assertThat(load).contains("putFirstSongsInPlayer(")
        assertThat(load).contains("loadRestOfQueue(")
        // The items of the rest are built on Dispatchers.Default, and the attach is the only thing on the main thread.
        assertThat(load).contains("withContext(Dispatchers.Default)")
        assertThat(load).contains("mainContext = Dispatchers.Main.immediate")
        // The old one-song start followed by a 4,800-item build is gone from this path.
        assertThat(load).doesNotContain("preparePlaybackQueueSegments(")
        assertThat(load).doesNotContain("enginePlayer.setMediaItem(startMediaItem, 0L)\n            // After the load")
    }

    @Test
    fun `skips are held from the first line of the load, and every exit ends the hold`() {
        val load = block(vm, "    private suspend fun internalPlaySongs(", "    private suspend fun buildResolvedPlaybackMediaItem")
        val begin = load.indexOf("loadHold.begin()")
        assertThat(begin).isAtLeast(0)
        // Before anything that can take time or fail.
        assertThat(begin).isLessThan(load.indexOf("buildResolvedPlaybackMediaItem(effectiveStartSong)"))
        assertThat(load).contains("loadHold.end(loadToken)\n            throw failure")
        assertThat(load).contains("} else {\n                loadHold.end(loadToken)")
    }

    @Test
    fun `every controller's skip passes through the session player, which asks the hold`() {
        listOf("override fun seekToNext()", "override fun seekToNextMediaItem()", "override fun seekToPreviousMediaItem()", "override fun seekToPrevious()")
            .forEach { assertThat(mapping).contains(it) }
        // B19-S3: the player's neighbour in that direction (repeat mode ignored) and its repeat mode, not a "can move" that wraps.
        assertThat(mapping).contains("hold.hold(direction, innerPlayer.hasQueueNeighbour(direction), innerPlayer.repeatMode == Player.REPEAT_MODE_ALL)")
        assertThat(mapping).contains("queueLoadHold?.dropHeld()")
        assertThat(service).contains("queueLoadHold = engine.queueLoadHold")
        // A previous in the app's own UI is routed through it too while a load is in flight.
        assertThat(holder).contains("dualPlayerEngine.queueLoadHold.previousMayStillHaveSomewhereToGo")
    }

    @Test
    fun `the external VIEW keeps its own attach`() {
        assertThat(vm).contains("attachPreparedQueueSegmentsIfCurrent(player, chosen.id, prepared)")
        assertThat(source("$main/presentation/viewmodel/QueueLoadPlanning.kt")).contains("internal suspend fun attachQueueSegments(")
    }

    // --- R3: what a song change no longer does on the main thread ---------------------------------------------

    @Test
    fun `the playback snapshot of the whole queue is rebuilt when the queue changes, not for every source update`() {
        // ExoPlayer replaces its Timeline object for each source update (the duration of the song just prepared: one or two per
        // song change). The snapshot memo used to key on that object and so walked ~4,870 items on the main thread after every song.
        val capture = block(service, "    private fun capturePlaybackSnapshotNow(", "    /** The play order read off the queue")
        assertThat(capture).contains("snapshotItemsMemo.get(engine.queueRevision, player,")
        assertThat(capture).contains("shuffleOrderMemo.get(engine.queueRevision, player)")
        assertThat(capture).doesNotContain("snapshotItemsMemo.get(player.currentTimeline")
        assertThat(capture).doesNotContain("shuffleOrderMemo.get(player.currentTimeline")

        // The token is replaced on a playlist change and nowhere else.
        val listener = block(engine, "        override fun onTimelineChanged(timeline: Timeline, reason: Int) {", "        override fun onShuffleModeEnabledChanged(")
        assertThat(listener).contains("if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {\n                playlistGeneration++\n                queueRevision = Any()")
        assertThat(engine.split("queueRevision = Any()").size - 1).isEqualTo(1) // assigned in that one place (the declaration initialises with `: Any = Any()`)
    }

    @Test
    fun `a source update does not make the app read the whole queue again unless its size changed`() {
        val listener = block(vm, "            override fun onTimelineChanged(timeline: Timeline, reason: Int) = timedSection(\"PlayerViewModel.onTimelineChanged\") {", "        playerCtrl.addListener(checkNotNull(mediaControllerPlaybackListener))")
        assertThat(listener).contains("reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE && timeline.windowCount != lastQueueSignature?.count")
        assertThat(listener).contains("reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
    }

    @Test
    fun `every handler that runs on the main thread when a song changes names itself in the trace and the log`() {
        listOf(
            "PlayerViewModel.onMediaItemTransition", "PlayerViewModel.onTimelineChanged", "PlayerViewModel.syncDisplayedMediaItem",
        ).forEach { assertThat(vm).contains("timedSection(\"$it\")") }
        listOf(
            "MusicService.onMediaItemTransition", "MusicService.onTimelineChanged", "MusicService.capturePlaybackSnapshot",
            "MusicService.grantArtworkUriPermissions", "MusicService.listeningStats",
        ).forEach { assertThat(service).contains("timedSection(\"$it\")") }
        assertThat(engine).contains("timedSection(\"DualPlayerEngine.reevaluateAutomaticRoute\")")
        // And the service says which publications a song change causes, with their size.
        assertThat(service).contains("Timeline changed reason=%d items=%d (%d ms after the last transition)")
    }

    // --- R4: one pending shuffle intent ------------------------------------------------------------------

    @Test
    fun `the engine routes every shuffle request and every outside flip through the gate`() {
        assertThat(engine).contains("shuffleGate.onFlagChanged()")
        val cancel = block(engine, "    fun cancelNext() {", "    private fun gainWithoutReplayGain()")
        assertThat(cancel).contains("shuffleGate.settle()")
        // The finished listeners are notified from a copy, so the gate can take its own listener off from inside one.
        assertThat(engine).contains("onTransitionFinishedListeners.toList().forEach { it() }")
        // No second place compares the flag before looking at what is pending.
        assertThat(engine).doesNotContain("if (player.shuffleModeEnabled == enabled) return")
    }

    // --- R5: the occurrence is the row ----------------------------------------------------------------------

    @Test
    fun `a swipe, the remove button and the more menu all carry the row`() {
        val sheet = source("$main/presentation/components/QueueBottomSheet.kt")
        assertThat(sheet).contains("onRemoveClick = { onRemoveSong(song.id, queueIndex) }")
        assertThat(sheet).contains("onDismissSong = { onRemoveSong(song.id, queueIndex) }")
        assertThat(sheet).doesNotContain("onRemoveSong(song.id)")

        val overlays = source("$main/presentation/components/UnifiedPlayerOverlaysLayer.kt")
        assertThat(overlays).contains("playerViewModel.removeSongFromQueue(id, queueIndex)")
        assertThat(overlays).contains("playerViewModel.noteQueueRowForSongActions(song.id, queueIndex)")
    }

    @Test
    fun `the removal is decided by the row, mapped to the player through the play order, and the undo keeps that row`() {
        val undo = source("$main/presentation/viewmodel/QueueUndoStateHolder.kt")
        assertThat(undo).contains("QueueOccurrence.row(")
        assertThat(undo).contains("QueueOccurrence.timelineIndexOfRow(")
        // The two separate lookups by id are gone.
        assertThat(undo).doesNotContain("currentQueue.find { it.id == songId }")
        assertThat(undo).doesNotContain("currentQueue.indexOfFirst { it.id == songId }")
        assertThat(undo).doesNotContain("controller.getMediaItemAt(i).mediaId == songId")

        val remove = block(vm, "    fun removeSongFromQueue(songId: String, queueRow: Int? = null)", "    fun undoRemoveSongFromQueue()")
        assertThat(remove).contains("playbackStateHolder.playOrderOf(controller)")
        assertThat(remove).contains("position = row")
        // The undo still puts the song back through the order, at the row that was kept.
        val reinsert = block(vm, "    private fun reinsertIntoShuffleOrder(", "    fun hideQueueItemUndoBar()")
        assertThat(reinsert).contains("PlayOrder.placeAt(order, timelineIndex = at, position = position)")
    }

    @Test
    fun `no other queue operation identifies an occurrence by its song id`() {
        // Tap on a row: the row is passed (indexInQueue) and decides, and the player index it maps to is checked against the id.
        // A tap from a library list has no row of the queue and is not an occurrence of anything: that one looks the song up.
        assertThat(vm).contains("val songIndexInQueue = indexInQueue ?: currentQueue.indexOfFirst { it.id == song.id }")
        assertThat(vm).contains("isExplicitQueueTarget = indexInQueue != null")
        assertThat(vm).contains("return playerIndex.takeIf { mediaIdAtTarget == songId }")
        // Move: rows, not ids.
        val reorder = block(vm, "    fun reorderQueueItem(fromIndex: Int, toIndex: Int)", "    fun togglePlayerSheetState(")
        assertThat(reorder).doesNotContain("songId")
        // Play next / add to queue add a new item; they never look an existing one up by id.
        val adds = block(vm, "    fun addSongToQueue(song: Song)", "    private fun buildPlaybackMediaItem(")
        assertThat(adds).doesNotContain("indexOfFirst")
        assertThat(adds).doesNotContain("mediaId ==")
    }
}
