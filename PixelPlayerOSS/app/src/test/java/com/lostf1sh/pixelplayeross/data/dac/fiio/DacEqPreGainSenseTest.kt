package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B31 P1.2: **what a positive global gain does.** The sources disagree (WolfEQ and DevicePEQ since 2026-07-21 write the AutoEQ preamp as a
 * signed gain; DevicePEQ before that wrote `maxGain + preamp`; nothing says it is an attenuation stored as a positive number, and
 * nothing excludes it), and the owner's USER 3 (`+8.0` under ten AutoEQ-shaped bands) reads like the older convention. So the model carries a
 * [PreGainSense], is [PreGainSense.UNCONFIRMED] until the reviewer's listening check, and judges every level under both readings.
 */
class DacEqPreGainSenseTest {
    private val real = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val boost = real.copy(preGainSense = PreGainSense.BOOST)
    private val attenuation = real.copy(preGainSense = PreGainSense.ATTENUATION)
    private val slot = 0xA2

    private fun curve(vararg gains: Pair<Int, Double>): List<DacEqBand> {
        val map = gains.toMap()
        return (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, DacEqDefaults.FREQUENCIES[it], map[it] ?: 0.0, 0.71) }
    }

    // ── the model ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the real Melody row does not know the sense, and nothing else does either`() {
        assertThat(real.preGainSense).isEqualTo(PreGainSense.UNCONFIRMED)
        assertThat(FiioHidProfiles.all.map { it.preGainSense }.toSet()).containsExactly(PreGainSense.UNCONFIRMED)
    }

    @Test
    fun `a register moves the level one way under each reading, and the worse way while it is not known`() {
        assertThat(PreGainSense.BOOST.offsetDb(-60)).isEqualTo(-6.0)
        assertThat(PreGainSense.ATTENUATION.offsetDb(-60)).isEqualTo(6.0)
        assertThat(PreGainSense.UNCONFIRMED.offsetDb(-60)).isEqualTo(6.0)
        assertThat(PreGainSense.UNCONFIRMED.offsetDb(60)).isEqualTo(6.0)
        assertThat(PreGainSense.UNCONFIRMED.offsetDb(0)).isEqualTo(0.0)
        assertThat(PreGainSense.UNCONFIRMED.readings).containsExactly(PreGainSense.BOOST, PreGainSense.ATTENUATION).inOrder()
        assertThat(PreGainSense.BOOST.readings).containsExactly(PreGainSense.BOOST)
    }

    @Test
    fun `the register that holds a boost down is the other sign under the other reading, and none exists while it is unknown`() {
        val boosted = curve(4 to 6.0)
        val attenuation = DacEqCurve.attenuationTenthsFor(boosted)
        assertThat(attenuation).isGreaterThan(59)
        assertThat(DacEqCurve.preGainTenthsFor(boosted, PreGainSense.BOOST)).isEqualTo(-attenuation)
        assertThat(DacEqCurve.preGainTenthsFor(boosted, PreGainSense.ATTENUATION)).isEqualTo(attenuation)
        assertThat(DacEqCurve.preGainTenthsFor(boosted, PreGainSense.UNCONFIRMED)).isNull()
        // A curve that never rises above 0 dB needs nothing, whatever the sense.
        val cuts = curve(2 to -3.0, 7 to -9.0)
        for (sense in PreGainSense.entries) assertThat(DacEqCurve.preGainTenthsFor(cuts, sense)).isEqualTo(0)
        // Under each known reading the register brings the peak to 0 or below.
        assertThat(DacEqCurve.netPeakDb(boosted, DacEqCurve.preGainTenthsFor(boosted, PreGainSense.BOOST)!!, PreGainSense.BOOST)).isAtMost(0.0)
        assertThat(DacEqCurve.netPeakDb(boosted, DacEqCurve.preGainTenthsFor(boosted, PreGainSense.ATTENUATION)!!, PreGainSense.ATTENUATION)).isAtMost(0.0)
    }

    @Test
    fun `USER 3's factory curve comes out at a 10 dB boost if the register amplifies, and at 5 dB below the limit if it attenuates`() {
        val factory = RecordedMelody.user3Factory
        val peak = DacEqCurve.peakBoostDb(factory.bands)
        assertThat(peak).isWithin(0.1).of(2.7) // ten bands: +5.4 at 3 kHz held down by the cuts around it
        assertThat(DacEqLive.of(factory, PreGainSense.BOOST).netPeakDb).isWithin(0.1).of(10.7)
        assertThat(DacEqLive.of(factory, PreGainSense.ATTENUATION).netPeakDb).isWithin(0.1).of(-5.3)
        // While the sense is unknown the worse reading counts, so the slot is not safe to select.
        val unknown = DacEqLive.of(factory, PreGainSense.UNCONFIRMED)
        assertThat(unknown.netPeakDb).isWithin(0.1).of(10.7)
        assertThat(unknown.netPeaksDb).hasSize(2)
        assertThat(unknown.netPeaksDb[0]).isWithin(0.1).of(10.7) // read as amplify
        assertThat(unknown.netPeaksDb[1]).isWithin(0.1).of(-5.3) // read as attenuate
    }

    // ── the level rule, shared by the planner and the gate ──────────────────────────────────────────────

    @Test
    fun `a known sense keeps the ceiling at 0 dB, and an unknown one never lets a reading get louder than it was`() {
        val factory = RecordedMelody.user3Factory
        assertThat(DacEqLevelRule.ceilingsDb(DacEqLive.of(factory, PreGainSense.BOOST))).containsExactly(0.0)
        val ceilings = DacEqLevelRule.ceilingsDb(DacEqLive.of(factory, PreGainSense.UNCONFIRMED))
        assertThat(ceilings).hasSize(2)
        assertThat(ceilings[0]).isWithin(0.1).of(10.7) // the amplify reading was at +10.7
        assertThat(ceilings[1]).isEqualTo(0.0) // the attenuate reading was at −5.3: its ceiling is the limit
        val flat = DacEqLive(0, curve(), PreGainSense.UNCONFIRMED)
        assertThat(DacEqLevelRule.ceilingsDb(flat)).containsExactly(0.0, 0.0)
    }

    // ── the planner ──────────────────────────────────────────────────────────────────────────────────────

    private fun flat(sense: PreGainSense) = DacEqLive(0, DacEqDefaults.flatBands(10), sense)

    private fun levelsAlong(plan: DacEqWritePlan): List<List<Double>> = plan.steps.map { it.after.netPeaksDb }

    @Test
    fun `taking USER 3 over with the sense unknown flattens the bands first and moves the pre-gain to 0 last`() {
        val start = DacEqLive.of(RecordedMelody.user3Factory, PreGainSense.UNCONFIRMED)
        val plan = DacEqWritePlanner.plan(start, flat(PreGainSense.UNCONFIRMED))
        assertThat(plan.steps.last().name).isEqualTo("pre-gain")
        assertThat(plan.steps.first().name).startsWith("band")
        assertThat(plan.steps.count { it.name == "pre-gain" }).isEqualTo(1)
        // No reading is ever louder than it was at the start, or than 0 dB if it was below that.
        val ceilings = DacEqLevelRule.ceilingsDb(start)
        for (levels in levelsAlong(plan)) levels.forEachIndexed { reading, level -> assertThat(level).isAtMost(ceilings[reading] + 1e-9) }
        assertThat(DacEqLevelRule.firstExcess(start, plan.frames, exemptFinal = false)).isNull()
        // It ends neutral under both readings.
        assertThat(plan.steps.last().after.netPeaksDb).isEqualTo(listOf(0.0, 0.0))
        // "The pre-gain to its minimum first" is not acceptable while the sense is unknown: a register of −12 dB is +12 dB if it attenuates.
        val preGainFirst = listOf(
            AllowedFrame(FiioHidWrites.CMD_GAIN, FiioHidWrites.encodeGain(-120).toList()),
        )
        assertThat(DacEqLevelRule.firstExcess(start, preGainFirst, exemptFinal = false)).contains("above")
    }

    @Test
    fun `taking USER 3 over with the sense known as amplify lowers the pre-gain first and every state is at or below the limit`() {
        val start = DacEqLive.of(RecordedMelody.user3Factory, PreGainSense.BOOST)
        val plan = DacEqWritePlanner.plan(start, flat(PreGainSense.BOOST))
        assertThat(plan.steps.first().name).isEqualTo("pre-gain")
        assertThat(plan.steps.first().after.preGainTenthsDb).isLessThan(0)
        assertThat(plan.steps.all { it.netPeakDb <= 0.0 }).isTrue()
        assertThat(plan.steps.last().after.netPeakDb).isEqualTo(0.0)
    }

    @Test
    fun `taking USER 3 over with the sense known as attenuate never needs the pre-gain to move first`() {
        val start = DacEqLive.of(RecordedMelody.user3Factory, PreGainSense.ATTENUATION)
        val plan = DacEqWritePlanner.plan(start, flat(PreGainSense.ATTENUATION))
        assertThat(plan.steps.all { it.netPeakDb <= 0.0 }).isTrue()
        assertThat(plan.steps.last().after.netPeakDb).isEqualTo(0.0)
    }

    // ── the gate ─────────────────────────────────────────────────────────────────────────────────────────

    private fun gateContext(
        profile: FiioHidProfile,
        sense: PreGainSense,
        dac: FakeFiioDac,
        backup: DacEqBackup,
        playing: Boolean = false,
    ): WriteContext {
        val state = FiioEqReader.read(FiioHidSession(dac, profile))
        return WriteContext(profile, "k", state, 0, backup, true, sense = sense, playing = playing)
    }

    private fun freeSlotRig(profile: FiioHidProfile): Pair<FakeFiioDac, DacEqBackup> {
        val dac = FakeFiioDac(profile, activePreset = 0xF0)
        val state = FiioEqReader.read(FiioHidSession(dac, profile))
        val backup = DacEqBackup.of(AttachedFiio("p", "i", "k", "n", "p", profile), state, 1L)!!
            .withSlot(DacEqSlotSnapshot(slot, 0, dac.slot(slot).bands.toList()))
        return dac to backup
    }

    @Test
    fun `with the sense unknown a curve that rises above 0 dB is refused and one that never does is written with the pre-gain at 0`() {
        val (dac, backup) = freeSlotRig(real)
        val context = gateContext(real, PreGainSense.UNCONFIRMED, dac, backup)
        val boosted = DacEqWriteGate().authorise(WriteRequest(WriteKind.APPLY_CURVE, WriteInitiator.USER_ACTION, slot, curve(4 to 3.0)), context)
        assertThat(boosted).isInstanceOf(GateDecision.Refused::class.java)
        assertThat((boosted as GateDecision.Refused).refusal).isEqualTo(GateRefusal.PRE_GAIN_SENSE_UNCONFIRMED)
        val cuts = DacEqWriteGate().authorise(WriteRequest(WriteKind.APPLY_CURVE, WriteInitiator.USER_ACTION, slot, curve(4 to -3.0)), context)
        assertThat(cuts).isInstanceOf(GateDecision.Authorised::class.java)
        assertThat((cuts as GateDecision.Authorised).preGainTenthsDb).isEqualTo(0)
    }

    @Test
    fun `with the sense known the same boost is written with the pre-gain of the right sign`() {
        for ((sense, positive) in listOf(PreGainSense.BOOST to false, PreGainSense.ATTENUATION to true)) {
            val profile = real.copy(preGainSense = sense)
            val (dac, backup) = freeSlotRig(profile)
            val decision = DacEqWriteGate().authorise(
                WriteRequest(WriteKind.APPLY_CURVE, WriteInitiator.USER_ACTION, slot, curve(4 to 3.0)),
                gateContext(profile, sense, dac, backup),
            ) as GateDecision.Authorised
            val pre = decision.preGainTenthsDb!!
            assertThat(if (positive) pre > 0 else pre < 0).isTrue()
            assertThat(DacEqCurve.netPeakDb(curve(4 to 3.0), pre, sense)).isAtMost(0.0)
        }
    }

    @Test
    fun `a slot that holds a boost is not safe to select while the sense is unknown, and is when the other reading is known to be true`() {
        val dac = RecordedMelody.fakeDac(activePreset = 0xF0, profile = real)
        val closed = FiioEqReader.read(FiioHidSession(dac, real))
        val backup = DacEqBackup.of(AttachedFiio("p", "i", "k", "n", "p", real), closed, 1L)!!.withSlot(RecordedMelody.user3Factory)
        for ((sense, refused) in listOf(PreGainSense.UNCONFIRMED to true, PreGainSense.BOOST to true, PreGainSense.ATTENUATION to false)) {
            val decision = DacEqWriteGate().authorise(
                WriteRequest(WriteKind.APPLY_CURVE, WriteInitiator.USER_ACTION, slot, curve(4 to -1.0)),
                gateContext(real.copy(preGainSense = sense), sense, dac, backup),
            )
            if (refused) {
                assertThat((decision as GateDecision.Refused).refusal).isEqualTo(GateRefusal.SLOT_UNSAFE_TO_SELECT)
            } else {
                assertThat(decision).isInstanceOf(GateDecision.Authorised::class.java)
            }
        }
    }

    // ── the reviewer's answer on this phone ──────────────────────────────────────────────────────────────

    private class MemoryText(var value: String? = null) : DebugTextStore {
        var writes = 0
        override fun read() = value
        override fun write(value: String?) {
            this.value = value
            writes++
        }
    }

    @Test
    fun `the reviewer's answer is unset by default, persists across a process, and unconfirmed clears it`() {
        val store = MemoryText()
        val switch = DacEqPreGainSenseSwitch(store, debugBuild = true)
        assertThat(switch.override()).isNull()
        assertThat(switch.effective(real)).isEqualTo(PreGainSense.UNCONFIRMED)
        assertThat(store.writes).isEqualTo(0)
        switch.set(PreGainSense.BOOST)
        assertThat(switch.effective(real)).isEqualTo(PreGainSense.BOOST)
        assertThat(DacEqPreGainSenseSwitch(store, debugBuild = true).override()).isEqualTo(PreGainSense.BOOST)
        switch.set(PreGainSense.UNCONFIRMED)
        assertThat(DacEqPreGainSenseSwitch(store, debugBuild = true).override()).isNull()
        assertThat(store.value).isNull()
    }

    @Test
    fun `a release build ignores a stored answer and refuses to record one`() {
        val store = MemoryText("attenuation")
        val release = DacEqPreGainSenseSwitch(store, debugBuild = false)
        assertThat(release.override()).isNull()
        assertThat(release.effective(real)).isEqualTo(PreGainSense.UNCONFIRMED)
        release.set(PreGainSense.BOOST)
        assertThat(release.override()).isNull()
        assertThat(store.writes).isEqualTo(0)
    }

    @Test
    fun `the answer is parsed from a word and a typo is not an answer`() {
        assertThat(PreGainSense.parse("boost")).isEqualTo(PreGainSense.BOOST)
        assertThat(PreGainSense.parse("Attenuation")).isEqualTo(PreGainSense.ATTENUATION)
        assertThat(PreGainSense.parse("unconfirmed")).isEqualTo(PreGainSense.UNCONFIRMED)
        assertThat(PreGainSense.parse("louder")).isNull()
        assertThat(PreGainSense.parse(null)).isNull()
    }
}
