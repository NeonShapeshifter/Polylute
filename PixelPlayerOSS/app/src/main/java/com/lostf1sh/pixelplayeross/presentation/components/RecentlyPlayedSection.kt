package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.model.RecentlyPlayedSongUiModel
import com.lostf1sh.pixelplayeross.ui.ds.*
import com.lostf1sh.pixelplayeross.utils.ArtworkRequestSize
import kotlinx.collections.immutable.ImmutableList

internal const val RecentlyPlayedSectionMinSongsToShow = 1

/** B57 3.1 (owner, 11-03): B55's 2 x 3 grid was too big; Recently played is a horizontal carousel of small cards, about this many songs. */
internal const val RecentlyPlayedCarouselCount = 15

/** One card's width: small enough that the next one peeks past the gutter on a phone, so the row reads as scrolling and no card is cut through its text. */
internal val RecentlyPlayedCardWidth = 116.dp

internal fun <T> recentCarouselOrder(songs: List<T>): List<T> = songs.take(RecentlyPlayedCarouselCount)

/**
 * The most recent songs, newest first, in a horizontal carousel of small cards (the song's cover over its title and artist). B55's rules stay: what counts is decided by the ledger
 * (a listen of at least 30 s, the whole history), the header opens the history, and the queue is the order shown (the caller builds it from the same list).
 */
@Composable
fun RecentlyPlayedSection(
    songs: ImmutableList<RecentlyPlayedSongUiModel>, onSongClick: (Song) -> Unit, onOpenAllClick: () -> Unit,
    modifier: Modifier=Modifier, currentSongId: String?=null,
    contentPadding: PaddingValues=PaddingValues(horizontal=DapSpacing.Gutter),
) {
    val visible=recentCarouselOrder(songs)
    if(visible.isEmpty()) return
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(DapSpacing.Md)) {
        DapFeatureHeader(stringResource(R.string.presentation_batch_g_recently_played_title),
            Modifier.padding(horizontal=DapSpacing.Gutter),onClick=onOpenAllClick)
        val artSize=ArtworkRequestSize.forCell(RecentlyPlayedCardWidth)
        LazyRow(Modifier.fillMaxWidth().testTag("recent-carousel"),contentPadding=contentPadding,horizontalArrangement=Arrangement.spacedBy(DapSpacing.Sm)) {
            items(visible,key={ it.song.id }) { item ->
                DapRecentCard(songTitleLabel(item.song),songArtistLabel(item.song),
                    modifier=Modifier.width(RecentlyPlayedCardWidth).testTag("recent-tile-${item.song.id}"),active=item.song.id==currentSongId,
                    artwork={ OptimizedAlbumArt(uri=item.song.albumArtUriString,title=songTitleLabel(item.song),targetSize=artSize,modifier=Modifier.fillMaxSize()) },
                    onClick={onSongClick(item.song)})
            }
        }
    }
}
