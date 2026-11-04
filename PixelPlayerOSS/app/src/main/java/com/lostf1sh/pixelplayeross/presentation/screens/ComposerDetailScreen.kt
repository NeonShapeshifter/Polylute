package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.presentation.components.songArtistLabel
import com.lostf1sh.pixelplayeross.presentation.components.songTitleLabel
import com.lostf1sh.pixelplayeross.presentation.stats.RowCover
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.ds.DapEmptyState
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import kotlinx.coroutines.flow.map

/** The tags of a composer page. */
internal object ComposerPageTags {
    const val Songs = "composer-songs"
}

/**
 * B56 phase 6 (Sherlock #36): the songs that credit a composer, as a page: the name, Play and Shuffle, and the songs in title order. **The queue follows that list**: Play queues
 * it from its first song, a row starts from its own place in it, Shuffle puts random on top of the same list (the project rule). A composer is not an artist: no Artists page,
 * no photo, nothing in the Artists tab.
 */
@Composable
fun ComposerDetailScreen(
    navController: NavHostController,
    composer: String,
    playerViewModel: PlayerViewModel,
    viewModel: ComposerDetailViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(),
) {
    val songs by viewModel.songs(composer).collectAsStateWithLifecycle(initialValue = emptyList())
    val currentSongId by remember(playerViewModel) { playerViewModel.stablePlayerState.map { it.currentSong?.id } }.collectAsStateWithLifecycle(initialValue = null)
    val source = remember(composer) { ListenSource(ListenSourceKind.GENRE, "composer:$composer") }
    ComposerPage(
        name = composer,
        songs = songs,
        currentSongId = currentSongId,
        onBack = { navController.popBackStack() },
        onPlay = { songs.firstOrNull()?.let { playerViewModel.playSongs(songs, it, composer, source = source) } },
        onShuffle = { if (songs.isNotEmpty()) playerViewModel.playSongsShuffled(songsToPlay = songs, queueName = composer, startAtZero = true, source = source) },
        onPlaySong = { song -> playerViewModel.showAndPlaySong(song, songs, composer, source = source) },
    )
}

/** The page as a function of its state: what the route above feeds and the render tests draw. */
@Composable
internal fun ComposerPage(
    name: String,
    songs: List<Song>,
    currentSongId: String?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlaySong: (Song) -> Unit,
) {
    DapBackScaffold(Modifier.fillMaxSize(), onBack = onBack) {
        LazyColumn(Modifier.fillMaxSize().testTag(ComposerPageTags.Songs), contentPadding = PaddingValues(bottom = LocalDapPlayerShelfHeight.current + 24.dp)) {
            item(key = "title") {
                DapScreenTitle(text = name, modifier = Modifier.padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Md))
            }
            if (songs.isEmpty()) {
                item(key = "empty") {
                    DapEmptyState(title = name, message = stringResource(R.string.composer_empty), modifier = Modifier.padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.GroupGap))
                }
            } else {
                item(key = "actions") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter).padding(bottom = DapSpacing.Md), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DapSpacing.Sm)) {
                        DapPrimaryButton(label = stringResource(R.string.presentation_batch_b_play_it), onClick = onPlay, modifier = Modifier.weight(1f))
                        DapSecondaryButton(label = stringResource(R.string.shortcut_shuffle_short), onClick = onShuffle, modifier = Modifier.weight(1f))
                    }
                }
                items(songs, key = { it.id }) { song ->
                    DapMediaRow(
                        title = songTitleLabel(song),
                        subtitle = songArtistLabel(song),
                        state = if (currentSongId == song.id) com.lostf1sh.pixelplayeross.ui.ds.DapRowState.Current else com.lostf1sh.pixelplayeross.ui.ds.DapRowState.Normal,
                        leading = { RowCover(song.albumArtUriString, song.title) },
                        onClick = { onPlaySong(song) },
                        modifier = Modifier.padding(horizontal = DapSpacing.Gutter),
                    )
                }
            }
        }
    }
}
