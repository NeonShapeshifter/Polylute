package com.lostf1sh.pixelplayeross.data.historyimport

import com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Applies a reviewed [ImportPreview] atomically/idempotently in one "Done" action.
 *
 * Guarantees idempotency across double-taps and mid-commit crash + retry:
 * - History: uses [PreparedImport.batchId] via [ImportedHistoryStore.commitImportIfAbsent] so the same preview
 *   batch is never written twice, alongside content [DedupKey] deduplication.
 * - Likes: reads existing favorites first and only calls `setFavoriteStatus(id, true)` for absent IDs; never removes.
 * - Playlists: passes each playlist's pre-assigned [ImportPlaylistPreview.targetId] as `customId` to
 *   [PlaylistPreferencesRepository.createPlaylist], then [PlaylistPreferencesRepository.addSongsToPlaylist].
 *   If a crash occurred after `createPlaylist`, a subsequent Done finds `targetId` already present and only ensures
 *   any missing songs are added without creating a duplicate playlist or `" (2)"` suffix. The id is derived from the
 *   picked file's content ([HistoryImportPipeline.stableImportPlaylistId]), so this also holds after the process died
 *   and the user picked the same files again (review 11-25).
 */
@Singleton
class UnifiedImportApplier @Inject constructor(
    private val store: ImportedHistoryStore,
    private val musicRepository: MusicRepository,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
) {
    private val applyMutex = Mutex()

    suspend fun apply(
        preview: ImportPreview,
        includedPlaylistIds: Set<String> = preview.playlists.filter { it.included }.mapTo(HashSet()) { it.targetId },
        importedAtMs: Long = System.currentTimeMillis(),
    ): UnifiedImportResult = applyMutex.withLock {
        val playsAdded = if (preview.counts.plays > 0) {
            val committedNew = store.commitImportIfAbsent(preview, importedAtMs)
            if (committedNew) preview.counts.plays else 0
        } else {
            0
        }

        var likesAdded = 0
        val matchedLikeIds = preview.likes.matchedTracks.map { it.songId }.distinct()
        if (matchedLikeIds.isNotEmpty()) {
            val existingFavs = musicRepository.getFavoriteSongIdsOnce()
            for (songId in matchedLikeIds) {
                if (songId !in existingFavs) {
                    musicRepository.setFavoriteStatus(songId, true)
                    likesAdded++
                }
            }
        }

        var playlistsCreated = 0
        val toCreate = preview.playlists.filter { it.targetId in includedPlaylistIds && it.matchedTracks.isNotEmpty() }
        if (toCreate.isNotEmpty()) {
            val existingPlaylists = playlistPreferencesRepository.getPlaylistsOnce()
            val existingById = existingPlaylists.associateBy { it.id }
            val usedNames = existingPlaylists.mapTo(HashSet()) { it.name }

            for (pl in toCreate) {
                val songIds = pl.matchedTracks.map { it.songId }.distinct()
                val alreadyCreated = existingById[pl.targetId]
                if (alreadyCreated != null) {
                    val missing = songIds.filter { it !in alreadyCreated.songIds }
                    if (missing.isNotEmpty()) {
                        playlistPreferencesRepository.addSongsToPlaylist(alreadyCreated.id, missing)
                    }
                } else {
                    val uniqueName = resolveUniquePlaylistName(pl.name, usedNames)
                    usedNames += uniqueName
                    // One write: a crash can no longer leave an empty playlist between create and add.
                    playlistPreferencesRepository.createPlaylist(
                        name = uniqueName,
                        songIds = songIds,
                        customId = pl.targetId,
                    )
                    playlistsCreated++
                }
            }
        }

        UnifiedImportResult(
            playsAdded = playsAdded,
            likesAdded = likesAdded,
            playlistsCreated = playlistsCreated,
            dateRangeStartMs = preview.dateRangeStartMs,
            dateRangeEndMs = preview.dateRangeEndMs,
            matchPercent = preview.counts.matchPercent,
            sourceKind = preview.sourceKind,
        )
    }

    companion object {
        fun resolveUniquePlaylistName(baseName: String, usedNames: Set<String>): String {
            val trimmed = baseName.trim().ifEmpty { "Imported Playlist" }
            if (trimmed !in usedNames) return trimmed
            var suffix = 2
            while ("$trimmed ($suffix)" in usedNames) {
                suffix++
            }
            return "$trimmed ($suffix)"
        }
    }
}
