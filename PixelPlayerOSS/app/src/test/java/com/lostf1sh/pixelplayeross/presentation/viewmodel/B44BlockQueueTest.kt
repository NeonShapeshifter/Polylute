package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class B44BlockQueueTest {
    private fun block(id: Int, kind: ListenSourceKind): SourceSongBlock = SourceSongBlock(
        ListenSource(kind, "$id"),
        (1..7).reversed().map { track -> Song.emptySong().copy(id = "$id-$track", title = "Track $track", albumId = id.toLong(), album = "Album $id", trackNumber = track) },
    )

    @Test fun `whole library block order needs exactly two timeline publications`() {
        runBlocking {
            val queue = blockQueue((0 until 700).map { block(it, ListenSourceKind.ALBUM) }, kotlin.random.Random(44))
            for (album in queue.songs.chunked(7)) {
                assertThat(album.map { it.albumId }.distinct()).hasSize(1)
                assertThat(album.map { it.trackNumber }).containsExactly(1, 2, 3, 4, 5, 6, 7).inOrder()
            }
            val ids = queue.songs.map { it.id }
            val load = QueueLoadHarness(ids.size, 0, shuffle = false, idOf = { ids[it] })
            load.putFirstSongs()
            load.loadRest()
            assertThat(load.fake.publications).isEqualTo(2)
            assertThat(load.fake.shuffle).isFalse()
            assertThat(load.finalIds()).isEqualTo(ids)
        }
    }

    @Test fun `artist and genre blocks follow their pages and keep their listening source kind`() {
        for (kind in listOf(ListenSourceKind.ARTIST, ListenSourceKind.GENRE)) {
            val pages = (1..3).map { block(it, kind) }
            val queue = blockQueue(pages, kotlin.random.Random(44))
            assertThat(queue.source?.kind).isEqualTo(kind)
            for (songs in queue.songs.chunked(7)) {
                val original = pages.single { it.songs.first().albumId == songs.first().albumId }.songs
                val expected = if (kind == ListenSourceKind.ARTIST) SourceOrder.artistPage(original) else SourceOrder.genrePage(original)
                assertThat(songs).isEqualTo(expected)
            }
        }
    }
}
