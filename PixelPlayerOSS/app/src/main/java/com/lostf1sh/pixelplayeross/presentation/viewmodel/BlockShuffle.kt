package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import kotlin.random.Random

internal data class SourceSongBlock(val source: ListenSource, val songs: List<Song>)
internal data class BlockQueue(
    val source: ListenSource?,
    val songs: List<Song>,
    val blocks: List<SourceSongBlock> = emptyList(),
)

/** Randomness moves source pages; shared items belong to the first page unless repetition is chosen. */
internal fun blockQueue(
    blocks: List<SourceSongBlock>,
    random: Random = Random.Default,
    repeatSharedSongs: Boolean = false,
): BlockQueue {
    val seen = HashSet<String>()
    val ordered = blocks.filter { it.songs.isNotEmpty() }.shuffled(random).mapNotNull { block ->
        val base = when (block.source.kind) {
            ListenSourceKind.ALBUM -> SourceOrder.albumPage(block.songs)
            ListenSourceKind.ARTIST -> SourceOrder.artistPage(block.songs)
            ListenSourceKind.GENRE -> SourceOrder.genrePage(GenreBrowse.distinctFiles(block.songs))
            else -> block.songs
        }
        val sharedSource = block.source.kind == ListenSourceKind.ARTIST || block.source.kind == ListenSourceKind.GENRE
        val deduplicate = !repeatSharedSongs && sharedSource
        val songs = if (deduplicate) {
            base.filter { song -> seen.add(song.libraryItemId.ifBlank { "locator:${song.id}" }) }
        } else {
            base
        }
        songs.takeIf { it.isNotEmpty() }?.let { block.copy(songs = it) }
    }
    return BlockQueue(ordered.firstOrNull()?.source, ordered.flatMap { it.songs }, ordered)
}
