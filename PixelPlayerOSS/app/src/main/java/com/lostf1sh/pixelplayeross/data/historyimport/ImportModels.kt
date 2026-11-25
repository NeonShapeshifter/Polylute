package com.lostf1sh.pixelplayeross.data.historyimport

import java.util.UUID

/** Where an imported play came from. */
enum class ImportSourceKind {
    SPOTIFY_EXTENDED,
    SPOTIFY_ACCOUNT,
    YOUTUBE_MUSIC,
    CSV,
    POLYLUTE_JSON,
    POLYLUTE_CSV,
}

/** How well an imported play matched a track currently in the library. */
enum class MatchConfidence {
    EXACT,
    FOLDED,
    PARTIAL,
    NONE,
}

data class TrackMatchResult(
    val identity: String?,
    val songId: String?,
    val confidence: MatchConfidence,
) {
    val isMatched: Boolean get() = identity != null && confidence != MatchConfidence.NONE

    companion object {
        val Unmatched = TrackMatchResult(null, null, MatchConfidence.NONE)
    }
}

/**
 * Shared play record produced by every history reader and consumed by matching, deduplication,
 * persistence, and Stats (and designed for B74 export/re-import round-trips).
 */
data class ImportedPlayRecord(
    val timestampMs: Long,
    val artistRaw: String,
    val artists: List<String>,
    val title: String,
    val album: String? = null,
    /** Listened ms: real when source had it, estimated from matched track duration, or null when unknown and unmatched. */
    val listenedMs: Long? = null,
    val isEstimated: Boolean = false,
    val sourceKind: ImportSourceKind,
    val sourceRowRef: String,
    val externalUri: String? = null,
    val match: TrackMatchResult = TrackMatchResult.Unmatched,
    /** Original listened ms reported by the source file (null when the source has no duration field). */
    val sourceListenedMs: Long? = null,
)

data class ImportRejection(
    val rowRef: String,
    val reason: String,
)

data class UnmatchedArtistCount(
    val name: String,
    val plays: Int,
)

data class ImportCounts(
    val rowsRead: Int = 0,
    val plays: Int = 0,
    val skips: Int = 0,
    val podcastsSkipped: Int = 0,
    val nonMusicSkipped: Int = 0,
    val duplicatesSkipped: Int = 0,
    val rejectedCount: Int = 0,
    val matchedExact: Int = 0,
    val matchedFolded: Int = 0,
    val matchedPartial: Int = 0,
    val unmatched: Int = 0,
    val estimatedPlays: Int = 0,
    val estimatedTimeMs: Long = 0L,
    val zeroDurationPlays: Int = 0,
    val totalListenedMs: Long = 0L,
) {
    val matchedTotal: Int get() = matchedExact + matchedFolded + matchedPartial
    val matchPercent: Int get() = if (plays <= 0) 0 else ((matchedTotal * 100.0) / plays).toInt()
}

data class ImportRecord(
    val id: String,
    val sourceKind: ImportSourceKind,
    val fileNames: List<String>,
    val importedAtMs: Long,
    val dateRangeStartMs: Long?,
    val dateRangeEndMs: Long?,
    val counts: ImportCounts,
    val topUnmatchedArtists: List<UnmatchedArtistCount> = emptyList(),
)

data class ImportProgress(
    val filesDone: Int,
    val filesTotal: Int,
    val rowsRead: Int,
    val currentFileName: String,
)

enum class CsvDurationUnit {
    MILLISECONDS,
    SECONDS,
}

data class CsvColumnMapping(
    val delimiter: Char,
    val hasHeader: Boolean,
    val headers: List<String>,
    val sampleRows: List<List<String>>,
    val dateColumn: Int,
    val artistColumn: Int,
    val titleColumn: Int,
    val albumColumn: Int? = null,
    val durationColumn: Int? = null,
    val durationUnit: CsvDurationUnit = CsvDurationUnit.MILLISECONDS,
)

/** Distinct track metadata inside a prepared/stored import so matching and Stats stay compact. */
data class ImportedTrackKey(
    val artistRaw: String,
    val artists: List<String>,
    val title: String,
    val album: String?,
    val match: TrackMatchResult,
    val matchedDurationMs: Long?,
)

