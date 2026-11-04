package com.lostf1sh.pixelplayeross.data.repository

import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.model.Song

/** B56-3.1: what the phone already has for a song's lyrics: nothing, lines with no timing, or lines with timing. */
enum class LocalLyricsTiming { NONE, PLAIN, SYNCED }

interface LyricsRepository {
    /** Discovery reads local sources and remote answers without persisting them as lyrics. */
    suspend fun hasLocalLyrics(song: Song): Boolean = false

    /**
     * B56-3.1: the same question as [hasLocalLyrics], told by timing. A repository that cannot say counts what it has as timed, so the downloader treats
     * it as it always did.
     */
    suspend fun localLyricsTiming(song: Song): LocalLyricsTiming =
        if (hasLocalLyrics(song)) LocalLyricsTiming.SYNCED else LocalLyricsTiming.NONE
    suspend fun discoverLyrics(song: Song): String? = null
    suspend fun saveDiscoveredLyrics(song: Song, raw: String): Boolean = false
    suspend fun downloadedSidecarIdentities(): Set<String> = emptySet()

    /**
     * Returns already-persisted lyrics without performing any network request.
     */
    suspend fun getStoredLyrics(song: Song): Pair<Lyrics, String>?

    /** Explicit selection restores downloads for these songs only; playback never clears a deletion. */
    suspend fun allowDownloads(songs: List<Song>) {}

    /** Explicit offline download: keeps existing/local lyrics, then downloads a missing result with the user's one-shot consent. */
    suspend fun prepareForOffline(song: Song): Lyrics? = getLyrics(song)

    /**
     * Get lyrics for a song with source preference support.
     * 
     * @param song The song to get lyrics for
     * @param sourcePreference The preferred order of sources to try (API, Embedded, Local)
     * @param forceRefresh If true, bypasses in-memory cache
     * @return Lyrics object or null if not found
     */
    suspend fun getLyrics(
        song: Song,
        sourcePreference: LyricsSourcePreference = LyricsSourcePreference.EMBEDDED_FIRST,
        forceRefresh: Boolean = false
    ): Lyrics?
    
    /**
     * Fetch lyrics from remote API and save to database.
     */
    suspend fun fetchFromRemote(song: Song): Result<Pair<Lyrics, String>>
    
    /**
     * Search for lyrics on remote API and return multiple results.
     */
    suspend fun searchRemote(song: Song): Result<Pair<String, List<LyricsSearchResult>>>
  
    /**
     * Search for lyrics on remote API using query title and artist, and return multiple results.
     */
    suspend fun searchRemoteByQuery(title: String, artist: String? = null): Result<Pair<String, List<LyricsSearchResult>>>
    
    /**
     * Update lyrics for a song in the database.
     */
    suspend fun updateLyrics(songId: Long, lyricsContent: String)
    
    /**
     * Reset lyrics for a song (remove from database and cache).
     */
    /** Removes only downloaded remote lyrics. Embedded tags, sidecars and their cached DB rows survive. */
    suspend fun deleteDownloadedLyrics(song: Song) {}
    suspend fun deleteJobDownloads(owner: String) {}

    suspend fun resetLyrics(songId: Long)
    
    /**
     * Reset all lyrics (clear database and cache).
     */
    suspend fun resetAllLyrics()
    
    /**
     * Clear in-memory cache only.
     */
    fun clearCache()

    /**
     * Scans local .lrc files for the provided songs and updates the database if found.
     * 
     * @param songs List of songs to scan for
     * @param onProgress Callback for progress updates (current, total)
     * @return Number of songs updated
     */
    suspend fun scanAndAssignLocalLrcFiles(
        songs: List<Song>,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): Int
}
