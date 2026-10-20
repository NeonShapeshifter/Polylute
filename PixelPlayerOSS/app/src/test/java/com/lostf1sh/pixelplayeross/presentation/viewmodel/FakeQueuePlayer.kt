package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ShuffleOrder
import com.lostf1sh.pixelplayeross.utils.OrderedTestTimeline
import io.mockk.every
import io.mockk.mockk
import java.util.Random

/**
 * A [Player] that is only a queue (B18): the items, the song that is playing and Media3's shuffle order, kept the way Media3
 * keeps them, so the loading of a queue can be walked through on the JVM.
 *
 * What it models of ExoPlayer, and nothing else: `setMediaItems` replaces the queue and deals a random order; `addMediaItems`
 * shifts the playing index when items go in front of it and gives the new items random places in the shuffle order, leaving the
 * relative order of the old ones alone (`DefaultShuffleOrder.cloneAndInsert`); `seekToNext`/`seekToPrevious` follow the shuffle
 * order when it is on; a skip with nowhere to go does nothing. [publications] counts timeline changes, which is the number that
 * costs ~0.12 ms per item of the queue on the device.
 *
 * B19: it also reports a media item transition to its listeners whenever a skip or a seek lands on another item (what ExoPlayer does,
 * and what the record of what was played listens to), follows [repeat] the way Media3's `hasNextMediaItem` does (repeat all wraps),
 * and takes a shuffle order the way `ExoPlayer.setShuffleOrder` does ([setOrder]: one more publication).
 */
internal class FakeQueuePlayer(seed: Long = 7L, startShuffle: Boolean = false) {
    val ids = ArrayList<String>()
    var current = -1
        private set

    /** Positions to timeline indices; only followed while [shuffle] is on. */
    val order = ArrayList<Int>()
    var shuffle = startShuffle
    var publications = 0
        private set
    var restarts = 0
        private set
    var removed = 0
        private set
    val addCalls = ArrayList<Int>()

    /** `Player.REPEAT_MODE_*`: only [Player.REPEAT_MODE_ALL] changes what a skip can do, as in Media3 (one behaves as off for skips). */
    var repeat = Player.REPEAT_MODE_OFF

    /** Every timeline index the playhead landed on after the queue was loaded, in order: the path a listener took. */
    val path = ArrayList<Int>()

    /** How many times an order was set (`setShuffleOrder`). */
    var orderCalls = 0
        private set

    private val listeners = ArrayList<Player.Listener>()

    /** How many listeners are registered on the player right now. */
    val listenerCount: Int get() = listeners.size
    private val random = Random(seed)

    val player: Player = stub(mockk(relaxed = true))

