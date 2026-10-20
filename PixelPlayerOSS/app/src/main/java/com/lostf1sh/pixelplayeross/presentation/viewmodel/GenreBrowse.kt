package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.compose.runtime.Immutable
import com.lostf1sh.pixelplayeross.data.model.Song

/** An album that has songs in the genre. [songCount] counts only those songs. */
@Immutable
data class GenreAlbum(
    val albumId: Long,
    val title: String,
    /** The album's credited artist as the songs say it (the album artist when they carry one, else the primary artist). */
    val artist: String,
    val artUri: String?,
    val year: Int?,
    val songCount: Int,
)

/** An artist who has songs in the genre, by **primary artist**. [songCount] counts only those songs. */
@Immutable
data class GenreArtist(
    val artistId: Long,
    val name: String,
    val songCount: Int,
    val albumCount: Int,
)

/**
 * What a genre page shows, derived once from the songs the repository returned for it (B29-L6).
 *
 * **Where the repeats came from.** The query (`MusicDao.getSongsByGenreContaining`) reads one table with no join, so a song is never
 * returned twice, and the owner's library has no genre whose result repeats a song id (checked on the 2026-10-18 copy). What the
 * page repeated was *headers and credits*: the Artist sort grouped by the song's credit text ("Dr. Dre; Snoop Dogg" is not "Dr. Dre"),
 * so the same album appeared under one header per distinct credit string, and Play queued the songs in another order than the page
 * showed. Two files with the same tags (five pairs in Hip Hop on the copy) are two files with their own sizes and lengths: they
 * stay two rows. Only a row that names the same **library item** twice is one file listed twice, and it is listed once.
 */
internal object GenreBrowse {

    /** One row per library item: a file the result names twice is shown once; two files that merely look alike are both kept. */
    fun distinctFiles(songs: List<Song>): List<Song> {
        val seen = HashSet<String>()
        return songs.filter { seen.add(it.libraryItemId.ifBlank { "locator:${it.id}" }) }
    }

    fun albums(songs: List<Song>): List<GenreAlbum> = songs
        .groupBy { it.albumId }
        .map { (albumId, group) ->
            val first = group.first()
            GenreAlbum(
                albumId = albumId,
                title = first.album,
                artist = first.albumArtist?.takeIf { it.isNotBlank() } ?: first.primaryArtist.name,
                artUri = group.firstNotNullOfOrNull { it.albumArtUriString?.takeIf(String::isNotBlank) },
                year = group.maxOf { it.year }.takeIf { it > 0 },
                songCount = group.size,
            )
        }
        .sortedWith(compareBy<GenreAlbum> { it.title.lowercase() }.thenBy { it.artist.lowercase() }.thenBy { it.albumId })

    fun artists(songs: List<Song>): List<GenreArtist> = songs
        .groupBy { it.primaryArtist.id }
        .map { (artistId, group) ->
            GenreArtist(
                artistId = artistId,
                name = group.first().primaryArtist.name,
                songCount = group.size,
                albumCount = group.map { it.albumId }.distinct().size,
            )
        }
        .sortedWith(compareBy<GenreArtist> { it.name.lowercase() }.thenBy { it.artistId })
}
