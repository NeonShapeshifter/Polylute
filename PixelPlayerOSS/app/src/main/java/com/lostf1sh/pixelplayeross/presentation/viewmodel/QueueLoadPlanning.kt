package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.lostf1sh.pixelplayeross.data.service.player.QueueLoadSkipHold
import com.lostf1sh.pixelplayeross.data.service.player.hasQueueNeighbour
import com.lostf1sh.pixelplayeross.utils.PlayOrder

/**
 * What a tap on a library row may skip, decided from ids (B13-4).
 *
 * Kept apart from [PlayerViewModel] so the decision runs on the JVM without a player.
 */
internal object LoadedQueueReuse {
    /**
     * True when [queueIds] (the queue the app holds, in order) is exactly the list [sortedIds] that was just tapped in.
     *
     * Same size and same id at every position. A list with a song missing from the database resolves to a
     * shorter queue than its ids, so it is not "the same list" and the caller rebuilds, as it always did.
     */
    fun sameList(queueIds: List<String>, sortedIds: List<Long>): Boolean {
        if (queueIds.isEmpty() || queueIds.size != sortedIds.size) return false
        for (i in sortedIds.indices) {
            if (queueIds[i] != sortedIds[i].toString()) return false
        }
        return true
    }

    /** The index of [songId] among the [count] items a player holds, or null: it is not in the window it was given. */
    fun indexInWindow(count: Int, mediaIdAt: (Int) -> String?, songId: String): Int? =
        (0 until count).firstOrNull { mediaIdAt(it) == songId }
}

/**
 * Extends a one-song timeline into the whole queue with **two** `addMediaItems` calls (B13-4).
 *
 * The start song is already playing alone; what comes before it goes in front and what comes after it goes
 * behind. Every call publishes the whole timeline to the media session, the controllers and the engine's
 * snapshot, and a publication costs more the longer the timeline already is: the previous 200-item batches were 26
 * calls and ~8 s of main-thread time for 4,870 songs on the HONOR. Two calls are two publications.
 *
 * Nothing is added unless the player still holds exactly the start song: another tap, a skip or a queue change
 * since the segments were prepared means they are no longer this queue's.
 *
 * Since B18-R2 a list loaded from the library or a playlist starts with its neighbours and uses [attachRestAroundWindow]; this
 * one is what an external VIEW (a single file whose folder follows) still uses.
 *
 * @return whether the queue was attached (both sides that had items).
 */
internal suspend fun attachQueueSegments(
    player: Player,
    startSongId: String,
    beforeCurrent: List<MediaItem>,
    afterCurrent: List<MediaItem>,
    yieldBetweenCalls: suspend () -> Unit = { kotlinx.coroutines.yield() },
): Boolean {
    if (player.currentMediaItem?.mediaId != startSongId) return false
    if (player.mediaItemCount != 1) return false
    if (player.getMediaItemAt(0).mediaId != startSongId) return false

    val beforeSize = beforeCurrent.size
    if (beforeSize > 0) {
        player.addMediaItems(0, beforeCurrent)
        yieldBetweenCalls()
        // The main thread was free while the segments were queued: only extend what is still ours.
        if (player.currentMediaItem?.mediaId != startSongId || player.mediaItemCount != beforeSize + 1) return false
    }
    if (afterCurrent.isNotEmpty()) {
        player.addMediaItems(beforeSize + 1, afterCurrent)
    }
    return true
}

/**
 * Which songs of a list go into the player at once, and which wait (B18-R2).
 *
 * The first load is the song that was asked for **with its neighbours** ([behind] before it and [ahead] after it, in the order
 * the list is in), so next and previous work the moment playback starts. The rest of the list is attached afterwards, around
 * that block, without touching it. Before this the player held one song for about two seconds (the time to build ~4,800
 * `MediaItem`s), and a skip made then had nowhere to go.
 *
 * The block is contiguous in the list's own order, whatever shuffle says: the timeline is always the order the list was
 * loaded in (B17), and shuffle is only Media3's order over it.
 *
 * With shuffle on and a sequence to play (a persistent random, a Shuffle button) the plan also carries that sequence as [order]
 * (positions to list indices, over the whole list) and the player's order over the block is **anchored** to it (B19-S1): [blockOrder]
 * is that same order restricted to the block. Left to itself Media3 deals the block a random order of its own, in which the start
 * song can be anywhere, and what the listener went through in it says nothing about the sequence.
 */
