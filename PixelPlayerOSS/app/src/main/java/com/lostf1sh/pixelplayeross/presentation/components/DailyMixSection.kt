package com.lostf1sh.pixelplayeross.presentation.components

import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapOverflowButton
import com.lostf1sh.pixelplayeross.ui.ds.DapRowState
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.player.TrackDetailsHost
import com.lostf1sh.pixelplayeross.presentation.components.resolveNavBarOccupiedHeight
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.EnhancedSongListItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.utils.shapes.RoundedStarShape
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import androidx.compose.ui.res.stringResource
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import kotlinx.collections.immutable.persistentListOf
import com.lostf1sh.pixelplayeross.presentation.components.songTitleLabel
import com.lostf1sh.pixelplayeross.presentation.components.songArtistLabel


@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun DailyMixSection(
    songs: ImmutableList<Song>,
    playerViewModel: PlayerViewModel,
    onClickOpen: () -> Unit = {},
    onNavigateToAlbum: (Song) -> Unit = {},
    onNavigateToArtist: (Song) -> Unit = {},
    onNavigateToGenre: (Song) -> Unit = {},
) {
    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
    var showSongInfoSheet by remember { mutableStateOf(false) }
    // Cleared whenever the action sheet closes, by keying on its visibility: every
    // dismissal path in this file sets that flag, so this cannot go stale.
    var showTrackDetails by remember(showSongInfoSheet) { mutableStateOf(false) }
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    val dailyMixQueueName = stringResource(R.string.presentation_batch_g_daily_mix_queue_name)

    // No horizontal padding here: the card and its rows own the screen gutter. Padding at both
    // levels put the heading at 36 dp and the rows at 28 dp, off the 20 dp line every other
    // section on Home sits on.
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Spacer(Modifier.height(16.dp))
        DailyMixCard(
            songs = songs,
            playerViewModel = playerViewModel,
            onClickOpen = onClickOpen,
            onMoreOptionsClick = { song ->
                playerViewModel.selectSongForInfo(song)
                showSongInfoSheet = true
            }
        )
    }

    if (showSongInfoSheet && selectedSongForInfo != null) {
        val song = selectedSongForInfo!!
        SongInfoBottomSheet(
            song = song,
            isFavorite = favoriteSongIds.contains(song.id),
            onToggleFavorite = { playerViewModel.toggleFavoriteSpecificSong(song) },
            onDismiss = {
                showSongInfoSheet = false
                showPlaylistBottomSheet = false
            },
            onPlaySong = {
                playerViewModel.showAndPlaySong(
                    song = song,
                    contextSongs = songs,
                    queueName = dailyMixQueueName,
                    updatesLastPlaylistShortcut = false,
                    source = ListenSource(ListenSourceKind.HOME_MIX, "daily_mix")
                )
                showSongInfoSheet = false
            },
            onAddToQueue = {
                playerViewModel.addSongToQueue(song)
                showSongInfoSheet = false
            },
            onAddNextToQueue = {
                playerViewModel.addSongNextToQueue(song)
                showSongInfoSheet = false
            },
            onAddToPlayList = {
                showPlaylistBottomSheet = true
            },
            onDeleteFromDevice = playerViewModel::deleteFromDevice,
            onNavigateToAlbum = {
                onNavigateToAlbum(song)
                showSongInfoSheet = false
            },
            onNavigateToArtist = {
                onNavigateToArtist(song)
                showSongInfoSheet = false
            },
            onViewTrackDetails = { showTrackDetails = true },
            onNavigateToGenre = {
                onNavigateToGenre(song)
                showSongInfoSheet = false
            },
            onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate ->
                playerViewModel.editSongMetadata(
                    song,
                    newTitle,
                    newArtist,
                    newAlbum,
                    newAlbumArtist,
                    newComposer,
                    newGenre,
                    newLyrics,
                    newTrackNumber,
                    newDiscNumber,
                    replayGainTrackGainDb,
                    replayGainAlbumGainDb,
                    coverArtUpdate
                )
            },
            // The mix is a snapshot the player holds, not a query over the library, so removing the
            // file from the device has to take it out of the mix too.
            removeFromListTrigger = { playerViewModel.removeFromDailyMix(song.id) }
        )

        // The canonical details surface, composed above this list's own action sheet.
        // Closing it returns here, to the sheet the user actually opened.
        if (showTrackDetails) {
            TrackDetailsHost(
                song = song,
                playerViewModel = playerViewModel,
                onDismiss = { showTrackDetails = false },
            )
        }

        if (showPlaylistBottomSheet) {
            PlaylistBottomSheet(
                playlistUiState = playlistUiState,
                songs = persistentListOf(song),
                onDismiss = { showPlaylistBottomSheet = false },
                bottomBarHeight = bottomBarHeightDp,
                playerViewModel = playerViewModel,
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun DailyMixCard(
    songs: ImmutableList<Song>,
    onClickOpen: () -> Unit,
    playerViewModel: PlayerViewModel,
    onMoreOptionsClick: (Song) -> Unit
) {
    val headerSongs = remember(songs) { songs.take(3).toImmutableList() }
    val visibleSongs = remember(songs) { songs.take(4).toImmutableList() }
    val cornerRadius = 30.dp
    Card(
        shape = AbsoluteSmoothCornerShape(
            cornerRadiusBR = cornerRadius,
            smoothnessAsPercentTL = 60,
            cornerRadiusTR = cornerRadius,
            smoothnessAsPercentTR = 60,
            cornerRadiusBL = cornerRadius,
            smoothnessAsPercentBL = 60,
            cornerRadiusTL = cornerRadius,
            smoothnessAsPercentBR = 60
        ),
        colors = CardDefaults.cardColors(containerColor = Dap.background),
        elevation = CardDefaults.elevatedCardElevation(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DailyMixHeader(thumbnails = headerSongs)
            DailyMixSongList(
                songs = visibleSongs,
                playbackQueue = songs,
                playerViewModel = playerViewModel,
                onMoreOptionsClick = onMoreOptionsClick
            )
            ViewAllDailyMixButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 10.dp,
                        end = 10.dp,
                        top = 6.dp,
                        bottom = 6.dp
                    ),
                onClickOpen = {
                    onClickOpen()
                },
            )
        }
    }
}

