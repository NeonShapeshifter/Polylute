package com.lostf1sh.pixelplayeross.boundary

import com.decent.usbaudio.media3.AnchorSource
import com.decent.usbaudio.media3.MappingEpochTracker
import com.decent.usbaudio.media3.MappingState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Contract tests for the epoch-scoped mapping.
 *
 * These pin the invariant rather than the symptoms: a mapping may be established only from a
 * presentation timestamp and an anchor that describe the *same* playback-position epoch. A
 * numerically old anchor is valid when the timestamp describes that same position — the earlier
 * benign latch proved that — so several of these tests exist specifically to make sure a fix does
 * not over-reject.
 */
class MappingEpochTest {

    private val base = 1_000_000_000_000L

    private fun tracker() = MappingEpochTracker()

    @Nested
    @DisplayName("TEST 1 / TEST 7 — restart and restore must not reuse a previous position")
    inner class NewPositionEpoch {

        @Test
        fun `restart at zero does not latch against the previous EOF anchor`() {
            val t = tracker()

            // The failing sequence: an epoch anchored at EOF, then a restart at 0 opening a new one.
            t.beginEpoch(anchorMediaPositionUs = 356_893_000L, source = AnchorSource.RESTORED_POSITION)
            // The restart establishes its own epoch before any buffer of it arrives.
            val restarted = t.beginEpoch(anchorMediaPositionUs = 0L, source = AnchorSource.IMPLICIT_RESTART)

            assertThat(restarted.epochId).isGreaterThan(1L)
            assertThat(restarted.anchorMediaPositionUs).isEqualTo(0L)

            t.latch(presentationTimeUs = base)

            // The historical failure produced base - 356_893_000 here.
            assertThat(t.windowOffsetUs).isEqualTo(base)
            assertThat(t.state).isInstanceOf(MappingState.Latched::class.java)
        }

        @Test
        fun `a restored EOF anchor is valid for its own epoch`() {
            val t = tracker()
            t.beginEpoch(anchorMediaPositionUs = 356_893_000L, source = AnchorSource.RESTORED_POSITION)
            t.latch(presentationTimeUs = base + 356_893_000L)
            assertThat(t.windowOffsetUs).isEqualTo(base)
        }
    }

    @Nested
    @DisplayName("TEST 2 / TEST 10 — numeric age alone is never a reason to reject")
    inner class BenignStaleAnchor {

        @Test
        fun `an old anchor latches normally when the timestamp describes that same position`() {
            val t = tracker()

            // Deliberately the value that corrupted the mapping in the failing runs...
            t.beginEpoch(anchorMediaPositionUs = 356_880_000L, source = AnchorSource.RESTORED_POSITION)
            // ...paired with a timestamp that describes that same position, which is benign.
            t.latch(presentationTimeUs = base + 356_880_000L)

            assertThat(t.isLatched).isTrue()
            assertThat(t.windowOffsetUs).isEqualTo(base)
            assertThat(t.refusedLatchCount).isEqualTo(0L)
        }

        @Test
        fun `a large anchor never produces a rejection on its own`() {
            val t = tracker()
            t.beginEpoch(anchorMediaPositionUs = Long.MAX_VALUE / 4, source = AnchorSource.EXPLICIT_SEEK_TARGET)
            t.latch(presentationTimeUs = base + Long.MAX_VALUE / 4)
            assertThat(t.isLatched).isTrue()
        }
    }

    @Nested
    @DisplayName("TEST 3 / TEST 4 — seeks in both directions open a new epoch at the target")
    inner class Seeks {

        @Test
        fun `forward seek anchors at the target`() {
            val t = tracker()
            t.beginEpoch(30_000_000L, AnchorSource.DECLARED_ITEM_START)
            t.latch(base + 30_000_000L)

            t.beginEpoch(200_000_000L, AnchorSource.EXPLICIT_SEEK_TARGET)
            t.latch(base + 200_000_000L)

            assertThat(t.windowOffsetUs).isEqualTo(base)
            assertThat(t.anchorMediaPositionUs).isEqualTo(200_000_000L)
        }

        @Test
        fun `backward seek anchors at the target`() {
            val t = tracker()
            t.beginEpoch(200_000_000L, AnchorSource.DECLARED_ITEM_START)
            t.latch(base + 200_000_000L)

            t.beginEpoch(30_000_000L, AnchorSource.EXPLICIT_SEEK_TARGET)
            t.latch(base + 30_000_000L)

            assertThat(t.windowOffsetUs).isEqualTo(base)
            assertThat(t.anchorMediaPositionUs).isEqualTo(30_000_000L)
        }

        @Test
        fun `the backward seek never subtracts the pre-seek anchor`() {
            val t = tracker()
            t.beginEpoch(200_000_000L, AnchorSource.DECLARED_ITEM_START)
            t.latch(base + 200_000_000L)
            t.beginEpoch(30_000_000L, AnchorSource.EXPLICIT_SEEK_TARGET)
            t.latch(base + 30_000_000L)

            // Had the old anchor survived, the offset would be base - 170_000_000.
            assertThat(t.windowOffsetUs).isNotEqualTo(base - 170_000_000L)
        }
    }

