package com.lostf1sh.pixelplayeross.presentation.components

import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapFeatureHeader
import com.lostf1sh.pixelplayeross.ui.ds.DapNavRow
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapOverflowButton
import com.lostf1sh.pixelplayeross.ui.ds.DapRoundPlayAction
import com.lostf1sh.pixelplayeross.ui.ds.DapRowState
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.player.TrackDetailsHost
import com.lostf1sh.pixelplayeross.presentation.components.resolveNavBarOccupiedHeight
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import androidx.compose.ui.res.stringResource
import kotlinx.collections.immutable.persistentListOf


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
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
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
            onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, newDate, coverArtUpdate ->
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
                    coverArtUpdate,
                    newDate = newDate
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
            val playlistViewModel: PlaylistViewModel = hiltViewModel()
            val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()

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
    // B57-fix1 1: the header's control is Shuffle: the mix as shown (the whole list the page lists), shuffled, as every shuffle button does. "See all" below opens the page.
    val dailyMixQueueName = stringResource(R.string.presentation_batch_g_daily_mix_queue_name)
    val shuffle = {
        playerViewModel.playSongsShuffled(
            songsToPlay = songs,
            queueName = dailyMixQueueName,
            startAtZero = true,
            source = ListenSource(ListenSourceKind.HOME_MIX, "daily_mix"),
        )
    }
    Card(
        shape = RoundedCornerShape(DapRadius.Surface),
        colors = CardDefaults.cardColors(containerColor = Dap.panel),
        elevation = CardDefaults.elevatedCardElevation(0.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DailyMixHeader(thumbnails = headerSongs, onShuffle = shuffle)
            DailyMixSongList(
                songs = visibleSongs,
                playbackQueue = songs,
                playerViewModel = playerViewModel,
                onMoreOptionsClick = onMoreOptionsClick
            )
            ViewAllDailyMixButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DapSpacing.Lg, vertical = DapSpacing.Xs),
                onClickOpen = {
                    onClickOpen()
                },
            )
        }
    }
}

@Composable
fun DailyMixHeader(thumbnails: ImmutableList<Song>, onShuffle: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Lg, vertical = DapSpacing.Md)
    ) {
        // One control, one action (B57-fix1 1): the title is text, and the round shuffle button (the one "Your mix" has) is the header's control.
        DapFeatureHeader(
            title = stringResource(R.string.home_daily_mix_title),
            trailing = onShuffle?.let { shuffle ->
                {
                    DapRoundPlayAction(
                        shuffle,
                        kind = DapIconKind.Shuffle,
                        size = DapSizes.MinTouchTarget,
                        iconSize = DapSpacing.Gutter,
                        contentDescription = stringResource(R.string.cd_shuffle_play),
                    )
                }
            },
            maxTitleLines = 1,
        )
        // The covers have their own row under the title, in the size and shape they always had.
        if (thumbnails.isNotEmpty()) {
            Spacer(Modifier.height(DapSpacing.Sm))
            Row(horizontalArrangement = Arrangement.spacedBy(DapSpacing.Xs)) {
                thumbnails.forEach { song ->
                    Box(
                        modifier = Modifier
                            .size(DapSpacing.GroupGap)
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
            .padding(top = DapSpacing.Sm, start = DapSpacing.Lg, end = DapSpacing.Lg)
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
                        targetSize = com.lostf1sh.pixelplayeross.utils.ArtworkRequestSize.forCell(DapSizes.ThumbRow),
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
    DapNavRow(
        label = stringResource(R.string.presentation_batch_g_daily_mix_see_all, stringResource(R.string.collection_name_daily_mix)),
        modifier = modifier,
        onClick = onClickOpen,
        divider = false,
    )
}
