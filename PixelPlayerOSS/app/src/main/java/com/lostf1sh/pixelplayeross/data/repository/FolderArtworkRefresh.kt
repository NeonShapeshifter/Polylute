package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.worker.SyncRunContext
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemeProcessor
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.DiscCoverNames
import com.lostf1sh.pixelplayeross.utils.FolderArtworkDecisions
import com.lostf1sh.pixelplayeross.utils.FolderArtworkSession
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import timber.log.Timber

class FolderArtworkRefresh @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao,
    private val preferences: UserPreferencesRepository,
    private val musicInfo: MusicInfoRepository,
    private val colors: ColorSchemeProcessor
) {
    /**
     * B58 2 (#68): every song's new artwork address is collected while the files are read, and **all of them are written in one commit**, so the observed library tables are
     * invalidated once and not once per song (before, 5,000 songs meant 5,000 commits and 42,000 list queries run again for one Full Rescan on the test host). The sync lends this
     * step how to write in one transaction and where to say how far it is ([SyncRunContext], carried by the coroutine); called alone, the writes run one after the other.
     */
    /** B60: how a sync opens the session of one folder. Only the tests replace it (a slow, counting stand-in for the animated-cover folder the listener chose, which the boundary rules keep out of this file). */
    @Volatile
    internal var sessionFactory: (Context) -> FolderArtworkSession = { FolderArtworkSession(it) }

    /** B60: how the album's own cover finds a song with an embedded picture (the file's headers only). B61: and how each song's folder step asks the same question. Only the tests replace it. */
    @Volatile
    internal var embeddedPictureProbe: (String) -> Boolean = com.lostf1sh.pixelplayeross.utils.EmbeddedPictureProbe::hasPicture

    suspend fun refresh() {
        val run = currentCoroutineContext()[SyncRunContext]
        val inTransaction: suspend (suspend () -> Unit) -> Unit = run?.inTransaction ?: { it() }
        val onProgress: suspend (done: Int, total: Int) -> Unit = run?.onProgress ?: { _, _ -> }
        val enabled = preferences.folderCoversEnabledFlow.first()
        val rules = preferences.currentDirectoryRules()
        val resolver = rules.resolver()
        val songs = musicDao.getAllSongsList().filter { !resolver.isBlocked(it.parentDirectoryPath) }
        val changedAlbums = mutableSetOf<Long>()
        val newArtwork = ArrayList<Pair<Long, String?>>()
        // B60 (#82 follow-up): the album's own cover, for an album whose folder has disc covers: never a disc's picture (see AlbumArtUtils.chooseAlbumCover)
        val albumCovers = LinkedHashMap<Long, String?>()
        val downloadedCovers = mutableMapOf<Pair<String, String>, Boolean>()
        // B59 2 (#80): the provenance of every folder cover this sync installs or drops is written in one commit at the end, not one commit per song
        val provenance = AlbumArtUtils.FolderArtworkProvenanceBatch()
        // B61: the folders this sync found settled ("nothing to install", see FolderArtworkDecisions), written once at the end; a Full Rescan decides every folder again
        val decisions = FolderArtworkDecisions.Batch()
        // B61: what this sync really did, for the log line at the end (the reviewer reads it on the HONOR): folders skipped as settled / looked at, header probes, listings of the animated-cover folder
        var skippedFolders = 0
        var lookedFolders = 0
        var probes = 0
        var listings = 0
        val countedProbe: (String) -> Boolean = { path -> probes++; embeddedPictureProbe(path) }
        if (run?.fullRescan == true) FolderArtworkDecisions.clear(context)
        // where the animated covers are read from is the same for the whole sync: asked once, of the first folder's session
        val animatedIdentity by lazy { sessionFactory(context).animatedIdentity }
        var looked = 0
        onProgress(0, songs.size)
        try {
        for ((directory, members) in songs.groupBy { it.parentDirectoryPath }) {
            decisions.saw(directory)
            val eligible = AlbumArtUtils.isSingleAlbumFolder(directory, members.map { it.albumId to it.albumName })
            // B59 review (HONOR 11-06): per-disc covers are looked for only where the folder holds songs of two or more discs. Looking costs one listing of the chosen animated-cover folder
            // (a SAF query, ~100 ms on the HONOR), and asking it in all 154 folders added ~17 s to every "Update library"; in a one-disc album `disc1.*` adds nothing `cover.*` does not
            val multiDisc = members.mapTo(HashSet()) { DiscCoverNames.effectiveDisc(it.discNumber) }.size >= 2
            // B61 (item 1): a folder where nothing is installed and every answer was had is not asked about again until something it depends on changes (see FolderArtworkDecisions)
            val settledSignature = if (eligible && enabled && members.none { AlbumArtUtils.hasFolderProvenance(context, it.id) }) {
                FolderArtworkDecisions.signature(
                    directory, multiDisc, File(directory).lastModified(), animatedIdentity,
                    members.map { FolderArtworkDecisions.Member(it.id, it.mediaStoreDateModified, it.albumArtUriString, DiscCoverNames.effectiveDisc(it.discNumber)) },
                )
            } else {
                null
            }
            if (settledSignature != null && FolderArtworkDecisions.isSettled(context, directory, settledSignature)) {
                // the album's own cover can only be left over from disc covers that are gone; that check is a memory read
                if (AlbumArtUtils.forgetAlbumCover(context, members.first().albumId)) {
                    changedAlbums.add(members.first().albumId)
                    decisions.unsettle(directory)
                } else {
                    decisions.settle(directory, settledSignature)
                }
                skippedFolders++
                val before = looked
                looked += members.size
                if (looked / PROGRESS_STEP != before / PROGRESS_STEP) onProgress(looked, songs.size)
                continue
            }
            // Release a folder's bytes before moving to the next one, even in a very large library.
            val session = sessionFactory(context)
            lookedFolders++
            var settled = settledSignature != null
            var anyDiscCover = false
            for (song in members) {
                currentCoroutineContext().ensureActive()
                try {
                    // B59 4 (#82): a disc's own file (`disc2.jpg`) is the cover of that disc's songs, over the folder image and over the file's own picture; checked for every song of an eligible
                    // folder (a disc file added later must reach songs whose cache is already full), and a song without one goes on to the ordinary rules
                    val disc = if (eligible && enabled && multiDisc) {
                        AlbumArtUtils.installDiscArtwork(
                            context, song.id, song.filePath, song.albumArtUriString, DiscCoverNames.effectiveDisc(song.discNumber), eligible, enabled, session, provenance,
                        )
                    } else {
                        null
                    }
                    if (disc != null) {
                        anyDiscCover = true
                        settled = false
                        if (disc.changed) {
                            newArtwork += song.id to disc.uri
                            changedAlbums.add(song.albumId)
                            song.albumArtUriString?.let { colors.invalidateScheme(it) }
                        }
                        continue
                    }
                    if (!AlbumArtUtils.needsFolderArtworkRefresh(context, song.id, song.albumArtUriString, eligible, enabled)) continue
                    val album = song.albumName to (song.albumArtist ?: song.artistName)
                    val downloaded = enabled && downloadedCovers.getOrPut(album) {
                        musicInfo.service.cachedCover(album.first, album.second) != null
                    }
                    if (downloaded) settled = false
                    val result = AlbumArtUtils.refreshFolderArtwork(
                        context, song.id, song.filePath, song.albumArtUriString, eligible, enabled, downloaded, session,
                        hasEmbeddedPicture = countedProbe, provenanceBatch = provenance
                    )
                    if (result.changed) {
                        settled = false
                        // Update the projection even if palette invalidation fails. The next UI read sees the new URI.
                        newArtwork += song.id to result.uri
                        changedAlbums.add(song.albumId)
                        song.albumArtUriString?.let { colors.invalidateScheme(it) }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    settled = false
                    Timber.w(error, "Could not refresh folder artwork in %s for song %s", directory, song.id)
                } finally {
                    looked++
                    if (looked % PROGRESS_STEP == 0) onProgress(looked, songs.size)
                }
            }
            if (eligible && enabled) {
                // B60: a single-album folder: the album's cover is its own when disc covers were installed in it; an album that has none (any more) goes back to the ordinary rule
                val albumId = members.first().albumId
                try {
                    if (anyDiscCover) {
                        albumCovers[albumId] = AlbumArtUtils.chooseAlbumCover(
                            context, albumId, members.sortedBy { it.id }.map { it.id to it.filePath }, session, embeddedPictureProbe,
                        )
                    } else if (AlbumArtUtils.forgetAlbumCover(context, albumId)) {
                        changedAlbums.add(albumId)
                        settled = false
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    settled = false
                    Timber.w(error, "Could not decide the album cover in %s", directory)
                }
            }
            // everything asked was answered (a failed listing is not an answer), nothing was installed or taken away: the next sync need not ask again
            listings += session.listingsAsked
            if (settled && !session.listingFailed && settledSignature != null) decisions.settle(directory, settledSignature) else decisions.unsettle(directory)
        }
        } finally {
            Timber.tag("SyncWorker").i(
                "folder-artwork: %d folder(s) settled and skipped, %d looked at, %d embedded-picture probe(s), %d listing(s) of the animated-cover folder",
                skippedFolders, lookedFolders, probes, listings,
            )
            if (!provenance.flush(context)) Timber.w("Could not write the folder artwork provenance")
            if (!decisions.flush(context)) Timber.w("Could not write the folder artwork decisions")
        }
        onProgress(songs.size, songs.size)
        if (newArtwork.isEmpty() && changedAlbums.isEmpty() && albumCovers.isEmpty()) return
        try {
            inTransaction {
                newArtwork.forEach { (songId, uri) -> musicDao.updateSongAlbumArt(songId, uri) }
                changedAlbums.chunked(400).forEach { musicDao.refreshAlbumArtwork(it) }
                // after the line above, which takes the lowest-id song's artwork (a disc's, for an album with disc covers); written only where it differs
                albumCovers.forEach { (albumId, uri) -> musicDao.setAlbumArtworkIfDifferent(albumId, uri) }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Nothing was written (one transaction): the provenance of each file is still recorded, so the next sync finds the same songs and writes them again.
            Timber.w(error, "Could not write the refreshed folder artwork for %s songs", newArtwork.size)
        }
    }

    private companion object {
        const val PROGRESS_STEP = 100
    }
}
