package com.lostf1sh.pixelplayeross.data.model

import androidx.compose.runtime.Immutable

@Immutable
sealed interface SearchResultItem {
    data class SongItem(val song: Song) : SearchResultItem
    data class AlbumItem(val album: Album) : SearchResultItem
    data class ArtistItem(val artist: Artist) : SearchResultItem
    data class PlaylistItem(val playlist: Playlist) : SearchResultItem

    /** B56-6: a composer (a part of a COMPOSER tag) and the songs that credit them. Not an artist. */
    data class ComposerItem(val name: String, val songCount: Int) : SearchResultItem
}
