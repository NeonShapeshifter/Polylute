package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.model.Song

/**
 * The order a screen lists a source in, defined once (B24-Q1).
 *
 * A queue is always its source list: whatever button starts it, its base order is the list as the listener sees it, and a shuffle
 * is only Media3's shuffle order on top. A button that builds its list from somewhere else than the screen that shows the source
 * (a database query with its own `ORDER BY`, a random sample) breaks that, so the screens and the buttons ask here.
 */
internal object SourceOrder {

    /**
     * An album's page: disc first (no disc counts as the first), then the track (no track last), then the title. The same order the
     * artist page uses inside each of its albums.
     */
    val albumPage: Comparator<Song> = compareBy<Song> { it.discNumber ?: 1 }
        .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
        .thenBy { it.title.lowercase() }

    fun albumPage(songs: List<Song>): List<Song> = songs.sortedWith(albumPage)

    /** An artist's page: album by album (newest first, undated albums after, by title), each album in its page order. */
    fun artistPage(songs: List<Song>): List<Song> = buildAlbumSections(songs).flatMap { it.songs }

    /**
     * A genre's songs (B29-L6, B33-U4). One flat list, once each, by title, then artist, then album (then the album's page order, then the
     * song's locator, so the order never depends on how the database happened to return the rows). That list is what Play queues, what a
     * row starts from and what Shuffle puts random on top of. There is no other order: the page has no sort.
     */
    fun genrePage(songs: List<Song>): List<Song> {
        val byTitle = compareBy<Song> { it.title.lowercase() }
        val byArtist = compareBy<Song> { it.primaryArtist.name.lowercase() }
        val byAlbum = compareBy<Song> { it.album.lowercase() }.thenBy { it.albumId }
        return songs.sortedWith(byTitle.then(byArtist).then(byAlbum).then(albumPage).thenBy { it.id })
    }
}
