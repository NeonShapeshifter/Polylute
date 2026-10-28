package com.lostf1sh.pixelplayeross.data.offline

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import java.nio.file.Files
import org.junit.Test

class B43CoverPlanTest {
    @Test fun `a lazy locator cannot displace a real cover as the album representative`() {
        val file = Files.createTempFile("b43-cover", ".jpg").toFile()
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val good = Song.emptySong().copy(id = "1", album = "Album", artist = "Artist", albumArtUriString = file.toURI().toString())
            val lazy = good.copy(id = "2", albumArtUriString = LocalArtworkUri.buildSongUri(2L))
            assertThat(OfflinePlan.build(listOf(good, lazy), setOf(OfflineKind.COVERS)).single().songId).isEqualTo("1")
        } finally { file.delete() }
    }
}
