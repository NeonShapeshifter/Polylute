package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B31 P1.1 (auditor R30-F1-01): **every safety point of a plan is confirmed before the plan depends on it.**
 *
 * B30-fix1 ordered the SETs so that no *predicted* state was above the limit, but a transfer that returned a count was taken as the
 * SET applied: a DAC that receives a whole report and ignores **one** SET, while applying the next, put a +6 dB peak live (the pre-gain
 * that was to hold it down ignored; or the cut that was to make room ignored while the pre-gain came back up). The auditor reproduced both
 * on the fake DAC: `+6.000000000000068 dB`, then `Mismatch(restored = true)` after the save.
 *
 * Now every gain and band SET is confirmed before the next goes out, by the DAC's own acknowledgement when it sends a usable one and by a
 * read-back when it does not; a contrary acknowledgement stops the plan; and where a step relies on an earlier one (a band that adds
 * boost on the lowered pre-gain; a pre-gain that gives headroom back on the cuts) that earlier value is read back first, whatever the
 * acknowledgement said. The level of what the DAC holds is read **after every transfer** (the fake's `afterSet`) in every case.
 */
class DacEqConfirmedPlanTest {
    private val melody = signedMelody
    private val slot = 0xA2
    private val dirs = mutableListOf<File>()

    @AfterEach
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private fun curve(vararg gains: Pair<Int, Double>): List<DacEqBand> {
        val map = gains.toMap()
        return (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], map[it] ?: 0.0, 0.71) }
    }

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> =
            HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
    }

    private inner class Rig(ackMode: FakeFiioDac.AckMode = FakeFiioDac.AckMode.ECHO) {
        val dac = FakeFiioDac(melody, activePreset = 0xF0, ackMode = ackMode)
        val log = mutableListOf<String>()
        val store = DacEqBackupStore(Files.createTempDirectory("dac-eq-confirmed").toFile().also { dirs += it })
        val att = AttachedFiio("path-a", "identity-a", "profile-a", "Melody A", "path-a", melody)
        val published = mutableListOf<DacEqState>()
        val invalidated = mutableListOf<String>()
        val writer = DacEqWriter(
            DacEqHidAccess(FakeOpener(dac), Dispatchers.Unconfined), DacEqWriteGate(), store, { true }, { 0L }, { 1_700_000_000_000L },
            publish = { _, state -> published += state }, pause = {}, log = { log += it }, invalidate = { _, why -> invalidated += why },
        )

        /** The level of the app's slot after each transfer that left it selected, in the order they happened. */
        val levels = mutableListOf<Double>()
        val trace = mutableListOf<String>()

        init {
            val state = FiioEqReader.read(FiioHidSession(dac, melody))
            val backup = DacEqBackup.of(att, state, 1L)!!
            store.save(backup)
            store.update(backup.withSlot(dac.slot(slot).let { DacEqSlotSnapshot(slot, it.preGainTenths, it.bands.toList()) }))
            dac.afterSet = { d, report ->
                val command = report[5].toInt() and 0xFF
                val live = d.slot(d.activePreset)
                val net = DacEqCurve.netPeakDb(live.bands, live.preGainTenths)
                trace += "SET %02X → preset 0x%02X net %.2f dB".format(command, d.activePreset, net)
                if (d.activePreset == slot) levels += net
            }
        }

        fun apply(bands: List<DacEqBand>) = runBlocking { writer.applyCurve(att, bands, WriteInitiator.USER_ACTION) }
        fun sentCommands() = dac.sets.map { it.first }
        fun held() = dac.slot(slot).let { it.preGainTenths to it.bands.toList() }
        fun noMoreThanTheLimit(what: String) {
            assertWithMessage("$what: the level after each transfer: $trace").that(levels).isNotEmpty()
            assertWithMessage("$what: the loudest state was ${levels.max()} dB; $trace").that(levels.max()).isAtMost(0.0)
        }
    }

    // ── the cooperative cases: every step is confirmed, and by what ───────────────────────────────────────

    @Test
    fun `with a DAC that acknowledges, every gain and band is confirmed by its acknowledgement`() {
        val r = Rig()
        assertThat(r.apply(curve(4 to 6.0))).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.log.count { it.contains("ACK pre-gain ok") }).isEqualTo(1)
        assertThat(r.log.count { it.contains("ACK band 5 ok") }).isEqualTo(1)
        assertThat(r.log.none { it.contains("reading the value back instead") }).isTrue()
        // The band adds boost on the pre-gain this plan lowered: that value is read back first, whatever the acknowledgement said.
        assertThat(r.log.any { it.contains("READBACK pre-gain") && it.contains("before band 5 adds boost") }).isTrue()
        r.noMoreThanTheLimit("flat → +6 dB")
    }

    @Test
    fun `with a DAC that sends nothing or a bare counter, every step is read back instead and the log says so`() {
        for (mode in listOf(FakeFiioDac.AckMode.NONE, FakeFiioDac.AckMode.BARE)) {
            val r = Rig(mode)
            assertThat(r.apply(curve(4 to 6.0))).isInstanceOf(DacEqWriteResult.Done::class.java)
            assertWithMessage("$mode").that(r.log.count { it.contains("no usable acknowledgement") && it.contains("reading the value back instead") })
                .isAtLeast(2)
            assertThat(r.log.any { it.contains("READBACK pre-gain") && it.contains("instead of an acknowledgement") }).isTrue()
            assertThat(r.log.any { it.contains("READBACK band 5") && it.contains("instead of an acknowledgement") }).isTrue()
            r.noMoreThanTheLimit("flat → +6 dB, acknowledgements $mode")
        }
    }

    @Test
    fun `a DAC that answers under the reply head is understood too`() {
        val r = Rig(FakeFiioDac.AckMode.REPLY_HEAD)
        assertThat(r.apply(curve(4 to 6.0))).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.log.none { it.contains("reading the value back instead") }).isTrue()
        r.noMoreThanTheLimit("reply-headed acknowledgements")
    }

    @Test
    fun `the acknowledgement of every SET is logged with its bytes, and nothing is drained unread`() {
        val r = Rig()
        r.apply(curve(4 to 6.0))
        assertThat(r.log.count { it.startsWith("DAC_HID|ACK ") && it.contains("len=") && it.contains("AA 0A") }).isAtLeast(3)
        assertThat(r.log.none { it.contains("drained stale") }).isTrue()
    }

    // ── a contrary acknowledgement stops the plan ───────────────────────────────────────────────────────

    @Test
    fun `an acknowledgement that contradicts the pre-gain stops the plan before any band that adds boost, and the rollback is within the limit`() {
        val r = Rig()
        r.dac.contraryAcks += 0x17
        val result = r.apply(curve(4 to 6.0))
        assertThat(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertThat((result as DacEqWriteResult.Failed).why).contains("pre-gain was not confirmed")
        assertThat(r.log.any { it.contains("ACK pre-gain CONTRARY") }).isTrue()
        // No band SET went out: nothing that relied on the pre-gain was added.
        assertThat(r.sentCommands()).doesNotContain(0x15)
        r.noMoreThanTheLimit("contrary acknowledgement of the pre-gain")
    }

    @Test
    fun `a contrary acknowledgement of a band stops the plan at that band`() {
        val r = Rig()
        r.dac.contraryAcks += 0x15
        val result = r.apply(curve(4 to 6.0, 5 to 2.0))
        assertThat(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertThat((result as DacEqWriteResult.Failed).why).contains("was not confirmed")
        // Exactly one band was sent: the one whose acknowledgement contradicted.
        assertThat(r.dac.sets.count { it.first == 0x15 }).isAtLeast(1)
        assertThat(r.log.count { it.contains("ACK band") && it.contains("CONTRARY") }).isAtLeast(1)
        r.noMoreThanTheLimit("contrary acknowledgement of a band")
    }

    // ── the auditor's shape: one SET ignored, the transfer successful ───────────────────────────────────

    @Test
    fun `flat to a peak, the pre-gain SET ignored and not acknowledged, the band that adds boost is never sent`() {
        val r = Rig()
        r.dac.ignoreNthSet = 2 // 1 select, 2 pre-gain
        val result = r.apply(curve(4 to 6.0))
        assertThat(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertThat((result as DacEqWriteResult.Failed).why).contains("pre-gain was not confirmed")
        assertThat(r.log.any { it.contains("CONFIRM pre-gain: no usable acknowledgement") }).isTrue()
        assertThat(r.dac.sets.filter { it.first == 0x15 && (it.second[0].toInt() and 0xFF) == 4 && it.second[2].toInt() != 0 }).isEmpty()
        r.noMoreThanTheLimit("flat → +6 dB with the pre-gain ignored")
        assertThat(r.held().second.all { it.gainTenthsDb == 0 }).isTrue()
    }

    @Test
    fun `flat to a peak, the pre-gain SET ignored but acknowledged anyway, the read-back before the band stops it`() {
        val r = Rig()
        r.dac.ignoreNthSet = 2
        r.dac.ackIgnored = true // a DAC that lies: the echo says it was taken
        val result = r.apply(curve(4 to 6.0))
        assertThat(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertThat((result as DacEqWriteResult.Failed).why).contains("the pre-gain this plan lowered is not confirmed")
        assertThat(r.log.any { it.contains("ACK pre-gain ok") }).isTrue() // the acknowledgement did say so
        assertThat(r.dac.sets.count { it.first == 0x15 }).isEqualTo(0) // and still no band went out
        r.noMoreThanTheLimit("flat → +6 dB with the pre-gain ignored and acknowledged")
    }

    private fun boostedRig(mode: FakeFiioDac.AckMode = FakeFiioDac.AckMode.ECHO): Rig {
        val r = Rig(mode)
        assertThat(r.apply(curve(4 to 6.0))).isInstanceOf(DacEqWriteResult.Done::class.java)
        r.levels.clear(); r.trace.clear(); r.log.clear()
        return r
    }

    @Test
    fun `a peak to flat, the cut SET ignored and not acknowledged, the pre-gain is never raised`() {
        val r = boostedRig()
        val before = r.held().first
        r.dac.ignoreNthSet = 5 // 1 select, 2 pre-gain, 3 band, 4 save of the first write; this one's first SET (the cut) is the 5th
        val result = r.apply(curve())
        assertThat(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertThat((result as DacEqWriteResult.Failed).why).contains("was not confirmed")
        // The SET after the cut that was not confirmed is not the pre-gain: the plan stopped, and the rollback sends the cut again first.
        assertThat(r.sentCommands()[5]).isEqualTo(0x15)
        r.noMoreThanTheLimit("+6 dB → flat with the cut ignored")
        assertThat(before).isLessThan(0)
    }

    @Test
    fun `a peak to flat, the cut SET ignored but acknowledged anyway, the bands are read back before the pre-gain gives headroom back`() {
        val r = boostedRig()
        r.dac.ignoreNthSet = 5
        r.dac.ackIgnored = true
        val result = r.apply(curve())
        assertThat(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertThat((result as DacEqWriteResult.Failed).why).contains("a band this plan changed is not confirmed")
        r.noMoreThanTheLimit("+6 dB → flat with the cut ignored and acknowledged")
    }

    // ── the rollback follows the same rule ──────────────────────────────────────────────────────────────

    @Test
    fun `a rollback that cannot confirm its own steps stops, and the labels are told what the DAC reads or that it is unknown`() {
        val r = Rig()
        r.dac.contraryAcks += 0x17 // every pre-gain acknowledgement contradicts, the rollback's too
        val result = r.apply(curve(4 to 6.0))
        assertThat(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertWithMessage(r.log.joinToString("\n")).that((result as DacEqWriteResult.Failed).restored).isFalse()
        assertThat(r.log.any { it.contains("the rollback stopped") }).isTrue()
        r.noMoreThanTheLimit("a rollback that cannot confirm")
        // Nobody keeps saying "off": the state read after the failure was published, or the reading was invalidated.
        val told = r.published.lastOrNull()?.verdict
        assertWithMessage(r.log.joinToString("\n")).that(told != DacEqVerdict.OFF || r.invalidated.isNotEmpty()).isTrue()
    }

    // ── the transfer length ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a pre-gain transfer that comes back short is a failure, and no band follows it`() {
        val r = Rig()
        r.dac.shortWriteNthSet = 2
        val result = r.apply(curve(4 to 6.0))
        assertThat(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        assertThat((result as DacEqWriteResult.Failed).why).contains("pre-gain could not be sent")
        assertThat(r.log.any { it.contains("SHORT WRITE") }).isTrue()
        assertThat(r.sentCommands()).doesNotContain(0x15)
        r.noMoreThanTheLimit("a short write of the pre-gain")
    }

    // ── the guard, just before the first SET (auditor R30-F1-02, second half) ──────────────────────────

    @Test
    fun `a guard that changes after the last authorisation and before the first SET sends nothing at all`() {
        val dac = FakeFiioDac(melody, activePreset = 0xF0)
        val log = mutableListOf<String>()
        val store = DacEqBackupStore(Files.createTempDirectory("dac-eq-confirmed").toFile().also { dirs += it })
        val att = AttachedFiio("path-a", "identity-a", "profile-a", "Melody A", "path-a", melody)
        val state = FiioEqReader.read(FiioHidSession(dac, melody))
        val backup = DacEqBackup.of(att, state, 1L)!!
        store.save(backup)
        store.update(backup.withSlot(dac.slot(slot).let { DacEqSlotSnapshot(slot, it.preGainTenths, it.bands.toList()) }))
        var flipped = false
        val writer = DacEqWriter(
            DacEqHidAccess(FakeOpener(dac), Dispatchers.Unconfined), DacEqWriteGate(), store, { true }, { 0L }, { 1L },
            pause = {},
            // The auditor's probe changed the guard from the hook of the log line of the authorisation, before any SET: so does this.
            log = { line ->
                log += line
                if (line.contains("GATE APPLY_CURVE authorised")) flipped = true
            },
        )
        val result = runBlocking {
            writer.applyCurve(att, curve(4 to 3.0), WriteInitiator.USER_ACTION) { if (flipped) "PURITY_ON" else null }
        }
        assertThat(result).isEqualTo(DacEqWriteResult.Refused(GateRefusal.EDIT_OUTDATED, "PURITY_ON"))
        assertThat(dac.sets).isEmpty()
        assertThat(dac.written.none { (it[1].toInt() and 0xFF) == 0xAA }).isTrue()
        assertThat(log.any { it.contains("EDIT_OUTDATED just before the first SET") }).isTrue()
    }
}
