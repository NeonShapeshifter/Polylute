package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import androidx.media3.common.MediaItem
import com.lostf1sh.pixelplayeross.data.service.player.MappingPlayer
import com.lostf1sh.pixelplayeross.data.service.player.QueueLoadSkipHold
import com.lostf1sh.pixelplayeross.utils.PlayOrder
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers

/**
 * One load of a list of [size] songs started on [start], with the pieces the view model wires, run through the real functions: the
 * plan, the first stage, the second stage, the hold and the session's player, against a [FakeQueuePlayer] (B18-R2, B19).
 *
 * [idOf] gives the id of the song at a list index; a list that holds a song twice maps two indices to one id. With [shuffle] the
 * list is played in a sequence: it leads with the start song when [startFirst] (a Shuffle button that starts at zero) and keeps it
 * where the list had it otherwise (a persistent random, which anchors the start song in place).
 */
internal class QueueLoadHarness(
    val size: Int,
    val start: Int,
    val shuffle: Boolean = false,
    seed: Long = 7L,
    behind: Int = QueueLoadPlan.BEHIND,
    ahead: Int = QueueLoadPlan.AHEAD,
    val idOf: (Int) -> String = { "s$it" },
    startFirst: Boolean = true,
    sequenceSeed: Long = 99L,
    /** False: the engine refuses to set the block's order (a windowed master), so the player keeps the order Media3 dealt it. */
    val anchor: Boolean = true,
    /** B24: the sequence the app really dealt (what `prepareShuffledQueueSuspending` returns), instead of one drawn here. */
    givenSequence: List<String>? = null,
    /** B31 P2.2: the draw as the list indices it is (what `dealShuffledQueue` returns); the play order is taken from it as it is. */
    givenOrder: IntArray? = null,
) {
    var clock = 0L
    val hold = QueueLoadSkipHold { clock }
    val fake = FakeQueuePlayer(seed)
    val baseIds: List<String> = (0 until size).map(idOf)

    /** The sequence the list is dealt in when [shuffle] is on; empty otherwise. */
    val sequence: List<String> = if (!shuffle) emptyList() else givenSequence ?: givenOrder?.let { dealt -> dealt.map { baseIds[it] } } ?: run {
        val others = (0 until size).filter { it != start }.map { baseIds[it] }.shuffled(java.util.Random(sequenceSeed))
        if (startFirst) {
            listOf(baseIds[start]) + others
        } else {
            // The start song keeps the place it had in the list, as an anchored shuffle gives it.
            others.take(start) + baseIds[start] + others.drop(start)
        }
    }

    /** The whole list in sequence order as list indices: what the view model computes off the main thread. */
    val order: IntArray? = if (shuffle) givenOrder ?: PlayOrder.fromSequence(baseIds, sequence) else null

    val plan: QueueLoadPlan = QueueLoadPlan.of(size, start, behind, ahead, order)
    val token = hold.begin()
    val mapping = MappingPlayer(fake.player, mockk<Context>(relaxed = true), queueLoadHold = hold)
    val firstItems: List<MediaItem> = plan.window.map { item(idOf(it)) }
    val buildCalls = ArrayList<IntRange>()
    lateinit var visits: BlockVisits

    /** The list indices the final order was applied with, one entry per attach. */
    val played = ArrayList<List<Int>>()

    fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    fun setRepeat(mode: Int) {
        fake.repeat = mode
    }

    fun putFirstSongs() {
        visits = putFirstSongsInPlayer(
            fake.player, plan, item(idOf(start)), firstItems, hold, token,
            setBlockOrder = if (anchor) fake::setOrder else { _ -> false },
        ) {
            fake.shuffle = shuffle
        }
    }

    suspend fun loadRest(
        betweenAttachCalls: suspend () -> Unit = {},
        onAttachedExtra: (List<Int>) -> Unit = {},
    ): Boolean = loadRestOfQueue(
        player = fake.player,
        plan = plan,
        windowFirstId = firstItems.first().mediaId,
        windowLastId = firstItems.last().mediaId,
        buildItems = { range ->
            buildCalls += range
            range.map { item(idOf(it)) }
        },
        hold = hold,
        token = token,
        mainContext = Dispatchers.Unconfined,
        visits = visits,
        yieldBetweenCalls = betweenAttachCalls,
        onAttached = { playedIndices, _ ->
            played += playedIndices
            onAttachedExtra(playedIndices)
            plan.orderAfterAttach(playedIndices)?.let { fake.setOrder(it) }
        },
    )

    /** The ids in the order the whole list plays, as the queue screen would list them. */
    fun finalIds(): List<String> = fake.playOrderIds()

    companion object {
        fun songId(index: Int) = "s$index"
    }
}
