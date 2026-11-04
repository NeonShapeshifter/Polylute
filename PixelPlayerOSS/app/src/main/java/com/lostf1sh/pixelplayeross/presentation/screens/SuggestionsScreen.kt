package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.presentation.collections.songCountText
import com.lostf1sh.pixelplayeross.presentation.components.*
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.ds.*
import com.lostf1sh.pixelplayeross.utils.formatListeningDurationCompact

/** Home is the only entry. There is no collection id, Playlists row, hide action or new listen-source key. */
@Composable
fun SuggestionsScreen(playerViewModel:PlayerViewModel) {
    val mixes by playerViewModel.homeMixes.collectAsStateWithLifecycle()
    val needsFallback=mixes.isReady && mixes.yours.isEmpty() && mixes.daily.isEmpty() && !mixes.dailyHidden
    val fallback = if(needsFallback) {
        val overview: com.lostf1sh.pixelplayeross.presentation.viewmodel.HomeOverviewViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
        val flow=remember(overview) { overview.fallbackPreview() }
        flow.collectAsStateWithLifecycle(null).value
    } else emptyList()
    val songs=remember(mixes.yours,fallback) { mixes.yours.map { it.song }.ifEmpty { fallback.orEmpty() } }
    val name=stringResource(R.string.home_your_mix_queue_name)
    val source=remember { ListenSource(ListenSourceKind.HOME_MIX,"your_mix") }
    SuggestionsPage(songs,mixes.isReady && (!needsFallback || fallback != null),mixes.fallback || needsFallback,
        onPlay={songs.firstOrNull()?.let { playerViewModel.playSongs(songs,it,name,source=source) }},
        onShuffle={playerViewModel.playSongsShuffled(songs,name,startAtZero=true,source=source)},
        onSong={playerViewModel.showAndPlaySong(it,songs,name,source=source)})
}

@Composable
internal fun SuggestionsPage(songs:List<Song>,ready:Boolean,fallback:Boolean,onPlay:()->Unit,onShuffle:()->Unit,onSong:(Song)->Unit) {
    val shelf=LocalDapPlayerShelfHeight.current
    DapBackScaffold {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=DapSpacing.Gutter,end=DapSpacing.Gutter,
            top=DapSpacing.Sm,bottom=shelf+DapSpacing.SectionGap),verticalArrangement=Arrangement.spacedBy(DapSpacing.Sm)) {
            item("title") { DapScreenTitle(stringResource(R.string.home_your_mix_heading)) }
            if(!ready) item("loading") { DapSkeletonList(count=6,showArt=true) }
            else if(songs.isEmpty()) item("empty") {
                DapEmptyState(stringResource(R.string.home_your_mix_heading),stringResource(R.string.home_suggestions_empty_body))
            } else {
                item("summary") {
                    Column(verticalArrangement=Arrangement.spacedBy(DapSpacing.Md)) {
                        if(fallback) Text(stringResource(R.string.home_suggestions_library_caption),style=DapType.Caption,color=Dap.secondaryText)
                        Text("${songCountText(songs.size)} · ${formatListeningDurationCompact(songs.sumOf { it.duration }, androidx.compose.ui.platform.LocalResources.current)}",style=DapType.Caption,color=Dap.secondaryText)
                        DapCollectionActions(onPlay,onShuffle,stringResource(R.string.presentation_batch_b_play_it),stringResource(R.string.shortcut_shuffle_short))
                    }
                }
                items(songs,key={it.id}) { song ->
                    DapMediaRow(title=songTitleLabel(song),subtitle=songArtistLabel(song),
                        leading={OptimizedAlbumArt(song.albumArtUriString,songTitleLabel(song),Modifier.size(DapSizes.ThumbRow),targetSize=com.lostf1sh.pixelplayeross.utils.ArtworkRequestSize.forCell(DapSizes.ThumbRow))},
                        onClick={onSong(song)})
                }
            }
        }
    }
}
