package com.lostf1sh.pixelplayeross.data.musicinfo

import android.content.Context
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.di.AppScope
import com.lostf1sh.pixelplayeross.utils.AppLocaleManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * B40-F4: the app's side of the music-info features: where the cache is, the three switches, the language, the queue that fetches missing
 * album covers one at a time, and "Delete downloaded data". The questions themselves are [MusicInfoService]'s.
 *
 * **Automatic requests run only with their switch on; explicit offline selections permit one-shot requests**, and a cover is fetched only for an album the app was asked to draw that has no
 * cover of its own (and then in the background, one request per second like everything else on this path, at most [MAX_QUEUE] waiting).
 */
@Singleton
class MusicInfoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferencesRepository,
    baseClient: OkHttpClient,
    private val musicDao: MusicDao,
    @AppScope private val scope: CoroutineScope,
) {
    private val dir = File(context.filesDir, FOLDER)

    // Downloaded music info is durable offline content, not an automatically evicted disk cache.
    val cache = MusicInfoCache(dir, limitBytes = { 0L })

    private val http = MusicInfoHttp(baseClient)
    val service = MusicInfoService(
        cache = cache,
        brainz = MusicBrainzClient(http),
        coverArt = CoverArtArchiveClient(http),
        wikidata = WikidataClient(http),
        commons = CommonsClient(http),
        wikipedia = WikipediaClient(http),
        artistPhotosEnabled = { preferences.musicInfoArtistPhotosEnabledFlow.first() },
        coversEnabled = { preferences.musicInfoCoversEnabledFlow.first() },
        descriptionsEnabled = { preferences.musicInfoDescriptionsEnabledFlow.first() },
        languages = ::languages,
    )

    /** The app's language, then English: what a description is asked for in. */
    fun languages(): List<String> {
        val tag = AppLocaleManager.currentLanguageTag(context)
        val language = tag.ifBlank { context.resources.configuration.locales[0].language }.lowercase().take(3)
        return listOf(language.takeIf { it.length in 2..3 } ?: "en", "en").distinct()
    }

    // ── the cover queue ──────────────────────────────────────────────────────────────────────────

    private val _coverVersion = MutableStateFlow(0)

    /** Moves when a cover arrives: a screen that drew a placeholder for an album draws again. */
    val coverVersion: StateFlow<Int> = _coverVersion.asStateFlow()

    private data class CoverRequest(val songId: Long, val album: String, val artist: String)

    private val queue = Channel<CoverRequest>(capacity = MAX_QUEUE, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_LATEST)
    private val waiting = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var worker: kotlinx.coroutines.Job? = null

    /** The cover for the album of [songId] if one was downloaded already; never touches the network. */
    suspend fun cachedCoverForSong(songId: Long): File? = withContext(Dispatchers.IO) {
        val song = musicDao.getSongByIdOnce(songId) ?: return@withContext null
        service.cachedCover(song.albumName, song.albumArtist ?: song.artistName)
    }

    /**
     * An album with no cover of its own was drawn: if covers are switched on and none was downloaded, it is queued. Cheap and safe to call
     * from the image loader for every miss: the switch is read first, a duplicate is dropped, and the queue is bounded.
     */
    fun noteMissingCover(songId: Long) {
        scope.launch(Dispatchers.IO) {
            if (!preferences.musicInfoCoversEnabledFlow.first()) return@launch
            val song = musicDao.getSongByIdOnce(songId) ?: return@launch
            val album = song.albumName
            val artist = song.albumArtist ?: song.artistName
            if (album.isBlank() || artist.isBlank() || service.cachedCover(album, artist) != null) return@launch
            val key = MusicInfoService.coverKey(album, artist)
            if (!waiting.add(key)) return@launch
            if (queue.trySend(CoverRequest(songId, album, artist)).isFailure) waiting.remove(key)
            startWorker()
        }
    }

    @Synchronized
    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch(Dispatchers.IO) {
            while (true) {
                val request = queue.tryReceive().getOrNull() ?: break
                try {
                    if (service.fetchCover(request.album, request.artist) != null) {
                        Timber.tag(TAG).i("Cover for %s downloaded", request.album)
                        // the image loader holds nothing for this song's artwork now: it asks again, and the cache answers
                        runCatching { com.lostf1sh.pixelplayeross.data.image.LocalArtworkCache.invalidate(context, request.songId) }
                        _coverVersion.update { it + 1 }
                    }
                } finally {
                    waiting.remove(MusicInfoService.coverKey(request.album, request.artist))
                }
            }
        }
    }

    suspend fun downloadedCoverArrived(songId: Long) = withContext(Dispatchers.IO) {
        com.lostf1sh.pixelplayeross.data.image.LocalArtworkCache.invalidate(context, songId)
        _coverVersion.update { it + 1 }
    }

    // ── what the listener controls ───────────────────────────────────────────────────────────────

    fun usedBytes(): Long = cache.usedBytes()

    /** "Delete downloaded data": every picture and description that was downloaded, and the stored artist pictures that pointed at them. */
    suspend fun deleteDownloadedData(): Long = withContext(Dispatchers.IO) {
        val freed = cache.clear()
        musicDao.clearArtistImagesUnder(dir.absolutePath)
        _coverVersion.update { it + 1 }
        freed
    }

    companion object {
        const val FOLDER = "music-info"
        private const val TAG = "MusicInfo"
        const val MAX_QUEUE = 300
    }
}
