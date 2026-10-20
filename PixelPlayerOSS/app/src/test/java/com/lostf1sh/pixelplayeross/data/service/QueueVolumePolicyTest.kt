package com.lostf1sh.pixelplayeross.data.service

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.RestoreQueuePolicy.Presence
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B12a-8 #2: `content://media/external/...` names every volume, so a not-found on it is a deleted file only if the
 * item's volume is mounted. With the SD card out of the DAP its songs fail to open exactly like deleted ones, and the
 * restore used to drop them from the queue.
 */
class QueueVolumePolicyTest {

    private val mounted = setOf("external_primary")

    private fun notFound(
        uriVolume: String = "external",
        rowVolume: String? = null,
        mountedVolumes: Set<String>? = mounted,
        pathMounted: Boolean? = null,
        removableUnmounted: Boolean? = null,
    ) = QueueVolumePolicy.notFoundMeans(uriVolume, rowVolume, mountedVolumes, pathMounted, removableUnmounted)

    // ── the SD card is out ─────────────────────────────────────────────────────────────────────

    @Test
    fun `an item whose library path is on a storage that is not mounted is kept, whatever the URI says`() {
        assertThat(notFound(pathMounted = false)).isEqualTo(Presence.UNKNOWN)
        assertThat(notFound(uriVolume = "external_primary", pathMounted = false)).isEqualTo(Presence.UNKNOWN)
        assertThat(notFound(rowVolume = "external_primary", pathMounted = false)).isEqualTo(Presence.UNKNOWN)
    }

    @Test
    fun `an item whose row says it is on a card that is not mounted is kept`() {
        assertThat(notFound(rowVolume = "1a2b-3c4d")).isEqualTo(Presence.UNKNOWN)
        assertThat(notFound(uriVolume = "1a2b-3c4d")).isEqualTo(Presence.UNKNOWN)
        assertThat(QueueVolumePolicy.rowVolumeUnavailable("1a2b-3c4d", mounted)).isTrue()
    }

    @Test
    fun `the primary volume's own URI is only missing while the primary is mounted`() {
        assertThat(notFound(uriVolume = "external_primary", mountedVolumes = mounted)).isEqualTo(Presence.MISSING)
        assertThat(notFound(uriVolume = "external_primary", mountedVolumes = emptySet())).isEqualTo(Presence.UNKNOWN)
    }

    @Test
    fun `an external URI with nothing else known is kept while a removable storage is not mounted`() {
        assertThat(notFound(removableUnmounted = true)).isEqualTo(Presence.UNKNOWN)
        assertThat(notFound(removableUnmounted = null)).isEqualTo(Presence.UNKNOWN)
    }

    // ── a file that is really gone ─────────────────────────────────────────────────────────────

    @Test
    fun `a file gone from a mounted volume is missing, so deleted songs are still dropped`() {
        // a row on a mounted volume whose file is gone
        assertThat(notFound(rowVolume = "external_primary")).isEqualTo(Presence.MISSING)
        assertThat(notFound(rowVolume = "1a2b-3c4d", mountedVolumes = mounted + "1a2b-3c4d")).isEqualTo(Presence.MISSING)
        // the library's path is on a mounted storage and the file is not there
        assertThat(notFound(pathMounted = true, removableUnmounted = true)).isEqualTo(Presence.MISSING)
        // nothing says a card is out
        assertThat(notFound(removableUnmounted = false)).isEqualTo(Presence.MISSING)
    }

    @Test
    fun `a volume name is compared in lowercase, as the platform lists them`() {
        assertThat(notFound(rowVolume = "1A2B-3C4D", mountedVolumes = mounted + "1a2b-3c4d")).isEqualTo(Presence.MISSING)
        assertThat(notFound(rowVolume = "1A2B-3C4D")).isEqualTo(Presence.UNKNOWN)
    }

