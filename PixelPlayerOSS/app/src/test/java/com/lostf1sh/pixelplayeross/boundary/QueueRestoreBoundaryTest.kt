package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * F-09: the restore reads the platform (the content resolver, storage state) and replaces the player's
 * queue, so the wiring is asserted at source level, as the rest of this area is; the rules are in
 * `RestoreQueuePolicyTest`.
 */
class QueueRestoreBoundaryTest {

    private val java = "java/com/lostf1sh/pixelplayeross"
    private fun source(relative: String): String =
        File("src/main", "$java/$relative").readText().replace("\r\n", "\n")

    private val service = source("data/service/MusicService.kt")
    private val restore = service.substringAfter("private suspend fun restorePlaybackQueueSnapshotIfNeeded()")
        .substringBefore("private fun buildMediaItemFromSnapshot")

    @Test
    fun `restore probes the files off the main thread and brings back only what can be played`() {
        assertThat(restore).contains("withContext(Dispatchers.IO) {")
        assertThat(restore).contains("RestoreQueuePolicy.select(restoredItems.size, resolvedIndex)")
        assertThat(restore).contains("QueueFileProbe.presence(applicationContext")
        // What is handed to the player is the selection, not the saved list.
        assertThat(restore).contains("player.setMediaItems(\n                    playableItems,")
        assertThat(restore).doesNotContain("player.setMediaItems(\n                    restoredItems,")
    }

    @Test
    fun `a replaced current song starts from zero, and an unplayable queue clears the snapshot`() {
        assertThat(restore).contains("if (selection.startWasReplaced) 0L else snapshot.currentPositionMs.coerceAtLeast(0L)")
        assertThat(restore).contains("engine.declareItemStartTarget(restoreStartPositionMs)")
        val nothing = restore.substringAfter("if (startIndexInQueue == null) {").substringBefore("}\n        val playableItems")
        assertThat(nothing).contains("userPreferencesRepository.setPlaybackQueueSnapshot(null)")
        assertThat(nothing).contains("return")
    }

    @Test
    fun `the probe reports missing only for a certain not found`() {
        val probe = source("data/service/QueueFileProbe.kt")
        assertThat(probe).contains("openAssetFileDescriptor(uri, \"r\")")
        val content = probe.substringAfter("private fun contentState(").substringBefore("/**\n     * A MediaStore URI")
        // B12a-8: a not-found is MISSING only through the volume policy (an SD card that is out is not a deleted file)
        assertThat(content).contains("catch (_: FileNotFoundException) {")
        assertThat(content).contains("QueueVolumePolicy.notFoundMeans(")
        assertThat(content).doesNotContain("FileNotFoundException) {\n            RestoreQueuePolicy.Presence.MISSING")
        assertThat(probe).contains("MediaStore.MediaColumns.VOLUME_NAME")
        assertThat(content).contains("catch (_: SecurityException) {\n            RestoreQueuePolicy.Presence.UNKNOWN")
        // A volume that is not mounted is not a deleted file.
        assertThat(content).contains("if (!volumeAvailable(context, uri)) return RestoreQueuePolicy.Presence.UNKNOWN")
        assertThat(probe).contains("Environment.MEDIA_MOUNTED")
    }

