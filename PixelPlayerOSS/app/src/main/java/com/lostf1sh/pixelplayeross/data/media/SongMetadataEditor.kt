package com.lostf1sh.pixelplayeross.data.media

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.EvidenceRow
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.SongArtistCrossRef
import com.lostf1sh.pixelplayeross.data.database.serializeArtistRefs
import com.lostf1sh.pixelplayeross.data.identity.EditedEvidence
import com.lostf1sh.pixelplayeross.data.identity.EditedFileFacts
import com.lostf1sh.pixelplayeross.data.identity.ItemEvidence
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.worker.collectArtistNames
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.gagravarr.opus.OpusFile
import org.gagravarr.opus.OpusTags
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v23Frame
import org.jaudiotagger.tag.id3.ID3v23Frames
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Frames
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.jaudiotagger.tag.wav.WavTag
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "SongMetadataEditor"

/**
 * Error types for metadata editing operations
 */
enum class MetadataEditError {
    FILE_NOT_FOUND,
    NO_WRITE_PERMISSION,
    INVALID_INPUT,
    UNSUPPORTED_FORMAT,
    TAGLIB_ERROR,
    TIMEOUT,
    FILE_CORRUPTED,
    IO_ERROR,
    UNKNOWN
}

private const val REPLAYGAIN_TRACK_GAIN_KEY = "REPLAYGAIN_TRACK_GAIN"
private const val REPLAYGAIN_ALBUM_GAIN_KEY = "REPLAYGAIN_ALBUM_GAIN"
private const val MP4_REVERSE_DNS_ISSUER = "com.apple.iTunes"

internal sealed interface ReplayGainUpdate {
    data object Keep : ReplayGainUpdate
    data object Clear : ReplayGainUpdate
    data class Set(val formattedValue: String) : ReplayGainUpdate
}


/** What a writer could not do for the file in front of it. One per edit; a writer that is retried resets it. */
internal class WriteReport {
    var coverNote: CoverNote? = null
}

/** One edit of a file's tags, in the terms every tag format shares. [applyTo] writes it to a JAudioTagger [Tag]. */
internal class TagEdit(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val composer: String?,
    val genre: String,
    val lyrics: String,
    val trackNumber: Int,
    val discNumber: Int?,
    val replayGainTrack: ReplayGainUpdate,
    val replayGainAlbum: ReplayGainUpdate,
    val cover: CoverArtUpdate?,
    val date: String?,
) {
    fun applyTo(tag: Tag, report: WriteReport) {
        tag.setField(FieldKey.TITLE, title)
        tag.setField(FieldKey.ARTIST, artist)
        tag.setField(FieldKey.ALBUM, album)
        if (!albumArtist.isNullOrBlank()) {
            tag.setField(FieldKey.ALBUM_ARTIST, albumArtist)
        }
        if (!composer.isNullOrBlank()) {
            tag.setField(FieldKey.COMPOSER, composer)
        } else {
            tag.deleteField(FieldKey.COMPOSER)
        }

        if (genre.isNotBlank()) {
            tag.setField(FieldKey.GENRE, genre)
        } else {
            tag.deleteField(FieldKey.GENRE)
        }

        if (lyrics.isNotBlank()) {
            tag.setField(FieldKey.LYRICS, lyrics)
        } else {
            tag.deleteField(FieldKey.LYRICS)
        }

        tag.setField(FieldKey.TRACK, trackNumber.toString())
        if (discNumber != null && discNumber > 0) {
            tag.setField(FieldKey.DISC_NO, discNumber.toString())
        } else {
            tag.deleteField(FieldKey.DISC_NO)
        }
        tag.applyReplayGainUpdate(REPLAYGAIN_TRACK_GAIN_KEY, replayGainTrack)
        tag.applyReplayGainUpdate(REPLAYGAIN_ALBUM_GAIN_KEY, replayGainAlbum)
        if (date != null) {
            if (date.isBlank()) tag.deleteField(FieldKey.YEAR) else tag.setField(FieldKey.YEAR, date.trim())
        }
        cover?.let { report.coverNote = tag.applyCover(it) }
    }
}

/**
 * B57-fix1 5.2. AndroidArtwork is JAudioTagger's picture for Android, which has no ImageIO: its [setImageFromData] throws. FLAC and Vorbis-comment tags call it
 * before they read the picture's width and height, so every cover for those formats failed (and, because the old cover was deleted first, was lost). The caller
 * has already set the width and height, so there is nothing to decode.
 */
private class DirectArtwork : AndroidArtwork() {
    override fun setImageFromData(): Boolean = true
}

/**
 * Puts a cover in [this] tag, or takes it out. The new picture is **built before the old one is removed**: a cover that cannot be built changes nothing, and the
 * file keeps the cover it had. Returns why the cover was not written, or null when it was.
 */
private fun Tag.applyCover(update: CoverArtUpdate): CoverNote? {
    if (update.isDeletion) {
        try {
            deleteArtworkField()
            Timber.tag(TAG).d("JAUDIOTAGGER: Removed cover art")
        } catch (e: UnsupportedOperationException) {
            Timber.tag(TAG).d("JAUDIOTAGGER: this tag type holds no cover to remove")
        }
        return null
    }
    val bytes = update.bytes
    if (bytes == null) {
        Timber.tag(TAG).w("JAUDIOTAGGER: Ignoring invalid CoverArtUpdate with no bytes and no deletion flag")
        return null
    }
    if (bytes.isEmpty()) return CoverNote.UNREADABLE_IMAGE
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val artwork = DirectArtwork().apply {
        binaryData = bytes
        mimeType = update.mimeType.ifBlank { "image/jpeg" }
        pictureType = 3
        description = ""
        width = bounds.outWidth.coerceAtLeast(0)
        height = bounds.outHeight.coerceAtLeast(0)
    }
    val field = try {
        createField(artwork)
    } catch (e: UnsupportedOperationException) {
        Timber.tag(TAG).w(e, "JAUDIOTAGGER: this tag type cannot hold a cover")
        return CoverNote.NOT_SUPPORTED
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "JAUDIOTAGGER: Failed to create artwork from bytes")
        return CoverNote.WRITER_REFUSED
    }
    deleteArtworkField()
    setField(field)
    Timber.tag(TAG).d("JAUDIOTAGGER: Embedded new cover art (${update.mimeType}, ${bounds.outWidth}x${bounds.outHeight})")
    return null
}

/**
 * Asks Android to index a file again and, unlike a fire-and-forget scan request, **waits** for the answer
 * (bounded), so the editor can read back what MediaStore now reports before it records the file's evidence.
 */
fun interface MediaRescanner {
    suspend fun rescan(path: String)
}

