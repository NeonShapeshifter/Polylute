package com.lostf1sh.pixelplayeross.presentation.stats

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.data.stats.StatsEngine
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsRankMode
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import com.lostf1sh.pixelplayeross.presentation.B32Renders
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B32-Q2: the rankings of Stats over a made-up history, at the HONOR's width: artists, albums and genres, one of each with nothing the
 * library can open (an artist and an album with no id: dimmer). The same source runs against the tree as it was at the start of the batch
 * (`B32_PREFIX=before`: every row is text, as it was) and as it is now (`after`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h1500dp-xhdpi")
class B32StatsRankingsRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Kit.at(2026, 10, 15, 20)

    private fun song(id: Long, title: String, artist: String, artistId: Long, album: String, albumId: Long, genre: String): Song =
        Kit.song(id, title = title, artist = artist, album = album, albumId = albumId, track = id.toInt())
            .copy(artists = listOf(ArtistRef(artistId, artist, true)), artistId = artistId, genre = genre)

    private val songs = listOf(
        song(1, "Cancion Uno", "Artista Alfa", 11, "Disco Uno", 5, "Ska Punk"),
        song(2, "Cancion Dos", "Artista Beta", 12, "Disco Dos", 6, "Hip Hop"),
        song(3, "Cancion Tres", "Artista Cero", 0, "Disco Cero", 0, ""),
    )
    private val catalog = Kit.catalog(songs)
    private val events = listOf(1L to 5, 2L to 3, 3L to 2)
        .flatMap { (id, plays) -> (1..plays).map { n -> Kit.play(id, now - (id * 10 + n) * Kit.HOUR) } }
    private val ledger = ListeningLedger.build(events.sortedBy { it.timestamp }, catalog)
    private val links = StatsLinks.of(catalog) //AFTER-ONLY

    @Test
    fun `the artists, albums and genres of a made-up history`() {
        val report = StatsEngine.report(ledger, StatsPeriod.Preset(StatsTimeRange.ALL), now, Kit.zone)
        val look = StatsLook(StatsRankMode.PLAYS, Kit.zone, Locale.forLanguageTag("es"), StatsOpener(links, { })) //AFTER-ONLY
        //val look = StatsLook(StatsRankMode.PLAYS, Kit.zone, Locale.forLanguageTag("es")) //BEFORE-ONLY
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    Column(Modifier.fillMaxSize().background(Dap.background).padding(horizontal = 20.dp)) {
                        listOf(StatsList.SONGS, StatsList.ARTISTS, StatsList.ALBUMS, StatsList.GENRES).forEach { kind ->
                            RankingRows(kind, report, look, limit = 5)
                        }
                    }
                }
            }
        }
        B32Renders.save(compose, "q2-stats-rankings")
    }
}