    @Test
    fun `an unreadable volume list changes nothing about a named volume that is not the primary`() {
        // not knowing which volumes are mounted is not evidence that the file is deleted... or that it is not
        assertThat(notFound(rowVolume = "external_primary", mountedVolumes = null)).isEqualTo(Presence.MISSING)
        assertThat(QueueVolumePolicy.rowVolumeUnavailable("1a2b-3c4d", null)).isFalse()
    }

    // ── what the restore does with it ──────────────────────────────────────────────────────────

    @Test
    fun `a current item on an unmounted volume is kept and playback stays parked on it`() {
        val presence = mapOf(0 to notFound(pathMounted = false), 1 to notFound(rowVolume = "external_primary"), 2 to Presence.PRESENT)
        val selection = RestoreQueuePolicy.select(count = 3, savedIndex = 0) { presence.getValue(it) }
        // The card item is not dropped (it plays when the card is back); before, its not-found was MISSING and it was.
        assertThat(selection.keptPositions).containsExactly(0, 1, 2).inOrder()
        assertThat(selection.droppedCount).isEqualTo(0)
        assertThat(selection.startPosition).isEqualTo(0)
        assertThat(selection.startWasReplaced).isFalse()
    }

    @Test
    fun `a deleted current item is still dropped, and the next one on the card that is out is the one playback parks on`() {
        val presence = mapOf(0 to Presence.PRESENT, 1 to notFound(rowVolume = "external_primary"), 2 to notFound(rowVolume = "1a2b-3c4d"))
        val selection = RestoreQueuePolicy.select(count = 3, savedIndex = 1) { presence.getValue(it) }
        assertThat(selection.keptPositions).containsExactly(0, 2).inOrder()
        assertThat(selection.droppedCount).isEqualTo(1)
        assertThat(selection.startPosition).isEqualTo(1)            // position 2 of the queue, index 1 of what is kept
        assertThat(selection.startWasReplaced).isTrue()
    }

    @Test
    fun `a queue that is all on the card that is out comes back whole`() {
        val selection = RestoreQueuePolicy.select(count = 5, savedIndex = 2) { notFound(rowVolume = "1a2b-3c4d") }
        assertThat(selection.keptPositions).containsExactly(0, 1, 2, 3, 4).inOrder()
        assertThat(selection.droppedCount).isEqualTo(0)
        assertThat(selection.startPosition).isEqualTo(2)
    }

    // ── the platform glue ──────────────────────────────────────────────────────────────────────

    private val probe = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/QueueFileProbe.kt").readText()
    private val service = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/MusicService.kt").readText()
    private val viewModel = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/PlayerViewModel.kt").readText()

    @Test
    fun `the probe asks the platform for the row's volume, the mounted set, the path's storage and the removable state`() {
        for (call in listOf(
            "MediaStore.MediaColumns.VOLUME_NAME", "MediaStore.getExternalVolumeNames(context)",
            "Environment.getExternalStorageState(File(path))", "storage.storageVolumes.any { it.isRemovable && it.state != Environment.MEDIA_MOUNTED }",
        )) assertThat(probe).contains(call)
        // the old rule - external and external_primary are always available - is gone from the pre-open check's reasoning
        val fileNotFound = probe.substringAfter("catch (_: FileNotFoundException) {").substringBefore("catch (_: SecurityException)")
        assertThat(fileNotFound).contains("QueueVolumePolicy.notFoundMeans(")
        assertThat(fileNotFound).doesNotContain("Presence.MISSING")
    }

    @Test
    fun `both callers hand the probe the library's path for the song`() {
        assertThat(service).contains("QueueFileProbe.presence(applicationContext, item.localConfiguration?.uri, libraryPath)")
        assertThat(service).contains("musicRepository.getSong(item.mediaId).first()?.path")
        assertThat(viewModel).contains("QueueFileProbe.presence(context, MediaItemBuilder.playbackUri(song), song.path)")
    }
}