internal class AndroidMediaRescanner(
    private val context: Context,
    private val timeoutMs: Long = RESCAN_TIMEOUT_MS,
) : MediaRescanner {
    override suspend fun rescan(path: String) {
        if (!File(path).exists()) {
            Timber.tag(TAG).w("RESCAN: file does not exist")
            return
        }
        val answered = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { continuation ->
                try {
                    MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ ->
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "RESCAN: could not start")
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        if (answered == null) Timber.tag(TAG).w("RESCAN: no answer in ${timeoutMs} ms")
    }

    companion object {
        const val RESCAN_TIMEOUT_MS = 4_000L
    }
}

class SongMetadataEditor(
    private val context: Context,
    private val musicDao: MusicDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    /** Off only on a host that cannot load TagLib's native library (JVM tests): the writer then falls back to JAudioTagger, as it does on the phone when TagLib declines a file. */
    private val tagLibEnabled: Boolean = true,
    private val rescanner: MediaRescanner = AndroidMediaRescanner(context),
) {

    /** The tag writers, each with the formats it is responsible for. */
    enum class Writer { VORBIS_JAVA, WAV, J_AUDIO_TAGGER, TAG_LIB }

    companion object {
        /**
         * B57-fix1 5. The writers to try for a file of this container, in order; the first one whose copy passes the audio guard wins.
         * - Opus: vorbis-java (the only writer that keeps an Opus stream valid).
         * - WAV: [WavTagWriter] only. JAudioTagger corrupted the audio of some WAV layouts, so it is never a fallback here.
         * - Ogg Vorbis: JAudioTagger (TagLib is not used for it).
         * - everything else, FLAC of any resolution included: TagLib, then JAudioTagger. On a host without TagLib's native library (JVM tests) JAudioTagger alone.
         */
        internal fun writersFor(container: String, tagLibAvailable: Boolean): List<Writer> = when {
            container == "opus" -> listOf(Writer.VORBIS_JAVA)
            container == "wav" -> listOf(Writer.WAV)
            container == "ogg" -> listOf(Writer.J_AUDIO_TAGGER)
            tagLibAvailable -> listOf(Writer.TAG_LIB, Writer.J_AUDIO_TAGGER)
            else -> listOf(Writer.J_AUDIO_TAGGER)
        }
    }

    /**
     * Maximum allowed length for metadata fields to prevent buffer overflows
     */
    private object MetadataLimits {
        const val MAX_TITLE_LENGTH = 500
        const val MAX_ARTIST_LENGTH = 500
        const val MAX_ALBUM_LENGTH = 500
        const val MAX_ALBUM_ARTIST_LENGTH = 500
        const val MAX_COMPOSER_LENGTH = 500
        const val MAX_GENRE_LENGTH = 100
        const val MAX_LYRICS_LENGTH = com.lostf1sh.pixelplayeross.data.lyrics.LyricsTextPolicy.MAX_CHARACTERS
    }

    /**
     * Validates metadata input and returns error message if invalid
     */
    private fun validateMetadataInput(
        title: String,
        artist: String,
        album: String,
        albumArtist: String?,
        composer: String?,
        genre: String,
        lyrics: String
    ): String? {
        if (title.isBlank()) return "Title cannot be empty"
        if (title.length > MetadataLimits.MAX_TITLE_LENGTH) return "Title too long"
        if (artist.length > MetadataLimits.MAX_ARTIST_LENGTH) return "Artist name too long"
        if (album.length > MetadataLimits.MAX_ALBUM_LENGTH) return "Album name too long"
        if (!albumArtist.isNullOrBlank() && albumArtist.length > MetadataLimits.MAX_ALBUM_ARTIST_LENGTH) return "Album artist name too long"
        if (!composer.isNullOrBlank() && composer.length > MetadataLimits.MAX_COMPOSER_LENGTH) return "Composer name too long"
        if (genre.length > MetadataLimits.MAX_GENRE_LENGTH) return "Genre too long"
        if (lyrics.length > MetadataLimits.MAX_LYRICS_LENGTH) return "Lyrics too long"
        return null
    }

    private fun validateDate(date: String?): String? {
        if (date == null || date.isBlank()) return null
        return if (EditableDate.isValid(date)) null else "Date must be YYYY, YYYY-MM or YYYY-MM-DD"
    }

    private fun parseReplayGainUpdate(rawValue: String?, fieldName: String): Result<ReplayGainUpdate> {
        if (rawValue == null) return Result.success(ReplayGainUpdate.Keep)

        val trimmedValue = rawValue.trim()
        if (trimmedValue.isEmpty()) return Result.success(ReplayGainUpdate.Clear)

        val normalizedValue = trimmedValue
            .replace(',', '.')
            .replace(Regex("(?i)\\s*d\\s*b\\s*$"), "")
            .trim()

        val gainDb = normalizedValue.toFloatOrNull()
            ?: return Result.failure(IllegalArgumentException("$fieldName must be a valid dB value"))

        return Result.success(
            ReplayGainUpdate.Set(
                formattedValue = String.format(Locale.US, "%.2f dB", gainDb)
            )
        )
    }

    private suspend fun updateSongArtistMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String?,
        trackNumber: Int,
        discNumber: Int?,
        libraryItemId: String?,
        evidence: ItemEvidence,
        fileSize: Long,
        year: Int?,
    ) {
        val existingArtists = musicDao.getAllArtistsListRaw()
        val existingByNormalizedName =
            existingArtists.associateBy { it.name.trim().lowercase(Locale.ROOT) }.toMutableMap()
        var nextArtistId = (musicDao.getMaxArtistId() ?: 0L) + 1L

        val artistDelimiters = userPreferencesRepository.artistDelimitersFlow.first()
        val wordDelimiters = userPreferencesRepository.artistWordDelimitersFlow.first()
        val extractFromTitle = userPreferencesRepository.extractArtistsFromTitleFlow.first()

        val artistNames =
            collectArtistNames(
                rawArtistName = artist,
                title = title,
                artistDelimiters = artistDelimiters,
                wordDelimiters = wordDelimiters,
                extractFromTitle = extractFromTitle
            ).ifEmpty {
                listOf(artist.trim().ifEmpty { "Unknown Artist" })
            }

        val artistsToEnsure = mutableListOf<ArtistEntity>()
        val artistRefs = artistNames.mapIndexed { index, rawName ->
            val normalizedName = rawName.trim()
            val normalizedKey = normalizedName.lowercase(Locale.ROOT)
            val existingArtist = existingByNormalizedName[normalizedKey]
            val artistId =
                if (existingArtist != null) {
                    existingArtist.id
                } else {
                    val newId = nextArtistId++
                    val newArtist = ArtistEntity(
                        id = newId,
                        name = normalizedName,
                        trackCount = 0,
                        imageUrl = null,
                        customImageUri = null
                    )
                    existingByNormalizedName[normalizedKey] = newArtist
                    artistsToEnsure += newArtist
                    newId
                }

            ArtistRef(
                id = artistId,
                name = normalizedName,
                isPrimary = index == 0
            )
        }.filter { it.name.isNotEmpty() }

        val primaryArtistId = artistRefs.firstOrNull()?.id ?: -1L
        val artistsJson = serializeArtistRefs(artistRefs)
        val crossRefs = artistRefs.map { ref ->
            SongArtistCrossRef(
                songId = songId,
                artistId = ref.id,
                isPrimary = ref.isPrimary
            )
        }

        // The `songs` row, the artist links and the item's evidence are one transaction (B57 1.1): a
        // write the library row does not know about is what made the next scan mint a new identity.
        musicDao.commitEditedSongMetadata(
            songId = songId,
            title = title,
            artist = artist,
            artistId = primaryArtistId,
            artistsJson = artistsJson,
            album = album,
            genre = genre,
            trackNumber = trackNumber,
            discNumber = discNumber,
            artistsToEnsure = artistsToEnsure,
            crossRefs = crossRefs,
            libraryItemId = libraryItemId,
            evidence = evidence,
            fileSize = fileSize,
            year = year,
        )
    }

    suspend fun editSongMetadata(
        songId: Long,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newAlbumArtist: String? = null,
        newComposer: String? = null,
        newGenre: String,
        newLyrics: String,
        newTrackNumber: Int,
        newDiscNumber: Int?,
        newReplayGainTrackGainDb: String? = null,
        newReplayGainAlbumGainDb: String? = null,
        coverArtUpdate: CoverArtUpdate? = null,
        /** B57: the file's date tag. null = leave as it is, blank = remove it, else `YYYY`, `YYYY-MM` or `YYYY-MM-DD`. */
        newDate: String? = null,
    ): SongMetadataEditResult = withContext(Dispatchers.IO) {
        val validationError = validateMetadataInput(newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics)
            ?: validateDate(newDate)
        if (validationError != null) {
            Timber.w("Metadata validation failed: $validationError")
            return@withContext SongMetadataEditResult(
                success = false,
                updatedAlbumArtUri = null,
                error = MetadataEditError.INVALID_INPUT,
                errorMessage = validationError
            )
        }

        try {
            val trimmedLyrics = newLyrics.trim()
            val trimmedGenre = newGenre.trim()
            val normalizedGenre = trimmedGenre.takeIf { it.isNotBlank() }
            val replayGainTrackUpdate = parseReplayGainUpdate(
                rawValue = newReplayGainTrackGainDb,
                fieldName = "Track ReplayGain"
            ).getOrElse { error ->
                return@withContext SongMetadataEditResult(
                    success = false,
                    updatedAlbumArtUri = null,
                    error = MetadataEditError.INVALID_INPUT,
                    errorMessage = error.message ?: "Invalid Track ReplayGain value"
                )
            }
            val replayGainAlbumUpdate = parseReplayGainUpdate(
                rawValue = newReplayGainAlbumGainDb,
                fieldName = "Album ReplayGain"
            ).getOrElse { error ->
                return@withContext SongMetadataEditResult(
                    success = false,
                    updatedAlbumArtUri = null,
                    error = MetadataEditError.INVALID_INPUT,
                    errorMessage = error.message ?: "Invalid Album ReplayGain value"
                )
            }

            val filePath = getFilePathFromMediaStore(songId)

            if (filePath.isNullOrBlank()) {
                Timber.tag(TAG).e("Could not get file path for songId: $songId")
                return@withContext SongMetadataEditResult(
                    success = false,
                    updatedAlbumArtUri = null,
                    error = MetadataEditError.FILE_NOT_FOUND,
                    errorMessage = "Could not find file in media library"
                )
            }

            val finalFilePath = filePath
            val extension = finalFilePath.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val detectedContainer = if (finalFilePath.isNotBlank() && File(finalFilePath).exists()) {
                detectContainerFormat(finalFilePath)
            } else {
                DetectedContainer.UNKNOWN
            }
            val effectiveExtension = when {
                detectedContainer == DetectedContainer.OGG_OPUS -> {
                    if (extension != detectedContainer.canonicalExtension) {
                        Timber.tag(TAG).d(
                            "METADATA_EDIT: Detected Ogg Opus stream in .$extension file. " +
                                "Routing write through the Opus-safe metadata path."
                        )
                    }
                    detectedContainer.canonicalExtension
                }
                detectedContainer != DetectedContainer.UNKNOWN &&
                    detectedContainer.canonicalExtension != extension -> {
                    Timber.tag(TAG).w(
                        "METADATA_EDIT: Extension mismatch — filename has .$extension but magic bytes " +
                            "indicate ${detectedContainer.name}. Routing write as .${detectedContainer.canonicalExtension} " +
                            "via temp-file swap to avoid container corruption."
                    )
                    detectedContainer.canonicalExtension
                }
                else -> extension
            }
            val needsExtensionSwap =
                effectiveExtension != extension && detectedContainer != DetectedContainer.OGG_OPUS
            val fileExists = finalFilePath.isNotBlank() && File(finalFilePath).exists()

            // B57-fix1 5: what a writer could not do is reported, never silently dropped.
            val report = WriteReport()
            val tagEdit = TagEdit(
                title = newTitle,
                artist = newArtist,
                album = newAlbum,
                albumArtist = newAlbumArtist,
                composer = newComposer,
                genre = trimmedGenre,
                lyrics = trimmedLyrics,
                trackNumber = newTrackNumber,
                discNumber = newDiscNumber,
                replayGainTrack = replayGainTrackUpdate,
                replayGainAlbum = replayGainAlbumUpdate,
                cover = coverArtUpdate,
                date = newDate,
            )
            val writerFor: (Writer) -> (String) -> Boolean = { writer ->
                { path ->
                    Timber.tag(TAG).d("METADATA_EDIT: Using $writer for $effectiveExtension: $path")
                    when (writer) {
                        Writer.VORBIS_JAVA -> updateFileMetadataWithVorbisJava(
                            filePath = path,
                            newTitle = newTitle,
                            newArtist = newArtist,
                            newAlbum = newAlbum,
                            newAlbumArtist = newAlbumArtist,
                            newComposer = newComposer,
                            newGenre = trimmedGenre,
                            newLyrics = trimmedLyrics,
                            newTrackNumber = newTrackNumber,
                            newDiscNumber = newDiscNumber,
                            replayGainTrackUpdate = replayGainTrackUpdate,
                            replayGainAlbumUpdate = replayGainAlbumUpdate,
                            coverArtUpdate = coverArtUpdate,
                            newDate = newDate
                        )
                        Writer.WAV -> updateWavFile(path, tagEdit, report)
                        Writer.J_AUDIO_TAGGER -> updateFileMetadataWithJAudioTagger(path, tagEdit, report)
                        Writer.TAG_LIB -> updateFileMetadataWithTagLib(
                            filePath = path,
                            newTitle = newTitle,
                            newArtist = newArtist,
                            newAlbum = newAlbum,
                            newAlbumArtist = newAlbumArtist,
                            newComposer = newComposer,
                            newGenre = trimmedGenre,
                            newLyrics = trimmedLyrics,
                            newTrackNumber = newTrackNumber,
                            newDiscNumber = newDiscNumber,
                            replayGainTrackUpdate = replayGainTrackUpdate,
                            replayGainAlbumUpdate = replayGainAlbumUpdate,
                            coverArtUpdate = coverArtUpdate,
                            newDate = newDate,
                            report = report,
                        )
                    }
                }
            }

            // B57 1.1. Parked BEFORE the file is touched. If the evidence cannot be parked, nothing is
            // written. From here until the commit below, an interruption (process death, a failing
            // transaction) leaves the item with no stored evidence, and the resolver keeps an item with
            // no evidence and lets the next scan record it, so the identity survives a half-finished edit.
            val parked: EvidenceRow? = if (fileExists) {
                try {
                    musicDao.parkEvidenceForSong(songId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "METADATA_EDIT: could not park the item's identity evidence; the file is not written")
                    return@withContext SongMetadataEditResult(
                        success = false,
                        updatedAlbumArtUri = null,
                        error = MetadataEditError.IO_ERROR,
                        errorMessage = "Could not prepare the library for the edit"
                    )
                }
            } else {
                null
            }
            val fileBefore = File(finalFilePath).let { it.length() to it.lastModified() }

            val fileUpdateSuccess = if (!fileExists) {
                Timber.tag(TAG).e("METADATA_EDIT: File does not exist: $finalFilePath")
                false
            } else {
                // Always on a copy that then replaces the original (sibling temp + fsync + rename),
                // never in place: TagLib saved over the open original, so an interruption mid-save
                // could corrupt the audio (AUD-034). With a matching extension this is the same
                // path, just without the extension change.
                if (needsExtensionSwap) {
                    Timber.tag(TAG).d("METADATA_EDIT: Writing via .$effectiveExtension copy for $finalFilePath")
                }
                // B57-fix1 5.3: each writer gets its own fresh copy of the original, and a copy whose audio is not the
                // original's is thrown away before it can replace anything (FLAC and WAV: AudioPayloadGuard). A writer
                // that fails or fails the guard hands over to the next one; the last failure is the edit's failure.
                var written = false
                for (writer in writersFor(effectiveExtension, tagLibEnabled)) {
                    report.coverNote = null
                    written = writeMetadataViaExtensionSwap(finalFilePath, effectiveExtension, writerFor(writer))
                    if (written) break
                    Timber.tag(TAG).w("METADATA_EDIT: $writer did not produce a usable copy of $finalFilePath")
                }
                written
            }

            if (!fileUpdateSuccess) {
                Timber.tag(TAG).e("Failed to update file metadata for songId: $songId")
                // The evidence goes back only if the file is demonstrably as it was; otherwise it stays
                // "none" and the next scan records what is there.
                val untouched = File(finalFilePath).let { it.exists() && it.length() == fileBefore.first && it.lastModified() == fileBefore.second }
                if (parked != null && untouched) restoreEvidence(songId, parked)
                return@withContext SongMetadataEditResult(
                    success = false,
                    updatedAlbumArtUri = null,
                    error = MetadataEditError.TAGLIB_ERROR,
                    errorMessage = "Failed to write metadata to file"
                )
            }

            val mediaStoreSuccess = updateMediaStoreMetadata(
                songId = songId,
                title = newTitle,
                artist = newArtist,
                album = newAlbum,
                albumArtist = newAlbumArtist,
                genre = trimmedGenre,
                trackNumber = newTrackNumber,
                discNumber = newDiscNumber
            )
            if (!mediaStoreSuccess) {
                Timber.w("MediaStore update failed, but file was updated for songId: $songId")
            }

            // Let Android index the file as it is now and wait (bounded) for it, then read back what a
            // scan will read: that, not a prediction, is what the item's evidence has to equal.
            if (finalFilePath.isNotBlank()) {
                rescanner.rescan(finalFilePath)
            }
            val newFileSize = File(finalFilePath).length()
            val newEvidence = settleEvidence(songId, newFileSize)

            var storedCoverArtUri: String? = null
            updateSongArtistMetadata(
                songId = songId,
                title = newTitle,
                artist = newArtist,
                album = newAlbum,
                genre = normalizedGenre,
                trackNumber = newTrackNumber,
                discNumber = newDiscNumber,
                libraryItemId = parked?.libraryItemId,
                year = if (newDate != null) EditableDate.yearOf(newDate) ?: 0 else null,
                evidence = newEvidence,
                // Unconfirmed evidence stays "none" in both halves: the size stays blanked too (0 = unknown),
                // otherwise the scan would derive a signature from it.
                fileSize = if (newEvidence.isEmpty) 0L else newFileSize,
            )

            // A cover the writer could not put in the file changes nothing about the song's art: the old cover is still there.
            if (report.coverNote == null) {
                coverArtUpdate?.let {
                    AlbumArtUtils.clearCacheForSong(context, songId)
                    storedCoverArtUri = if (it.isDeletion) null else LocalArtworkUri.buildSongUriWithTimestamp(songId)
                    musicDao.updateSongAlbumArt(songId, storedCoverArtUri)
                }
            } else {
                Timber.tag(TAG).w("METADATA_EDIT: saved without the cover (${report.coverNote}) for songId: $songId")
            }

            Timber.tag(TAG).d("METADATA_EDIT: Successfully updated metadata for songId: $songId")
            SongMetadataEditResult(success = true, updatedAlbumArtUri = storedCoverArtUri, coverNote = report.coverNote)
        } catch (e: RecoverableSecurityException) {
            SongMetadataEditResult(
                success = false,
                updatedAlbumArtUri = null,
                error = MetadataEditError.NO_WRITE_PERMISSION,
                errorMessage = e.localizedMessage,
                permissionIntentSender = e.userAction.actionIntent.intentSender,
            )
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception editing metadata for songId: $songId")
            SongMetadataEditResult(
                success = false,
                updatedAlbumArtUri = null,
                error = MetadataEditError.NO_WRITE_PERMISSION,
                errorMessage = "Permission denied: ${e.localizedMessage}"
            )
        } catch (e: IOException) {
            Timber.e(e, "IO exception editing metadata for songId: $songId")
            SongMetadataEditResult(
                success = false,
                updatedAlbumArtUri = null,
                error = MetadataEditError.IO_ERROR,
                errorMessage = "Error accessing file: ${e.localizedMessage}"
            )
        } catch (e: OutOfMemoryError) {
            Timber.e(e, "OOM editing metadata for songId: $songId")
            SongMetadataEditResult(
                success = false,
                updatedAlbumArtUri = null,
                error = MetadataEditError.FILE_CORRUPTED,
                errorMessage = "File too large or corrupted"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to update metadata for songId: $songId")
            val errorType = when {
                e.message?.contains("corrupt", ignoreCase = true) == true -> MetadataEditError.FILE_CORRUPTED
                e.message?.contains("unsupported", ignoreCase = true) == true -> MetadataEditError.UNSUPPORTED_FORMAT
                else -> MetadataEditError.UNKNOWN
            }
            SongMetadataEditResult(
                success = false,
                updatedAlbumArtUri = null,
                error = errorType,
                errorMessage = e.localizedMessage ?: "Unknown error occurred"
            )
        }
    }

    /**
     * Copies [originalPath] to a temp file with [correctExtension], runs [writer] on the temp
     * path (so JAudioTagger/TagLib pick the correct parser by extension), and streams the edited
     * bytes back into the original location. The original filename/extension are preserved so
     * MediaStore URIs stay valid; only the file's byte content is replaced.
     */
    private fun writeMetadataViaExtensionSwap(
        originalPath: String,
        correctExtension: String,
        writer: (String) -> Boolean
    ): Boolean {
        val originalFile = File(originalPath)
        val tempFile = File(
            context.cacheDir,
            "metadata_edit_${System.nanoTime()}.$correctExtension"
        )
        return try {
            originalFile.inputStream().use { input ->
                FileOutputStream(tempFile).use { out -> input.copyTo(out) }
            }
            Timber.tag(TAG).d(
                "METADATA_EDIT: Copied ${originalFile.length()} bytes to temp ${tempFile.name} for extension-corrected write"
            )
            val writeOk = writer(tempFile.absolutePath)
            if (!writeOk) {
                Timber.tag(TAG).e("METADATA_EDIT: Writer failed on temp file ${tempFile.absolutePath}")
                return false
            }
            // B57-fix1: the edited copy carries the original's audio, or it replaces nothing.
            if (!AudioPayloadGuard.sameAudio(originalFile, tempFile, correctExtension)) {
                Timber.tag(TAG).e("METADATA_EDIT: The edited copy of ${originalFile.name} does not carry the original's audio; the original is left as it is")
                return false
            }
            replaceFileContentSafely(tempFile, originalFile)
            Timber.tag(TAG).d(
                "METADATA_EDIT: Restored ${tempFile.length()} edited bytes back to $originalPath"
            )
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "METADATA_EDIT: Extension-swap pipeline failed for $originalPath")
            false
        } finally {
            if (tempFile.exists() && !tempFile.delete()) {
                Timber.tag(TAG).w("METADATA_EDIT: Could not delete temp file ${tempFile.absolutePath}")
            }
        }
    }

    /**
     * Replaces [target]'s content with [source]'s bytes as atomically as the filesystem allows:
     * the bytes are first copied (and fsynced) to a sibling temp file in [target]'s directory,
     * then renamed over [target], so an interruption (crash, ENOSPC) can never leave [target]
     * truncated. [source] typically lives in cacheDir — a different filesystem than the music
     * file — which is why a direct rename of [source] itself is not possible. Falls back to a
     * direct truncate+overwrite only when the sibling cannot be created or renamed (e.g.
     * scoped-storage grants that cover the file but not its directory).
     */
    private fun replaceFileContentSafely(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null) {
            val sibling = File(parent, "${target.name}.${System.nanoTime()}.tmp")
            try {
                source.inputStream().use { input ->
                    FileOutputStream(sibling).use { out ->
                        input.copyTo(out)
                        out.fd.sync()
                    }
                }
                if (sibling.renameTo(target)) {
                    return
                }
                Timber.tag(TAG).w(
                    "METADATA_EDIT: Atomic rename onto ${target.path} failed; falling back to in-place overwrite"
                )
            } catch (e: Exception) {
                Timber.tag(TAG).w(
                    e,
                    "METADATA_EDIT: Sibling temp write for ${target.path} failed; falling back to in-place overwrite"
                )
            } finally {
                if (sibling.exists() && !sibling.delete()) {
                    Timber.tag(TAG).w("METADATA_EDIT: Could not delete temp file ${sibling.absolutePath}")
                }
            }
        }
        source.inputStream().use { input ->
            FileOutputStream(target, false).use { out ->
                input.copyTo(out)
                out.fd.sync()
            }
        }
    }

    private enum class DetectedContainer(val canonicalExtension: String) {
        MP3("mp3"),
        MP4("m4a"),
        FLAC("flac"),
        OGG_OPUS("opus"),
        OGG_VORBIS("ogg"),
        OGG("ogg"),
        WAV("wav"),
        UNKNOWN("")
    }

    /**
     * Detects the actual audio container by reading the file's magic bytes.
     * Many files in the wild have wrong extensions (e.g. MP4/M4A served as .mp3 by YouTube rippers
     * or remote-provider cache). Writing ID3v2 tags to an MP4 container corrupts it irreversibly, so the
     * tag-writing pipeline must route by real content, not by extension.
     */
    private fun detectContainerFormat(filePath: String): DetectedContainer {
        return try {
            File(filePath).inputStream().use { input ->
                val header = ByteArray(512)
                var bytesRead = 0
                while (bytesRead < header.size) {
                    val read = input.read(header, bytesRead, header.size - bytesRead)
                    if (read <= 0) break
                    bytesRead += read
                }
                if (bytesRead < 4) return DetectedContainer.UNKNOWN
                when {
                    header[0] == 'I'.code.toByte() &&
                        header[1] == 'D'.code.toByte() &&
                        header[2] == '3'.code.toByte() -> DetectedContainer.MP3
                    header[0] == 0xFF.toByte() &&
                        (header[1].toInt() and 0xE0) == 0xE0 -> DetectedContainer.MP3
                    bytesRead >= 8 &&
                        header[4] == 'f'.code.toByte() &&
                        header[5] == 't'.code.toByte() &&
                        header[6] == 'y'.code.toByte() &&
                        header[7] == 'p'.code.toByte() -> DetectedContainer.MP4
                    header[0] == 'f'.code.toByte() &&
                        header[1] == 'L'.code.toByte() &&
                        header[2] == 'a'.code.toByte() &&
                        header[3] == 'C'.code.toByte() -> DetectedContainer.FLAC
                    header[0] == 'O'.code.toByte() &&
                        header[1] == 'g'.code.toByte() &&
                        header[2] == 'g'.code.toByte() &&
                        header[3] == 'S'.code.toByte() -> detectOggContainer(header, bytesRead)
                    bytesRead >= 12 &&
                        header[0] == 'R'.code.toByte() &&
                        header[1] == 'I'.code.toByte() &&
                        header[2] == 'F'.code.toByte() &&
                        header[3] == 'F'.code.toByte() &&
                        header[8] == 'W'.code.toByte() &&
                        header[9] == 'A'.code.toByte() &&
                        header[10] == 'V'.code.toByte() &&
                        header[11] == 'E'.code.toByte() -> DetectedContainer.WAV
                    else -> DetectedContainer.UNKNOWN
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Container detection failed for $filePath")
            DetectedContainer.UNKNOWN
        }
    }

    private fun detectOggContainer(header: ByteArray, bytesRead: Int): DetectedContainer {
        if (bytesRead < 28) return DetectedContainer.OGG

        val segmentCount = header[26].toInt() and 0xFF
        val bodyOffset = 27 + segmentCount
        if (bodyOffset >= bytesRead) return DetectedContainer.OGG

        return when {
            header.matchesAscii(bodyOffset, "OpusHead", bytesRead) -> DetectedContainer.OGG_OPUS
            header[bodyOffset] == 0x01.toByte() &&
                header.matchesAscii(bodyOffset + 1, "vorbis", bytesRead) -> DetectedContainer.OGG_VORBIS
            else -> DetectedContainer.OGG
        }
    }

    private fun ByteArray.matchesAscii(offset: Int, value: String, bytesRead: Int): Boolean {
        if (offset < 0 || offset + value.length > bytesRead) return false
        for (index in value.indices) {
            if (this[offset + index] != value[index].code.toByte()) return false
        }
        return true
    }

    private fun updateFileMetadataWithTagLib(
        filePath: String,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newAlbumArtist: String?,
        newComposer: String?,
        newGenre: String,
        newLyrics: String,
        newTrackNumber: Int,
        newDiscNumber: Int?,
        replayGainTrackUpdate: ReplayGainUpdate = ReplayGainUpdate.Keep,
        replayGainAlbumUpdate: ReplayGainUpdate = ReplayGainUpdate.Keep,
        coverArtUpdate: CoverArtUpdate? = null,
        newDate: String? = null,
        report: WriteReport = WriteReport(),
    ): Boolean {
        if (!tagLibEnabled) return false
        // B57-fix1 5.3: there used to be a refusal here for FLAC above 96 kHz or 24 bit ("may not work correctly with TagLib"). No reproducer, test or
        // report came with it, and the host probe (taglib-highres-probe.py: 16/24/32-bit up to 384 kHz) shows TagLib leaves STREAMINFO and every audio frame
        // byte for byte. The safety net is AudioPayloadGuard, which runs on the copy before it replaces the file, whatever the writer.
        
        return try {
            val audioFile = File(filePath)
            if (!audioFile.exists()) {
                Timber.tag(TAG).e("TAGLIB: Audio file does not exist: $filePath")
                return false
            }
            Timber.tag(TAG).d("TAGLIB: Opening file: $filePath")

            ParcelFileDescriptor.open(audioFile, ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                Timber.tag(TAG).d("TAGLIB: Getting existing metadata...")
                val metadataFd = fd.dup()
                val existingMetadata = TagLib.getMetadata(metadataFd.detachFd())
                Timber.tag(TAG)
                    .e("TAGLIB: Existing metadata: ${existingMetadata?.propertyMap?.keys}")
                val propertyMap = HashMap(existingMetadata?.propertyMap ?: emptyMap())

                propertyMap["TITLE"] = arrayOf(newTitle)
                propertyMap["ARTIST"] = arrayOf(newArtist)
                propertyMap["ALBUM"] = arrayOf(newAlbum)
                if (!newAlbumArtist.isNullOrBlank()) {
                    propertyMap["ALBUMARTIST"] = arrayOf(newAlbumArtist)
                }
                propertyMap.upsertOrRemove("COMPOSER", newComposer)
                propertyMap.upsertOrRemove("GENRE", newGenre)
                propertyMap.upsertOrRemove("LYRICS", newLyrics)
                propertyMap["TRACKNUMBER"] = arrayOf(newTrackNumber.toString())
                if (newDiscNumber != null && newDiscNumber > 0) {
                    propertyMap["DISCNUMBER"] = arrayOf(newDiscNumber.toString())
                } else {
                    propertyMap.remove("DISCNUMBER")
                }
                propertyMap.applyReplayGainUpdate(REPLAYGAIN_TRACK_GAIN_KEY, replayGainTrackUpdate)
                propertyMap.applyReplayGainUpdate(REPLAYGAIN_ALBUM_GAIN_KEY, replayGainAlbumUpdate)
                if (newDate != null) {
                    // DATE is the property TagLib maps to ID3 TDRC, MP4 ©day and the Vorbis DATE comment; YEAR is the old spelling some files carry.
                    propertyMap.remove("YEAR")
                    propertyMap.upsertOrRemove("DATE", newDate.trim().takeIf { it.isNotEmpty() })
                }
                Timber.tag(TAG).d("TAGLIB: Updated property map, saving...")

                val saveFd = fd.dup()
                val metadataSaved = TagLib.savePropertyMap(saveFd.detachFd(), propertyMap)
                Timber.tag(TAG).d("TAGLIB: savePropertyMap result: $metadataSaved")
                if (!metadataSaved) {
                    Timber.tag(TAG).e("TAGLIB: Failed to save metadata for file: $filePath")
                    return false
                }

                coverArtUpdate?.let { update ->
                    val pictures = if (update.isDeletion) {
                        emptyArray<Picture>()
                    } else if (update.bytes != null) {
                        arrayOf(
                            Picture(
                                data = update.bytes,
                                description = "Front Cover",
                                pictureType = "Front Cover",
                                mimeType = update.mimeType
                            )
                        )
                    } else {
                        null
                    }

                    pictures?.let {
                        val pictureFd = fd.dup()
                        val coverSaved = TagLib.savePictures(pictureFd.detachFd(), it)
                        if (!coverSaved) {
                            // B57-fix1 5.2: said, not swallowed.
                            report.coverNote = CoverNote.WRITER_REFUSED
                            Timber.tag(TAG)
                                .w("TAGLIB: Failed to save cover art, but metadata was saved")
                        } else {
                            Timber.tag(TAG)
                                .d("TAGLIB: Successfully ${if (update.isDeletion) "removed" else "embedded"} cover art")
                        }
                    }
                }
            }

            try {
                java.io.RandomAccessFile(audioFile, "rw").use { raf ->
                    raf.fd.sync()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not sync file, changes should still be persisted")
            }

            Timber.tag(TAG).d("TAGLIB: SUCCESS - Updated file metadata: ${audioFile.path}")
            true

        } catch (e: Exception) {
            Timber.tag(TAG).e("TAGLIB ERROR: ${e.javaClass.simpleName}: ${e.message}")
            Timber.e(e)
            false
        }
    }

    private fun updateFileMetadataWithJAudioTagger(filePath: String, edit: TagEdit, report: WriteReport): Boolean {
        val targetFile = File(filePath)
        
        return try {
            java.util.logging.Logger.getLogger("org.jaudiotagger").level = java.util.logging.Level.OFF
            
            val audioFile = AudioFileIO.read(targetFile)
            val tag = audioFile.tag ?: audioFile.createDefaultTag()
            edit.applyTo(tag, report)
            audioFile.commit()
            Timber.tag(TAG).d("JAUDIOTAGGER: SUCCESS - Updated file metadata: $filePath")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e("JAUDIOTAGGER ERROR: ${e.javaClass.simpleName}: ${e.message}")
            Timber.e(e)
            false
        }
    }

    /**
     * B57-fix1 5.1: a WAV file's tags, written by [WavTagWriter] (the chunks that are not tags are copied byte for byte).
     * [filePath] is the working copy; the result replaces it, so the copy is rewritten through a sibling file.
     */
    private fun updateWavFile(filePath: String, edit: TagEdit, report: WriteReport): Boolean {
        val target = File(filePath)
        val rewritten = File(target.parentFile, "${target.name}.wav-${System.nanoTime()}.tmp")
        return try {
            val info = buildMap<String, String?> {
                put(WavTagWriter.InfoId.TITLE, edit.title)
                put(WavTagWriter.InfoId.ARTIST, edit.artist)
                put(WavTagWriter.InfoId.ALBUM, edit.album)
                put(WavTagWriter.InfoId.GENRE, edit.genre)
                put(WavTagWriter.InfoId.TRACK, edit.trackNumber.takeIf { it > 0 }?.toString())
                if (edit.date != null) put(WavTagWriter.InfoId.DATE, edit.date.trim())
            }
            java.util.logging.Logger.getLogger("org.jaudiotagger").level = java.util.logging.Level.OFF
            WavTagWriter.edit(target, rewritten, info) { id3 -> edit.applyTo(id3, report) }
            rewritten.inputStream().use { input -> FileOutputStream(target, false).use { out -> input.copyTo(out); out.fd.sync() } }
            Timber.tag(TAG).d("WAV: SUCCESS - Updated file metadata: $filePath")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e("WAV ERROR: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            if (rewritten.exists() && !rewritten.delete()) Timber.tag(TAG).w("WAV: Could not delete temp file ${rewritten.absolutePath}")
        }
    }

    private fun updateFileMetadataWithVorbisJava(
        filePath: String,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newAlbumArtist: String?,
        newComposer: String?,
        newGenre: String,
        newLyrics: String,
        newTrackNumber: Int,
        newDiscNumber: Int?,
        replayGainTrackUpdate: ReplayGainUpdate = ReplayGainUpdate.Keep,
        replayGainAlbumUpdate: ReplayGainUpdate = ReplayGainUpdate.Keep,
        coverArtUpdate: CoverArtUpdate? = null,
        newDate: String? = null,
    ): Boolean {
        val audioFile = File(filePath)
        val originalExtension = audioFile.extension.ifBlank { "opus" }
        var tempFile: File? = null
        var opusFile: OpusFile? = null
        
        return try {
            if (!audioFile.exists()) {
                Timber.tag(TAG).e("VORBISJAVA: Audio file does not exist: $filePath")
                return false
            }

            Timber.tag(TAG).d("VORBISJAVA: Reading Opus file: $filePath")
            
            val sourceOpusFile = OpusFile(audioFile)
            opusFile = sourceOpusFile
            val tags = sourceOpusFile.tags ?: OpusTags()

            Timber.tag(TAG).d("VORBISJAVA: Existing tags read (${tags.allComments.size} fields)")
            
            tags.replaceSingleComment("TITLE", newTitle)
            tags.replaceSingleComment("ARTIST", newArtist)
            tags.replaceSingleComment("ALBUMARTIST", newAlbumArtist?.takeIf { it.isNotBlank() })
            tags.replaceSingleComment("COMPOSER", newComposer)
            tags.replaceSingleComment("ALBUM", newAlbum)
            tags.replaceSingleComment("GENRE", newGenre)
            tags.replaceSingleComment("LYRICS", newLyrics)
            tags.replaceSingleComment("TRACKNUMBER", newTrackNumber.takeIf { it > 0 }?.toString())
            tags.replaceSingleComment("DISCNUMBER", newDiscNumber?.takeIf { it > 0 }?.toString())
            tags.applyReplayGainUpdate(REPLAYGAIN_TRACK_GAIN_KEY, replayGainTrackUpdate)
            tags.applyReplayGainUpdate(REPLAYGAIN_ALBUM_GAIN_KEY, replayGainAlbumUpdate)
            if (newDate != null) tags.replaceSingleComment("DATE", newDate.trim().takeIf { it.isNotEmpty() })
            coverArtUpdate?.let { update ->
                tags.applyCoverArtUpdate(update)
            }

            Timber.tag(TAG).d("VORBISJAVA: Updated tags (${tags.allComments.size} fields)")
            
            tempFile = File(
                context.cacheDir,
                "metadata_edit_opus_${System.nanoTime()}.$originalExtension"
            )
            
            Timber.tag(TAG).d("VORBISJAVA: Writing to temp file: ${tempFile.path}")
            FileOutputStream(tempFile).use { fos ->
                OpusFile(fos, sourceOpusFile.info, tags).use { newOpusFile ->
                    var packet = sourceOpusFile.nextAudioPacket
                    while (packet != null) {
                        newOpusFile.writeAudioData(packet)
                        packet = sourceOpusFile.nextAudioPacket
                    }
                }
            }
            sourceOpusFile.close()
            opusFile = null
            
            if (!tempFile.exists() || tempFile.length() == 0L) {
                Timber.tag(TAG).e("VORBISJAVA: Temp file creation failed or is empty")
                return false
            }
            Timber.tag(TAG)
                .e("VORBISJAVA: Temp file size: ${tempFile.length()} bytes, original: ${audioFile.length()} bytes")
            
            replaceFileContentSafely(tempFile, audioFile)

            Timber.tag(TAG).d("VORBISJAVA: SUCCESS - Updated file metadata: ${audioFile.path}")
            true

        } catch (e: Exception) {
            Timber.tag(TAG).e("VORBISJAVA ERROR: ${e.javaClass.simpleName}: ${e.message}")
            Timber.e(e)
            false
        } finally {
            try {
                opusFile?.close()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "VORBISJAVA: Could not close source Opus file")
            }
            if (tempFile != null && tempFile.exists() && tempFile.delete() == false) {
                Timber.tag(TAG).w("VORBISJAVA: Could not delete temp file ${tempFile.absolutePath}")
            }
        }
    }

    private fun updateMediaStoreMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        albumArtist: String?,
        genre: String,
        trackNumber: Int,
        discNumber: Int?
    ): Boolean {
        return try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)

            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, album)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    put(MediaStore.Audio.Media.GENRE, genre)
                }
                val encodedTrack = ((discNumber ?: 0) * 1000) + trackNumber
                put(MediaStore.Audio.Media.TRACK, encodedTrack)
                put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !albumArtist.isNullOrBlank()) {
                    put(MediaStore.Audio.Media.ALBUM_ARTIST, albumArtist)
                }
            }

            val rowsUpdated = context.contentResolver.update(uri, values, null, null)
            val success = rowsUpdated > 0

            Timber.d("MediaStore update: $rowsUpdated row(s) affected")
            success

        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && e is RecoverableSecurityException) throw e
            Timber.e(e, "Failed to update MediaStore for songId: $songId")
            false
        }
    }

    /** Puts an item's evidence back after a write that failed with the file unchanged. Failing to do so is harmless: the item stays "no evidence". */
    private suspend fun restoreEvidence(songId: Long, row: EvidenceRow) {
        try {
            musicDao.restoreEvidenceForSong(songId, row)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "METADATA_EDIT: could not restore the item's evidence; it stays empty and the next scan records it")
        }
    }

    /**
     * The evidence a scan will compute for the file as it is now, read from the row MediaStore reports
     * (the scan's own source), or [ItemEvidence.NONE] when MediaStore has not caught up: its size must
     * equal the file's real size, otherwise the row is still the old one and recording it would plant the
     * very mismatch this exists to prevent. "None" is always safe: the resolver keeps the item and the
     * next scan records the evidence.
     */
    private fun settleEvidence(songId: Long, fileSize: Long): ItemEvidence {
        if (fileSize <= 0L) return ItemEvidence.NONE
        return try {
            context.contentResolver.query(
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId),
                arrayOf(
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.TRACK,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return ItemEvidence.NONE
                val reportedSize = cursor.getLong(1)
                val duration = cursor.getLong(0)
                if (reportedSize != fileSize || duration <= 0L) {
                    Timber.tag(TAG).w("METADATA_EDIT: MediaStore has not caught up (size $reportedSize, file $fileSize); evidence left empty")
                    return ItemEvidence.NONE
                }
                EditedEvidence.of(
                    EditedFileFacts(
                        durationMs = duration,
                        fileSize = reportedSize,
                        title = cursor.getString(2),
                        artist = cursor.getString(3),
                        album = cursor.getString(4),
                        rawTrack = cursor.getInt(5),
                    )
                )
            } ?: ItemEvidence.NONE
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "METADATA_EDIT: could not read the file back from MediaStore; evidence left empty")
            ItemEvidence.NONE
        }
    }

    private fun getFilePathFromMediaStore(songId: Long): String? {
        Timber.tag(TAG).e("getFilePathFromMediaStore: Looking up songId: $songId")
        return try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DATA),
                "${MediaStore.Audio.Media._ID} = ?",
                arrayOf(songId.toString()),
                null
            )?.use { cursor ->
                Timber.tag(TAG).e("getFilePathFromMediaStore: Cursor count: ${cursor.count}")
                if (cursor.moveToFirst()) {
                    val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
                    Timber.tag(TAG).e("getFilePathFromMediaStore: Found path: $path")
                    path
                } else {
                    Timber.tag(TAG)
                        .e("getFilePathFromMediaStore: No file found for songId: $songId")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e("getFilePathFromMediaStore: Error querying MediaStore: ${e.message}")
            null
        }
    }
}