internal class QueueLoadPlan private constructor(
    val size: Int,
    val startIndex: Int,
    /** First and last list index of the block the player starts with, both included. */
    val windowStart: Int,
    val windowEnd: Int,
    /** The whole list in the order it will play (list indices per position), or null: the list's own order (shuffle off). */
    val order: IntArray? = null,
) {
    /** Where the start song sits inside the block. */
    val startLocalIndex: Int get() = startIndex - windowStart

    val windowSize: Int get() = windowEnd - windowStart + 1

    /** The list indices of the block. */
    val window: IntRange get() = windowStart..windowEnd

    /** The list indices that go in front of the block, and behind it, once they are built. */
    val restBefore: IntRange get() = 0 until windowStart
    val restAfter: IntRange get() = (windowEnd + 1) until size

    val hasRest: Boolean get() = windowSize < size

    /** The player's order over the block (positions to indices inside it): [order] restricted to the block. Null without a sequence. */
    val blockOrder: IntArray? = order?.let { PlayOrder.restrictTo(it, windowStart, windowSize) }

    /**
     * Songs still to be attached in front of the block / behind it, in the order the player plays them. With the list's own order
     * that is the list's own edges. With a sequence the songs that are not in the block go behind what was played, wherever the
     * sequence had them, so any song still to come comes "after" (and a skip that finds nothing yet waits for the attach to see).
     */
    val songsBefore: Boolean get() = if (order == null) windowStart > 0 else hasRest
    val songsAfter: Boolean get() = if (order == null) windowEnd < size - 1 else hasRest

    /**
     * The order the whole list plays in once it is attached: [order] with the occurrences that were [played] while only the block was
     * in the player (list indices, in the order the player got to them) leading it; null without a sequence. Nothing played is the
     * sequence exactly as it was dealt.
     */
    fun orderAfterAttach(played: List<Int>): IntArray? =
        order?.let { if (played.isEmpty()) it else PlayOrder.leadWith(it, played) }

    /** The same load with the sequence it will play in; a [newOrder] that is not a permutation of the list is ignored. */
    fun withOrder(newOrder: IntArray?): QueueLoadPlan =
        QueueLoadPlan(size, startIndex, windowStart, windowEnd, newOrder?.takeIf { PlayOrder.isPermutation(it, size) })

    companion object {
        const val BEHIND = 5
        const val AHEAD = 20

        fun of(size: Int, startIndex: Int, behind: Int = BEHIND, ahead: Int = AHEAD, order: IntArray? = null): QueueLoadPlan {
            require(size > 0 && startIndex in 0 until size) { "start $startIndex outside a list of $size" }
            return QueueLoadPlan(
                size = size,
                startIndex = startIndex,
                windowStart = (startIndex - behind).coerceAtLeast(0),
                windowEnd = (startIndex + ahead).coerceAtMost(size - 1),
                order = order?.takeIf { PlayOrder.isPermutation(it, size) },
            )
        }
    }
}

/**
 * Attaches the rest of a list around the block the player started with, with **two** `addMediaItems` calls (B13-4, B18-R2).
 *
 * Every call publishes the whole timeline to the media session, the controllers and the engine's snapshot, and a publication
 * costs in proportion to the timeline it carries: the previous 200-item batches were 26 calls and ~8 s of main-thread time for
 * 4,870 songs on the HONOR. What goes in front is added at index 0 and what goes behind at the end, so the block that is playing
 * (and the next period Media3 has already prepared inside it) is never replaced or moved relative to itself.
 *
 * Nothing is added unless the player still holds exactly the block it was started with ([windowFirstId] .. [windowLastId],
 * [windowSize] items): another load, a removal or an edit since the items were built means they are no longer this queue's. The
 * song that is playing is **not** part of that check: the listener may have skipped through the block meanwhile.
 *
 * [onFrontAttached] runs right after the songs that go in front are in, before the main thread is given back: from then on the
 * block's timeline indices are shifted by their number, which whoever is recording where the player goes needs to know.
 *
 * @return whether the queue was attached (both sides that had items).
 */
