package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.*
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
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.ui.ds.*

@Composable
fun RecentArtistsSection(artists: List<Artist>, onArtistClick:(Artist)->Unit, modifier:Modifier=Modifier) {
    if(artists.isEmpty()) return
    Column(modifier.fillMaxWidth().testTag("recent-artists"),verticalArrangement=Arrangement.spacedBy(DapSpacing.Md)) {
        DapFeatureHeader(stringResource(R.string.home_recent_artists),Modifier.padding(horizontal=DapSpacing.Gutter))
        LazyRow(contentPadding=PaddingValues(horizontal=DapSpacing.Gutter),horizontalArrangement=Arrangement.spacedBy(DapSpacing.Md)) {
            items(artists,key={it.id}) { artist ->
                DapArtistGridItem(artistNameLabel(artist),Modifier.width(DapSizes.ThumbGrid),artwork={
                    if(artist.effectiveImageUrl == null) DapArtworkPlaceholder(Modifier.fillMaxSize())
                    else AsyncImage(artist.effectiveImageUrl,contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
                },onClick={onArtistClick(artist)})
            }
        }
    }
}

@Composable
fun SuggestedAlbumsSection(albums: List<Album>, onAlbumClick:(Album)->Unit, modifier:Modifier=Modifier) {
    if(albums.isEmpty()) return
    Column(modifier.fillMaxWidth().testTag("suggested-albums"),verticalArrangement=Arrangement.spacedBy(DapSpacing.Md)) {
        DapFeatureHeader(stringResource(R.string.home_suggested_albums),Modifier.padding(horizontal=DapSpacing.Gutter))
        LazyRow(contentPadding=PaddingValues(horizontal=DapSpacing.Gutter),horizontalArrangement=Arrangement.spacedBy(DapSpacing.Md)) {
            items(albums,key={it.id}) { album ->
                DapAlbumGridItem(albumTitleLabel(album),album.artist,Modifier.width(DapSizes.ArtDetail),
                    artwork={OptimizedAlbumArt(album.albumArtUriString,albumTitleLabel(album),targetSize=com.lostf1sh.pixelplayeross.utils.ArtworkRequestSize.forCell(DapSizes.ArtDetail),modifier=Modifier.fillMaxSize())},
                    onClick={onAlbumClick(album)})
            }
        }
    }
}
