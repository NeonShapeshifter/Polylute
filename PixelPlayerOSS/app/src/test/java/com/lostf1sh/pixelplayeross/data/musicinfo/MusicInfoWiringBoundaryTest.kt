package com.lostf1sh.pixelplayeross.data.musicinfo

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B40-F4, at source level (the image loader, the view models and the settings need a phone): where the music-info features reach the app,
 * and that each one stops at its switch.
 */
class MusicInfoWiringBoundaryTest {
    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")
    private fun source(path: String) = File(main, path).readText().replace("\r\n", "\n")

    @Test
    fun `a cover is looked for in the cache by the image loader, and a miss only queues the album if covers are on`() {
        val fetcher = source("data/image/LocalArtworkCoilFetcher.kt")
        assertThat(fetcher).contains("?: downloadedCover?.invoke(songId) ?: return null")
        assertThat(fetcher).contains("repository.cachedCoverForSong(songId) ?: run { repository.noteMissingCover(songId); null }")
        val repository = source("data/musicinfo/MusicInfoRepository.kt")
        val note = repository.substringAfter("fun noteMissingCover(songId: Long) {").substringBefore("@Synchronized")
        // the switch is read before anything is looked up or queued
        assertThat(note.indexOf("musicInfoCoversEnabledFlow")).isAtLeast(0)
        assertThat(note.indexOf("musicInfoCoversEnabledFlow")).isLessThan(note.indexOf("getSongByIdOnce"))
        assertThat(repository).contains("Channel<CoverRequest>(capacity = MAX_QUEUE")
        assertThat(repository).contains("const val MAX_QUEUE = 300")
    }

    @Test
    fun `the service gates each kind on its own switch before any request`() {
        val service = source("data/musicinfo/MusicInfoService.kt")
        for ((fetch, gate) in listOf("fetchArtistPhoto" to "artistPhotosEnabled()", "fetchCover" to "coversEnabled()", "fetchAbout" to "descriptionsEnabled()")) {
            // B40-fix1: the public question only moves to the service's IO dispatcher; the switch is read in the function it runs
            val body = service.substringAfter("private suspend fun ${fetch}OnIo(").substringBefore("\n    }\n")
            assertThat(body.indexOf(gate)).isAtLeast(0)
            assertThat(body.indexOf(gate)).isLessThan(body.indexOf("attempt("))
        }
        // the switches come from three separate preferences, off by default
        val repository = source("data/musicinfo/MusicInfoRepository.kt")
        assertThat(repository).contains("artistPhotosEnabled = { preferences.musicInfoArtistPhotosEnabledFlow.first() }")
        assertThat(repository).contains("coversEnabled = { preferences.musicInfoCoversEnabledFlow.first() }")
        assertThat(repository).contains("descriptionsEnabled = { preferences.musicInfoDescriptionsEnabledFlow.first() }")
        val preferences = source("data/preferences/UserPreferencesRepository.kt")
        for (key in listOf("MUSIC_INFO_COVERS_ENABLED", "MUSIC_INFO_ARTIST_PHOTOS_ENABLED", "MUSIC_INFO_DESCRIPTIONS_ENABLED")) {
            assertThat(preferences).contains("it[PreferencesKeys.$key] ?: false")
        }
    }

    @Test
    fun `every address the music-info clients build is HTTPS, and the only hosts are the five services`() {
        val clients = source("data/musicinfo/MusicInfoClients.kt").lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        val hosts = Regex("https?://([a-z.$]+)").findAll(clients).map { it.groupValues[1] }.toSet()
        assertThat(clients).doesNotContain("http://")
        assertThat(hosts).containsExactly("musicbrainz.org", "coverartarchive.org", "www.wikidata.org", "commons.wikimedia.org", "\$lang.wikipedia.org")
    }

    @Test
    fun `delete downloaded data clears the cache and the artist pictures that pointed at it`() {
        val repository = source("data/musicinfo/MusicInfoRepository.kt")
        val delete = repository.substringAfter("suspend fun deleteDownloadedData(): Long").substringBefore("\n    }\n")
        assertThat(delete).contains("cache.clear()")
        assertThat(delete).contains("musicDao.clearArtistImagesUnder(dir.absolutePath)")
    }

    @Test
    fun `the artist and album pages show the About section only from what the view models hold`() {
        assertThat(source("presentation/screens/ArtistDetailScreen.kt")).contains("if (!about.isEmpty) {")
        assertThat(source("presentation/screens/AlbumDetailScreen.kt")).contains("if (!about.isEmpty) {")
        val about = source("presentation/components/MusicInfoAbout.kt")
        assertThat(about).contains("R.string.music_info_from_wikipedia")
        assertThat(about).contains("R.string.music_info_text_license")
        assertThat(about).contains("R.string.music_info_photo_credit")
    }

    @Test
    fun `PRIVACY lists the three music-info features with what they send and to whom, and says Deezer is gone`() {
        val privacy = File("../PRIVACY.md").readText()
        for (needle in listOf("Missing album covers", "Artist photos", "Artist and album descriptions", "musicbrainz.org", "coverartarchive.org",
            "wikidata.org", "commons.wikimedia.org", "wikipedia.org", "Delete downloaded data", "Deezer is no longer used")) {
            assertThat(privacy).contains(needle)
        }
        assertThat(privacy).doesNotContain("Artist artwork from Deezer\")")
        val notices = File("src/main/assets/licenses/THIRD_PARTY_NOTICES.md").readText()
        for (needle in listOf("Cover Art Archive", "Wikidata", "Wikimedia Commons", "Wikipedia", "CC BY-SA 4.0")) assertThat(notices).contains(needle)
    }
}
