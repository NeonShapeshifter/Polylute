package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import org.junit.jupiter.api.Test

class B51ArtworkHydrationTest {
    @Test fun `a changed folder image generation reaches the currently selected song`() {
        val playing = Song.emptySong().copy(id = "51", albumArtUriString = "pixelplayer_local_art://song/51?t=old")
        val refreshed = playing.copy(albumArtUriString = "pixelplayer_local_art://song/51?t=new")
        assertThat(playing.withRepositoryHydration(refreshed).albumArtUriString).isEqualTo(refreshed.albumArtUriString)
    }
    @Test fun `a removed local folder cover clears the current player artwork`() {
        val playing = Song.emptySong().copy(id = "51", albumArtUriString = "pixelplayer_local_art://song/51?t=old")
        assertThat(playing.withRepositoryHydration(playing.copy(albumArtUriString = null)).albumArtUriString).isNull()
    }
}
