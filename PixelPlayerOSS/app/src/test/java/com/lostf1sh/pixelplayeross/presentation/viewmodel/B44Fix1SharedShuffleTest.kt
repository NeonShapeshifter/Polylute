package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class B44Fix1SharedShuffleTest {
    private val a = Song.emptySong().copy(id = "a", libraryItemId = "item-a", title = "1 A", album = "Album", albumId = 1, trackNumber = 1)
    private val shared = a.copy(id = "shared", libraryItemId = "item-shared", title = "2 Shared", trackNumber = 2)
    private val b = a.copy(id = "b", libraryItemId = "item-b", title = "3 B", trackNumber = 3)
    private fun pages(kind: ListenSourceKind) = listOf(
        SourceSongBlock(ListenSource(kind, "A"), listOf(a, shared)),
        SourceSongBlock(ListenSource(kind, "B"), listOf(shared, b)),
    )
    private fun configured(pages: List<SourceSongBlock>, repeat: Boolean, seed: Int): BlockQueue {
        val method = Class.forName("com.lostf1sh.pixelplayeross.presentation.viewmodel.BlockShuffleKt")
            .methods.firstOrNull { it.name == "blockQueue" && it.parameterCount == 3 }
        return method?.invoke(null, pages, Random(seed), repeat) as? BlockQueue ?: blockQueue(pages, Random(seed))
    }
    @Test fun `default artist shuffle plays a shared song in its first shuffled block only`() {
        for (seed in 0..12) {
            val queue = blockQueue(pages(ListenSourceKind.ARTIST), Random(seed))
            val expected = if (queue.source?.ref == "A") listOf("a", "shared", "b") else listOf("shared", "b", "a")
            assertThat(queue.songs.map { it.id }).isEqualTo(expected)
        }
    }
    @Test fun `genre shuffle deduplicates by library identity while the switch on keeps complete blocks`() {
        for (kind in listOf(ListenSourceKind.ARTIST, ListenSourceKind.GENRE)) {
            val blocks = pages(kind)
            val off = configured(blocks, false, 44)
            assertThat(off.songs.map { it.libraryItemId }).containsExactly("item-a", "item-shared", "item-b")
            val on = configured(blocks, true, 44)
            assertThat(on.songs.map { it.id }).containsExactly("a", "shared", "shared", "b")
            assertThat(on.source).isEqualTo(off.source)
            val renumbered = blocks[1].copy(songs = listOf(shared.copy(id = "new-locator"), b))
            assertThat(configured(listOf(blocks[0], renumbered), false, 44).songs).hasSize(3)
        }
    }
    @Test fun `deduplication drops empty blocks while albums remain unchanged`() {
        val blocks = listOf("A", "B").map { SourceSongBlock(ListenSource(ListenSourceKind.ARTIST, it), listOf(shared)) }
        val queue = configured(blocks, false, 44)
        assertThat(queue.songs).containsExactly(shared)
        val getter = queue.javaClass.methods.firstOrNull { it.name == "getBlocks" }
        val kept = getter?.invoke(queue) as? List<*> ?: blocks
        assertThat(kept).hasSize(1)
        val albums = blocks.map { it.copy(source = it.source.copy(kind = ListenSourceKind.ALBUM)) }
        assertThat(configured(albums, false, 44).songs).hasSize(2)
        assertThat(configured(albums, true, 44).songs).hasSize(2)
    }
    @Test fun `seven hundred shared song blocks still publish the timeline only twice`() {
        runBlocking {
            for (repeat in listOf(false, true)) {
                val blocks = (0 until 700).map { block ->
                    SourceSongBlock(ListenSource(ListenSourceKind.ARTIST, "$block"), listOf(shared) +
                        (1..6).map { index -> a.copy(id = "$block-$index", libraryItemId = "item-$block-$index", title = "$index", trackNumber = index) })
                }
                val started = System.nanoTime()
                val queue = configured(blocks, repeat, 44)
                val ids = queue.songs.map { it.id }
                val load = QueueLoadHarness(ids.size, 0, shuffle = false, idOf = { ids[it] })
                load.putFirstSongs()
                load.loadRest()
                println("B44-fix1 HOST block queue repeat=$repeat songs=${ids.size} publications=${load.fake.publications} ms=${(System.nanoTime() - started) / 1_000_000.0}")
                assertThat(ids).hasSize(if (repeat) 4900 else 4201)
                assertThat(load.fake.publications).isEqualTo(2)
                assertThat(load.fake.shuffle).isFalse()
                assertThat(load.finalIds()).isEqualTo(ids)
            }
        }
    }
}