    /** Makes [mock] (a `Player`, or a `MediaController`) behave as this queue. */
    fun <T : Player> stub(mock: T): T {
        with(mock) {
            every { mediaItemCount } answers { ids.size }
            every { currentMediaItemIndex } answers { current }
            every { currentMediaItem } answers { ids.getOrNull(current)?.let(::item) }
            every { getMediaItemAt(any()) } answers { item(ids[firstArg()]) }
            every { shuffleModeEnabled } answers { shuffle }
            every { repeatMode } answers { repeat }
            every { hasNextMediaItem() } answers { neighbour(+1, wrap = repeat == Player.REPEAT_MODE_ALL) != null }
            every { hasPreviousMediaItem() } answers { neighbour(-1, wrap = repeat == Player.REPEAT_MODE_ALL) != null }
            every { seekToNextMediaItem() } answers { move(+1) }
            every { seekToNext() } answers { move(+1) }
            every { seekToPreviousMediaItem() } answers { move(-1) }
            every { seekTo(any<Long>()) } answers { restarts++ }
            every { seekTo(any<Int>(), any<Long>()) } answers { land(firstArg()) }
            every { addListener(any()) } answers { listeners += firstArg<Player.Listener>() }
            every { removeListener(any()) } answers { listeners -= firstArg<Player.Listener>() }
            every { currentTimeline } answers { timeline() }
            every { setMediaItem(any<MediaItem>(), any<Long>()) } answers { load(listOf(firstArg<MediaItem>()), 0) }
            every { setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) } answers { load(firstArg<List<MediaItem>>(), secondArg<Int>()) }
            every { addMediaItems(any<Int>(), any<List<MediaItem>>()) } answers { insert(firstArg<Int>(), secondArg<List<MediaItem>>()) }
            every { addMediaItems(any<List<MediaItem>>()) } answers { insert(ids.size, firstArg<List<MediaItem>>()) }
            every { addMediaItem(any<Int>(), any<MediaItem>()) } answers { insert(firstArg<Int>(), listOf(secondArg<MediaItem>())) }
            every { removeMediaItem(any<Int>()) } answers { remove(firstArg<Int>()) }
        }
        return mock
    }

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    private fun load(items: List<MediaItem>, startIndex: Int) {
        ids.clear()
        ids.addAll(items.map { it.mediaId })
        current = startIndex
        order.clear()
        order.addAll(com.lostf1sh.pixelplayeross.utils.PlayOrder.randomPermutation(ids.size, random).toList())
        publications++
    }

    private fun insert(index: Int, items: List<MediaItem>) {
        val count = items.size
        ids.addAll(index, items.map { it.mediaId })
        if (current >= index) current += count
        // DefaultShuffleOrder.cloneAndInsert: old indices shift, the new ones land at random places among them.
        for (position in order.indices) if (order[position] >= index) order[position] = order[position] + count
        for (offset in 0 until count) order.add(random.nextInt(order.size + 1), index + offset)
        publications++
        addCalls.add(count)
    }

    private fun remove(index: Int) {
        ids.removeAt(index)
        if (current > index) current-- else if (current == index) current = current.coerceAtMost(ids.size - 1)
        order.remove(index)
        for (position in order.indices) if (order[position] > index) order[position] = order[position] - 1
        removed++
        publications++
    }

    /** The timeline index [direction] steps away from the song that is playing, in play order, or null; [wrap]: repeat all. */
    private fun neighbour(direction: Int, wrap: Boolean = false): Int? {
        if (ids.isEmpty() || current !in ids.indices) return null
        val play = if (shuffle) order else ids.indices.toList()
        val at = play.indexOf(current) + direction
        return play.getOrNull(at) ?: if (wrap) play.getOrNull(if (direction > 0) 0 else play.lastIndex) else null
    }

    private fun move(direction: Int) {
        neighbour(direction, wrap = repeat == Player.REPEAT_MODE_ALL)?.let { land(it) }
    }

    /** Puts the playhead on [index] and tells the listeners, as ExoPlayer does for a seek or a skip that changes the item. */
    private fun land(index: Int, reason: Int = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
        if (index == current || index !in ids.indices) return
        current = index
        path += index
        val moved = item(ids[index])
        for (listener in listeners.toList()) listener.onMediaItemTransition(moved, reason)
    }

    /** The song that is playing ends on its own and the next one starts, as a gapless transition (ExoPlayer reports it as AUTO). */
    fun songEnds() {
        neighbour(+1, wrap = repeat == Player.REPEAT_MODE_ALL)?.let { land(it, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
    }

    /** `ExoPlayer.setShuffleOrder`: [positions] must be a permutation of the queue; one more publication. */
    fun setOrder(positions: IntArray): Boolean {
        if (!com.lostf1sh.pixelplayeross.utils.PlayOrder.isPermutation(positions, ids.size)) return false
        order.clear()
        order.addAll(positions.toList())
        orderCalls++
        publications++
        return true
    }

    private fun timeline(): Timeline = OrderedTestTimeline(ids.size, ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), 1L))

    /** The ids in the order they play from the first, following the shuffle order when it is on. */
    fun playOrderIds(): List<String> = if (shuffle) order.map { ids[it] } else ids.toList()

    /** The id of the song that is playing. */
    val playing: String? get() = ids.getOrNull(current)

    /** The ids that follow the song that is playing, in play order. */
    fun upcoming(): List<String> {
        val all = playOrderIds()
        val at = all.indexOf(playing)
        return if (at < 0) emptyList() else all.drop(at + 1)
    }
}
