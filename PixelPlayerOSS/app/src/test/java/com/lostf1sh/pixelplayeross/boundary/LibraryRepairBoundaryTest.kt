package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * FIX-3 (B11-fix): the repair touches the owner's library, which holds favourites, lyrics and play
 * history. Room, the content resolver and the tag reader are Android-bound, so the wiring and the SQL
 * are asserted at source level, as the rest of this area is; the rules are in `LibraryRepairPolicyTest`,
 * `YearRepairRunTest` and `LibraryIdentityFrozenKeyTest`, and the SQL itself was run against a copy of
 * the device's database (see B11-fix-resultado.md).
 */
class LibraryRepairBoundaryTest {

    private val java = "java/com/lostf1sh/pixelplayeross"
    private fun source(relative: String): String =
        File("src/main", "$java/$relative").readText().replace("\r\n", "\n")

    private val dao = source("data/database/MusicDao.kt")
    private val repairSection = dao.substringAfter("// --- B11-fix FIX-3: library repair")
        .substringBefore("/** One page of years and the albums they touch")

    /** The section without its comments: the statements and the signatures, which is what runs. */
    private val repairSql = repairSection.lines()
        .filterNot { line -> line.trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/**") } }
        .joinToString("\n")

    @Test
    fun `the track repair selects only packed values and writes only track and disc`() {
        val statement = repairSql.substringAfter("UPDATE songs\n        SET disc_number").substringBefore("suspend fun repairPackedTrackNumbers")
        assertThat(statement).contains("WHERE track_number >= 1000")
        assertThat(statement).contains("track_number = track_number % 1000")
        assertThat(statement).contains("THEN disc_number ELSE track_number / 1000")
        for (forbidden in listOf("title", "artist", "album_name", "genre", "_user_edited", "stable_key", "library_item_id", "is_favorite", "DELETE")) {
            assertThat(statement).doesNotContain(forbidden)
        }
    }

    @Test
    fun `no repair statement deletes anything or touches identity, favourites, lyrics or edits`() {
        assertThat(repairSql.uppercase()).doesNotContain("DELETE")
        for (forbidden in listOf("library_items", "favorites", "lyrics", "song_engagements", "playlist", "user_edited", "stable_key")) {
            assertThat(repairSql).doesNotContain(forbidden)
        }
        // The only columns written: songs.track_number, songs.disc_number, songs.year, albums.year.
        val sets = Regex("""SET\s+(\w+)\s*=""").findAll(repairSql).map { it.groupValues[1] }.toSet()
        assertThat(sets).containsExactly("disc_number", "year")
    }

    @Test
    fun `a year is only ever set where there is none`() {
        assertThat(repairSql).contains("UPDATE songs SET year = :year WHERE id = :songId AND year <= 0")
        assertThat(repairSql).contains("WHERE year <= 0 AND id > :afterId")
    }

    @Test
    fun `a page of years and its albums is applied in one transaction`() {
        assertThat(dao).contains("@Transaction\n    suspend fun applyYearPage(")
    }

    @Test
    fun `the sync decodes the display position correctly but keeps the identity key frozen`() {
        val sync = source("data/worker/SyncWorker.kt")
        assertThat(Regex("""LibraryIdentity\.fingerprintPosition\(""").findAll(sync).count()).isEqualTo(2)
        // No fingerprint is built from the display decoder.
        assertThat(sync).doesNotContain("trackNumber = decoded.track ?: 0,\n                discNumber = decoded.disc ?: 0\n        )")
        assertThat(sync).doesNotContain("trackNumber = position.track ?: 0,\n                        discNumber = position.disc ?: 0")
        assertThat(sync).contains("trackNumber = keyTrack,")
        assertThat(sync).contains("discNumber = keyDisc")
    }

    @Test
    fun `the merge follows the scan for rows the user did not edit, and keeps the year`() {
        val sync = source("data/worker/SyncWorker.kt")
        assertThat(sync).contains("LibraryRepairPolicy.mergePosition(")
        assertThat(sync).contains("trackNumber = position.track,")
        assertThat(sync).contains("discNumber = position.disc,")
        assertThat(sync).contains("year = LibraryRepairPolicy.mergeYear(localSong.year, mediaStoreSong.year),")
        // The old rule that let a packed value outlive every sync.
        assertThat(sync).doesNotContain("if (localSong.trackNumber != 0) localSong.trackNumber else mediaStoreSong.trackNumber")
        // The user-edit flags still travel with the row.
        assertThat(sync).contains("titleUserEdited = localSong.titleUserEdited,")
        assertThat(sync).contains("genreUserEdited = localSong.genreUserEdited")
        assertThat(sync).contains("LibraryRepairPolicy.isYearUnchanged(existing.year, raw.year)")
        assertThat(sync).contains("year = MetadataNormalizer.albumYear(songsInAlbum.map { it.year }),")
    }

    @Test
    fun `the repair runs at start and after every sync, never during one`() {
        val repair = source("data/metadata/LibraryMetadataRepair.kt")
        assertThat(repair).contains(".filter { syncing -> !syncing }")
        assertThat(repair).contains("mutex.withLock")
        assertThat(repair).contains("musicDao.repairPackedTrackNumbers()")
        assertThat(repair).contains("YearRepairRun.run(")
        assertThat(repair).contains("Year repair: scanned %d, filled %d")
        assertThat(source("PixelPlayerApplication.kt")).contains("libraryMetadataRepair.get().start(startupScope)")
    }

    @Test
    fun `the year is read from the tags with one open and no pictures`() {
        val reader = source("data/media/AudioMetadataReader.kt")
        val readYear = reader.substringAfter("fun readYear(file: File): Int? = runCatching {").substringBefore("}.getOrNull()")
        assertThat(readYear).contains("TagLib.getMetadata(fd.detachFd(), readPictures = false)")
        assertThat(readYear).doesNotContain("getPictures")
        assertThat(readYear).doesNotContain("readWithJAudioTagger")
    }

    @Test
    fun `a failed read is told apart from a file without a year, and only the second is remembered at once`() {
        val reader = source("data/media/AudioMetadataReader.kt")
        val tag = reader.substringAfter("fun readYearTag(file: File): YearTagRead {").substringBefore("/** The tag keys a release year")
        assertThat(tag).contains("TagLib.getMetadata(fd.detachFd(), readPictures = false)")
        assertThat(tag).doesNotContain("getPictures")
        assertThat(tag).contains("} ?: return YearTagRead.Failed")
        assertThat(tag).contains("?: YearTagRead.NoYearTag")
        val repair = source("data/metadata/LibraryMetadataRepair.kt")
        assertThat(repair).contains("AudioMetadataReader.YearTagRead.Failed -> YearRepairRun.Read.ReadFailed")
        assertThat(repair).contains("AudioMetadataReader.YearTagRead.NoYearTag -> YearRepairRun.Read.NoYear")
        assertThat(repair).contains("readFailures = readFailures,")
        assertThat(repair).contains("cache.save(cached)")
        assertThat(source("data/metadata/YearRepairCache.kt")).contains("putStringSet(KEY_FAILURES")
    }

    @Test
    fun `a year cache that cannot be migrated waits for the next run instead of ending the repair`() {
        val repair = source("data/metadata/LibraryMetadataRepair.kt")
        assertThat(repair).contains("val cached = runCatching { cache.load() }")
        assertThat(repair).contains(".getOrNull() ?: return@withContext")
        // Nothing reads a tag before the cache has been migrated.
        assertThat(repair.indexOf("cache.load()")).isLessThan(repair.indexOf("YearRepairRun.run("))
    }

    @Test
    fun `every list of an album's tracks puts the disc before the track`() {
        assertThat(source("data/repository/MusicRepositoryImpl.kt")).contains("LibraryRepairPolicy.albumOrder<Song>(")
        assertThat(source("data/repository/FolderTreeBuilder.kt")).contains("LibraryRepairPolicy.albumOrder<Song>(")
        assertThat(source("data/repository/MusicRepositoryImpl.kt")).doesNotContain(".sortedBy { it.trackNumber }")
        assertThat(dao).contains("ORDER BY disc_number ASC, track_number ASC")
    }

    @Test
    fun `Track Details prints an unpacked track and follows the stored row`() {
        val sheet = source("presentation/components/player/TrackDetailsSheet.kt")
        assertThat(sheet).contains("LibraryRepairPolicy.trackLabel(song.trackNumber, song.discNumber)")
        assertThat(sheet).doesNotContain("\"\$disc-\$track\"")
        val vm = source("presentation/viewmodel/TrackDetailsViewModel.kt")
        assertThat(vm).contains("musicDao.getSongById(songId).collect")
        assertThat(vm).contains("state.song?.withStoredTags(stored)")
        assertThat(vm).contains("if (isNewSong) {\n            loadMusicBrainzIds(song)\n            observeStoredTags(song)")
        // The same row carries the MusicBrainz ids, so an enrichment that finishes while open shows up.
        assertThat(vm).contains("recordingId = entity.mbRecordingId,")
    }

    @Test
    fun `Track Details re-reads the Android output when an audio device comes or goes`() {
        val vm = source("presentation/viewmodel/TrackDetailsViewModel.kt")
        assertThat(vm).contains("audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)")
        assertThat(vm).contains("audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)")
        assertThat(vm.substringAfter("private val audioDeviceCallback").substringBefore("init {"))
            .contains("refreshOutput(dualPlayerEngine.audioRouteState.value)")
    }
}
