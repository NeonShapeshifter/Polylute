package com.lostf1sh.pixelplayeross.utils

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * The shape of ExoPlayer's `PlaylistTimeline` / a controller's `RemotableTimeline`: windows in the base order, and
 * the shuffle questions (`first`, `last`, `next` with `shuffleModeEnabled = true`) answered from a [ShuffleOrder].
 * One class so the tests of the helper, the session wrapper and the queue screen read the same thing.
 */
class OrderedTestTimeline(private val count: Int, private val order: ShuffleOrder) : Timeline() {
    override fun getWindowCount(): Int = count
    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window = window
    override fun getPeriodCount(): Int = count
    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period = period
    override fun getIndexOfPeriod(uid: Any): Int = C.INDEX_UNSET
    override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex

    override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int =
        if (count == 0) C.INDEX_UNSET else if (shuffleModeEnabled) order.firstIndex else 0

    override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int =
        if (count == 0) C.INDEX_UNSET else if (shuffleModeEnabled) order.lastIndex else count - 1

    override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
        if (repeatMode == Player.REPEAT_MODE_ONE) return windowIndex
        if (windowIndex == getLastWindowIndex(shuffleModeEnabled)) {
            return if (repeatMode == Player.REPEAT_MODE_ALL) getFirstWindowIndex(shuffleModeEnabled) else C.INDEX_UNSET
        }
        return if (shuffleModeEnabled) order.getNextIndex(windowIndex) else windowIndex + 1
    }

    override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
        if (repeatMode == Player.REPEAT_MODE_ONE) return windowIndex
        if (windowIndex == getFirstWindowIndex(shuffleModeEnabled)) {
            return if (repeatMode == Player.REPEAT_MODE_ALL) getLastWindowIndex(shuffleModeEnabled) else C.INDEX_UNSET
        }
        return if (shuffleModeEnabled) order.getPreviousIndex(windowIndex) else windowIndex - 1
    }

    companion object {
        /** A timeline over `order.size` songs whose play order is exactly [order]. */
        fun of(vararg order: Int) = OrderedTestTimeline(order.size, ShuffleOrder.DefaultShuffleOrder(order, 42L))
    }
}
