package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.DacLevelGuard
import com.decent.usbaudio.DacLockState
import com.decent.usbaudio.FeatureUnitPort
import com.decent.usbaudio.OpenLevelCheck
import com.decent.usbaudio.ResetLevelRestorer
import com.decent.usbaudio.StreamingBusReset
import com.decent.usbaudio.UsbVolumeRange
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B47-A5, step 2 of a locked-up DAC's recovery: the bus reset in the middle of a stream.
 *
 * What is proven here is the order (reset, the claims at once with streaming first, only then the level) and the volume guarantee: **the level never comes back higher than it was**.
 * The guarantee is the code every open already uses: [DacLevelGuard.restoreAfterReconfigure] -> [com.decent.usbaudio.DirectOpenLevelPolicy.target], which writes a level only when it
 * is below the one the DAC reports, reads it back, and answers UNVERIFIED when the read-back is above the target. The tests below drive the REAL guard and policy against a fake
 * Feature Unit that boots at 100 % after a reset, like the Melody can.
 */
class StreamingBusResetTest {

    /** A DAC whose Feature Unit boots at [bootUnits] after a bus reset. */
    private class FakeFeatureUnit(var units: Int, private val range: UsbVolumeRange, private val bootUnits: Int, private val writesTake: Boolean = true) : FeatureUnitPort {
        val writes = ArrayList<Int>()
        fun reset() { units = bootUnits }
        override fun readRange(): UsbVolumeRange? = range
        override fun readUnits(): Int? = units
        override fun writeUnits(units: Int): Int? {
            writes += units
            if (writesTake) this.units = units
            return this.units
        }
    }

    private val range = UsbVolumeRange(minUnits = -100 * 256, maxUnits = 0, resolutionUnits = 1)
    private val db = 256

    private fun sequence(
        events: MutableList<String>,
        unit: FakeFeatureUnit?,
        savedUnits: Int?,
        guardUnits: Int?,
        resetOk: Boolean = true,
        streamingClaims: Boolean = true,
        controlClaims: Boolean = true,
        hasControl: Boolean = true,
    ): StreamingBusReset {
        val guard = DacLevelGuard { events += "log:$it" }
        return StreamingBusReset(
            reset = { events += "reset"; unit?.reset(); resetOk },
            hasStreaming = true,
            hasControl = hasControl,
            claimStreaming = { events += "claim-streaming"; streamingClaims },
            claimControl = { events += "claim-control"; controlClaims },
            restoreLevel = {
                events += "restore-level"
                if (unit == null) OpenLevelCheck.NO_HARDWARE_VOLUME else guard.restoreAfterReconfigure(unit, savedUnits, guardUnits, "bus-reset-recovery")
            },
        )
    }

    @Test
    fun `the claims come at once after the reset, streaming first, and the level only after them`() {
        val events = ArrayList<String>()
        val unit = FakeFeatureUnit(units = -30 * db, range = range, bootUnits = 0)
        val result = sequence(events, unit, savedUnits = -30 * db, guardUnits = null).run()
        val steps = events.filter { !it.startsWith("log:") }
        assertThat(steps).containsExactly("reset", "claim-streaming", "claim-control", "restore-level").inOrder()
        assertThat(result.ok).isTrue()
        assertThat(result.outcome).isEqualTo(ResetLevelRestorer.Outcome.RESTORED)
        assertThat(result.heldStreaming).isTrue()
        assertThat(result.heldControl).isTrue()
        assertThat(result.levelOwed).isFalse()
    }

    @Test
    fun `a DAC that boots at full level comes back no louder than the level it had`() {
        for ((saved, guard) in listOf(-30 * db to null, -30 * db to -40 * db, null to -25 * db, -12 * db to -12 * db)) {
            val events = ArrayList<String>()
            val unit = FakeFeatureUnit(units = saved ?: guard!!, range = range, bootUnits = 0)   // 0 dB = 100 % after the reset
            val result = sequence(events, unit, savedUnits = saved, guardUnits = guard).run()
            val ceiling = listOfNotNull(saved, guard).min()
            assertThat(unit.units).isAtMost(ceiling)                           // never above the quietest of the saved level and the guard
            assertThat(unit.writes).isNotEmpty()
            assertThat(unit.writes.max()).isAtMost(ceiling)                    // and no write ever put it higher
            assertThat(result.ok).isTrue()
        }
    }

