package com.lostf1sh.pixelplayeross.data.offline

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import org.junit.Test

class OfflinePlanTest {
    private fun song(id: String, artist: String = "Artist", album: String = "Album") = Song.emptySong().copy(id = id, title = "Song $id", artist = artist, album = album)
    @Test fun `photos are unique artists and descriptions are unique artists and albums`() {
        val songs = listOf(song("1"), song("2"), song("3").copy(artists = listOf(ArtistRef(1, "Artist", true), ArtistRef(2, "Guest", false))))
        val plan = OfflinePlan.build(songs, OfflineKind.entries.toSet())
        assertThat(plan.count { it.kind == OfflineKind.LYRICS }).isEqualTo(3)
        assertThat(plan.filter { it.kind == OfflineKind.ARTIST_PHOTOS }.map { it.title }).containsExactly("Artist", "Guest")
        assertThat(plan.filter { it.kind == OfflineKind.COVERS }).hasSize(1)
        assertThat(plan.filter { it.kind == OfflineKind.DESCRIPTIONS }).hasSize(3)
        assertThat(plan.take(4).map { it.kind }).containsExactlyElementsIn(OfflineKind.entries).inOrder()
    }
    @Test fun `same album title by different artists stays separate and names ignore casing and edge spaces`() {
        val plan = OfflinePlan.build(listOf(song("1"), song("2", " artist "), song("3", "Other")), setOf(OfflineKind.ARTIST_PHOTOS, OfflineKind.COVERS))
        assertThat(plan.count { it.kind == OfflineKind.ARTIST_PHOTOS }).isEqualTo(2)
        assertThat(plan.count { it.kind == OfflineKind.COVERS }).isEqualTo(2)
    }
    @Test fun `an existing album cover is preferred as the representative regardless of song order`() {
        val plan = OfflinePlan.build(listOf(song("1").copy(albumArtUriString = "content://cover"), song("2")), setOf(OfflineKind.COVERS)) { it.id == "1" }
        assertThat(plan.single().songId).isEqualTo("1")
    }
}
