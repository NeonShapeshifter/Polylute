package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.model.Song

/** Same membership as Artists: primary plus each collaboration credit, independent of the file locator. */
internal fun newestAddedByArtist(songs:Iterable<Song>):Map<Long,Long> {
    val newest=hashMapOf<Long,Long>()
    for(song in songs) for(id in song.artists.map { it.id }.ifEmpty { listOf(song.artistId) }.distinct()) {
        newest[id]=maxOf(newest[id] ?: 0L,song.dateAdded)
    }
    return newest
}
