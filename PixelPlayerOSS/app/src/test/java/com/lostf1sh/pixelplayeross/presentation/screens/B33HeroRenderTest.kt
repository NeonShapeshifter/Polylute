package com.lostf1sh.pixelplayeross.presentation.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.B33Renders
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AlbumDetailUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AlbumDetailViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ArtistAlbumSection
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ArtistDetailUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ArtistDetailViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ThemeStateHolder
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSettingRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.theme.LocalPixelPlayerDarkTheme
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B33-U3: the pages with a header image (Album, Artist) and the pages without one (Playlist, Stats, Settings), drawn through the real
 * `DapBackScaffold` at the HONOR's size (720 x 1610 px at 2x) with a 24 dp status bar, over made-up data, with a bright and a dark cover
 * so the Back chevron is judged on both. The same source runs against the tree as it was at the start of the batch (`B33_PREFIX=before`)
 * and as it is now (`after`): it uses only what both have, so the pair differ in nothing but the page.
 *
 * Album and Artist are the **real screens** over stubbed view models (no Hilt on the JVM); Playlist is too. Stats and Settings are the
 * scaffold around the rows their pages are made of.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
open class B33HeroRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val statusBar = 24
    private var hostView: android.view.View? = null

    private fun cover(name: String, from: Int, to: Int): String {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, 400f, 400f, from, to, Shader.TileMode.CLAMP) }
        Canvas(bitmap).drawRect(0f, 0f, 400f, 400f, paint)
        val file = File(context.cacheDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "file://${file.absolutePath}"
    }

    private val bright get() = cover("b33-bright", AColor.rgb(250, 247, 235), AColor.rgb(236, 226, 180))
    private val dark get() = cover("b33-dark", AColor.rgb(18, 30, 60), AColor.rgb(120, 40, 50))

    private fun song(id: Int, title: String, artist: String, artistId: Long, album: String, albumId: Long, art: String?) = Song.emptySong().copy(
        id = "$id", libraryItemId = "item-$id", title = title, artist = artist, artistId = artistId,
        artists = listOf(ArtistRef(artistId, artist, true)), album = album, albumId = albumId, trackNumber = id, duration = 200_000L + id * 1_000L,
        genre = "Ska", albumArtUriString = art,
    )

    private val player = mockk<PlayerViewModel>(relaxed = true).also {
        every { it.stablePlayerState } returns MutableStateFlow(StablePlayerState())
        every { it.favoriteSongIds } returns MutableStateFlow(emptySet())
        every { it.navBarCompactMode } returns MutableStateFlow(false)
        every { it.selectedSongForInfo } returns MutableStateFlow(null)
        every { it.isSortingSheetVisible } returns MutableStateFlow(false)
        every { it.themeStateHolder } returns mockk<ThemeStateHolder>(relaxed = true).also { holder ->
            every { holder.getAlbumColorSchemeFlow(any(), any()) } returns MutableStateFlow(null)
        }
    }

    @Composable
    private fun Shell(body: @Composable () -> Unit) {
        val view = LocalView.current
        SideEffect { hostView = view }
        DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
            CompositionLocalProvider(
                LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher()),
                LocalPixelPlayerDarkTheme provides true,
            ) { Box(Modifier.fillMaxSize().background(Dap.background)) { body() } }
        }
    }

    private fun show(body: @Composable () -> Unit) {
        compose.setContent { Shell(body) }
        compose.waitForIdle()
        // a 24 dp status bar, handed over the way the system does, once the screen is listening for it
        val statusPx = (statusBar * compose.density.density).toInt()
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                hostView!!,
                WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0)).build(),
            )
        }
        compose.waitForIdle()
        // the image loader reads on its own threads: give the covers a moment, then let the frame settle
        Thread.sleep(1500)
        compose.waitForIdle()
    }

    private fun album(art: String) = Album(id = 1L, title = "Game Over", artist = "Ska-P", year = 2018, dateAdded = 0L, albumArtUriString = art, songCount = 3)

    private fun albumPage(art: String) {
        val songs = listOf(
            song(1, "Colores", "Ska-P", 1L, "Game Over", 1L, art),
            song(2, "Jaque al Rey", "Ska-P", 1L, "Game Over", 1L, art),
            song(3, "Cannabis", "Ska-P", 1L, "Game Over", 1L, art),
        )
        val viewModel = mockk<AlbumDetailViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(AlbumDetailUiState(album = album(art), songs = songs))
        show {
            AlbumDetailScreen(
                albumId = "1", navController = rememberNavController(), playerViewModel = player,
                viewModel = viewModel, playlistViewModel = mockk<PlaylistViewModel>(relaxed = true),
            )
        }
    }

    private fun artistPage(art: String) {
        val songs = listOf(
            song(1, "Colores", "Ska-P", 1L, "Game Over", 1L, art),
            song(2, "Jaque al Rey", "Ska-P", 1L, "Game Over", 1L, art),
            song(3, "Cannabis", "Ska-P", 1L, "99%", 2L, art),
        )
        val viewModel = mockk<ArtistDetailViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(
            ArtistDetailUiState(
                artist = Artist(id = 1L, name = "Ska-P", songCount = 3, imageUrl = art), songs = songs, effectiveImageUrl = art,
                albumSections = listOf(
                    ArtistAlbumSection(1L, "Game Over", 2018, art, songs.take(2)),
                    ArtistAlbumSection(2L, "99%", 2013, art, songs.drop(2)),
                ),
            )
        )
        every { viewModel.artistColorScheme } returns MutableStateFlow(null)
        show {
            ArtistDetailScreen(
                artistId = "1", navController = rememberNavController(), playerViewModel = player,
                viewModel = viewModel, playlistViewModel = mockk<PlaylistViewModel>(relaxed = true),
            )
        }
    }

    @Test
    fun `an album that is still loading keeps its skeleton clear of the chevron`() {
        val viewModel = mockk<AlbumDetailViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(AlbumDetailUiState(isLoading = true))
        show {
            AlbumDetailScreen(
                albumId = "1", navController = rememberNavController(), playerViewModel = player,
                viewModel = viewModel, playlistViewModel = mockk<PlaylistViewModel>(relaxed = true),
            )
        }
        val chevron = compose.onNodeWithContentDescription(context.getString(com.lostf1sh.pixelplayeross.R.string.auth_cd_back)).fetchSemanticsNode().boundsInRoot
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val ground = bitmap.getPixel(2, 2)
        fun drawnIn(top: Int, bottom: Int) = (top until bottom).any { y -> (300 until bitmap.width).any { x -> bitmap.getPixel(x, y) != ground } }
        // nothing but the page's ground on the chevron's row (right of the chevron), and the skeleton starts right under it
        assertThat(drawnIn((chevron.top + 2f).toInt(), (chevron.bottom - 2f).toInt())).isFalse()
        assertThat(drawnIn((chevron.bottom + 4f).toInt(), (chevron.bottom + 400f).toInt())).isTrue()
    }

    @Test
    fun `album with a bright cover`() {
        albumPage(bright)
        B33Renders.save(compose, "u3-album-bright")
    }

    @Test
    fun `album with a dark cover`() {
        albumPage(dark)
        B33Renders.save(compose, "u3-album-dark")
    }

    @Test
    fun `artist with a bright image`() {
        artistPage(bright)
        B33Renders.save(compose, "u3-artist-bright")
    }

    @Test
    fun `artist with a dark image`() {
        artistPage(dark)
        B33Renders.save(compose, "u3-artist-dark")
    }

    @Test
    fun `playlist page`() {
        val songs = listOf(
            song(1, "Colores", "Ska-P", 1L, "Game Over", 1L, null),
            song(2, "Jaque al Rey", "Ska-P", 1L, "Game Over", 1L, null),
            song(3, "Cannabis", "Ska-P", 1L, "99%", 2L, null),
        )
        val viewModel = mockk<PlaylistViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(
            PlaylistUiState(
                currentPlaylistDetails = Playlist(id = "p1", name = "Para correr", songIds = songs.map { it.id }),
                currentPlaylistSongs = songs,
            )
        )
        show {
            PlaylistDetailScreen(
                playlistId = "p1", onBackClick = {}, onDeletePlayListClick = {}, playerViewModel = player,
                playlistViewModel = viewModel, navController = rememberNavController(),
            )
        }
        B33Renders.save(compose, "u3-playlist")
    }

    @Test
    fun `stats page`() {
        show {
            DapBackScaffold(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(horizontal = DapSpacing.Gutter)) {
                    DapScreenTitle(text = "Estadísticas")
                    DapGroup(title = "Canciones más escuchadas") {
                        listOf("999", "Elite", "El Tigre Mono").forEach { DapSettingRow(label = it, description = "1 repro.", onClick = {}, showChevron = false); DapDivider() }
                    }
                }
            }
        }
        B33Renders.save(compose, "u3-stats")
    }

    @Test
    fun `settings page`() {
        show {
            DapBackScaffold(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(horizontal = DapSpacing.Gutter)) {
                    DapScreenTitle(text = "Ajustes")
                    DapGroup(title = "Ajustes") {
                        listOf("Audio", "Apariencia", "Biblioteca", "Sistema").forEach { DapSettingRow(label = it, description = "…", onClick = {}, showChevron = true); DapDivider() }
                    }
                }
            }
        }
        B33Renders.save(compose, "u3-settings")
    }
}