    @Nested
    @DisplayName("TEST 5 — an item transition never inherits the previous item's anchor")
    inner class ItemTransition {

        @Test
        fun `the new item opens its own epoch`() {
            val t = tracker()
            t.beginEpoch(200_000_000L, AnchorSource.DECLARED_ITEM_START)
            t.latch(base + 200_000_000L)

            t.beginEpoch(0L, AnchorSource.ITEM_TRANSITION_START)
            t.latch(base)

            assertThat(t.anchorMediaPositionUs).isEqualTo(0L)
            assertThat(t.anchorSource).isEqualTo(AnchorSource.ITEM_TRANSITION_START)
            assertThat(t.windowOffsetUs).isEqualTo(base)
        }
    }

    @Nested
    @DisplayName("TEST 6 — a same-position rebuild preserves the mapping")
    inner class SamePositionRebuild {

        @Test
        fun `a realign preserves the latched mapping and its epoch`() {
            val t = tracker()
            t.beginEpoch(12_108_000L, AnchorSource.RESTORED_POSITION)
            t.latch(base + 12_108_000L)
            val before = t.state

            t.preserve()

            assertThat(t.state).isEqualTo(before)
            assertThat(t.windowOffsetUs).isEqualTo(base)
            assertThat(t.epochId).isEqualTo(1L)
        }

        @Test
        fun `a rebuild cannot re-latch from a later timestamp`() {
            val t = tracker()
            t.beginEpoch(12_108_000L, AnchorSource.RESTORED_POSITION)
            t.latch(base + 12_108_000L)

            t.preserve()
            // A buffer from a much later position must not move the established mapping.
            t.latch(base + 90_000_000L)

            assertThat(t.windowOffsetUs).isEqualTo(base)
        }

        @Test
        fun `an unlatched epoch survives a rebuild with its anchor`() {
            val t = tracker()
            t.beginEpoch(12_108_000L, AnchorSource.RESTORED_POSITION)
            t.preserve()

            assertThat(t.state).isInstanceOf(MappingState.Unlatched::class.java)
            assertThat(t.anchorMediaPositionUs).isEqualTo(12_108_000L)
        }
    }

    @Nested
    @DisplayName("TEST 8 / TEST 9 — no latch without an anchor, and never before one exists")
    inner class LatchOrdering {

        @Test
        fun `a latch with no epoch open is refused rather than guessed`() {
            val t = tracker()
            val result = t.latch(base)

            assertThat(result).isEqualTo(MappingState.None)
            assertThat(t.isLatched).isFalse()
            assertThat(t.windowOffsetUs).isEqualTo(-1L)
            assertThat(t.refusedLatchCount).isEqualTo(1L)
        }

        @Test
        fun `beginning an epoch makes the anchor available before any latch`() {
            val t = tracker()
            val opened = t.beginEpoch(0L, AnchorSource.IMPLICIT_RESTART)

            // The anchor exists at the moment the epoch opens — before the first timestamp.
            assertThat(opened.anchorMediaPositionUs).isEqualTo(0L)
            assertThat(t.anchorMediaPositionUs).isEqualTo(0L)
            assertThat(t.isLatched).isFalse()
        }

        @Test
        fun `a refused latch does not leave a partial mapping behind`() {
            val t = tracker()
            t.latch(base)
            assertThat(t.state).isEqualTo(MappingState.None)
            assertThat(t.windowOffsetUs).isEqualTo(-1L)
        }
    }

    @Nested
    @DisplayName("Provenance and the period-offset cross-check")
    inner class Provenance {

        @Test
        fun `a latch records the epoch and anchor it was established from`() {
            val t = tracker()
            t.beginEpoch(30_000_000L, AnchorSource.EXPLICIT_SEEK_TARGET)
            t.latch(base + 30_000_000L)

            val latched = t.state as MappingState.Latched
            assertThat(latched.epochId).isEqualTo(1L)
            assertThat(latched.anchorMediaPositionUs).isEqualTo(30_000_000L)
            assertThat(latched.anchorSource).isEqualTo(AnchorSource.EXPLICIT_SEEK_TARGET)
        }

        @Test
        fun `the period offset cross-check reports a span, and does not reject`() {
            val t = tracker()
            t.beginEpoch(0L, AnchorSource.IMPLICIT_RESTART)
            t.latch(base)

            assertThat(t.periodOffsetMismatchUs(base)).isEqualTo(0L)
            // The check reports a mismatch; it is evidence, not an acceptance rule.
            assertThat(t.periodOffsetMismatchUs(base + 5L)).isEqualTo(-5L)
            assertThat(t.isLatched).isTrue()
        }

        @Test
        fun `the cross-check is unavailable when nothing is latched`() {
            val t = tracker()
            t.beginEpoch(0L, AnchorSource.IMPLICIT_RESTART)
            assertThat(t.periodOffsetMismatchUs(base)).isNull()
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    inner class Lifecycle {

        @Test
        fun `epoch ids increase monotonically across position changes`() {
            val t = tracker()
            val first = t.beginEpoch(0L, AnchorSource.DECLARED_ITEM_START).epochId
            val second = t.beginEpoch(30_000_000L, AnchorSource.EXPLICIT_SEEK_TARGET).epochId
            val third = t.beginEpoch(0L, AnchorSource.IMPLICIT_RESTART).epochId

            assertThat(second).isGreaterThan(first)
            assertThat(third).isGreaterThan(second)
        }

        @Test
        fun `reset clears the mapping and the epoch`() {
            val t = tracker()
            t.beginEpoch(0L, AnchorSource.DECLARED_ITEM_START)
            t.latch(base)
            t.reset()

            assertThat(t.state).isEqualTo(MappingState.None)
            assertThat(t.windowOffsetUs).isEqualTo(-1L)
            assertThat(t.epochId).isEqualTo(-1L)
        }
    }
}
