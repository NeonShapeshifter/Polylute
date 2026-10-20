package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import com.lostf1sh.pixelplayeross.data.media.guessImageMimeType
import com.lostf1sh.pixelplayeross.data.media.imageExtensionFromMimeType
import com.lostf1sh.pixelplayeross.data.media.isValidImageData
import com.lostf1sh.pixelplayeross.data.media.resolveAudioFileExtension
import com.lostf1sh.pixelplayeross.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

data class ExternalSongLoadResult(
    val song: Song,
    val relativePath: String?,
    val bucketId: Long?,
    val displayName: String?
)

class ExternalMediaStateHolder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * The songs that queue up behind [reference], the file that was opened, read from the MediaStore
     * index in **one** query and without opening a single file (FIX-1, B11-fix).
     *
     * This used to build each sibling with [buildExternalSongFromUri]: a query, a tag read with its
     * artwork (an open of the file), an artwork file written to the cache and a content-type lookup,
     * per file, before the song that was opened could start. On a 2855-file folder that is minutes of
     * work at the best of times. A sibling here is what the index says about it, or, when the library
     * already has the song ([libraryLookup]), the library's own `Song` (with its artwork and artists).
     * An indexed sibling has no artwork of its own until the library syncs it: the index's art cache
     * is not trusted per song (see `AlbumArtUtils.getMediaStoreAlbumArtUri`).
     *
     * Whether an entry's file still exists is not asked here; see [ExternalQueueContinuation].
     */
    suspend fun buildFolderContinuation(
        reference: ExternalSongLoadResult,
        originalUri: Uri,
        libraryLookup: (String) -> Song? = { null },
    ): List<Song> = withContext(Dispatchers.IO) {
        val rows = queryFolderRows(reference, originalUri) ?: return@withContext emptyList()
        FolderSiblings.after(rows, originalUri.toString(), reference.displayName)
            .asSequence()
            .map { row -> libraryLookup(row.id.toString()) ?: indexedSong(row) }
            .filter { it.id != reference.song.id }
            .distinctBy { it.id }
            .toList()
    }

    private fun queryFolderRows(reference: ExternalSongLoadResult, originalUri: Uri): List<IndexedFolderRow>? {
        val relativePath = reference.relativePath
        val bucketId = reference.bucketId
        if (relativePath.isNullOrEmpty() && bucketId == null) return null

        val selection: String
        val selectionArgs: Array<String>
        if (bucketId != null) {
            selection = "${MediaStore.Audio.Media.BUCKET_ID} = ?"
            selectionArgs = arrayOf(bucketId.toString())
        } else {
            selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
            selectionArgs = arrayOf(relativePath!!)
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
        )

        val rows = mutableListOf<IndexedFolderRow>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "LOWER(${MediaStore.Audio.Media.DISPLAY_NAME}) ASC"
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val artistId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val year = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val data = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val mime = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(id)
                    rows.add(
                        IndexedFolderRow(
                            id = mediaId,
                            uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId).toString(),
                            displayName = cursor.getString(displayName),
                            title = cursor.getString(title),
                            artist = cursor.getString(artist),
                            artistId = cursor.getLong(artistId).takeIf { it > 0L },
                            album = cursor.getString(album),
                            albumId = cursor.getLong(albumId).takeIf { it > 0L },
                            durationMs = cursor.getLong(duration).takeIf { it > 0L },
                            track = cursor.getInt(track),
                            year = cursor.getInt(year),
                            dateAddedSeconds = cursor.getLong(dateAdded).takeIf { it > 0L },
                            dataPath = cursor.getString(data),
                            mimeType = cursor.getString(mime),
                        )
                    )
                }
            }
        } catch (securityException: SecurityException) {
            Timber.w(securityException, "Unable to load sibling songs for uri: $originalUri")
            return null
        } catch (illegalArgumentException: IllegalArgumentException) {
            Timber.w(illegalArgumentException, "Invalid query while loading sibling songs for uri: $originalUri")
            return null
        }
        return rows
    }

    /** A song as the index describes it. The same shape the library gives a MediaStore song. */
    private fun indexedSong(row: IndexedFolderRow): Song {
        val path = row.dataPath?.takeIf { it.isNotBlank() }
        return Song(
            id = row.id.toString(),
            title = row.title?.takeIf { it.isNotBlank() }
                ?: row.displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: "Unknown Title",
            artist = row.artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist",
            artistId = row.artistId ?: -1,
            album = row.album?.takeIf { it.isNotBlank() } ?: "Unknown Album",
            albumId = row.albumId ?: -1,
            albumArtist = null,
            path = path ?: row.uri,
            contentUriString = row.uri,
            albumArtUriString = null,
            duration = row.durationMs ?: 0L,
            trackNumber = row.track ?: 0,
            year = row.year ?: 0,
            dateAdded = row.dateAddedSeconds ?: (System.currentTimeMillis() / 1000),
            mimeType = row.mimeType,
            bitrate = null,
            sampleRate = null,
        )
    }

    suspend fun buildExternalSongFromUri(
        uri: Uri,
        captureFolderInfo: Boolean = true
    ): ExternalSongLoadResult? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        var displayName: String? = null
        var relativePath: String? = null
        var bucketId: Long? = null
        var storeTitle: String? = null
        var storeArtist: String? = null
        var storeAlbum: String? = null
        var storeDuration: Long? = null
        var storeTrack: Int? = null
        var storeYear: Int? = null
        var storeDateAddedSeconds: Long? = null
        var storeDataPath: String? = null
        var storeMediaId: Long? = null

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            OpenableColumns.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.BUCKET_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATA
        )

        try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val mediaIdIndex = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    if (mediaIdIndex != -1) storeMediaId = cursor.getLong(mediaIdIndex)

                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) displayName = cursor.getString(displayNameIndex)

                    val relativePathIndex = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    if (relativePathIndex != -1) relativePath = cursor.getString(relativePathIndex)

                    val bucketIdIndex = cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_ID)
                    if (bucketIdIndex != -1) bucketId = cursor.getLong(bucketIdIndex)

                    val durationIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    if (durationIndex != -1) storeDuration = cursor.getLong(durationIndex)

                    val titleIndex = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    if (titleIndex != -1) storeTitle = cursor.getString(titleIndex)

                    val artistIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    if (artistIndex != -1) storeArtist = cursor.getString(artistIndex)

                    val albumIndex = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                    if (albumIndex != -1) storeAlbum = cursor.getString(albumIndex)
                    
                    val trackIndex = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
                    if (trackIndex != -1) storeTrack = cursor.getInt(trackIndex)
                    
                    val yearIndex = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
                    if (yearIndex != -1) storeYear = cursor.getInt(yearIndex)
                    
                    val dateAddedIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                    if (dateAddedIndex != -1) storeDateAddedSeconds = cursor.getLong(dateAddedIndex)

                    val dataPathIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (dataPathIndex != -1) storeDataPath = cursor.getString(dataPathIndex)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error querying MediaStore for uri: $uri")
        }

        val metadata = AudioMetadataReader.read(context, uri) ?: return@withContext null

        val albumArtUriString = metadata.artwork?.let { artwork ->
             if (isValidImageData(artwork.bytes)) {
                 persistExternalAlbumArt(uri, artwork.bytes, artwork.mimeType)
             } else null
        }

        val directFilePath = resolveDirectFilePath(uri, storeDataPath)

        val finalTitle = storeTitle ?: metadata.title ?: displayName ?: "Unknown Title"
        val finalArtist = storeArtist ?: metadata.artist ?: "Unknown Artist"
        val finalAlbum = storeAlbum ?: metadata.album ?: "Unknown Album"
        // This is the path taken by files opened from outside the library, and it is the one where
        // the MediaStore query above finds no row at all for a file:// URI. So when neither the
        // store nor the tag reader can measure the file — a Java tagger cannot time every
        // container — the platform's own reader is asked before falling back to nothing. The
        // player measures the track for itself once it is playing; this only covers the moment
        // before that, so the row is not filled with a length nobody knows.
        val finalDuration = storeDuration?.takeIf { it > 0 }
            ?: metadata.durationMs?.takeIf { it > 0 }
            ?: platformDurationMs(uri, directFilePath)
            ?: 0L

        val mimeType = context.contentResolver.getType(uri) ?: "audio/*"
        val cachedPlaybackFile = if (directFilePath == null && uri.scheme == "content") {
            persistExternalAudioForPlayback(uri)
        } else {
            null
        }
        val playbackContentUriString = when {
            cachedPlaybackFile != null -> Uri.fromFile(cachedPlaybackFile).toString()
            directFilePath != null -> Uri.fromFile(File(directFilePath)).toString()
            else -> uri.toString()
        }
        val playbackPath = cachedPlaybackFile?.absolutePath
            ?: directFilePath
            ?: uri.toString()

        val mediaStoreSongId = storeMediaId ?: uri.mediaStoreAudioId()
        val songId = mediaStoreSongId?.toString() ?: "external:${uri}"
        
        val song = Song(
            id = songId, 
            title = finalTitle,
            artist = finalArtist,
            artistId = -1,
            album = finalAlbum,
            albumId = -1,
            albumArtist = metadata.albumArtist,
            path = playbackPath,
            contentUriString = playbackContentUriString,
            albumArtUriString = albumArtUriString,
            duration = finalDuration,
            genre = metadata.genre,
            trackNumber = storeTrack ?: metadata.trackNumber ?: 0,
            year = storeYear ?: metadata.year ?: 0,
            dateAdded = storeDateAddedSeconds ?: (System.currentTimeMillis() / 1000),
            mimeType = mimeType,
            bitrate = metadata.bitrate,
            sampleRate = metadata.sampleRate
        )
        
        ExternalSongLoadResult(
            song = song,
            relativePath = if (captureFolderInfo) relativePath else null,
            bucketId = if (captureFolderInfo) bucketId else null,
            displayName = displayName
        )
    }

    private fun Uri.mediaStoreAudioId(): Long? {
        val normalizedUri = toString().substringBefore('?').substringBefore('#')
        if (
            !normalizedUri.startsWith("content://media/", ignoreCase = true) ||
            !normalizedUri.contains("/audio/media/", ignoreCase = true)
        ) {
            return null
        }

        return normalizedUri.substringAfterLast('/').toLongOrNull()?.takeIf { it > 0L }
    }

    /**
     * The platform's own reading of the file's length, or null when it cannot say.
     *
     * Asked only when the tag reader produced nothing, and never guessed: a null here becomes a
     * genuinely unknown duration rather than a number nobody measured.
     */
    private fun platformDurationMs(uri: Uri, directFilePath: String?): Long? = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            when {
                !directFilePath.isNullOrBlank() -> retriever.setDataSource(directFilePath)
                else -> retriever.setDataSource(context, uri)
            }
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        } finally {
            retriever.release()
        }
    }.onFailure {
        Timber.w(it, "Platform duration unavailable for: $uri")
    }.getOrNull()

    private fun resolveDirectFilePath(uri: Uri, storeDataPath: String?): String? {
        storeDataPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() && it.canRead() }
            ?.absolutePath
            ?.let { return it }

        if (uri.scheme == "file") {
            return uri.path
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { it.exists() && it.canRead() }
                ?.absolutePath
        }

        return null
    }

    private fun persistExternalAudioForPlayback(uri: Uri): File? {
        return runCatching {
            val directory = File(context.cacheDir, "external_audio")
            if (!directory.exists()) {
                directory.mkdirs()
            }

            val fileNamePrefix = "audio_${uri.toString().hashCode()}."
            directory.listFiles { file ->
                file.name.startsWith(fileNamePrefix)
            }?.forEach { it.delete() }

            val extension = resolveAudioFileExtension(context, uri).trimStart('.').ifBlank { "mp3" }
            val file = File(directory, "$fileNamePrefix$extension")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@runCatching null

            file.takeIf { it.length() > 0L }
        }.onFailure { throwable ->
            Timber.w(throwable, "Unable to persist external audio for playback uri: $uri")
        }.getOrNull()
    }

    private fun persistExternalAlbumArt(uri: Uri, data: ByteArray, mimeType: String? = null): String? {
        return runCatching {
            if (!isValidImageData(data)) {
                throw IllegalArgumentException("Invalid embedded album art for uri: $uri")
            }
            val directory = File(context.cacheDir, "external_artwork")
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val resolvedMimeType = mimeType ?: guessImageMimeType(data)
            val extension = imageExtensionFromMimeType(resolvedMimeType) ?: "jpg"
            val fileNamePrefix = "art_${uri.toString().hashCode()}."
            directory.listFiles { file ->
                file.name.startsWith(fileNamePrefix)
            }?.forEach { it.delete() }
            val fileName = "$fileNamePrefix$extension"
            val file = File(directory, fileName)
            file.outputStream().use { output ->
                output.write(data)
            }
            Uri.fromFile(file).toString()
        }.onFailure { throwable ->
            Timber.w(throwable, "Unable to persist album art for external uri: $uri")
        }.getOrNull()
    }
}
