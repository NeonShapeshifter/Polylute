package com.lostf1sh.pixelplayeross.presentation.components

import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.Playlist
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption

internal fun songFastScrollLabel(song: Song?, sortOption: SortOption): String? =
    when (sortOption) {
        SortOption.SongTitleAZ,
        SortOption.SongTitleZA,
        SortOption.LikedSongTitleAZ,
        SortOption.LikedSongTitleZA -> extractFastScrollGlyph(song?.title)

        SortOption.SongArtist,
        SortOption.SongArtistDesc,
        SortOption.LikedSongArtist,
        SortOption.LikedSongArtistDesc -> extractFastScrollGlyph(song?.artist)

        SortOption.SongAlbum,
        SortOption.SongAlbumDesc,
        SortOption.LikedSongAlbum,
        SortOption.LikedSongAlbumDesc -> extractFastScrollGlyph(song?.album)

        // B29-L8: the sorts by a date say the year the row is from, which is true to that order; the others (duration, the default
        // order) have no label that would be, and show none rather than a letter that suggests an alphabet.
        SortOption.SongDateAdded,
        SortOption.SongDateAddedAsc -> fastScrollYearOfEpoch(song?.dateAdded ?: 0L)

        else -> null
    }

internal fun albumFastScrollLabel(album: Album?, sortOption: SortOption): String? =
    when (sortOption) {
        SortOption.AlbumTitleAZ,
        SortOption.AlbumTitleZA -> extractFastScrollGlyph(album?.title)

        SortOption.AlbumArtist,
        SortOption.AlbumArtistDesc -> extractFastScrollGlyph(album?.artist)

        SortOption.AlbumReleaseYear,
        SortOption.AlbumReleaseYearAsc -> fastScrollYearLabel(album?.year ?: 0)

        SortOption.AlbumDateAdded -> fastScrollYearOfEpoch(album?.dateAdded ?: 0L)

        else -> null
    }

internal fun artistFastScrollLabel(artist: Artist?, sortOption: SortOption): String? =
    when (sortOption) {
        SortOption.ArtistNameAZ,
        SortOption.ArtistNameZA -> extractFastScrollGlyph(artist?.name)
        else -> null
    }

internal fun playlistFastScrollLabel(playlist: Playlist?, sortOption: SortOption?): String? =
    when (sortOption) {
        SortOption.PlaylistNameAZ,
        SortOption.PlaylistNameZA -> extractFastScrollGlyph(playlist?.name)
        else -> null
    }
