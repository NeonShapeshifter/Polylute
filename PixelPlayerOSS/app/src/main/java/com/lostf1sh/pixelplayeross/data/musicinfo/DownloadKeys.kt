package com.lostf1sh.pixelplayeross.data.musicinfo

import com.lostf1sh.pixelplayeross.data.model.Song

/** The remote asset's identity, shared by automatic lookups, deletion and offline targets. */
internal object DownloadKeys {
    fun photo(artist: String): String = "photo|${MusicInfoService.photoKey(artist)}"

    fun cover(album: String, artist: String): String {
        val creditedArtist = MusicBrainzClient.firstCreditedArtist(artist)
        return "cover|${MusicInfoService.coverKey(album, creditedArtist)}"
    }

    /** One decision covers every language, including descriptions saved under the legacy key. */
    fun about(kind: AboutKind, name: String, artist: String? = null): String {
        val creditedArtist = if (kind == AboutKind.ALBUM) {
            MusicBrainzClient.firstCreditedArtist(artist.orEmpty())
        } else {
            ""
        }
        return "about|${kind == AboutKind.ALBUM}|${MusicInfoMatching.normalize(name)}|${MusicInfoMatching.normalize(creditedArtist)}"
    }

    fun lyrics(song: Song): String = lyrics(song.id, song.libraryItemId)

    fun lyrics(songId: String, libraryItemId: String = ""): String {
        val identity = libraryItemId.ifBlank { "locator:$songId" }
        return "lyrics|$identity"
    }

    fun artist(artist: String): List<String> = listOf(photo(artist), about(AboutKind.ARTIST, artist))

    fun album(album: String, artist: String): List<String> = listOf(
        cover(album, artist),
        about(AboutKind.ALBUM, album, artist),
    )
}