/** A matched library song for likes or playlists in the unified import preview. */
data class ImportedTrackRef(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/** An unmatched track from likes or playlists shown collapsed in the review screen. */
data class UnmatchedTrackRef(
    val title: String,
    val artist: String,
    val album: String? = null,
)

data class ImportLikesPreview(
    val totalRead: Int = 0,
    val matchedTracks: List<ImportedTrackRef> = emptyList(),
    val alreadyFavoriteCount: Int = 0,
    val unmatchedTracks: List<UnmatchedTrackRef> = emptyList(),
) {
    val matchedCount: Int get() = matchedTracks.size
    val newCount: Int get() = (matchedCount - alreadyFavoriteCount).coerceAtLeast(0)
    val unmatchedCount: Int get() = unmatchedTracks.size
}

data class ImportPlaylistPreview(
    val targetId: String = UUID.randomUUID().toString(),
    val name: String,
    val totalItems: Int,
    val matchedTracks: List<ImportedTrackRef>,
    val unmatchedTracks: List<UnmatchedTrackRef> = emptyList(),
    val episodesSkipped: Int = 0,
    val localTracksSkipped: Int = 0,
    val included: Boolean = true,
) {
    val matchedCount: Int get() = matchedTracks.size
    val unmatchedCount: Int get() = unmatchedTracks.size
}

enum class ImportFileDataKind {
    SPOTIFY_EXTENDED_HISTORY,
    SPOTIFY_ACCOUNT_HISTORY,
    SPOTIFY_PLAYLISTS,
    SPOTIFY_LIKES,
    YOUTUBE_MUSIC_HISTORY,
    CSV_HISTORY,
    M3U_PLAYLIST,
    POLYLUTE_HISTORY,
    POLYLUTE_LIKES,
    POLYLUTE_PLAYLISTS,
    SKIPPED,
}

enum class ImportFileSkipReason {
    YOUTUBE_VIDEO_ID_ONLY,
    DUPLICATE_FILE,
    NON_MUSIC_METADATA,
    UNSUPPORTED_FORMAT,
    EMPTY_OR_NO_TRACKS,
}

data class ImportFileSummary(
    val fileName: String,
    val dataKind: ImportFileDataKind,
    val skipReason: ImportFileSkipReason? = null,
)

data class UnifiedImportResult(
    val playsAdded: Int,
    val likesAdded: Int,
    val playlistsCreated: Int,
    val dateRangeStartMs: Long?,
    val dateRangeEndMs: Long?,
    val matchPercent: Int,
    val sourceKind: ImportSourceKind,
)

/** Compact columnar play payload ready to persist without re-parsing the source file. */
class PreparedImport(
    val sourceKind: ImportSourceKind,
    val fileNames: List<String>,
    val tracks: List<ImportedTrackKey>,
    val trackIndices: IntArray,
    val timestampsMs: LongArray,
    val listenedMs: LongArray,
    val estimated: BooleanArray,
    val sourceListenedMs: LongArray,
    val dedupHi: LongArray,
    val dedupLo: LongArray,
    val dateRangeStartMs: Long?,
    val dateRangeEndMs: Long?,
    val counts: ImportCounts,
    val topUnmatchedArtists: List<UnmatchedArtistCount>,
    val rejectionsSample: List<ImportRejection>,
    val batchId: String = UUID.randomUUID().toString(),
)

data class ImportPreview(
    val sourceKind: ImportSourceKind,
    val fileNames: List<String>,
    val dateRangeStartMs: Long?,
    val dateRangeEndMs: Long?,
    val counts: ImportCounts,
    val topUnmatchedArtists: List<UnmatchedArtistCount>,
    val rejectionsSample: List<ImportRejection>,
    val hasDurationData: Boolean,
    val csvMapping: CsvColumnMapping? = null,
    val prepared: PreparedImport,
    val hasHistory: Boolean = counts.rowsRead > 0 || counts.plays > 0 || counts.duplicatesSkipped > 0,
    val fileSummaries: List<ImportFileSummary> = emptyList(),
    val likes: ImportLikesPreview = ImportLikesPreview(),
    val playlists: List<ImportPlaylistPreview> = emptyList(),
    val hiddenZeroMatchPlaylists: Int = 0,
    val playlistEpisodesSkipped: Int = 0,
    val playlistLocalTracksSkipped: Int = 0,
) {
    val hasAnythingToApply: Boolean
        get() = counts.plays > 0 ||
            likes.newCount > 0 ||
            playlists.any { it.included && it.matchedTracks.isNotEmpty() }
}

sealed class ImportInputException(message: String) : Exception(message)

class MusicoletUnsupportedException(val detectedFiles: List<String>) :
    ImportInputException("Musicolet encrypted backup detected: ${detectedFiles.joinToString()}")

class YouTubeHtmlFormatException :
    ImportInputException("YouTube Takeout HTML format detected; JSON watch-history.json is required")

class UnrecognizedImportFormatException(detail: String = "Unsupported file format") :
    ImportInputException(detail)
