package com.lostf1sh.pixelplayeross.data.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Phase 13 — the bounded hardware event log.
 *
 * The guarantees are boundedness, ordering and truthfulness of the drop count. A diagnostic log
 * that silently grows, silently reorders, or silently loses events without saying so is worse than
 * no log, because it reads as a complete record.
 */
class HardwareEventLogTest {

    @AfterEach fun tearDown() = HardwareEventLog.clear()

    @Test fun `records and reads back newest first`() {
        HardwareEventLog.record(HardwareEventType.USB_ATTACHED, "2972:0126")
        HardwareEventLog.record(HardwareEventType.DIRECT_ACTIVE, "96000 Hz")
        val snapshot = HardwareEventLog.snapshot()
        assertThat(snapshot).hasSize(2)
        assertThat(snapshot[0].type).isEqualTo(HardwareEventType.DIRECT_ACTIVE)
        assertThat(snapshot[1].type).isEqualTo(HardwareEventType.USB_ATTACHED)
        assertThat(snapshot[0].detail).isEqualTo("96000 Hz")
    }

    @Test fun `an empty log reads as empty rather than failing`() {
        assertThat(HardwareEventLog.snapshot()).isEmpty()
        assertThat(HardwareEventLog.droppedCount()).isEqualTo(0)
    }

    @Test fun `the log never exceeds its bound`() {
        repeat(HardwareEventLog.MAX_EVENTS + 25) { index ->
            HardwareEventLog.record(HardwareEventType.SCREEN_ON, "event $index")
        }
        assertThat(HardwareEventLog.snapshot()).hasSize(HardwareEventLog.MAX_EVENTS)
    }

    /** Oldest evicted, newest retained — and the loss is counted rather than hidden. */
    @Test fun `eviction drops the oldest and counts the loss`() {
        repeat(HardwareEventLog.MAX_EVENTS + 5) { index ->
            HardwareEventLog.record(HardwareEventType.SCREEN_ON, "event $index")
        }
        assertThat(HardwareEventLog.droppedCount()).isEqualTo(5)
        val snapshot = HardwareEventLog.snapshot()
        assertThat(snapshot.first().detail).isEqualTo("event ${HardwareEventLog.MAX_EVENTS + 4}")
        assertThat(snapshot.last().detail).isEqualTo("event 5")
    }

    @Test fun `clear empties the log and resets the drop count`() {
        repeat(HardwareEventLog.MAX_EVENTS + 3) {
            HardwareEventLog.record(HardwareEventType.SCREEN_OFF)
        }
        assertThat(HardwareEventLog.droppedCount()).isGreaterThan(0)
        HardwareEventLog.clear()
        assertThat(HardwareEventLog.snapshot()).isEmpty()
        assertThat(HardwareEventLog.droppedCount()).isEqualTo(0)
    }

    @Test fun `a null detail is allowed and stays null`() {
        HardwareEventLog.record(HardwareEventType.POWER_CONNECTED)
        assertThat(HardwareEventLog.snapshot().single().detail).isNull()
    }

    @Test fun `an oversized detail is truncated rather than stored whole`() {
        val huge = "x".repeat(5_000)
        HardwareEventLog.record(HardwareEventType.USB_ATTACHED, huge)
        val stored = HardwareEventLog.snapshot().single().detail
        assertThat(stored).isNotNull()
        assertThat(stored!!.length).isAtMost(120)
    }

    @Test fun `every event type is recordable`() {
        HardwareEventType.entries.forEach { HardwareEventLog.record(it) }
        assertThat(HardwareEventLog.snapshot()).hasSize(HardwareEventType.entries.size)
    }

    @Test fun `uptime label is readable minutes and seconds`() {
        val event = HardwareEvent(
            type = HardwareEventType.USB_ATTACHED,
            elapsedRealtimeMs = 125_000L,
        )
        assertThat(event.uptimeLabel).isEqualTo("2:05")
    }

    @Test fun `uptime label handles sub-minute and zero`() {
        assertThat(HardwareEvent(HardwareEventType.SCREEN_ON, 0L).uptimeLabel).isEqualTo("0:00")
        assertThat(HardwareEvent(HardwareEventType.SCREEN_ON, 9_000L).uptimeLabel).isEqualTo("0:09")
    }

    /**
     * Recording is called from broadcast receivers, so it must be safe from any thread. This is a
     * smoke test for the synchronisation, not a proof of it.
     */
    @Test fun `concurrent recording does not corrupt the bound`() {
        val threads = (1..4).map { threadIndex ->
            Thread {
                repeat(50) { HardwareEventLog.record(HardwareEventType.SCREEN_ON, "t$threadIndex") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertThat(HardwareEventLog.snapshot().size).isAtMost(HardwareEventLog.MAX_EVENTS)
        assertThat(HardwareEventLog.droppedCount()).isEqualTo(200 - HardwareEventLog.MAX_EVENTS)
    }
}
