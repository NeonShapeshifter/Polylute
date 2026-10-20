package com.lostf1sh.pixelplayeross.presentation.stats

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.stats.CatalogItem
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen

/**
 * What a row of Stats (or of a collection) opens (B32-Q2): a song in the Library, an album, an artist, a genre.
 *
 * A target is a place in the library, not a play: nothing here starts playback. [route] is the route that opens it, and the page that
 * asked is under it on the back stack, so Back returns there.
 */
sealed interface StatsTarget {
    /** The song, found in the Library's Songs list and marked (the Library's own "locate" scroll, asked for a song that is not playing). */
    data class Song(val songId: String) : StatsTarget

    data class Album(val albumId: Long) : StatsTarget

    data class Artist(val artistId: Long) : StatsTarget

    /** [genreId] is the genre's id in the library ([Genre.idFor]), which is what its page is opened by. */
    data class Genre(val genreId: String) : StatsTarget
}

/** The route that opens [this]. */
fun StatsTarget.route(): String = when (this) {
    is StatsTarget.Song -> Screen.LibraryLocate.createRoute(songId)
    is StatsTarget.Album -> Screen.AlbumDetail.createRoute(albumId)
    is StatsTarget.Artist -> Screen.ArtistDetail.createRoute(artistId)
    is StatsTarget.Genre -> Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(genreId, "UTF-8"))
}

/**
 * What the library can open, as it is now (B32-Q2): the artist pages, which Stats names only by the name the tags give.
 *
 * A row opens something only when the library still holds it: a song with no file now, an artist the library has no page for, an album
 * with no id are rows that look and act as plain text. Built from the same catalog the report was, so the two cannot disagree about what
 * exists.
 */
class StatsLinks(private val artistIds: Map<String, Long>) {

    /** A song the catalog holds (it has a file now): null for none. */
    fun song(item: CatalogItem?): StatsTarget? = item?.song?.id?.takeIf { it.isNotBlank() }?.let { StatsTarget.Song(it) }

    /** The album the report keys by its id (the Album screen's own grouping). */
    fun album(key: String): StatsTarget? = key.toLongOrNull()?.takeIf { it > 0L }?.let { StatsTarget.Album(it) }

    /** The artist the tags name; an artist with no name ("unknown artist") or no page in the library opens nothing. */
    fun artist(name: String): StatsTarget? = artistIds[name.trim().lowercase()]?.let { StatsTarget.Artist(it) }

    /** The genre by the name a row carries; a row of songs with no genre is the library's "unknown" genre. */
    fun genre(name: String): StatsTarget = StatsTarget.Genre(Genre.idFor(name))

    companion object {
        val Empty = StatsLinks(emptyMap())

        /** Ids the library uses for "no artist" ([com.lostf1sh.pixelplayeross.data.model.ArtistRef] of a song with none). */
        private fun isRealId(id: Long) = id != 0L && id != -1L

        fun of(catalog: LibraryCatalog): StatsLinks {
            val byName = HashMap<String, Long>(catalog.items.size)
            for (item in catalog.items) {
                val song = item.song
                if (song.artists.isEmpty()) {
                    // the song names its artist only by the text (and the id of its primary): the rows use that text
                    val name = song.displayArtist.trim().lowercase()
                    if (name.isNotBlank() && isRealId(song.artistId)) byName.putIfAbsent(name, song.artistId)
                } else {
                    for (ref in song.artists) {
                        val name = ref.name.trim().lowercase()
                        if (name.isNotBlank() && isRealId(ref.id)) byName.putIfAbsent(name, ref.id)
                    }
                }
            }
            return StatsLinks(byName)
        }
    }
}

/**
 * What a row of Stats does when it is tapped, and how it looks (B32-Q2): it opens [onClick]'s target, or it is [unavailable] (the
 * library holds nothing for it: no click, drawn dimmer), or it is plain text ([None]: nothing is wired, as in a preview).
 */
class RowOpen(val onClick: (() -> Unit)?, val unavailable: Boolean) {
    companion object {
        val None = RowOpen(onClick = null, unavailable = false)
    }
}

/** What a row of a ranking, a list or a summary line opens: the links the library gives and the one function that goes there. */
class StatsOpener(val links: StatsLinks, private val open: ((StatsTarget) -> Unit)?) {
    /** False where nothing is wired to go anywhere: every row is plain text. */
    val wired: Boolean get() = open != null

    fun rowOpen(target: StatsTarget?): RowOpen = when {
        open == null -> RowOpen.None
        target == null -> RowOpen(onClick = null, unavailable = true)
        else -> RowOpen(onClick = { open.invoke(target) }, unavailable = false)
    }

    fun song(item: CatalogItem?): RowOpen = rowOpen(links.song(item))
    fun album(key: String): RowOpen = rowOpen(links.album(key))
    fun artist(name: String): RowOpen = rowOpen(links.artist(name))
    fun genre(name: String): RowOpen = rowOpen(links.genre(name))

    companion object {
        val None = StatsOpener(StatsLinks.Empty, null)
    }
}

/** How much dimmer a row is that opens nothing because the library holds nothing for it. */
internal const val UnavailableRowAlpha = 0.5f

/** The row's tap and look: clickable when it opens something, dimmer when it cannot, as it was when nothing is wired. */
internal fun Modifier.statsRowOpen(open: RowOpen): Modifier = when {
    open.onClick != null -> clickable(role = Role.Button, onClick = open.onClick)
    open.unavailable -> alpha(UnavailableRowAlpha)
    else -> this
}
