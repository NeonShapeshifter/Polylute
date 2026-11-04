package com.lostf1sh.pixelplayeross.data.offline

/** All names, keys and counts are synthetic. No network, media files or playback. */
internal fun b55Fixture(): List<InfoDiscovery> {
    val counts = listOf("LYRICS" to 4938, "PHOTO" to 1572, "BIO" to 1572, "COVER" to 1779, "DESCRIPTION" to 1779)
    return counts.flatMap { (kind, count) -> (0 until count).map { n ->
        val availability = if(kind == "LYRICS") when { n < 4899 -> "LOCAL"; n < 4902 -> "AVAILABLE"; else -> "MISSING" } else "AVAILABLE"
        InfoDiscovery("$kind-$n",kind,"Synthetic $n","artist-${n % 1572}","Synthetic artist ${n % 1572}",
            albumKey="album-${n % 1779}",albumName="Synthetic album ${n % 1779}", songId="${n % 4948}",
            availability=availability,payload="synthetic payload",sourceBytes=if(n % 2 == 0) 512 else null,
            saved=kind == "PHOTO" && n < 959, selected=n % 3 == 0, downloadEpoch=n.toLong(),
            downloadState=when(n % 19) { 1 -> "QUEUED"; 2 -> "RETRY"; 3 -> "DOWNLOADING"; else -> "IDLE" },
            attempts=n % 4,retryAt=n.toLong(),deletedByUser=n % 17 == 0)
    } }
}


internal fun b55Songs() = (0 until 4948).map { n ->
    com.lostf1sh.pixelplayeross.data.model.Song.emptySong().copy(
        id="$n", libraryItemId="synthetic-$n", title="Synthetic $n", artist="Synthetic artist ${n % 1572}",
        album="Synthetic album ${n % 1779}", albumId=(n % 1779).toLong())
}
