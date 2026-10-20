package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.RendererOffsetLedger
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-136 (a): the next item's window offset is Media3's own renderer offset, not our arithmetic.
 *
 * B7-fix computed it as "previous offset + length". Any error in the previous offset — a latch
 * against a stale anchor, a seek past the end, a watchdog correction — was carried into the next
 * item: measured, a track that had just started sat at +167 s / +204 s and Media3 ended or sought it.
 *
 * Media3 hands every stream's renderer offset to the sink (`AudioSink.setOutputStreamOffsetUs`) as
 * its output reaches it: consecutive periods get strictly increasing offsets (previous + duration),
 * and a queue reset (seek to another item) or a renderer re-enable starts again from
 * 1_000_000_000_000. The ledger follows that sequence; the item the engine plays is found by
 * counting Media3's own transitions, never by adding lengths.
 */
class RendererOffsetLedgerTest {

    private companion object {
        const val START = 1_000_000_000_000L
        const val REPEAT = 0
        const val AUTO = 1
        const val SEEK = 2
    }

    /** Media3's side: period holders with their renderer offsets, reporting as ExoPlayer does. */
    private class Media3(private val durationsUs: List<Long>, private val ledger: RendererOffsetLedger) {
        private data class Holder(val item: Int, val offsetUs: Long)
        private val holders = ArrayList<Holder>()
        private var playing = 0

        val playingItem: Int get() = holders[playing].item
        val playingOffsetUs: Long get() = holders[playing].offsetUs

        /** Queue reset (start, NEXT/PREV to another item): renderers re-enabled at the initial offset. */
        fun startAt(item: Int, reportNow: Boolean = true) {
            holders.clear()
            holders += Holder(item, START)
            playing = 0
            if (reportNow) ledger.onStreamOffsetReported(START)
        }

        /** The renderer reads into the next period: its offset reaches the sink ahead of playback. */
        fun readAhead(nextItem: Int, reportNow: Boolean = true) {
            val last = holders.last()
            holders += Holder(nextItem, last.offsetUs + durationsUs[last.item])
            if (reportNow) ledger.onStreamOffsetReported(holders.last().offsetUs)
        }

        /** A report that was held back until the old stream's last output buffer was processed. */
        fun reportLate() = ledger.onStreamOffsetReported(holders.last().offsetUs)

        fun transition(reason: Int = AUTO) {
            playing++
            ledger.onItemTransition(reason)
        }

        /** Seek inside the playing item: read-ahead dropped, renderer re-enabled on the playing period. */
        fun seekWithin(hadReadAhead: Boolean) {
            if (hadReadAhead) {
                while (holders.size > playing + 1) holders.removeAt(holders.size - 1)
                ledger.onStreamOffsetReported(playingOffsetUs)
            }
        }

        fun seekToItem(item: Int) {
            startAt(item)
            ledger.onItemTransition(SEEK)
        }
    }

    private val durations = listOf(237_026_666L, 212_853_000L, 165_634_666L, 204_253_333L, 169_653_000L, 180_000_000L)

    @Test
    fun `mixed sequence - natural end, seek, natural end, NEXT, natural end - always Media3's offset`() {
        val ledger = RendererOffsetLedger()
        val m = Media3(durations, ledger)
        m.startAt(0)
        assertThat(ledger.playingOffsetUs()).isEqualTo(m.playingOffsetUs)

        // Natural end 0 → 1, read ahead in the last seconds.
        m.readAhead(1); m.transition()
        assertThat(ledger.playingOffsetUs()).isEqualTo(START + durations[0])

        // Manual seek inside item 1 (nothing read ahead yet), then its natural end.
        m.seekWithin(hadReadAhead = false)
        m.readAhead(2); m.transition()
        assertThat(ledger.playingOffsetUs()).isEqualTo(m.playingOffsetUs)

        // NEXT to item 3: queue reset, back to the initial offset.
        m.seekToItem(3)
        assertThat(ledger.playingOffsetUs()).isEqualTo(START)

        // Its natural end: the first item after a reset, so its end is at START + its length.
        m.readAhead(4); m.transition()
        assertThat(ledger.playingOffsetUs()).isEqualTo(START + durations[3])
        assertThat(m.playingItem).isEqualTo(4)
    }

    @Test
    fun `a seek inside an item that had read ahead goes back to that item's offset`() {
        val ledger = RendererOffsetLedger()
        val m = Media3(durations, ledger)
        m.startAt(0)
        m.readAhead(1)
        m.seekWithin(hadReadAhead = true)
        assertThat(ledger.playingOffsetUs()).isEqualTo(START)
        // And the next natural end still lands on Media3's offset for item 1.
        m.readAhead(1); m.transition()
        assertThat(ledger.playingOffsetUs()).isEqualTo(START + durations[0])
    }

    @Test
    fun `a report that arrives after the transition is picked up, nothing is invented before it`() {
        val ledger = RendererOffsetLedger()
        val m = Media3(durations, ledger)
        m.startAt(2)
        m.readAhead(3, reportNow = false)
        m.transition()
        assertThat(ledger.playingOffsetUs()).isNull()
        m.reportLate()
        assertThat(ledger.playingOffsetUs()).isEqualTo(START + durations[2])
    }

    @Test
    fun `repeat-one is a new period of the same item at the next offset`() {
        val ledger = RendererOffsetLedger()
        val m = Media3(durations, ledger)
        m.startAt(5)
        m.readAhead(5); m.transition(REPEAT)
        assertThat(ledger.playingOffsetUs()).isEqualTo(START + durations[5])
        m.readAhead(5); m.transition(REPEAT)
        assertThat(ledger.playingOffsetUs()).isEqualTo(START + 2 * durations[5])
    }

    @Test
    fun `NEXT from the first item of a sequence, when the reset repeats the same offset`() {
        val ledger = RendererOffsetLedger()
        val m = Media3(durations, ledger)
        m.startAt(0)
        m.seekToItem(1)
        assertThat(ledger.playingOffsetUs()).isEqualTo(START)
        // A natural transition whose report was still pending, then NEXT before it came.
        m.readAhead(2, reportNow = false)
        m.transition()
        m.seekToItem(4)
        assertThat(ledger.playingOffsetUs()).isEqualTo(START)
    }

    @Test
    fun `unset offsets are ignored and a long session stays bounded and exact`() {
        val ledger = RendererOffsetLedger()
        ledger.onStreamOffsetReported(Long.MIN_VALUE + 1)   // C.TIME_UNSET on disable
        assertThat(ledger.playingOffsetUs()).isNull()
        val m = Media3(durations, ledger)
        m.startAt(0)
        var expected = START
        repeat(200) { i ->
            val item = i % durations.size
            val next = (i + 1) % durations.size
            m.readAhead(next); m.transition()
            expected += durations[item]
            assertThat(ledger.playingOffsetUs()).isEqualTo(expected)
        }
    }
}
