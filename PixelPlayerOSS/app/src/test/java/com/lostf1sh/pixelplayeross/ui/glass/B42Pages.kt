package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.components.player.FullPlayerPortraitContent
import com.lostf1sh.pixelplayeross.presentation.components.player.NoActions
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerControlsSurface
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerLyricsLines
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerLyricsState
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerProgressSurface
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerSheetEnv
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerTrackInfoSurface
import com.lostf1sh.pixelplayeross.presentation.components.player.buildFullPlayerUiState
import com.lostf1sh.pixelplayeross.presentation.screens.HomeLayout
import com.lostf1sh.pixelplayeross.presentation.screens.YourMixHeader
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAlbumGridItem
import com.lostf1sh.pixelplayeross.ui.ds.DapBottomNav
import com.lostf1sh.pixelplayeross.ui.ds.DapBrandHeader
import com.lostf1sh.pixelplayeross.ui.ds.DapIconButton
import com.lostf1sh.pixelplayeross.ui.ds.DapIconButtonEdgeInset
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapNavItem
import com.lostf1sh.pixelplayeross.ui.ds.DapPlayerHeader
import com.lostf1sh.pixelplayeross.ui.ds.DapRowState
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette
import com.lostf1sh.pixelplayeross.ui.ds.dapColorScheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * B42: the screens that sit behind a sheet in the renders: the full player (the real portrait layout, with the cover and the two lines of
 * lyrics under it, as B41 draws it) and a Home page with the docked player and the bottom bar as panels on the glass. They are what the app
 * shows behind a sheet; what is under test is the sheet.
 */
internal object B42Pages {
    val song = Song(
        id = "1", title = "Cine", artist = "Gemini Sound", artistId = 7L, album = "Noche", albumId = 9L, path = "/m/1.flac",
        contentUriString = "content://1", albumArtUriString = null, duration = 245_000L, mimeType = "audio/flac", bitrate = 1000, sampleRate = 44100,
    )

    private val lyrics = Lyrics(
        synced = listOf(
            SyncedLine(1_000, "Abre la puerta, que estoy afuera"),
            SyncedLine(20_000, "Estás grande ya, pero tú eres mi nena"),
            SyncedLine(40_000, "Mira cómo brilla todo por la noche"),
        )
    )

    /** The full player over the glass, with [bottomRoom] left under the transport for the queue's folded strip. */
    @Composable
    fun Player(cover: B41Renders.Cover, bottomRoom: androidx.compose.ui.unit.Dp = 0.dp) {
        val actions = remember { NoActions() }
        val state = remember(cover) {
            buildFullPlayerUiState(
                env = PlayerSheetEnv(FullPlayerLoadingTweaks(delayAll = false), false, PlayerSheetState.EXPANDED, false, { 1f }),
                song = song, queue = persistentListOf(), queueSourceName = "All Songs", mediaItemIndex = 0, carouselStyle = CarouselStyle.NO_PEEK,
                artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
                audioMetadata = PlaybackAudioMetadata(mediaId = "1", mimeType = "audio/flac", sampleRate = 44100), showFileInfo = true,
                isFavorite = false, isBuffering = false, isShuffleEnabled = true, repeatMode = 0,
                isPlayingProvider = { true }, playWhenReadyProvider = { true }, currentPositionProvider = { 22_000L }, totalDurationProvider = { 245_000L },
                lyrics = PlayerLyricsState(
                    searchState = LyricsSearchUiState.Idle, stablePlayerStateFlow = MutableStateFlow(StablePlayerState()),
                    playbackPositionFlow = MutableStateFlow(0L), externalLyricsEnabled = false, lyricsSyncOffset = 0, immersiveLyricsEnabled = false,
                    immersiveLyricsTimeout = 4000L, isImmersiveTemporarilyDisabled = false, isShuffleEnabled = true, repeatMode = 0, isFavorite = false,
                    lyricsProvider = { lyrics }, lyricsResolveCompletedProvider = { true },
                ),
                isLandscape = false,
            )
        }
        CompositionLocalProvider(LocalMaterialTheme provides dapColorScheme(LocalDapAccent.current, LocalDapPalette.current)) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = { Box(Modifier.padding(top = 24.dp)) { DapPlayerHeader(lyricsActive = false, onCollapse = {}, onLyrics = {}, onDetails = {}) } },
            ) { padding ->
                FullPlayerPortraitContent(
                    paddingValues = androidx.compose.foundation.layout.PaddingValues(
                        top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + bottomRoom,
                    ),
                    albumCoverSection = { m ->
                        Box(m.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))) {
                            Image(cover.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    },
                    songMetadataSection = { PlayerTrackInfoSurface(state.trackInfo, state.env, actions, false, {}, {}) },
                    playerProgressSection = { PlayerProgressSurface(state.progress, state.env, actions) },
                    controlsSection = { PlayerControlsSurface(state.controls, state.env, actions) },
                    lyricsLinesSection = { PlayerLyricsLines(state.lyrics, state.progress, onOpen = {}) },
                )
            }
        }
    }

    /** A Home page, with the docked player and the bottom bar as panels on the glass. */
    @Composable
    fun Home(cover: B41Renders.Cover) {
        Column(Modifier.fillMaxSize().padding(top = 37.dp + DapSpacing.Sm)) {
            Row(Modifier.padding(start = DapSpacing.Gutter, end = DapSpacing.Gutter - DapIconButtonEdgeInset)) {
                DapBrandHeader(trailing = { DapIconButton(kind = DapIconKind.Gear, contentDescription = "Settings", onClick = {}) })
            }
            Spacer(Modifier.height(HomeLayout.gapBefore("your_mix_header")))
            YourMixHeader(song = "Tu mix de hoy", isShuffleEnabled = true, onPlayShuffled = {})
            Spacer(Modifier.height(HomeLayout.SectionGap))
            Row(Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter), horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md)) {
                listOf("Noche" to "Gemini Sound", "Calle" to "Amaia", "Rojo" to "Nao").forEach { (title, artist) ->
                    DapAlbumGridItem(title = title, subtitle = artist, modifier = Modifier.weight(1f), artwork = {
                        Image(cover.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    })
                }
            }
            Spacer(Modifier.height(HomeLayout.SectionGap))
            listOf("Cine" to "Gemini Sound", "Ventana roja" to "Amaia", "Luces de la ciudad" to "Nao").forEachIndexed { i, (title, artist) ->
                DapMediaRow(
                    title = title, subtitle = artist, duration = "3:2$i", state = if (i == 0) DapRowState.Current else DapRowState.Normal,
                    leading = { Image(cover.bitmap.asImageBitmap(), null, Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop) },
                    onClick = {},
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().height(60.dp).background(Dap.panel).padding(horizontal = DapSpacing.Gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(cover.bitmap.asImageBitmap(), null, Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.size(12.dp))
                androidx.compose.material3.Text("Cine", color = Dap.primaryText, style = com.lostf1sh.pixelplayeross.ui.ds.DapType.Row)
            }
            Box(Modifier.fillMaxWidth().background(Dap.panel)) {
                DapBottomNav(
                    items = listOf(
                        DapNavItem(R.string.tab_home, DapIconKind.Home), DapNavItem(R.string.search, DapIconKind.Search),
                        DapNavItem(R.string.tab_library, DapIconKind.Library),
                    ),
                    selectedIndex = 0, onSelect = {}, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 0.dp),
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
            }
        }
    }
}
