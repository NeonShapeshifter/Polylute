package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * B30-fix1 H1 (auditor R30-01): **no state the DAC is in between two SETs may be louder than the limit.**
 *
 * The writer is run against the fake DAC and the level of what the DAC holds is read **after every transfer** (the fake calls
 * [FakeFiioDac.afterSet] once each SET is applied), not only at the end: the first version wrote the final pre-gain before the bands,
 * so a move from a +6 dB peak held down by −6.1 dB of pre-gain to a flat curve had the peak live with no pre-gain for a moment, and every
 * test looked at the end.
 *
 * The level is [DacEqCurve.netPeakDb] of the slot that is selected, the model the gate checks the final curve with. The limit is 0 dB.
 * It is recorded only while the app's slot is selected: the owner's own presets ("Close EQ" reads +2.0 dB of pre-gain on the Melody
 * that was read) are not curves the app wrote.
 */
class DacEqSafeSequenceTest {
    private val melody = signedMelody
    private val slot = 0xA2
    private val dirs = mutableListOf<File>()

    @AfterEach
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private fun curve(vararg gains: Pair<Int, Double>, q: Double = 0.71): List<DacEqBand> {
        val map = gains.toMap()
        return (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], map[it] ?: 0.0, q) }
    }

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> =
            HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
    }

    /**
     * @param heldNow what USER 3 holds now (what the app wrote earlier, or the owner did)
     * @param original what the backup says USER 3 held when it was looked at: what a restore writes back
     */
    private inner class Rig(
        heldNow: Pair<Int, List<DacEqBand>>? = null,
        original: DacEqSlotSnapshot? = null,
        startPreset: Int = 0xF0,
    ) {
        val dac = FakeFiioDac(melody, activePreset = startPreset)
        val log = mutableListOf<String>()
        val store = DacEqBackupStore(Files.createTempDirectory("dac-eq-sequence").toFile().also { dirs += it })
        val att = AttachedFiio("path-a", "identity-a", "profile-a", "Melody A", "path-a", melody)
        val access = DacEqHidAccess(FakeOpener(dac), Dispatchers.Unconfined)
        val controller = DacEqController(MutableStateFlow(att), MutableStateFlow(true), access, { 0L }, attachedNow = { att })
        val writer = DacEqWriter(
            access, DacEqWriteGate(), store, { true }, { 0L }, { 1_700_000_000_000L },
            publish = controller::publish, pause = {}, log = { log += it }, invalidate = controller::invalidate,
        )

        /** The level of the app's slot after each transfer that left it selected, in the order they happened. */
        val levels = mutableListOf<Double>()
        val trace = mutableListOf<String>()

        init {
            // The backup is taken (and the slot looked at) before anything is written: what is in it is what a restore returns to.
            val state = FiioEqReader.read(FiioHidSession(dac, melody))
            val backup = DacEqBackup.of(att, state, 1L)!!
            store.save(backup)
            val before = original ?: dac.slot(slot).let { DacEqSlotSnapshot(slot, it.preGainTenths, it.bands.toList()) }
            store.update(backup.withSlot(before))
            heldNow?.let { (gain, bands) -> dac.setSlot(slot, gain, bands) }
            controller.resolveNow()
            controller.publish(att, FiioEqReader.read(FiioHidSession(dac, melody)))
            dac.afterSet = { d, report ->
                val command = report[5].toInt() and 0xFF
                val live = d.slot(d.activePreset)
                val net = DacEqCurve.netPeakDb(live.bands, live.preGainTenths)
                trace += "SET %02X → preset 0x%02X net %.2f dB".format(command, d.activePreset, net)
                if (d.activePreset == slot) levels += net
            }
        }

        fun apply(bands: List<DacEqBand>) = runBlocking { writer.applyCurve(att, bands, WriteInitiator.USER_ACTION) }
        fun roundtrip() = runBlocking { writer.roundtrip(att, WriteInitiator.REVIEWER_COMMAND) }
        fun restore() = runBlocking { writer.restore(att, WriteInitiator.REVIEWER_COMMAND) }
        fun held() = dac.slot(slot).let { it.preGainTenths to it.bands.toList() }
        fun truth(): DacEqLabel = DacEqLabel.of(FiioEqReader.read(FiioHidSession(dac, melody)).verdict)
        fun netOfSlot() = dac.slot(slot).let { DacEqCurve.netPeakDb(it.bands, it.preGainTenths) }
    }

    private fun assertEveryStateWithinLimit(r: Rig, what: String) {
        assertWithMessage("$what: the level after each transfer, in order: ${r.trace}").that(r.levels).isNotEmpty()
        assertWithMessage("$what: the loudest state was ${r.levels.max()} dB; ${r.trace}").that(r.levels.max()).isAtMost(0.0)
    }

    // ── From one safe curve to another: the states between ───────────────────────────────────────────────

    @Test
    fun `a move from a boosted curve to flat never has the boost live without its pre-gain, the auditor's safe endpoints`() {
        val r = Rig()
        assertThat(r.apply(curve(4 to 6.0))).isInstanceOf(DacEqWriteResult.Done::class.java)
        val startNet = r.netOfSlot()
        assertThat(startNet).isAtMost(0.0) // a safe curve on its own
        r.levels.clear(); r.trace.clear()

        assertThat(r.apply(curve())).isInstanceOf(DacEqWriteResult.Done::class.java)

        assertEveryStateWithinLimit(r, "boost → flat")
        val (gain, bands) = r.held()
        assertThat(gain).isEqualTo(0)
        assertThat(bands.all { it.gainTenthsDb == 0 }).isTrue()
    }

    @Test
    fun `a move from one boost to another passes through no state above the limit`() {
        for ((from, to) in listOf(
            curve(4 to 6.0) to curve(8 to 6.0),
            curve(2 to 3.0, 3 to 3.0) to curve(6 to 3.0, 7 to 3.0),
            curve(0 to 9.0) to curve(9 to 9.0),
            curve(4 to 6.0) to curve(4 to 2.0),
            curve(4 to 2.0) to curve(4 to 6.0),
        )) {
            val r = Rig()
            assertThat(r.apply(from)).isInstanceOf(DacEqWriteResult.Done::class.java)
            r.levels.clear(); r.trace.clear()
            assertThat(r.apply(to)).isInstanceOf(DacEqWriteResult.Done::class.java)
            assertEveryStateWithinLimit(r, "boost → another boost")
            assertThat(r.held().second.map { it.gainTenthsDb }).isEqualTo(to.map { it.gainTenthsDb })
            assertThat(r.netOfSlot()).isAtMost(0.0)
        }
    }

    @Test
    fun `overlapping filters, which boost more together than apart, are passed through safely too`() {
        for (q in listOf(0.71, 0.3, 2.0)) {
            val r = Rig()
            assertThat(r.apply(curve(4 to 4.0, 5 to 4.0, 6 to 4.0, q = q))).isInstanceOf(DacEqWriteResult.Done::class.java)
            r.levels.clear(); r.trace.clear()
            // The three boosts move one step up: for a moment two old and two new ones can be live together.
            assertThat(r.apply(curve(5 to 4.0, 6 to 4.0, 7 to 4.0, q = q))).isInstanceOf(DacEqWriteResult.Done::class.java)
            assertEveryStateWithinLimit(r, "overlapping filters, Q $q")
            assertThat(r.apply(curve())).isInstanceOf(DacEqWriteResult.Done::class.java)
            assertEveryStateWithinLimit(r, "overlapping filters to flat, Q $q")
        }
    }

    @Test
    fun `a move where the slot changes first selects only a slot the backup says is safe, and every state after the select is within the limit`() {
        // USER 3 holds a safe boost the app wrote earlier; the DAC is on "Close EQ"; the new curve is flat (the auditor's shape, after a select).
        val earlier = curve(4 to 6.0)
        val gain = DacEqCurve.preGainTenthsFor(earlier)
        val r = Rig(heldNow = gain to earlier)
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.apply(curve())).isInstanceOf(DacEqWriteResult.Done::class.java)
        // The select is the first SET, and what it switches on is the safe curve the slot holds.
        assertThat(r.dac.sets.first().first).isEqualTo(FiioHidWrites.CMD_PRESET)
        assertThat(r.trace.first()).startsWith("SET 16")
        assertEveryStateWithinLimit(r, "slot change")
        assertThat(r.levels.first()).isAtMost(0.0)
    }

    @Test
    fun `a slot the backup says boosts is not selected at all, by a write, a roundtrip or a restore`() {
        val loud = DacEqSlotSnapshot(slot, 0, curve(4 to 6.0))
        val r = Rig(original = loud)
        val write = r.apply(curve(2 to 1.0)) as DacEqWriteResult.Refused
        assertThat(write.refusal).isEqualTo(GateRefusal.SLOT_UNSAFE_TO_SELECT)
        assertThat((r.roundtrip() as DacEqWriteResult.Refused).refusal).isEqualTo(GateRefusal.SLOT_UNSAFE_TO_SELECT)
        // Not a single SET: the pointer did not move either, so the loud slot was never made audible.
        assertThat(r.dac.sets).isEmpty()
        // A restore has nothing to put back in a slot the app never selected: it leaves it alone and says so.
        val restored = r.restore()
        assertThat(restored).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(r.dac.sets).isEmpty()
        assertThat(r.log.any { it.contains("left alone") }).isTrue()
    }

    @Test
    fun `the look at an unknown slot warns when it boosts, and from then on no write selects it`() {
        // A DAC whose backup lacks the slot: the explicit look finds a loud curve in it.
        val dac = FakeFiioDac(melody)
        dac.setSlot(slot, 0, curve(4 to 6.0))
        val store = DacEqBackupStore(Files.createTempDirectory("dac-eq-look").toFile().also { dirs += it })
        val att = AttachedFiio("path-a", "identity-a", "profile-a", "Melody A", "path-a", melody)
        val log = mutableListOf<String>()
        val access = DacEqHidAccess(FakeOpener(dac), Dispatchers.Unconfined)
        val writer = DacEqWriter(access, DacEqWriteGate(), store, { true }, { 0L }, { 1L }, pause = {}, log = { log += it })
        val look = runBlocking { writer.discoverSlot(att) }
        assertThat(look).isInstanceOf(DacEqWriteResult.Done::class.java)
        assertThat(log.any { it.contains("WARNING") && it.contains("boosts") }).isTrue()
        assertThat(dac.activePreset).isEqualTo(0xF0) // put back
        dac.written.clear()
        val write = runBlocking { writer.applyCurve(att, curve(2 to 1.0), WriteInitiator.USER_ACTION) } as DacEqWriteResult.Refused
        assertThat(write.refusal).isEqualTo(GateRefusal.SLOT_UNSAFE_TO_SELECT)
        assertThat(dac.sets).isEmpty()
    }

    // ── A rollback follows the same rule ─────────────────────────────────────────────────────────────────

    private fun isSet(report: ByteArray) = (report[1].toInt() and 0xFF) == 0xAA

    @Test
    fun `a failure at any SET of a move, before it or after it, is rolled back through states that are all within the limit`() {
        val earlier = curve(4 to 6.0)
        val earlierGain = DacEqCurve.preGainTenthsFor(earlier)
        val target = curve(8 to 6.0, 9 to 3.0)

        // How many SETs a move takes when nothing goes wrong: the select, the plan, the save.
        val dry = Rig(heldNow = earlierGain to earlier)
        assertThat(dry.apply(target)).isInstanceOf(DacEqWriteResult.Done::class.java)
        val total = dry.dac.sets.size
        assertThat(total).isAtLeast(5)

        for (phase in listOf("before", "after")) {
            for (k in 1..total) {
                val r = Rig(heldNow = earlierGain to earlier)
                var seen = 0
                var thrown = false
                val inject = { if (!thrown && ++seen == k) { thrown = true; throw IllegalStateException("injected at SET $k $phase") } }
                if (phase == "before") {
                    r.dac.onWrite = { report -> if (isSet(report)) inject() }
                } else {
                    val record = r.dac.afterSet
                    r.dac.afterSet = { d, report -> record?.invoke(d, report); inject() }
                }
                val result = r.apply(target)
                val what = "an exception $phase SET $k of $total"
                assertWithMessage("$what: $result").that(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
                assertWithMessage("$what: restored").that((result as DacEqWriteResult.Failed).restored).isTrue()
                assertWithMessage("$what: ${r.trace}").that(r.levels.maxOrNull() ?: Double.NEGATIVE_INFINITY).isAtMost(0.0)
                // Where the DAC is now: the slot as it was before the move, or the backup's copy; never a half-written curve.
                val (gain, bands) = r.held()
                val asEarlier = gain == earlierGain && bands.map { it.gainTenthsDb } == earlier.map { it.gainTenthsDb }
                val asBackup = gain == 0 && bands.all { it.gainTenthsDb == 0 }
                assertWithMessage("$what: slot holds gain $gain ${bands.map { it.gainTenthsDb }}").that(asEarlier || asBackup).isTrue()
                assertThat(r.dac.activePreset).isEqualTo(0xF0)
                // And what the labels are told is what the DAC reads.
                assertWithMessage(what).that(r.controller.label.value).isEqualTo(r.truth())
            }
        }
    }

    @Test
    fun `a rollback of a boosted slot passes through no state above the limit even when the failure is in the middle of the bands`() {
        val boosted = curve(2 to 5.0, 3 to 5.0)
        val gain = DacEqCurve.preGainTenthsFor(boosted)
        // The DAC is on the app's slot and a move to another boost is begun; the cable fails after the first band SET.
        val r = Rig(heldNow = gain to boosted, startPreset = slot)
        var bandSets = 0
        r.dac.onWrite = { report ->
            if (isSet(report) && (report[5].toInt() and 0xFF) == FiioHidWrites.CMD_BAND && ++bandSets == 2) throw IllegalStateException("cable")
        }
        val result = r.apply(curve(6 to 5.0, 7 to 5.0))
        assertThat(result).isInstanceOf(DacEqWriteResult.Failed::class.java)
        // The rollback happened (the old code did not try one) and it ended on the backup's copy of the slot.
        assertThat((result as DacEqWriteResult.Failed).restored).isTrue()
        assertThat(r.held().second.all { it.gainTenthsDb == 0 }).isTrue()
        assertThat(r.held().first).isEqualTo(0)
        assertEveryStateWithinLimit(r, "rollback in the bands")
    }

    // ── The plan itself ──────────────────────────────────────────────────────────────────────────────────

    private fun live(gain: Int, bands: List<DacEqBand>) = DacEqLive(gain, bands)

    @Test
    fun `boost to flat is planned as the bands first and the pre-gain raised last, never the pre-gain first`() {
        val boosted = curve(4 to 6.0)
        val start = live(DacEqCurve.preGainTenthsFor(boosted), boosted)
        val plan = DacEqWritePlanner.plan(start, live(0, curve()))
        assertThat(plan.steps.map { it.name }).containsExactly("band 5", "pre-gain").inOrder()
        assertThat(plan.steps.all { it.netPeakDb <= 0.0 }).isTrue()
        assertThat(plan.steps.last().after).isEqualTo(plan.target)
    }

    @Test
    fun `a pre-gain that has to be lower for the way is lowered before the first band, and a boost that arrives needs the pre-gain first`() {
        val flat = live(0, curve())
        val plan = DacEqWritePlanner.plan(flat, live(DacEqCurve.preGainTenthsFor(curve(4 to 6.0)), curve(4 to 6.0)))
        assertThat(plan.steps.map { it.name }).containsExactly("pre-gain", "band 5").inOrder()
        assertThat(plan.steps.all { it.netPeakDb <= 0.0 }).isTrue()
    }

    @Test
    fun `a slot that is already above the limit has its pre-gain lowered first, whatever else the plan does`() {
        val loud = live(0, curve(4 to 6.0))
        val plan = DacEqWritePlanner.plan(loud, live(DacEqCurve.preGainTenthsFor(curve(8 to 6.0)), curve(8 to 6.0)))
        assertThat(plan.steps.first().name).isEqualTo("pre-gain")
        assertThat(plan.steps.first().netPeakDb).isAtMost(0.0)
        assertThat(plan.steps.all { it.netPeakDb <= 0.0 }).isTrue()
    }

    @Test
    fun `a roundtrip plan sends every band, changed or not, and a pre-gain frame first`() {
        val bands = curve(3 to 2.0)
        val state = live(DacEqCurve.preGainTenthsFor(bands), bands)
        val plan = DacEqWritePlanner.plan(state, state, forceAll = true)
        assertThat(plan.steps.first().name).isEqualTo("pre-gain")
        assertThat(plan.steps.count { it.command == FiioHidWrites.CMD_BAND }).isEqualTo(10)
        assertThat(plan.steps.all { it.netPeakDb <= 0.0 }).isTrue()
        // Nothing to do, nothing sent.
        assertThat(DacEqWritePlanner.plan(state, state).steps).isEmpty()
    }

    @Test
    fun `any move between two curves the gate would write passes through no state above the limit`() {
        val random = Random(3101)
        fun randomCurve() = (0 until 10).map { i ->
            val type = listOf(FiioFilterType.PEAK, FiioFilterType.LOW_SHELF, FiioFilterType.HIGH_SHELF).random(random)
            val gain = if (random.nextInt(3) == 0) 0.0 else random.nextInt(-80, 81) / 10.0
            DacEqBand.of(i, type, FakeFiioDac.FREQUENCIES[i] * (1 + random.nextInt(0, 3)) / 2 + 20, gain, 0.3 + random.nextDouble() * 4)
        }
        var checked = 0
        repeat(150) {
            val a = randomCurve()
            val b = randomCurve()
            val ga = DacEqCurve.preGainTenthsFor(a)
            val gb = DacEqCurve.preGainTenthsFor(b)
            if (ga < -120 || gb < -120) return@repeat
            val plan = DacEqWritePlanner.plan(live(ga, a), live(gb, b))
            checked++
            assertWithMessage("from $a to $b").that(plan.steps.maxOfOrNull { it.netPeakDb } ?: -1.0).isAtMost(0.0)
            assertThat(plan.steps.lastOrNull()?.after ?: plan.start).isEqualTo(plan.target)
            // The gate's own replay of the frames agrees.
            var replay = plan.start
            for (frame in plan.frames) replay = replay.after(frame.command, frame.data)
            assertThat(replay).isEqualTo(plan.target)
        }
        assertThat(checked).isAtLeast(30)
    }

    // ── The permit covers the sequence ───────────────────────────────────────────────────────────────────

    @Test
    fun `a frame of the plan sent out of its order is refused and puts nothing on the wire`() {
        val dac = FakeFiioDac(melody, activePreset = slot)
        val session = FiioHidSession(dac, melody)
        val state = FiioEqReader.read(session)
        val backup = DacEqBackup.of(AttachedFiio("p", "i", "k", "n", "p", melody), state, 1L)!!
        val gate = DacEqWriteGate()
        val bands = curve(4 to 6.0)
        val decision = gate.authorise(
            WriteRequest(WriteKind.APPLY_CURVE, WriteInitiator.USER_ACTION, slot, bands),
            WriteContext(melody, "k", state, 0, backup, true),
        ) as GateDecision.Authorised
        val plan = decision.plan!!
        assertThat(plan.steps.map { it.name }).containsExactly("pre-gain", "band 5").inOrder()
        val permit = decision.permit
        // The band first: not the next frame of the plan.
        assertThrows<IllegalStateException> {
            FiioHidWrites.send(session, permit, FiioHidWrites.CMD_BAND, FiioHidWrites.encodeBand(bands[4]), "band 5")
        }
        assertThat(dac.sets).isEmpty()
        // In order they go; the same frame twice does not.
        assertThat(FiioHidWrites.send(session, permit, FiioHidWrites.CMD_GAIN, FiioHidWrites.encodeGain(decision.preGainTenthsDb!!), "pre-gain")).isTrue()
        assertThrows<IllegalStateException> {
            FiioHidWrites.send(session, permit, FiioHidWrites.CMD_GAIN, FiioHidWrites.encodeGain(decision.preGainTenthsDb!!), "pre-gain")
        }
        assertThat(FiioHidWrites.send(session, permit, FiioHidWrites.CMD_BAND, FiioHidWrites.encodeBand(bands[4]), "band 5")).isTrue()
        assertThat(permit.sequenceDone).isTrue()
    }

    @Test
    fun `a plan with a state above the limit is refused by the gate whoever made it, step by step`() {
        val boosted = curve(4 to 6.0)
        val start = DacEqLive(DacEqCurve.preGainTenthsFor(boosted), boosted)
        val flat = DacEqLive(0, curve())
        // The first version's order: the final pre-gain first, then the bands. Each step is a frame the permit would otherwise cover.
        val wrong: (DacEqLive, DacEqLive, Boolean, Boolean) -> DacEqWritePlan = { from, to, _, _ ->
            var live = from
            val steps = ArrayList<DacEqPlanStep>()
            fun add(command: Int, data: ByteArray, name: String) {
                live = live.after(command, data.toList())
                steps += DacEqPlanStep(command, data.toList(), name, live, live.netPeakDb)
            }
            add(FiioHidWrites.CMD_GAIN, FiioHidWrites.encodeGain(to.preGainTenthsDb), "pre-gain")
            to.bands.filterIndexed { i, b -> !from.bands[i].sameAs(b) }.forEach { add(FiioHidWrites.CMD_BAND, FiioHidWrites.encodeBand(it), "band ${it.index + 1}") }
            DacEqWritePlan(from, to, steps)
        }
        val atSlot = FiioEqReader.read(FiioHidSession(FakeFiioDac(melody, activePreset = slot).also { it.setSlot(slot, start.preGainTenthsDb, start.bands) }, melody))
        val backup = DacEqBackup.of(AttachedFiio("p", "i", "k", "n", "p", melody), atSlot, 1L)!!
        val context = WriteContext(melody, "k", atSlot, 0, backup, true)
        val request = WriteRequest(WriteKind.APPLY_CURVE, WriteInitiator.USER_ACTION, slot, flat.bands)
        val refused = DacEqWriteGate(planner = wrong).authorise(request, context) as GateDecision.Refused
        assertThat(refused.refusal).isEqualTo(GateRefusal.NET_BOOST)
        // the auditor's number: the +6 dB peak live with the pre-gain already at 0
        assertThat(refused.detail).contains("step 1 (pre-gain) would leave the output 6.00 dB above the limit")
        // The real planner, through the same gate, is allowed: the same request, the same state.
        assertThat(DacEqWriteGate().authorise(request, context)).isInstanceOf(GateDecision.Authorised::class.java)
        // A plan that does not end at the state asked for is refused too.
        val short: (DacEqLive, DacEqLive, Boolean, Boolean) -> DacEqWritePlan = { from, to, force, exempt ->
            val real = DacEqWritePlanner.plan(from, to, force, exempt)
            DacEqWritePlan(from, to, real.steps.dropLast(1))
        }
        val incomplete = DacEqWriteGate(planner = short).authorise(request, context) as GateDecision.Refused
        assertThat(incomplete.detail).contains("does not end at the state asked for")
    }

    @Test
    fun `the gate asks for the level after every step, so a plan the planner got wrong would be refused`() {
        // The source says it in one place: planProblem replays the frames and refuses on the first step above the limit.
        val gate = File("src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio/DacEqWriteGate.kt").readText()
        assertThat(gate).contains("private fun planProblem")
        assertThat(gate).contains("live = live.after(frame.command, frame.data)")
        // B31 P1.2: the rule itself is one object that the planner and the gate both ask.
        assertThat(gate).contains("DacEqLevelRule.firstExcess(plan.start, plan.frames, exemptFinal, plan.target)")
        val rule = File("src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio/DacEqWritePlan.kt").readText()
        assertThat(rule).contains("live = live.after(frame.command, frame.data)")
        assertThat(rule).contains("would leave the output")
        // And the writer sends nothing of a content plan except through the permit's sequence.
        val writer = File("src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio/DacEqWriter.kt").readText()
        assertThat(Regex("""FiioHidWrites\.send\(""").findAll(writer).count()).isEqualTo(1) // the one `send` of the operation
        assertThat(writer).doesNotContain("encodeBand(")
        // B31 P1.2: the pre-gain is built outside a plan in one place, the reviewer's listening check (and the way it neutralises itself),
        // whose frames the gate whitelists as a set. Every other gain frame comes from a plan.
        val firstGain = writer.indexOf("encodeGain(")
        assertThat(firstGain).isGreaterThan(writer.indexOf("fun listeningCheck(lowLevel: Boolean, holdMs: Long)"))
        assertThat(writer.substring(0, writer.indexOf("fun listeningCheck(lowLevel: Boolean, holdMs: Long)"))).doesNotContain("encodeGain(")
    }
}
