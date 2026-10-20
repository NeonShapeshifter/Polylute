package com.lostf1sh.pixelplayeross.presentation.model

import androidx.annotation.StringRes
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.SortOption

/**
 * The sort row's method label ("Title", "Date Added", "Song Count"…), resolved in the presentation
 * layer.
 *
 * [SortOption.methodLabel] and [SortOption.displayName] are data-layer labels — the storage keys and
 * the direction logic hang off the same objects — so they stay English. Only the sheet that draws
 * the row uses this. [SortOption] is sealed, so this `when` is exhaustive by construction: every
 * option, in every tab, has a label or the file does not compile.
 */
@StringRes
fun SortOption.methodLabelRes(): Int = when (this) {
    SortOption.SongDefaultOrder -> R.string.sort_method_default_order
    SortOption.SongTitleAZ -> R.string.sort_method_title
    SortOption.SongTitleZA -> R.string.sort_method_title
    SortOption.SongArtist -> R.string.sort_method_artist
    SortOption.SongArtistDesc -> R.string.sort_method_artist
    SortOption.SongAlbum -> R.string.sort_method_album
    SortOption.SongAlbumDesc -> R.string.sort_method_album
    SortOption.SongDateAdded -> R.string.sort_method_date_added
    SortOption.SongDateAddedAsc -> R.string.sort_method_date_added
    SortOption.SongDuration -> R.string.sort_method_duration
    SortOption.SongDurationAsc -> R.string.sort_method_duration
    SortOption.AlbumTitleAZ -> R.string.sort_method_title
    SortOption.AlbumTitleZA -> R.string.sort_method_title
    SortOption.AlbumArtist -> R.string.sort_method_artist
    SortOption.AlbumArtistDesc -> R.string.sort_method_artist
    SortOption.AlbumReleaseYear -> R.string.sort_method_release_year
    SortOption.AlbumReleaseYearAsc -> R.string.sort_method_release_year
    SortOption.AlbumDateAdded -> R.string.sort_method_date_added
    SortOption.AlbumSizeAsc -> R.string.sort_method_song_count
    SortOption.AlbumSizeDesc -> R.string.sort_method_song_count
    SortOption.ArtistNameAZ -> R.string.sort_method_name
    SortOption.ArtistNameZA -> R.string.sort_method_name
    SortOption.ArtistNumSongsDesc -> R.string.sort_method_number_of_songs
    SortOption.ArtistNumSongsAsc -> R.string.sort_method_number_of_songs
    SortOption.PlaylistNameAZ -> R.string.sort_method_name
    SortOption.PlaylistNameZA -> R.string.sort_method_name
    SortOption.PlaylistDateCreated -> R.string.sort_method_date_created
    SortOption.PlaylistDateCreatedAsc -> R.string.sort_method_date_created
    SortOption.LikedSongTitleAZ -> R.string.sort_method_title
    SortOption.LikedSongTitleZA -> R.string.sort_method_title
    SortOption.LikedSongArtist -> R.string.sort_method_artist
    SortOption.LikedSongArtistDesc -> R.string.sort_method_artist
    SortOption.LikedSongAlbum -> R.string.sort_method_album
    SortOption.LikedSongAlbumDesc -> R.string.sort_method_album
    SortOption.LikedSongDateLiked -> R.string.sort_method_date_liked
    SortOption.LikedSongDateLikedAsc -> R.string.sort_method_date_liked
    SortOption.FolderNameAZ -> R.string.sort_method_name
    SortOption.FolderNameZA -> R.string.sort_method_name
    SortOption.FolderSongCountAsc -> R.string.sort_method_song_count
    SortOption.FolderSongCountDesc -> R.string.sort_method_song_count
    SortOption.FolderSubdirCountAsc -> R.string.sort_method_subfolder_count
    SortOption.FolderSubdirCountDesc -> R.string.sort_method_subfolder_count
}
