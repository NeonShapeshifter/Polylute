package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B30-fix1 H3 (auditor R30-03): **after a failure, every surface tells the same truth.**
 *
 * The first version caught an exception in the middle of a write and returned `Failed(restored = null)` without trying the restore
 * its own comment promised, and only a successful write published anything: a write that changed the DAC and then failed left the
 * label source on the reading from **before** it, which said "off or flat" (Purity) about a DAC that was now playing a curve.
 *
 * Here a failure is injected at every SET of a write, before the SET and after it, in three kinds (an exception that can be recovered
 * from, a DAC that stops answering reads, a cable that comes out), and one thing is asserted each time: what the label source says is
 * what a healthy read of the DAC says now, or "unknown". A stale off/flat never survives a write that changed the DAC. The Resonance
 * screen reads the same readout, so it cannot disagree (a screen test pins that too).
 */
class DacEqFailureStateTest {
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
        var unavailable: String? = null
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> =
            unavailable?.let { HidUse.Unavailable(it) } ?: HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
    }

    private inner class Rig {
        val dac = FakeFiioDac(melody)
        val opener = FakeOpener(dac)
        val log = mutableListOf<String>()
        val published = mutableListOf<DacEqState>()
        val store = DacEqBackupStore(Files.createTempDirectory("dac-eq-failure").toFile().also { dirs += it })
        val att = AttachedFiio("path-a", "identity-a", "profile-a", "Melody A", "path-a", melody)
        val access = DacEqHidAccess(opener, Dispatchers.Unconfined)
        val controller = DacEqController(MutableStateFlow(att), MutableStateFlow(true), access, { 0L }, attachedNow = { att })
        val service = DacEqService(
            controller, store, DacEqWriteSwitch(object : com.lostf1sh.pixelplayeross.data.service.player.DebugFlagStore {
                override fun read() = true
                override fun write(value: Boolean) = Unit
            }, true),
            elapsedMs = { 0L }, wallClockMs = { 1_700_000_000_000L }, pause = {}, log = { log += it },
        )
        val writer = DacEqWriter(
            access, DacEqWriteGate(), store, { true }, { 0L }, { 1_700_000_000_000L },
            publish = { a, s -> published += s; controller.publish(a, s) }, pause = {}, log = { log += it },
            invalidate = controller::invalidate,
        )

        init {
            // The DAC is closed, its slot is known, and the app has read all that: the label says off, which is true.
            val state = FiioEqReader.read(FiioHidSession(dac, melody))
            val backup = DacEqBackup.of(att, state, 1L)!!
            store.save(backup)
            store.update(backup.withSlot(dac.slot(slot).let { DacEqSlotSnapshot(slot, it.preGainTenths, it.bands.toList()) }))
            controller.resolveNow()
            controller.publish(att, state)
            check(controller.label.value == DacEqLabel.OFF_OR_FLAT)
        }

        fun apply(bands: List<DacEqBand>) = runBlocking { writer.applyCurve(att, bands, WriteInitiator.USER_ACTION) }
        fun restore() = runBlocking { writer.restore(att, WriteInitiator.REVIEWER_COMMAND) }

        /** What a read of the DAC says now, over a transport that works again. */
        fun truth(): DacEqLabel {
            dac.failWrites = false
            dac.failWritesAfter = null
            dac.dropReplies = false
            dac.onWrite = null
            dac.afterSet = null
            return DacEqLabel.of(FiioEqReader.read(FiioHidSession(dac, melody)).verdict)
        }
    }

    private fun isSet(report: ByteArray) = (report[1].toInt() and 0xFF) == 0xAA

    private fun assertLabelHonest(r: Rig, what: String) {
        val label = r.controller.label.value
        val readout = r.controller.readout.value
        val truth = r.truth()
        assertWithMessage("$what: the label source says $label, a healthy read says $truth").that(label == truth || label == DacEqLabel.UNKNOWN).isTrue()
        if (truth == DacEqLabel.ON) assertWithMessage("$what: stale Purity over a DAC that plays a curve").that(label).isNotEqualTo(DacEqLabel.OFF_OR_FLAT)
        // The readout the screen reads is the one the label comes from.
        assertThat(readout.label).isEqualTo(label)
    }

    private val target = curve(2 to 3.0, 6 to 3.0)

    /** How many SETs a successful write of [target] from a closed DAC takes: the select, the plan, the save. */
    private fun setsOfAWrite(): Int = Rig().also { assertThat(it.apply(target)).isInstanceOf(DacEqWriteResult.Done::class.java) }.dac.sets.size

    // ── The three kinds of failure, at every SET ─────────────────────────────────────────────────────────

    @Test
    fun `a recoverable exception at any SET is rolled back, and the label says what the DAC reads afterwards`() {
        val total = setsOfAWrite()
        for (phase in listOf("before", "after")) {
            for (k in 1..total) {
                val r = Rig()
                var seen = 0
                var thrown = false
                val inject = { if (!thrown && ++seen == k) { thrown = true; throw IllegalStateException("injected") } }
                if (phase == "before") r.dac.onWrite = { report -> if (isSet(report)) inject() } else r.dac.afterSet = { _, _ -> inject() }
                val result = r.apply(target)
                val what = "exception $phase SET $k of $total"
                assertWithMessage("$what: $result").that(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
                assertWithMessage("$what: the restore was tried and worked").that((result as DacEqWriteResult.Failed).restored).isTrue()
                assertWithMessage("$what: a restore or an undo is in the log").that(r.log.any { it.contains("RESTORE after a failure") }).isTrue()
                // What was read after the recovery was published: the DAC is closed again, and the label may say so because a read says so.
                assertWithMessage(what).that(r.published).isNotEmpty()
                assertThat(r.controller.label.value).isEqualTo(DacEqLabel.OFF_OR_FLAT)
                assertLabelHonest(r, what)
            }
        }
    }

    @Test
    fun `a DAC that stops answering reads after any SET never leaves the old off label standing`() {
        val total = setsOfAWrite()
        for (k in 1..total) {
            val r = Rig()
            var seen = 0
            // The SET is applied and then the DAC says nothing to any GET: the read-back, a restore's gate and the final read all fail.
            r.dac.afterSet = { d, _ -> if (++seen == k) d.dropReplies = true }
            val result = r.apply(target)
            val what = "replies lost after SET $k of $total ($result)"
            assertWithMessage(what).that(result is DacEqWriteResult.Failed || result is DacEqWriteResult.Mismatch || result is DacEqWriteResult.Done).isTrue()
            if (result is DacEqWriteResult.Done) continue // the last SET: nothing was left to read, and the write was already complete
            assertLabelHonest(r, what)
            // Whatever the DAC holds, the label is not the stale one unless a read after the write really says so.
            if (r.truth() == DacEqLabel.ON) assertThat(r.controller.label.value).isEqualTo(DacEqLabel.UNKNOWN)
        }
    }

    @Test
    fun `a cable that comes out after any SET leaves the label unknown, never the stale off`() {
        val total = setsOfAWrite()
        for (phase in listOf("before", "after")) {
            for (k in 1..total) {
                val r = Rig()
                var seen = 0
                val pull = { d: FakeFiioDac -> if (++seen == k) d.failWritesAfter = d.written.size }
                if (phase == "before") r.dac.onWrite = { report -> if (isSet(report)) pull(r.dac) } else r.dac.afterSet = { d, _ -> pull(d) }
                val result = r.apply(target)
                val what = "cable out $phase SET $k of $total ($result)"
                assertWithMessage(what).that(result is DacEqWriteResult.Failed || result is DacEqWriteResult.Mismatch || result is DacEqWriteResult.Done).isTrue()
                if (result is DacEqWriteResult.Done) continue
                assertLabelHonest(r, what)
            }
        }
    }

    // ── The recovery tells the labels what it found ──────────────────────────────────────────────────────

    @Test
    fun `the auditor's two cases, a recoverable exception and lost replies, no longer end on a stale off`() {
        // 1. one transient exception at the first band SET: the restore runs, and the post-recovery read is published (closed)
        val a = Rig()
        var injected = false
        a.dac.onWrite = { report ->
            if (!injected && isSet(report) && (report[5].toInt() and 0xFF) == FiioHidWrites.CMD_BAND) { injected = true; throw IllegalStateException("one transient exception") }
        }
        val result = a.apply(target) as DacEqWriteResult.Failed
        assertThat(result.restored).isTrue()
        assertThat(a.log.count { it.contains("RESTORE") }).isGreaterThan(0)
        assertThat(a.published.map { it.verdict }).containsExactly(DacEqVerdict.OFF)
        assertThat(a.dac.activePreset).isEqualTo(0xF0)

        // 2. the pre-gain is applied and then the DAC stops replying: the restore cannot pass a gate that needs a full read
        val b = Rig()
        b.dac.afterSet = { d, report -> if ((report[5].toInt() and 0xFF) == FiioHidWrites.CMD_GAIN) d.dropReplies = true }
        b.apply(target)
        assertThat(b.controller.label.value).isNotEqualTo(DacEqLabel.OFF_OR_FLAT)
        b.truth() // (heals the transport)
        assertThat(b.controller.label.value).isEqualTo(DacEqLabel.UNKNOWN)
    }

    @Test
    fun `a rollback that worked while the outer result is a mismatch publishes the state after the rollback`() {
        val r = Rig()
        r.dac.corruptBandOnWrite = 2
        val result = r.apply(target)
        assertThat(result).isInstanceOf(DacEqWriteResult.Mismatch::class.java)
        assertThat((result as DacEqWriteResult.Mismatch).restored).isTrue()
        // Not the snapshot taken before the rollback (the mismatching read-back, a curve that was never kept): the one after it.
        assertThat(r.published.map { it.verdict }).containsExactly(DacEqVerdict.OFF)
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.OFF_OR_FLAT)
        assertLabelHonest(r, "mismatch with a rollback")
    }

    @Test
    fun `a failed restore that could not be read afterwards invalidates, a successful one publishes`() {
        // successful: the explicit restore after a write returns the DAC to its backup, and what it reads is published
        val ok = Rig()
        assertThat(ok.apply(target)).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(ok.controller.label.value).isEqualTo(DacEqLabel.ON)
        assertThat(ok.restore()).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(ok.controller.label.value).isEqualTo(DacEqLabel.OFF_OR_FLAT)

        // failed: the restore begins and the DAC stops answering reads half way
        val bad = Rig()
        assertThat(bad.apply(target)).isInstanceOf(DacEqWriteResult.Done::class.java)
        var seen = 0
        bad.dac.afterSet = { d, _ -> if (++seen == 2) d.dropReplies = true }
        val result = bad.restore()
        assertThat(result).isNotInstanceOf(DacEqWriteResult.Done::class.java)
        assertLabelHonest(bad, "a restore that was cut")
    }

    // ── A failed read invalidates too ────────────────────────────────────────────────────────────────────

    @Test
    fun `a read the service could not make drops the old reading, however it failed`() {
        val unavailable = Rig()
        unavailable.opener.unavailable = "no USB permission for this DAC"
        assertThat(runBlocking { unavailable.service.read(unavailable.att) }).isNull()
        assertThat(unavailable.controller.label.value).isEqualTo(DacEqLabel.UNKNOWN)

        val silent = Rig()
        silent.dac.dropReplies = true
        runBlocking { silent.service.read(silent.att) }
        assertThat(silent.controller.label.value).isEqualTo(DacEqLabel.UNKNOWN)

        val refreshed = Rig()
        refreshed.opener.unavailable = "no UsbManager"
        runBlocking { refreshed.controller.refresh() }
        assertThat(refreshed.controller.label.value).isEqualTo(DacEqLabel.UNKNOWN)

        // And a read that works again gives the label back, from the DAC and not from memory.
        unavailable.opener.unavailable = null
        runBlocking { unavailable.service.read(unavailable.att) }
        assertThat(unavailable.controller.label.value).isEqualTo(DacEqLabel.OFF_OR_FLAT)
    }

    @Test
    fun `an invalidation for a DAC that is not the attached one any more changes nothing`() {
        val r = Rig()
        val other = r.att.copy(attachKey = "path-b")
        r.controller.invalidate(other, "not this one")
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.OFF_OR_FLAT)
        r.controller.invalidate(r.att, "this one")
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.UNKNOWN)
    }
}