    @Test
    fun `an external VIEW replaces the queue and plays, whatever state the player is in`() {
        val vm = source("presentation/viewmodel/PlayerViewModel.kt")
        val action = vm.substringAfter("private suspend fun internalPlaySongs(").substringBefore("if (mediaController == null) {")
        // B18-R2: the load goes through putFirstSongsInPlayer (the start song with its neighbours); for the one song a VIEW loads it is
        // the same `setMediaItem(startMediaItem, 0L)` as before, and what the caller does to the loaded player (prepare, play) follows it.
        // B19-S1: it also hands back what the player goes through, and takes the engine's one-call order for the block.
        val set = action.indexOf("putFirstSongsInPlayer(\n                enginePlayer, plan, startMediaItem, firstItems, loadHold, loadToken,")
        val first = source("presentation/viewmodel/QueueLoadPlanning.kt").substringAfter("internal fun putFirstSongsInPlayer(")
        assertThat(first).contains("player.setMediaItem(startMediaItem, 0L)")
        assertThat(first.indexOf("afterLoad()")).isGreaterThan(first.indexOf("player.setMediaItem(startMediaItem, 0L)"))
        val prepare = action.indexOf("enginePlayer.prepare()")
        val play = action.indexOf("enginePlayer.play()")
        // `prepare()` after the replacement is what takes a player out of a failed state.
        assertThat(set).isAtLeast(0)
        assertThat(prepare).isGreaterThan(set)
        assertThat(play).isGreaterThan(prepare)
        // FIX-1b: the replacement is loaded and prepared at once, and `play()` follows through
        // `ExternalPlayStart` once the app is in the foreground (Android 15 refuses focus before that).
        val external = vm.substringAfter("private suspend fun playExternalUriNow(").substringBefore("fun showPlayer()")
        assertThat(Regex("""internalPlaySongs\(\s*queueSongs, chosen, queueLabel, null,\s*source = ListenSource\(ListenSourceKind.EXTERNAL_VIEW\),\s*autoPlay = false,\s*onLoaded = """).containsMatchIn(external)).isTrue()
        assertThat(external).contains("startExternalPlayback(chosen, requestedAt, loaded, timeline)")
        assertThat(action).contains("if (autoPlay) enginePlayer.play()")
    }

    @Test
    fun `the external overlay does not dismiss itself while a VIEW is still being turned into playback`() {
        val vm = source("presentation/viewmodel/PlayerViewModel.kt")
        val entry = vm.substringAfter("fun playExternalUri(uri: Uri) {").substringBefore("private suspend fun playExternalUriNow")
        assertThat(entry).contains("_externalLoadInFlight.value = true")
        assertThat(entry).contains("finally {\n                _externalLoadInFlight.value = false")
        val overlay = source("presentation/components/external/ExternalPlayerOverlay.kt")
        assertThat(overlay).contains("LaunchedEffect(currentSong, externalLoadInFlight) {")
        assertThat(overlay).contains("} else if (!awaitingSong && !externalLoadInFlight) {")
    }

    @Test
    fun `the queue behind an external VIEW is built from the index, in the background, after the song starts`() {
        val holder = source("presentation/viewmodel/ExternalMediaStateHolder.kt")
        val continuation = holder.substringAfter("suspend fun buildFolderContinuation(")
            .substringBefore("suspend fun buildExternalSongFromUri(")
        // One query for the folder; no per-sibling tag read, artwork write, content-type lookup or open.
        assertThat(continuation).contains("context.contentResolver.query(")
        assertThat(continuation).doesNotContain("buildExternalSongFromUri(")
        assertThat(continuation).doesNotContain("AudioMetadataReader")
        assertThat(continuation).doesNotContain("openAssetFileDescriptor")
        assertThat(continuation).doesNotContain("QueueFileProbe")
        assertThat(holder).doesNotContain("fun buildExternalQueue(")

        val vm = source("presentation/viewmodel/PlayerViewModel.kt")
        val now = vm.substringAfter("private suspend fun playExternalUriNow(").substringBefore("fun showPlayer()")
        // The song that was opened is the whole queue at the start.
        assertThat(now).contains("val queueSongs = listOf(externalResult.song)")
        assertThat(Regex("""internalPlaySongs\(\s*queueSongs, chosen, queueLabel, null,\s*source = ListenSource\(ListenSourceKind.EXTERNAL_VIEW\),\s*autoPlay = false,\s*onLoaded = """).containsMatchIn(now)).isTrue()
        assertThat(now).contains("pendingQueueSegmentsJob = ExternalPlayback(")
        assertThat(now).contains("externalMediaStateHolder.buildFolderContinuation(")
        // Library songs are reused where the library has them; the probe runs off the main thread.
        assertThat(now).contains("libraryStateHolder.allSongsById.value[id]")
        assertThat(now).contains("withContext(Dispatchers.IO) {\n                        QueueFileProbe.presence(")
        // The tail is dropped if the queue was replaced meanwhile.
        assertThat(now).contains("player.currentMediaItem?.mediaId != chosen.id || player.mediaItemCount != 1")
    }

    @Test
    fun `restore is capped, and says what it cost`() {
        val policy = source("data/service/RestoreQueuePolicy.kt")
        assertThat(policy).contains("const val MAX_PROBES = 300")
        assertThat(restore).contains("Restore probed %d of %d queue items in %d ms")
    }
}