private fun OpusTags.replaceSingleComment(key: String, value: String?) {
    removeComments(key)
    if (!value.isNullOrBlank()) {
        addComment(key, value)
    }
}

private fun OpusTags.applyReplayGainUpdate(key: String, update: ReplayGainUpdate) {
    when (update) {
        ReplayGainUpdate.Keep -> Unit
        ReplayGainUpdate.Clear -> removeComments(key)
        is ReplayGainUpdate.Set -> replaceSingleComment(key, update.formattedValue)
    }
}

private fun OpusTags.applyCoverArtUpdate(update: CoverArtUpdate) {
    removeComments("METADATA_BLOCK_PICTURE")
    removeComments("COVERART")
    removeComments("COVERARTMIME")

    if (update.isDeletion) {
        Timber.tag(TAG).d("VORBISJAVA: Removed cover art")
        return
    }

    val imageBytes = update.bytes
    if (imageBytes == null) {
        Timber.tag(TAG).w("VORBISJAVA: Ignoring invalid CoverArtUpdate with no bytes and no deletion flag")
        return
    }

    addComment("METADATA_BLOCK_PICTURE", buildVorbisPictureBlock(imageBytes, update.mimeType))
    Timber.tag(TAG).d("VORBISJAVA: Embedded cover art (${update.mimeType}, ${imageBytes.size} bytes)")
}