internal suspend fun attachRestAroundWindow(
    player: Player,
    windowFirstId: String,
    windowLastId: String,
    windowSize: Int,
    before: List<MediaItem>,
    after: List<MediaItem>,
    yieldBetweenCalls: suspend () -> Unit = { kotlinx.coroutines.yield() },
    onFrontAttached: () -> Unit = {},
): Boolean {
    fun holdsWindow(extraBefore: Int): Boolean =
        player.mediaItemCount == extraBefore + windowSize &&
            player.getMediaItemAt(extraBefore).mediaId == windowFirstId &&
            player.getMediaItemAt(extraBefore + windowSize - 1).mediaId == windowLastId

    if (!holdsWindow(0)) return false
    if (before.isNotEmpty()) {
        player.addMediaItems(0, before)
        onFrontAttached()
        yieldBetweenCalls()
        // The main thread was free while the segments were queued: only extend what is still ours.
        if (!holdsWindow(before.size)) return false
    }
    if (after.isNotEmpty()) {
        player.addMediaItems(player.mediaItemCount, after)
    }
    return true
}

/**
 * The occurrences of the list the player really went through while the rest of it was being loaded (B19-S1), in the order it
 * first got to them, as list indices.
 *
 * B18 took "played" to be the whole prefix of the block's shuffle order up to the song that is playing. Media3 deals the block
 * an order of its own in which the start song can be anywhere, so that prefix held songs nobody had heard, and they ended up
 * behind the listener and never came round. This records what happened instead: every item the player was on, whatever moved it
 * (a next, a previous, a skip that was held and applied, a tap on a row, a song ending on its own), and nothing else.
 *
 * It is by **occurrence** (a list index), not by song id: a list can hold a song twice, and an id says which song, not which of
 * them was heard. While only the block is in the player a timeline index is the block's own, which is shifted by [QueueLoadPlan.windowStart]
 * to a list index; once the songs in front are in ([frontAttached]) the two are the same number.
 *
 * Only a load that has a sequence to put over the list ([QueueLoadPlan.order]) records anything; with shuffle off the list's own
 * order is the timeline's and nothing leads.
 */
internal class BlockVisits(
    private val player: Player,
    private val plan: QueueLoadPlan,
    private val hold: QueueLoadSkipHold,
    private val token: Long,
) : Player.Listener {
    private val visited = LinkedHashSet<Int>()
    private var frontAttached = false
    private var recording = false

    /** Starts recording with the song the player is on. Called once the block is in the player. */
    fun begin() {
        if (plan.order == null || !plan.hasRest) return
        recording = true
        player.addListener(this)
        note()
    }

    /** The songs in front of the block are attached: timeline indices are list indices from now on. */
    fun frontAttached() {
        frontAttached = true
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (!hold.isCurrent(token)) {
            stop()
            return
        }
        note()
    }

    private fun note() {
        val index = player.currentMediaItemIndex
        if (index == C.INDEX_UNSET) return
        visited.add(if (frontAttached) index else plan.windowStart + index)
    }

    /**
     * What was played: the songs the player was on, in the order it first got to them, ending with the song that is playing now.
     * Empty when it never left the song it started on (nobody skipped), which keeps the sequence exactly as it was dealt.
     */
    fun played(): List<Int> {
        if (!recording) return emptyList()
        note()
        return if (visited.size <= 1) emptyList() else visited.toList()
    }

    /** Stops listening. Safe to call twice, or without [begin]. */
    fun stop() {
        if (!recording) return
        recording = false
        player.removeListener(this)
    }
}

/** What [QueueLoadSkipHold.settle] needs from a player: the neighbours it has, Media3's own rule for moving, a restart. */
internal fun Player.skipSteps(): QueueLoadSkipHold.Steps = object : QueueLoadSkipHold.Steps {
    override fun hasNeighbour(direction: Int): Boolean = hasQueueNeighbour(direction)

    override fun canMove(direction: Int): Boolean = if (direction > 0) hasNextMediaItem() else hasPreviousMediaItem()

    override val repeatsAll: Boolean get() = repeatMode == Player.REPEAT_MODE_ALL

    override fun move(direction: Int) {
        if (direction > 0) seekToNextMediaItem() else seekToPreviousMediaItem()
    }

    override fun restart() {
        seekTo(0L)
    }
}

