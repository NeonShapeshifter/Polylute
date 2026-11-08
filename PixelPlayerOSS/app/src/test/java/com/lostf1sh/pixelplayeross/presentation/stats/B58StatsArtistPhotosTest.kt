package com.lostf1sh.pixelplayeross.presentation.stats

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.StatsEngine
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsRankMode
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import com.lostf1sh.pixelplayeross.presentation.B58Renders
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B58 6 (#74): Stats' top artists show the photo the library has for the artist (what Home shows) and the initials only when there is none. The photos come from addresses the
 * library already holds: this screen asks for nothing (no network, no lookup), which a test of the source and of the view model's inputs pins.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h1000dp-xhdpi")
class B58StatsArtistPhotosTest {
    @get:Rule val compose = createComposeRule()

    private val now = Kit.at(2026, 10, 15, 20)

    private fun song(id: Long, title: String, artist: String, artistId: Long, album: String, albumId: Long): Song =
        Kit.song(id, title = title, artist = artist, album = album, albumId = albumId, track = id.toInt())
            .copy(artists = listOf(ArtistRef(artistId, artist, true)), artistId = artistId, genre = "Pop")

    private val songs = listOf(
        song(1, "Song One", "Artist One", 11, "Album One", 5),
        song(2, "Song Two", "Artist Two", 12, "Album Two", 6),
        song(3, "Song Three", "Artist Three", 13, "Album Three", 7),
    )
    private val events = listOf(1L to 5, 2L to 3, 3L to 2)
        .flatMap { (id, plays) -> (1..plays).map { n -> Kit.play(id, now - (id * 10 + n) * Kit.HOUR) } }
    private val ledger = ListeningLedger.build(events.sortedBy { it.timestamp }, Kit.catalog(songs))
    private val links = StatsLinks.of(Kit.catalog(songs))

    /** A square photo made of a flat colour, saved to a file: the address a stored artist picture would be. */
    private fun photo(name: String, argb: Int): String {
        val file = File.createTempFile(name, ".png").apply { deleteOnExit() }
        val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return file.absolutePath
    }

    private fun draw(images: Map<String, String>, name: String? = null) {
        val report = StatsEngine.report(ledger, StatsPeriod.Preset(StatsTimeRange.ALL), now, Kit.zone)
        val look = StatsLook(StatsRankMode.PLAYS, Kit.zone, Locale.US, StatsOpener(links, { }), images)
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    Column(Modifier.fillMaxSize().background(Dap.background).padding(horizontal = 20.dp)) {
                        RankingRows(StatsList.ARTISTS, report, look, limit = 5)
                    }
                }
            }
        }
        compose.waitForIdle()
        // the stored picture is read off the main thread: give it a moment
        repeat(40) { Thread.sleep(50); compose.mainClock.advanceTimeBy(50); compose.waitForIdle() }
        if (name != null) B58Renders.save(compose, name)
    }

    @Test fun an_artist_with_a_stored_photo_shows_it_and_one_without_shows_its_initials() {
        draw(mapOf("artist one" to photo("one", 0xFFB06A3B.toInt()), "artist three" to photo("three", 0xFF3B7FB0.toInt())), name = "stats-artist-photos")
        // two photos (One and Three) and the third row, Two, has none
        compose.onAllNodesWithTag(ARTIST_PHOTO_TAG, useUnmergedTree = true).assertCountEquals(2)
    }

    @Test fun with_no_stored_photo_nothing_changes_and_every_row_has_its_initials() {
        draw(emptyMap(), name = "stats-artist-initials-only")
        compose.onAllNodesWithTag(ARTIST_PHOTO_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun the_photo_is_found_by_the_name_the_way_the_artist_links_are_trimmed_and_lower_case() {
        val images = mapOf("artist one" to "x")
        assertThat(StatsFormat.artistImage(images, "  Artist ONE ")).isEqualTo("x")
        assertThat(StatsFormat.artistImage(images, "Artist Two")).isNull()
        assertThat(StatsFormat.artistImage(mapOf("a" to " "), "A")).isNull()
        assertThat(StatsFormat.artistImage(images, "")).isNull()
    }

    @Test fun this_screen_asks_for_no_picture_from_anywhere() {
        val vm = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/StatsArtistImagesViewModel.kt").readText()
        // it reads the artists table and nothing else: no repository that fetches, no network
        assertThat(vm).contains("musicDao.getAllArtistsRaw()")
        assertThat(vm).doesNotContain("ArtistImageRepository")
        assertThat(vm).doesNotContain("getArtistImageUrl")
        assertThat(vm).doesNotContain("okhttp")
        assertThat(vm).doesNotContain("HttpURLConnection")
        val components = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/stats/StatsComponents.kt").readText()
        // the photo is drawn from an address already stored: the one image request is the picture itself (Home's own, cached by the same loader)
        assertThat(components).doesNotContain("cachedCover")
    }
}
