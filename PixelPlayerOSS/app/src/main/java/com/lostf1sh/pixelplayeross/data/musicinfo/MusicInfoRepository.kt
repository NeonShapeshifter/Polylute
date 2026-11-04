package com.lostf1sh.pixelplayeross.data.musicinfo

import com.lostf1sh.pixelplayeross.data.offline.InfoKind

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

    val deletedDownloads = cache.deletedDownloads

    private val assets = com.lostf1sh.pixelplayeross.data.network.DownloadAssetRegistry(File(context.filesDir, "download-claims.jsonl"))
    private val http = MusicInfoHttp(baseClient)
    val service = MusicInfoService(
        cache = cache,
        brainz = MusicBrainzClient(http, taggedId = { kind, name, artist ->
            when (kind) {
                "artist" -> musicDao.taggedArtistId(name)
                "release" -> musicDao.taggedReleaseId(name, artist.orEmpty())
                "release-group" -> musicDao.taggedReleaseGroupId(name, artist.orEmpty())
                else -> null
            }
        }),
        coverArt = CoverArtArchiveClient(http),
        wikidata = WikidataClient(http),
        commons = CommonsClient(http),
        wikipedia = WikipediaClient(http),
        artistPhotosEnabled = { preferences.musicInfoArtistPhotosEnabledFlow.first() },
        coversEnabled = { preferences.musicInfoCoversEnabledFlow.first() },
        descriptionsEnabled = { preferences.musicInfoDescriptionsEnabledFlow.first() },
        languages = ::languages,
        assets = assets,
    )

    /** The app's language, then English: what a description is asked for in. */
    fun languages(): List<String> {
        val tag = AppLocaleManager.currentLanguageTag(context)
        val language = tag.ifBlank { context.resources.configuration.locales[0].language }.lowercase().take(3)
        return listOf(language.takeIf { it.length in 2..3 } ?: "en", "en").distinct()
    }

    private val startupRepairGate = kotlinx.coroutines.sync.Mutex()

    suspend fun repairAtStartupOnce() = withContext(Dispatchers.IO) {
        startupRepairGate.lock()
        try {
            val marker = File(context.filesDir, "music-info-missing-b44.done")
            if (!marker.exists()) {
                cache.dropMissingMarks()
                marker.writeText("done")
            }
            repairBrokenImagesOnce()
        } finally {
            startupRepairGate.unlock()
        }
    }

    private val imageRepairGate = kotlinx.coroutines.sync.Mutex()

    /** Once per installation upgrade, on IO; clear references before deleting so interrupted repairs can resume. */
    suspend fun repairBrokenImagesOnce() = withContext(Dispatchers.IO) {
        imageRepairGate.lock()
        try {
            val marker = File(context.filesDir, "music-info-images-1.1.1.done")
            if (!marker.exists()) {
                cache.brokenImages().forEach { file ->
                    musicDao.clearArtistImagesUnder(file.absolutePath)
                    cache.removeBrokenImage(file)
                }
                marker.writeText("done")
            }
        } finally { imageRepairGate.unlock() }
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
        repairBrokenImagesOnce()
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

    private val interactiveSongs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun requestSongInfo(song: com.lostf1sh.pixelplayeross.data.model.Song) {
        val key = song.id
        val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            kotlinx.coroutines.supervisorScope {
                val artists = song.artists.map { it.name }.ifEmpty { listOf(song.artist) }
                artists.forEach { artist ->
                    launch {
                        service.fetchArtistPhoto(artist)?.let { photo ->
                            musicDao.getArtistIdByNormalizedName(artist)?.let { musicDao.updateArtistImageUrl(it, photo.path) }
                        }
                    }
                    launch { service.fetchAbout(AboutKind.ARTIST, artist) }
                }
                launch { service.fetchAbout(AboutKind.ALBUM, song.album, song.albumArtist?.takeIf { it.isNotBlank() } ?: song.artist) }
                launch {
                    if (preferences.musicInfoCoversEnabledFlow.first() && !com.lostf1sh.pixelplayeross.data.offline.OfflineArtwork.hasLocalCover(context, song)) {
                        if (service.fetchCover(song.album, song.albumArtist?.takeIf { it.isNotBlank() } ?: song.artist) != null) {
                            song.id.toLongOrNull()?.let { downloadedCoverArrived(it) }
                        }
                    }
                }
            }
        }
        if (interactiveSongs.putIfAbsent(key, job) == null) {
            job.invokeOnCompletion { interactiveSongs.remove(key, job) }; job.start()
        } else job.cancel()
    }

    // ── what the listener controls ───────────────────────────────────────────────────────────────

    suspend fun updateDiscoveredPhoto(name: String) = withContext(Dispatchers.IO) {
        service.cachedArtistPhoto(name)?.let { photo ->
            musicDao.getArtistIdByNormalizedName(name)?.let { musicDao.updateArtistImageUrl(it, photo.path) }
        }
    }

    suspend fun deleteDiscovered(kind: InfoKind, name: String, artist: String, songId: String) = withContext(Dispatchers.IO) {
        when (kind) {
            InfoKind.PHOTO -> {
                val key = MusicInfoService.photoKey(name)
                deleteAsset("info|ARTIST_PHOTO|$key", cache.get(MusicInfoCache.Kind.ARTIST_PHOTO, key)?.file?.absolutePath)
            }
            InfoKind.BIO -> deleteAbout(AboutKind.ARTIST, name, null)
            InfoKind.COVER -> {
                for (credit in setOf(artist, MusicBrainzClient.firstCreditedArtist(artist))) {
                    deleteAsset("info|COVER|${MusicInfoService.coverKey(name, credit)}", null)
                }
                songId.toLongOrNull()?.let { downloadedCoverArrived(it) }
            }
            InfoKind.DESCRIPTION -> {
                for (credit in setOf(artist, MusicBrainzClient.firstCreditedArtist(artist))) {
                    deleteAbout(AboutKind.ALBUM, name, credit)
                }
            }
            InfoKind.LYRICS -> Unit
        }
        _coverVersion.update { it + 1 }
    }

    suspend fun deleteLegacyEntry(id: String) = withContext(Dispatchers.IO) {
        cache.removeSavedEntry(id)?.let { musicDao.clearArtistImagesUnder(it.absolutePath) }
        _coverVersion.update { it + 1 }
    }

    suspend fun deleteJobDownloads(owner: String) = withContext(Dispatchers.IO) {
        assets.release(owner, "info|").forEach { deleteAsset(it.id, it.file) }
        _coverVersion.update { it + 1 }
    }

    private suspend fun deleteAsset(id: String, path: String?) {
        val parts = id.split('|', limit = 3)
        if (parts.size != 3) return
        if (path != null) musicDao.clearArtistImagesUnder(path)
        cache.remove(MusicInfoCache.Kind.valueOf(parts[1]), parts[2])
        assets.forget(id)
    }
    suspend fun deleteArtistInfo(name: String) = withContext(Dispatchers.IO) {
        service.suppressArtist(name)
        val key = MusicInfoService.photoKey(name)
        val path = cache.get(MusicInfoCache.Kind.ARTIST_PHOTO, key)?.file?.absolutePath
        deleteAsset("info|ARTIST_PHOTO|$key", path)
        deleteAbout(AboutKind.ARTIST, name, null)
        _coverVersion.update { it + 1 }
    }
    suspend fun deleteAlbumInfo(album: String, artist: String, songIds: List<String>) = withContext(Dispatchers.IO) {
        val artists = linkedSetOf(artist, MusicBrainzClient.firstCreditedArtist(artist))
        songIds.mapNotNull(String::toLongOrNull).forEach { songId ->
            musicDao.getSongByIdOnce(songId)?.takeIf { it.albumName == album }?.let { song ->
                artists += song.albumArtist?.takeIf(String::isNotBlank) ?: song.artistName
            }
        }
        deletedDownloads.mark(artists.flatMap { DownloadKeys.album(album, it) })
        artists.filter(String::isNotBlank).forEach { albumArtist ->
            deleteAsset("info|COVER|${MusicInfoService.coverKey(album, albumArtist)}", null)
            deleteAbout(AboutKind.ALBUM, album, albumArtist)
        }
        songIds.mapNotNull(String::toLongOrNull).forEach { downloadedCoverArrived(it) }
    }

    private suspend fun deleteAbout(kind: AboutKind, name: String, artist: String?) {
        val languages = cache.descriptionLanguages() + languages() + listOf("en", "es", "sv", "und")
        languages.distinct().forEach { language ->
            deleteAsset("info|ABOUT|${MusicInfoService.aboutKey(kind, name, artist, language)}", null)
        }
        deleteAsset("info|ABOUT|${MusicInfoService.aboutKey(kind, name, artist)}", null)
    }

    suspend fun allowDownloads(targets: List<com.lostf1sh.pixelplayeross.data.offline.OfflineTarget>) = withContext(Dispatchers.IO) {
        val keys = targets.mapNotNull { target ->
            when (target.kind) {
                com.lostf1sh.pixelplayeross.data.offline.OfflineKind.ARTIST_PHOTOS -> DownloadKeys.photo(target.title)
                com.lostf1sh.pixelplayeross.data.offline.OfflineKind.COVERS -> DownloadKeys.cover(target.title, target.albumArtist.orEmpty())
                com.lostf1sh.pixelplayeross.data.offline.OfflineKind.DESCRIPTIONS -> DownloadKeys.about(
                    if (target.album) AboutKind.ALBUM else AboutKind.ARTIST,
                    target.title,
                    target.albumArtist,
                )
                com.lostf1sh.pixelplayeross.data.offline.OfflineKind.LYRICS -> null
            }
        }
        deletedDownloads.allow(keys)
        Unit
    }

    suspend fun refreshArtistInfo(name: String) = withContext(Dispatchers.IO) {
        service.refreshArtist(name)
        service.cachedArtistPhoto(name)?.let { photo ->
            musicDao.getArtistIdByNormalizedName(name)?.let { musicDao.updateArtistImageUrl(it, photo.path) }
        }
    }

    suspend fun noteArtistPhotoUse(name: String, path: String) = withContext(Dispatchers.IO) {
        if (cache.owns(path)) assets.note(com.lostf1sh.pixelplayeross.data.network.DownloadAsset(
            "info|ARTIST_PHOTO|${MusicInfoService.photoKey(name)}", path), newlyWritten = false)
    }

    fun usedBytes(): Long = cache.usedBytes()

    /** "Delete downloaded data": every picture and description that was downloaded, and the stored artist pictures that pointed at them. */
    suspend fun deleteDownloadedData(): Long = withContext(Dispatchers.IO) {
        val freed = cache.clear()
        assets.forgetPrefix("info|")
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