/**
 * The first stage of loading a list (B18-R2): the start song, with its neighbours, goes into the player in one call, and then the
 * skips that were asked for while the queue was on its way happen, before the start song has made a sound.
 *
 * With a sequence to play ([QueueLoadPlan.blockOrder]) the player's order over the block is set to it right after the load, **before**
 * anything plays or any skip happens, so the neighbours a skip finds are the ones the sequence gives and the order applied once the
 * rest is attached agrees with it (B19-S1). [setBlockOrder] does that with one `setShuffleOrder` over the block's few songs; it is
 * not called without a sequence, and a refusal leaves Media3's own order, which only costs the exactness of the first moments.
 *
 * [afterLoad] is what the caller does to a freshly loaded player (the shuffle flag, `prepare`, `play`); it runs before the held
 * skips so they act on a player that is set up. A one-song list uses `setMediaItem` exactly as before.
 *
 * @return what the player goes through from here on, for [loadRestOfQueue] to lead the final order with.
 */
internal fun putFirstSongsInPlayer(
    player: Player,
    plan: QueueLoadPlan,
    startMediaItem: MediaItem,
    firstItems: List<MediaItem>,
    hold: QueueLoadSkipHold,
    token: Long,
    setBlockOrder: (IntArray) -> Boolean = { false },
    afterLoad: () -> Unit,
): BlockVisits {
    if (plan.windowSize == 1) {
        player.setMediaItem(startMediaItem, 0L)
    } else {
        player.setMediaItems(firstItems, plan.startLocalIndex, 0L)
    }
    plan.blockOrder?.takeIf { plan.windowSize > 1 }?.let(setBlockOrder)
    afterLoad()
    val visits = BlockVisits(player, plan, hold, token)
    visits.begin()
    hold.queueInPlayer(token, songsBefore = plan.songsBefore, songsAfter = plan.songsAfter)
    hold.settle(player.skipSteps())
    return visits
}

/**
 * The second stage of loading a list (B18-R2): builds the rest of the songs off the main thread, attaches them around the block
 * the player started with, and then applies the skips that were held meanwhile.
 *
 * The order on the main thread, once the items exist: the rest is attached, [onAttached] puts the order the list was dealt in over
 * the whole timeline (leading with what [visits] says was played, so nothing repeats and nothing nobody heard drops out), and only
 * then the held skips are applied, so they follow the final order. Whatever happens, the hold of this load ends here; one that was
 * replaced by a newer load leaves the newer one alone.
 *
 * @param buildItems the `MediaItem`s of the list indices in a range; it is called off the main thread and may take long.
 * @param onAttached the list indices that were played (see [BlockVisits.played]) and the milliseconds the attach took.
 * @return whether the queue was attached.
 */
internal suspend fun loadRestOfQueue(
    player: Player,
    plan: QueueLoadPlan,
    windowFirstId: String,
    windowLastId: String,
    buildItems: suspend (IntRange) -> List<MediaItem>,
    hold: QueueLoadSkipHold,
    token: Long,
    mainContext: kotlin.coroutines.CoroutineContext,
    visits: BlockVisits,
    onAttached: (played: List<Int>, attachMs: Long) -> Unit,
    clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    yieldBetweenCalls: suspend () -> Unit = { kotlinx.coroutines.yield() },
): Boolean {
    try {
        val before = buildItems(plan.restBefore)
        val after = buildItems(plan.restAfter)
        return kotlinx.coroutines.withContext(mainContext) {
            if (!hold.isCurrent(token)) return@withContext false
            val attachStartedAt = clockMs()
            val attached = attachRestAroundWindow(
                player, windowFirstId, windowLastId, plan.windowSize, before, after, yieldBetweenCalls,
                onFrontAttached = visits::frontAttached,
            )
            val attachMs = clockMs() - attachStartedAt
            if (attached && hold.isCurrent(token)) {
                val played = visits.played()
                visits.stop()
                onAttached(played, attachMs)
                // The whole queue is in: nothing waits for songs to come, so what is left is consumed the way Media3 consumes it.
                hold.queueInPlayer(token, songsBefore = false, songsAfter = false)
                hold.settle(player.skipSteps())
            }
            attached
        }
    } finally {
        visits.stop()
        hold.end(token)
    }
}
