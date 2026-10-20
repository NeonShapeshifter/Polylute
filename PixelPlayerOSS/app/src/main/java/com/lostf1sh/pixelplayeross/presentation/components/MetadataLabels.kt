package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.utils.withoutPlaceholderMetadata

/**
 * What a metadata field should say when it is drawn, as opposed to what is stored for it.
 *
 * The library carries absences as values — MediaStore's `<unknown>`, and the `Unknown Artist` /
 * `Unknown Title` / `Unknown Album` literals this app's own sync has written into the same columns.
 * Those are correct to keep in the database (they are what the file said) but wrong to show, and
 * they were being shown raw on ordinary rows.
 *
 * This is deliberately a *display* layer and nothing else. It does not touch `Song.displayArtist`,
 * which is also the input to lyrics search, MusicBrainz lookup, playlist NLP and album grouping —
 * changing what that returns would silently change what those subsystems look for. Only the pixels
 * are changed here.
 *
 * Two rules, per the product definition:
 *
 * - A field the row is *identified* by — a song's title, an album's title, an artist's name — must
 *   always say something, so it falls back to localized copy.
 * - A field that merely *describes* — the artist under a song title — says nothing when there is
 *   nothing to say. Printing "Unknown artist" on every untagged row is noise, and it makes the row
 *   look like it is telling you something.
 */

/**
 * The memoised body of every label below.
 *
 * [withoutPlaceholderMetadata] is not free: it trims, scans for five mojibake markers, strips
 * control characters and runs an ICU NFC pass, allocating several strings on the way. It was being
 * re-run on every recomposition of every row that draws a title or an artist, on a value that only
 * changes when the model does.
 *
 * The key is the owning model rather than the string, so callers whose field is a computed property
 * (notably `Song.displayArtist`) do not pay to recompute that property only to build the key.
 */
@Composable
private fun rememberPlaceholderFree(model: Any?, field: () -> String?): String? =
    remember(model) { field().withoutPlaceholderMetadata() }

/** A song's title. Never blank, never a sentinel. */
@Composable
fun songTitleLabel(song: Song): String =
    rememberPlaceholderFree(song) { song.title } ?: stringResource(R.string.unknown_song_title)

/**
 * The artist to draw under a song, or null when the track names none.
 *
 * Null means draw no second line at all, rather than a line that says nothing.
 */
@Composable
fun songArtistLabel(song: Song): String? =
    rememberPlaceholderFree(song) { song.displayArtist }

/**
 * The album to draw for a song, or null when the track sits on none.
 *
 * The song-side counterpart of [albumArtistLabel]: a *describing* field, so it is omitted rather
 * than filled in.
 */
@Composable
fun songAlbumLabel(song: Song): String? =
    rememberPlaceholderFree(song) { song.album }

/** An album's title. Never blank, never a sentinel. */
@Composable
fun albumTitleLabel(album: Album): String =
    rememberPlaceholderFree(album) { album.title } ?: stringResource(R.string.unknown_album)

/**
 * The artist to draw under an album, or null when it names none.
 *
 * Nullable for the same reason as [songArtistLabel]; callers that put it in the middle of a longer
 * line should join only the parts they actually have.
 */
@Composable
fun albumArtistLabel(album: Album): String? =
    rememberPlaceholderFree(album) { album.artist }

/** An artist's name. This is the row's whole identity, so it always says something. */
@Composable
fun artistNameLabel(artist: Artist): String =
    rememberPlaceholderFree(artist) { artist.name } ?: stringResource(R.string.unknown_artist)