    @Test
    fun `a DAC that already comes back quieter than its ceiling is not raised`() {
        val events = ArrayList<String>()
        val unit = FakeFeatureUnit(units = -30 * db, range = range, bootUnits = -50 * db)   // boots at -50 dB, the saved level is -30 dB
        val result = sequence(events, unit, savedUnits = -30 * db, guardUnits = null).run()
        assertThat(unit.units).isEqualTo(-50 * db)   // untouched: the policy only ever lowers
        assertThat(unit.writes).isEmpty()
        assertThat(result.ok).isTrue()
    }

    @Test
    fun `a restore that does not take leaves the level owed and the stream not to go on`() {
        val events = ArrayList<String>()
        val unit = FakeFeatureUnit(units = -30 * db, range = range, bootUnits = 0, writesTake = false)   // the write is accepted but the DAC stays at 0 dB
        val result = sequence(events, unit, savedUnits = -30 * db, guardUnits = null).run()
        assertThat(result.ok).isFalse()
        assertThat(result.outcome).isEqualTo(ResetLevelRestorer.Outcome.UNCONFIRMED)
        assertThat(result.levelOwed).isTrue()
        assertThat(result.heldStreaming).isTrue()       // Polylute keeps both interfaces: Android cannot reach the DAC at a level nobody confirmed
        assertThat(result.heldControl).isTrue()
    }

    @Test
    fun `a failed reset, and interfaces that cannot be claimed again, are not ok and say who holds what`() {
        run {
            val events = ArrayList<String>()
            val result = sequence(events, FakeFeatureUnit(-30 * db, range, 0), -30 * db, null, resetOk = false).run()
            assertThat(result.ok).isFalse()
            assertThat(result.outcome).isEqualTo(ResetLevelRestorer.Outcome.RESET_FAILED)
            assertThat(events.filter { it.startsWith("claim") }).isEmpty()      // nothing was claimed after a reset that did not happen
        }
        run {
            val result = sequence(ArrayList(), FakeFeatureUnit(-30 * db, range, 0), -30 * db, null, streamingClaims = false).run()
            assertThat(result.ok).isFalse()
            assertThat(result.outcome).isEqualTo(ResetLevelRestorer.Outcome.STREAMING_NOT_HELD)
            assertThat(result.heldStreaming).isFalse()
            // nothing is released here (the stream was going on): Polylute still holds exactly what its claims got, control, and the record says so
            assertThat(result.heldControl).isTrue()
        }
        run {
            val result = sequence(ArrayList(), FakeFeatureUnit(-30 * db, range, 0), -30 * db, null, controlClaims = false).run()
            assertThat(result.ok).isFalse()
            assertThat(result.outcome).isEqualTo(ResetLevelRestorer.Outcome.CONTROL_NOT_HELD)
        }
    }

    @Test
    fun `a DAC without a hardware volume still has its interfaces claimed again`() {
        val events = ArrayList<String>()
        val result = sequence(events, unit = null, savedUnits = null, guardUnits = null).run()
        assertThat(events.filter { !it.startsWith("log:") }).containsExactly("reset", "claim-streaming", "claim-control", "restore-level").inOrder()
        assertThat(result.ok).isTrue()
        assertThat(result.outcome).isEqualTo(ResetLevelRestorer.Outcome.NO_HARDWARE_VOLUME)
        assertThat(result.heldStreaming && result.heldControl).isTrue()
    }

    @Test
    fun `the native state array is read as it is laid out`() {
        val state = DacLockState.of(longArrayOf(1, 3, 1520, 49899, 4, 2))
        assertThat(state.locked).isTrue()
        assertThat(state.locks).isEqualTo(3L)
        assertThat(state.railedMs).isEqualTo(1520L)
        assertThat(state.railPpm).isEqualTo(49899L)
        assertThat(state.lateReaps).isEqualTo(4L)
        assertThat(state.step).isEqualTo(DacLockState.STEP_BUS_RESET)
        assertThat(DacLockState.of(longArrayOf(1, 2))).isEqualTo(DacLockState.NONE)   // a short array (an older native library) is no step
        assertThat(DacLockState.of(null)).isEqualTo(DacLockState.NONE)
    }
}
