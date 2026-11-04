package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import coil.size.Size
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.OptimizedAlbumArt
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreArtist
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreBrowse
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreDetailUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapArtistGridItem
import com.lostf1sh.pixelplayeross.ui.ds.DapAlbumGridItem
import com.lostf1sh.pixelplayeross.ui.ds.DapArtworkPlaceholder
import com.lostf1sh.pixelplayeross.ui.ds.DapFeatureHeader
import com.lostf1sh.pixelplayeross.ui.ds.DapNavRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing

/** The test tags of the genre page's sections. */
internal object GenreOverviewTags {
    const val TopArtists = "genre-top-artists"
    const val Albums = "genre-albums"
    const val AllSongs = "genre-all-songs"
}

/**
 * B56 phase 5.1 (#47): what the genre page shows under its header (the shuffle of the whole genre is the header's, as it was): its **top artists** in a row of round
 * pictures, like Home's Recent artists; a **grid of its albums**, two to a row; and one row, "All songs (N)", that opens the song list the page used to be.
 *
 * Top artists: the most songs in the genre, ties by plays ([GenreBrowse.topArtists]). Albums: an album belongs to the genre if any of its songs does; the most songs in the
 * genre first, then by name ([GenreBrowse.albumsRanked]). A section with nothing in it is not drawn.
 */
internal fun LazyListScope.genreOverviewItems(
    state: GenreDetailUiState,
    artistImages: Map<Long, String?>,
    onOpenArtist: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onAllSongs: () -> Unit,
) {
    val top = GenreBrowse.topArtists(state.artists)
    if (top.isNotEmpty()) {
        item(key = "top_artists") { GenreTopArtistsRow(top, artistImages, onOpenArtist) }
    }
    val albums = GenreBrowse.albumsRanked(state.albums)
    if (albums.isNotEmpty()) {
        item(key = "albums_header") {
            DapFeatureHeader(
                stringResource(R.string.genre_albums_header),
                Modifier.padding(horizontal = DapSpacing.Gutter).padding(top = DapSpacing.SectionGap, bottom = DapSpacing.Md),
            )
        }
        items(albums.chunked(2), key = { row -> "albums_${row.first().albumId}" }, contentType = { "album_row" }) { row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter).padding(bottom = DapSpacing.Md).testTag(GenreOverviewTags.Albums),
                horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md),
            ) {
                for (album in row) {
                    DapAlbumGridItem(
                        title = album.title,
                        subtitle = album.artist,
                        modifier = Modifier.weight(1f),
                        artwork = { OptimizedAlbumArt(album.artUri, album.title, targetSize = com.lostf1sh.pixelplayeross.utils.ArtworkRequestSize.forColumns(2, DapSpacing.Gutter * 2, DapSpacing.Md), modifier = Modifier.fillMaxSize()) },
                        onClick = { onOpenAlbum(album.albumId) },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    item(key = "all_songs") {
        DapNavRow(
            label = stringResource(R.string.genre_all_songs, state.songs.size),
            modifier = Modifier.padding(horizontal = DapSpacing.Gutter).padding(top = DapSpacing.Md).testTag(GenreOverviewTags.AllSongs),
            onClick = onAllSongs,
        )
    }
}

@Composable
private fun GenreTopArtistsRow(artists: List<GenreArtist>, images: Map<Long, String?>, onOpenArtist: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.SectionGap).testTag(GenreOverviewTags.TopArtists), verticalArrangement = Arrangement.spacedBy(DapSpacing.Md)) {
        DapFeatureHeader(stringResource(R.string.genre_top_artists_header), Modifier.padding(horizontal = DapSpacing.Gutter))
        LazyRow(contentPadding = PaddingValues(horizontal = DapSpacing.Gutter), horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md)) {
            items(artists, key = { it.artistId }) { artist ->
                val image = images[artist.artistId]
                DapArtistGridItem(
                    artist.name,
                    Modifier.width(DapSizes.ThumbGrid),
                    artwork = {
                        if (image == null) DapArtworkPlaceholder(Modifier.fillMaxSize())
                        else AsyncImage(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    },
                    onClick = { onOpenArtist(artist.artistId) },
                )
            }
        }
    }
}