@Composable
fun DailyMixHeader(thumbnails: ImmutableList<Song>) {
    val titleStyle = rememberDailyMixTitleStyle()

    fun shapeConditionalModifier(index: Int): Modifier {
        if (index == 0){
            return Modifier.size(50.dp).padding(top = 4.dp)
        } else {
            if (index == 1) {
                return Modifier.size(44.dp).aspectRatio(1f).padding(bottom = 4.dp)
            }
            return Modifier.size(48.dp)
        }
    }

    // A section, not a banner. The tinted gradient and the three novelty shapes were the loudest
    // thing on Home; the mix is a row with a name and a way in.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Md)
    ) {
        // Same hierarchy as "Your mix" (AUD-131, B25-S7): one Heading line and no label above it. The section's name was
        // "MIX DIARIO" over "Basado en el historial"; two headers for one section, like "TU MIX" over "Tu mix de hoy".
        //
        // The line has the whole row (AUD-132): the three covers they used to share it with left the
        // title ~200 dp for ~290 dp of text on a 360 dp phone, so it broke in two.
        Text(
            text = stringResource(R.string.home_daily_mix_title),
            style = DapType.Heading,
            color = Dap.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The covers have their own row under the title, in the size and shape they always had.
        if (thumbnails.isNotEmpty()) {
            Spacer(Modifier.height(DapSpacing.Sm))
            Row(horizontalArrangement = Arrangement.spacedBy(DapSpacing.Xs)) {
                thumbnails.forEach { song ->
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(DapRadius.Surface))
                    ) {
                        SmartImage(
                            model = song.albumArtUriString,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun threeShapeSwitch(index: Int, thirdShapeCornerRadius: Dp = 16.dp): Shape {
    return when (index) {
        0 -> RoundedStarShape(
            sides = 6,
            rotation = 10f
        )
        1 -> CircleShape
        2 -> AbsoluteSmoothCornerShape(
            cornerRadiusBL = thirdShapeCornerRadius,
            cornerRadiusTR = thirdShapeCornerRadius,
            smoothnessAsPercentBL = 60,
            smoothnessAsPercentTR = 60,
            cornerRadiusTL = thirdShapeCornerRadius,
            cornerRadiusBR = thirdShapeCornerRadius,
            smoothnessAsPercentTL = 60,
            smoothnessAsPercentBR = 60
        )
        else -> CircleShape
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun DailyMixSongList(
    songs: ImmutableList<Song>,
    playbackQueue: ImmutableList<Song>,
    playerViewModel: PlayerViewModel,
    onMoreOptionsClick: (Song) -> Unit
) {
    val dailyMixQueueName = stringResource(R.string.presentation_batch_g_daily_mix_queue_name)
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = DapSpacing.Gutter, end = DapSpacing.Gutter)
    ) {
        songs.forEach { song ->
            val isCurrent = stablePlayerState.currentSong?.id == song.id
            // The same song row as everywhere else in the product. This was an EnhancedSongListItem
            // — a Material surface container with its own corner radius and a three-dot overflow,
            // which is why the mix looked like a different app's list dropped into Home.
            DapMediaRow(
                title = songTitleLabel(song),
                subtitle = songArtistLabel(song),
                state = if (isCurrent) DapRowState.Current else DapRowState.Normal,
                leading = {
                    // Real artwork, from the same field and the same loader every other row in the
                    // product uses. This slot previously held a literal DapArtworkPlaceholder, so
                    // the Daily Mix list could never show a cover on any track — including ones
                    // whose art Library, Now Playing and the shelf all resolved correctly.
                    SmartImage(
                        model = song.albumArtUriString,
                        contentDescription = stringResource(R.string.cd_album_art_for_title, song.title),
                        shape = RoundedCornerShape(DapRadius.Surface),
                        targetSize = SmartImageCompactListTargetSize,
                        modifier = Modifier.size(DapSizes.ThumbRow),
                    )
                },
                trailing = {
                    DapOverflowButton(onClick = { onMoreOptionsClick(song) },
                        description = stringResource(R.string.presentation_batch_e_more_options_for_song, song.title))
                },
                onClick = {
                    playerViewModel.showAndPlaySong(
                        song = song,
                        contextSongs = playbackQueue,
                        queueName = dailyMixQueueName,
                        updatesLastPlaylistShortcut = false,
                    source = ListenSource(ListenSourceKind.HOME_MIX, "daily_mix")
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


@Composable
private fun ViewAllDailyMixButton(
    modifier: Modifier = Modifier,
    onClickOpen: () -> Unit
) {
    FilledTonalButton(
        modifier = modifier,
        onClick = {
            onClickOpen()
        },
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = Color.Transparent
        ),
        shape = AbsoluteSmoothCornerShape(
            cornerRadiusTL = 10.dp,
            cornerRadiusTR = 10.dp,
            smoothnessAsPercentTL = 70,
            smoothnessAsPercentTR = 70,
            cornerRadiusBL = 60.dp,
            cornerRadiusBR = 60.dp,
            smoothnessAsPercentBL = 70,
            smoothnessAsPercentBR = 70

        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.presentation_batch_g_daily_mix_see_all),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Icon(
                painter = painterResource(R.drawable.rounded_arrow_forward_24),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun rememberDailyMixTitleStyle(): TextStyle {
    return remember {
        TextStyle(
            fontFamily = FontFamily(
                Font(
                    resId = R.font.gflex_variable,
                    variationSettings = FontVariation.Settings(
                        FontVariation.weight(630),
                        FontVariation.width(136f),
                        FontVariation.grade(40),
                        FontVariation.Setting("ROND", 100f),
                        FontVariation.Setting("XTRA", 520f),
                        FontVariation.Setting("YOPQ", 90f),
                        FontVariation.Setting("YTLC", 505f)
                    )
                )
            ),
            fontWeight = FontWeight(630),
            fontSize = 20.sp,
            lineHeight = 22.sp,
            letterSpacing = (-0.35).sp
        )
    }
}