private fun buildVorbisPictureBlock(imageBytes: ByteArray, mimeType: String): String {
    val safeMimeType = mimeType.takeIf { it.isNotBlank() } ?: "image/jpeg"
    val mimeBytes = safeMimeType.toByteArray(Charsets.UTF_8)
    val descriptionBytes = "Front Cover".toByteArray(Charsets.UTF_8)
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
    val width = options.outWidth.takeIf { it > 0 } ?: 0
    val height = options.outHeight.takeIf { it > 0 } ?: 0

    val output = ByteArrayOutputStream()
    output.writeIntBigEndian(3)
    output.writeIntBigEndian(mimeBytes.size)
    output.write(mimeBytes)
    output.writeIntBigEndian(descriptionBytes.size)
    output.write(descriptionBytes)
    output.writeIntBigEndian(width)
    output.writeIntBigEndian(height)
    output.writeIntBigEndian(0)
    output.writeIntBigEndian(0)
    output.writeIntBigEndian(imageBytes.size)
    output.write(imageBytes)
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}

private fun ByteArrayOutputStream.writeIntBigEndian(value: Int) {
    write((value ushr 24) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

private fun MutableMap<String, Array<String>>.upsertOrRemove(key: String, value: String?) {
    if (value.isNullOrBlank()) {
        remove(key)
    } else {
        this[key] = arrayOf(value)
    }
}

private fun MutableMap<String, Array<String>>.applyReplayGainUpdate(
    key: String,
    update: ReplayGainUpdate
) {
    when (update) {
        ReplayGainUpdate.Keep -> Unit
        ReplayGainUpdate.Clear -> remove(key)
        is ReplayGainUpdate.Set -> this[key] = arrayOf(update.formattedValue)
    }
}

private fun Tag.applyReplayGainUpdate(key: String, update: ReplayGainUpdate) {
    when (update) {
        ReplayGainUpdate.Keep -> Unit
        ReplayGainUpdate.Clear -> removeReplayGainField(key)
        is ReplayGainUpdate.Set -> upsertReplayGainField(key, update.formattedValue)
    }
}

private fun Tag.upsertReplayGainField(key: String, value: String) {
    when (this) {
        is AbstractID3v2Tag -> upsertReplayGainId3Field(key, value)
        is WavTag -> {
            val id3Tag = getID3Tag() ?: ID3v24Tag().also(::setID3Tag)
            id3Tag.upsertReplayGainId3Field(key, value)
        }
        is FlacTag -> setField(key, value)
        is VorbisCommentTag -> setField(key, value)
        is Mp4Tag -> {
            val fieldId = replayGainMp4FieldId(key)
            deleteField(fieldId)
            setField(Mp4TagReverseDnsField(fieldId, MP4_REVERSE_DNS_ISSUER, key, value))
        }
        else -> Timber.tag(TAG).w("ReplayGain update is not supported for tag type: ${this::class.java.simpleName}")
    }
}

private fun Tag.removeReplayGainField(key: String) {
    when (this) {
        is AbstractID3v2Tag -> removeReplayGainId3Field(key)
        is WavTag -> getID3Tag()?.removeReplayGainId3Field(key)
        is FlacTag -> deleteField(key)
        is VorbisCommentTag -> deleteField(key)
        is Mp4Tag -> deleteField(replayGainMp4FieldId(key))
        else -> Timber.tag(TAG).w("ReplayGain removal is not supported for tag type: ${this::class.java.simpleName}")
    }
}

private fun AbstractID3v2Tag.upsertReplayGainId3Field(key: String, value: String) {
    val frame = if (this is ID3v23Tag) {
        ID3v23Frame(ID3v23Frames.FRAME_ID_V3_USER_DEFINED_INFO)
    } else {
        ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO)
    }
    frame.body = FrameBodyTXXX().apply {
        setDescription(key)
        setText(value)
    }
    setField(frame)
}

private fun AbstractID3v2Tag.removeReplayGainId3Field(key: String) {
    val frameId = if (this is ID3v23Tag) {
        ID3v23Frames.FRAME_ID_V3_USER_DEFINED_INFO
    } else {
        ID3v24Frames.FRAME_ID_USER_DEFINED_INFO
    }
    val frames = getFields(frameId)
    val iterator = frames.listIterator()
    while (iterator.hasNext()) {
        val frame = iterator.next() as? AbstractID3v2Frame ?: continue
        val body = frame.body as? FrameBodyTXXX ?: continue
        if (body.description == key) {
            if (frames.size == 1) {
                removeFrame(frameId)
            } else {
                iterator.remove()
            }
        }
    }
}

private fun replayGainMp4FieldId(key: String): String = "----:$MP4_REVERSE_DNS_ISSUER:$key"

data class SongMetadataEditResult(
    val success: Boolean,
    val updatedAlbumArtUri: String?,
    val error: MetadataEditError? = null,
    val errorMessage: String? = null,
    val permissionIntentSender: android.content.IntentSender? = null,
    /** B57-fix1 5.2: non-null when the text was saved but the cover could not be written to this file ("saved, without the cover: <reason>"). The old cover is untouched. */
    val coverNote: CoverNote? = null,
)

/** Why a cover was not written to a file whose other tags were saved. */
enum class CoverNote {
    /** The file's tag format has no place for a picture. */
    NOT_SUPPORTED,

    /** The tag writer refused this picture. */
    WRITER_REFUSED,

    /** The picture has no bytes. */
    UNREADABLE_IMAGE,
}

data class CoverArtUpdate(
    val bytes: ByteArray? = null,
    val mimeType: String = "image/jpeg",
    val isDeletion: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CoverArtUpdate

        if (!bytes.contentEquals(other.bytes)) return false
        if (mimeType != other.mimeType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

/**
 * The date the editor writes to a file's date tag (B57 1.3): a year, a year and month, or a full date, as every tag format spells it.
 * [yearOf] is the year the library keeps (`songs.year`); null when the text has none.
 */
object EditableDate {
    private val shape = Regex("""\d{4}(-\d{2}(-\d{2})?)?""")

    fun isValid(text: String): Boolean {
        val value = text.trim()
        if (!shape.matches(value)) return false
        val month = value.drop(5).take(2).toIntOrNull()
        val day = value.drop(8).take(2).toIntOrNull()
        return (month == null || month in 1..12) && (day == null || day in 1..31)
    }

    fun yearOf(text: String?): Int? = text?.trim()?.takeIf { shape.matches(it) }?.take(4)?.toIntOrNull()?.takeIf { it > 0 }
}
