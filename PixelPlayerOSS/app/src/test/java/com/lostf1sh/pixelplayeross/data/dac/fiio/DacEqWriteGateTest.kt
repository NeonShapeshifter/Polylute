package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * B30-P2 (rule 6): the write gate. Every condition has its own refusal here; the permit is a whitelist of exact frames; and the
 * source scan keeps SET frames in one file and permits in one class.
 */
class DacEqWriteGateTest {
    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val verified = melody.copy(verified = true)
    private val slot = 0xA2

    private fun flat(preset: Int = slot, gain: Int = 0) = DacEqSlotSnapshot(
        preset, gain, (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], 0.0, 0.71) },
    )

    private fun state(preset: Int = 0xF0, gain: Int = 0, bands: List<EqRead<DacEqBand>>? = null) = DacEqState(
        capability = DacEqCapability.of(melody),
        activePreset = EqRead.Read(preset),
        bandCount = EqRead.Read(10),
        preGainTenthsDb = EqRead.Read(gain),
        eqSwitch = EqRead.Unknown("no answer"),
        bands = bands ?: flat().bands.map { EqRead.Read(it) },
    )

    private fun backup(
        key: String = "profile-1",
        profile: String = "snowsky-melody",
        active: Int = 0xF0,
        slots: Map<Int, DacEqSlotSnapshot> = mapOf(0xF0 to flat(0xF0), slot to flat()),
    ) = DacEqBackup(key, profile, "SNOWSKY Melody", 1L, active, null, slots)

    private fun context(
        profile: FiioHidProfile = verified,
        read: DacEqState = state(),
        age: Long = 100,
        backup: DacEqBackup? = backup(),
        override: Boolean = false,
        key: String = "profile-1",
    ) = WriteContext(profile, key, read, age, backup, override)

    private fun curve(vararg gains: Pair<Int, Double>): List<DacEqBand> {
        val map = gains.toMap()
        return (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], map[it] ?: 0.0, 0.71) }
    }

    private val gate = DacEqWriteGate()

    private fun apply(
        bands: List<DacEqBand> = curve(3 to 2.0),
        initiator: WriteInitiator = WriteInitiator.USER_ACTION,
        target: Int? = slot,
        ctx: WriteContext = context(),
    ) = gate.authorise(WriteRequest(WriteKind.APPLY_CURVE, initiator, target, bands), ctx)

    private fun refusal(decision: GateDecision) = (decision as GateDecision.Refused).refusal

    // ── Rule 6, one condition at a time ──────────────────────────────────────────────────────────────────

    @Test
    fun `the happy path is authorised for a user action and for a reviewer command`() {
        assertThat(apply(initiator = WriteInitiator.USER_ACTION)).isInstanceOf(GateDecision.Authorised::class.java)
        assertThat(apply(initiator = WriteInitiator.REVIEWER_COMMAND)).isInstanceOf(GateDecision.Authorised::class.java)
    }

    @Test
    fun `nothing is written on connect or by itself`() {
        for (kind in WriteKind.entries) {
            val decision = gate.authorise(WriteRequest(kind, WriteInitiator.AUTOMATIC, slot, curve()), context())
            assertThat(refusal(decision)).isEqualTo(GateRefusal.NOT_USER_INITIATED)
        }
    }

    @Test
    fun `a profile without a slot of ours is read-only whatever else is switched on`() {
        val k13 = FiioHidProfiles.all.single { it.id == "fiio-k13-r2r" }.copy(verified = true)
        val decision = apply(ctx = context(profile = k13, override = true))
        assertThat(refusal(decision)).isEqualTo(GateRefusal.PROFILE_CANNOT_BE_WRITTEN)
    }

    @Test
    fun `an unverified profile is refused, and only the debug override lifts that`() {
        assertThat(melody.verified).isFalse()
        assertThat(refusal(apply(ctx = context(profile = melody, override = false)))).isEqualTo(GateRefusal.PROFILE_NOT_VERIFIED)
        assertThat(apply(ctx = context(profile = melody, override = true))).isInstanceOf(GateDecision.Authorised::class.java)
        // The override lifts "verified" and nothing else.
        assertThat(refusal(apply(ctx = context(profile = melody, override = true, backup = null)))).isEqualTo(GateRefusal.NO_BACKUP)
    }

    @Test
    fun `a read that did not work refuses, whichever value failed`() {
        val missingBand = state(bands = flat().bands.mapIndexed { i, b -> if (i == 4) EqRead.Failed("garbled") else EqRead.Read(b) })
        assertThat(refusal(apply(ctx = context(read = missingBand)))).isEqualTo(GateRefusal.READ_NOT_WORKING)
        val noGain = state().copy(preGainTenthsDb = EqRead.Unknown("none"))
        assertThat(refusal(apply(ctx = context(read = noGain)))).isEqualTo(GateRefusal.READ_NOT_WORKING)
        val wrongCount = state().copy(bandCount = EqRead.Read(8))
        assertThat(refusal(apply(ctx = context(read = wrongCount)))).isEqualTo(GateRefusal.READ_NOT_WORKING)
    }

    @Test
    fun `a read that is too old refuses`() {
        assertThat(refusal(apply(ctx = context(age = DacEqWriteGate.MAX_READ_AGE_MS + 1)))).isEqualTo(GateRefusal.STALE_READ)
        assertThat(refusal(apply(ctx = context(age = -1)))).isEqualTo(GateRefusal.STALE_READ)
    }

    @Test
    fun `no backup, a backup of another DAC, an incomplete one, or one without the slot all refuse`() {
        assertThat(refusal(apply(ctx = context(backup = null)))).isEqualTo(GateRefusal.NO_BACKUP)
        assertThat(refusal(apply(ctx = context(backup = backup(key = "profile-2"))))).isEqualTo(GateRefusal.BACKUP_FOR_OTHER_DAC)
        assertThat(refusal(apply(ctx = context(backup = backup(profile = "fiio-k13-r2r"))))).isEqualTo(GateRefusal.BACKUP_FOR_OTHER_DAC)
        // Incomplete: the active preset's snapshot is not there.
        assertThat(refusal(apply(ctx = context(backup = backup(slots = mapOf(slot to flat())))))).isEqualTo(GateRefusal.BACKUP_INCOMPLETE)
        // The app's slot is not in it: it has never been looked at, and the gate does not let the write be the first look (B30-fix1 H1).
        assertThat(refusal(apply(ctx = context(backup = backup(slots = mapOf(0xF0 to flat(0xF0))))))).isEqualTo(GateRefusal.SLOT_NOT_INSPECTED)
    }

    @Test
    fun `only the user slot the app owns is ever written, never a factory preset`() {
        for (target in listOf(0x00, 0x01, 0x06, 0xF0, 0xA0, 0xA1, 0x55, null)) {
            assertThat(refusal(apply(target = target))).isEqualTo(GateRefusal.NOT_USER_SLOT)
        }
        assertThat(apply(target = 0xA2)).isInstanceOf(GateDecision.Authorised::class.java)
        // A peek and a roundtrip are about that slot only, too.
        assertThat(refusal(gate.authorise(WriteRequest(WriteKind.PEEK_SLOT, WriteInitiator.REVIEWER_COMMAND, 0xA0), context())))
            .isEqualTo(GateRefusal.NOT_USER_SLOT)
        assertThat(refusal(gate.authorise(WriteRequest(WriteKind.ROUNDTRIP, WriteInitiator.USER_ACTION, 0x01), context())))
            .isEqualTo(GateRefusal.NOT_USER_SLOT)
    }

    @Test
    fun `values outside the profile's limits are refused`() {
        fun with(change: (DacEqBand) -> DacEqBand): List<DacEqBand> = curve().mapIndexed { i, b -> if (i == 2) change(b) else b }
        val cases = mapOf(
            "gain above the range" to with { it.copy(gainTenthsDb = 130) },
            "gain below the range" to with { it.copy(gainTenthsDb = -130) },
            "frequency below 20 Hz" to with { it.copy(frequencyHz = 19) },
            "frequency above 20 kHz" to with { it.copy(frequencyHz = 20_001) },
            "Q below 0.1" to with { it.copy(qHundredths = 9) },
            "Q above 10" to with { it.copy(qHundredths = 1001) },
            "a filter type the app does not write" to with { it.copy(typeCode = FiioFilterType.LOW_PASS.code) },
            "a filter type that does not exist" to with { it.copy(typeCode = 9) },
            "nine bands" to curve().take(9),
            "eleven bands" to curve() + DacEqBand.of(10, FiioFilterType.PEAK, 1000, 0.0, 1.0),
            "a band with the wrong index" to curve().mapIndexed { i, b -> if (i == 3) b.copy(index = 5) else b },
        )
        for ((what, bands) in cases) {
            val decision = apply(bands = bands)
            assertThat(decision).isInstanceOf(GateDecision.Refused::class.java)
            assertThat(refusal(decision)).isEqualTo(GateRefusal.OUT_OF_LIMITS)
            assertThat(what).isNotEmpty()
        }
        // The edges themselves are fine.
        assertThat(apply(bands = curve(0 to 12.0).mapIndexed { i, b -> if (i == 1) b.copy(gainTenthsDb = -120, frequencyHz = 20, qHundredths = 10) else b }))
            .isInstanceOf(GateDecision.Authorised::class.java)
    }

    @Test
    fun `three overlapping +6 dB boosts need more pre-gain than the DAC has, and are refused rather than clipped`() {
        val decision = apply(bands = curve(0 to 6.0, 1 to 6.0, 2 to 6.0))
        assertThat(refusal(decision)).isEqualTo(GateRefusal.NET_BOOST)
    }

    @Test
    fun `a curve that cannot be brought to 0 dB net inside the DAC's pre-gain range is refused`() {
        // Ten +12 dB peaks on one frequency: far beyond what -12 dB of pre-gain can take back.
        val stacked = (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, 1000, 12.0, 1.0) }
        assertThat(refusal(apply(bands = stacked))).isEqualTo(GateRefusal.NET_BOOST)
    }

    // ── The pre-gain and the net peak ────────────────────────────────────────────────────────────────────

    @Test
    fun `the gate computes the pre-gain and the net peak after it is at most 0 dB`() {
        for (bands in listOf(curve(3 to 2.0), curve(0 to 3.0, 1 to 3.0, 2 to 3.0), curve(5 to -4.0), curve(0 to 9.0, 9 to 9.0))) {
            val decision = apply(bands = bands) as GateDecision.Authorised
            val pre = decision.preGainTenthsDb!!
            assertThat(pre).isAtMost(0)
            assertThat(DacEqCurve.netPeakDb(bands, pre)).isAtMost(0.0)
        }
        // A cut-only curve needs none; a lone boost needs its own height, rounded down to the tenth.
        assertThat((apply(bands = curve(5 to -4.0)) as GateDecision.Authorised).preGainTenthsDb).isEqualTo(0)
        val lone = (apply(bands = curve(5 to 3.0)) as GateDecision.Authorised).preGainTenthsDb!!
        assertThat(lone).isAtMost(-30)
        assertThat(lone).isAtLeast(-33)
    }

    @Test
    fun `the pre-gain bound holds on an independent grid, at every rate, on both readings of the filter`() {
        val random = Random(30)
        repeat(60) {
            val bands = (0 until 10).map { i ->
                val type = listOf(FiioFilterType.PEAK, FiioFilterType.LOW_SHELF, FiioFilterType.HIGH_SHELF).random(random)
                DacEqBand.of(i, type, (20 * 1000.0.pow(random.nextDouble())).toInt().coerceIn(20, 20_000),
                    gainDb = random.nextInt(-120, 121) / 10.0, q = (0.1 + random.nextDouble() * 9.9))
            }
            val pre = DacEqCurve.preGainTenthsFor(bands)
            if (pre < -120) return@repeat
            for (rate in doubleArrayOf(44_100.0, 48_000.0, 96_000.0, 192_000.0)) {
                for (widened in booleanArrayOf(false, true)) {
                    val peak = (0..2000).maxOf { k ->
                        val f = 20.0 * 1000.0.pow(k / 2000.0)
                        if (f >= rate / 2) Double.NEGATIVE_INFINITY else referenceDb(bands, f, rate, widened)
                    }
                    assertThat(peak + pre / 10.0).isAtMost(1e-9)
                }
            }
        }
    }

    /** The same RBJ maths, written again from the cookbook, to check the gate's own evaluation on a finer grid. */
    private fun referenceDb(bands: List<DacEqBand>, f: Double, fs: Double, widened: Boolean): Double {
        var total = 0.0
        for (band in bands) {
            if (band.gainTenthsDb == 0) continue
            val gain = band.gainDb
            val a = 10.0.pow(gain / 40.0)
            var q = band.q
            if (widened && band.type == FiioFilterType.PEAK) q /= 10.0.pow(abs(gain) / 40.0)
            val w0 = 2 * PI * band.frequencyHz / fs
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            val (b, aa) = when (band.type) {
                FiioFilterType.PEAK -> doubleArrayOf(1 + alpha * a, -2 * c, 1 - alpha * a) to doubleArrayOf(1 + alpha / a, -2 * c, 1 - alpha / a)
                FiioFilterType.LOW_SHELF -> {
                    val t = 2 * sqrt(a) * alpha
                    doubleArrayOf(a * ((a + 1) - (a - 1) * c + t), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - t)) to
                        doubleArrayOf((a + 1) + (a - 1) * c + t, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - t)
                }
                else -> {
                    val t = 2 * sqrt(a) * alpha
                    doubleArrayOf(a * ((a + 1) + (a - 1) * c + t), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - t)) to
                        doubleArrayOf((a + 1) - (a - 1) * c + t, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - t)
                }
            }
            val w = 2 * PI * f / fs
            val numRe = b[0] + b[1] * cos(w) + b[2] * cos(2 * w)
            val numIm = -(b[1] * sin(w) + b[2] * sin(2 * w))
            val denRe = aa[0] + aa[1] * cos(w) + aa[2] * cos(2 * w)
            val denIm = -(aa[1] * sin(w) + aa[2] * sin(2 * w))
            total += 20 * log10(hypot(numRe, numIm) / hypot(denRe, denIm))
        }
        return total
    }

    @Test
    fun `a lone peak boosts by its gain at its centre and a cut never boosts`() {
        val peak = curve(5 to 6.0)
        assertThat(DacEqCurve.peakBoostDb(peak)).isWithin(0.15).of(6.0)
        assertThat(DacEqCurve.peakBoostDb(curve(5 to -6.0))).isAtMost(0.0)
        assertThat(DacEqCurve.preGainTenthsFor(curve())).isEqualTo(0)
        assertThat(DacEqCurve.preGainTenthsFor(curve(2 to -3.0, 7 to -9.0))).isEqualTo(0)
    }

    // ── The permit ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a permit covers exactly the frames of its operation, the gain and band frames in the order of its plan, and no others`() {
        val bands = curve(3 to 2.0)
        // The app's slot is the one selected: the gate answers with the plan and a permit for that sequence.
        val decision = apply(bands = bands, ctx = context(read = state(preset = slot))) as GateDecision.Authorised
        val permit = decision.permit
        val pre = decision.preGainTenthsDb!!
        val plan = decision.plan!!
        assertThat(plan.frames).containsExactly(
            AllowedFrame(FiioHidWrites.CMD_GAIN, FiioHidWrites.encodeGain(pre).toList()),
            AllowedFrame(FiioHidWrites.CMD_BAND, FiioHidWrites.encodeBand(bands[3]).toList()),
        ).inOrder()
        // The save is a set frame; the plan's frames are covered one at a time, in order.
        assertThat(permit.allows(melody.saveCommand, FiioHidWrites.encodePreset(slot))).isTrue()
        assertThat(permit.allows(FiioHidWrites.CMD_BAND, FiioHidWrites.encodeBand(bands[3]))).isFalse() // not yet: the pre-gain is first
        assertThat(permit.allows(FiioHidWrites.CMD_GAIN, FiioHidWrites.encodeGain(pre))).isTrue()
        assertThat(permit.take(FiioHidWrites.CMD_GAIN, FiioHidWrites.encodeGain(pre))).isTrue()
        assertThat(permit.allows(FiioHidWrites.CMD_GAIN, FiioHidWrites.encodeGain(pre))).isFalse() // each only once
        assertThat(permit.take(FiioHidWrites.CMD_BAND, FiioHidWrites.encodeBand(bands[3]))).isTrue()
        assertThat(permit.sequenceDone).isTrue()
        // Not another slot, not a factory preset, not another pre-gain, not another band value, not a save of another slot.
        assertThat(permit.allows(FiioHidWrites.CMD_PRESET, FiioHidWrites.encodePreset(0x01))).isFalse()
        assertThat(permit.allows(FiioHidWrites.CMD_PRESET, FiioHidWrites.encodePreset(slot))).isFalse() // selected already: no select is covered
        assertThat(permit.allows(melody.saveCommand, FiioHidWrites.encodePreset(0x01))).isFalse()
        // Nor the volume commands, nor any other command: no volume SET of any kind.
        assertThat(permit.allows(0x02, byteArrayOf(0x01, 0x01, 0x10))).isFalse()
        assertThat(permit.allows(0x1A, byteArrayOf(1))).isFalse()

        // The app's slot is not selected yet: the first answer covers the select (and the way back) and no content.
        val first = apply(bands = bands) as GateDecision.Authorised
        assertThat(first.plan).isNull()
        assertThat(first.permit.allows(FiioHidWrites.CMD_PRESET, FiioHidWrites.encodePreset(slot))).isTrue()
        assertThat(first.permit.allows(FiioHidWrites.CMD_PRESET, FiioHidWrites.encodePreset(0xF0))).isTrue()
        assertThat(first.permit.allows(FiioHidWrites.CMD_PRESET, FiioHidWrites.encodePreset(0x01))).isFalse()
        assertThat(first.permit.allows(FiioHidWrites.CMD_GAIN, FiioHidWrites.encodeGain(pre))).isFalse()
        assertThat(first.permit.allows(FiioHidWrites.CMD_BAND, FiioHidWrites.encodeBand(bands[3]))).isFalse()
    }

    @Test
    fun `a restore selects only the slots it writes, and a roundtrip only the app's slot`() {
        val withFactory = backup(active = 0x01, slots = mapOf(0x01 to flat(0x01), slot to flat()))
        // The pointer stage selects what the backup recorded: a factory preset can be selected again, never written.
        val pointer = gate.authorise(
            WriteRequest(WriteKind.RESTORE, WriteInitiator.REVIEWER_COMMAND),
            context(read = state(preset = slot), backup = withFactory),
        ) as GateDecision.Authorised
        assertThat(pointer.permit.allows(FiioHidWrites.CMD_PRESET, FiioHidWrites.encodePreset(0x01))).isTrue()
        assertThat(pointer.permit.allows(FiioHidWrites.CMD_PRESET, FiioHidWrites.encodePreset(0x02))).isFalse()
        assertThat(pointer.permit.allows(FiioHidWrites.CMD_GAIN, FiioHidWrites.encodeGain(0))).isFalse()
        assertThat(pointer.permit.allows(melody.saveCommand, FiioHidWrites.encodePreset(0x01))).isFalse()
        // A factory preset's content is never a restore stage at all.
        val factory = gate.authorise(
            WriteRequest(WriteKind.RESTORE, WriteInitiator.REVIEWER_COMMAND, 0x01),
            context(read = state(preset = 0x01), backup = withFactory),
        )
        assertThat(refusal(factory)).isEqualTo(GateRefusal.NOT_USER_SLOT)

        val roundtrip = gate.authorise(
            WriteRequest(WriteKind.ROUNDTRIP, WriteInitiator.REVIEWER_COMMAND, slot),
            context(read = state(preset = slot)),
        ) as GateDecision.Authorised
        assertThat(roundtrip.permit.allows(melody.saveCommand, FiioHidWrites.encodePreset(slot))).isTrue()
        assertThat(roundtrip.permit.allows(melody.saveCommand, FiioHidWrites.encodePreset(0xA0))).isFalse()
        assertThat(roundtrip.plan!!.frames.count { it.command == FiioHidWrites.CMD_BAND }).isEqualTo(10) // every band, changed or not
    }

    @Test
    fun `a restore and a roundtrip still pass every condition but the net boost`() {
        for (kind in listOf(WriteKind.RESTORE, WriteKind.ROUNDTRIP)) {
            fun ask(ctx: WriteContext) = gate.authorise(WriteRequest(kind, WriteInitiator.REVIEWER_COMMAND, slot), ctx)
            assertThat(refusal(ask(context(profile = melody, override = false)))).isEqualTo(GateRefusal.PROFILE_NOT_VERIFIED)
            assertThat(refusal(ask(context(backup = null)))).isEqualTo(GateRefusal.NO_BACKUP)
            assertThat(refusal(ask(context(backup = backup(key = "other"))))).isEqualTo(GateRefusal.BACKUP_FOR_OTHER_DAC)
            assertThat(refusal(ask(context(age = DacEqWriteGate.MAX_READ_AGE_MS + 1)))).isEqualTo(GateRefusal.STALE_READ)
        }
        // A backup that holds the user's own boosted curve is written back as it was: the net-boost rule is about curves we author.
        val boosted = DacEqSlotSnapshot(slot, 20, curve(2 to 6.0).also { }) // +2.0 dB pre-gain and a +6 dB band
        val restore = gate.authorise(
            WriteRequest(WriteKind.RESTORE, WriteInitiator.REVIEWER_COMMAND),
            context(backup = backup(slots = mapOf(0xF0 to flat(0xF0), slot to boosted))),
        )
        assertThat(restore).isInstanceOf(GateDecision.Authorised::class.java)
    }

    @Test
    fun `sending a frame the permit does not cover throws and puts nothing on the wire`() {
        val dac = FakeFiioDac(melody)
        val session = FiioHidSession(dac, melody)
        val permit = (apply() as GateDecision.Authorised).permit
        assertThrows<IllegalStateException> {
            FiioHidWrites.send(session, permit, FiioHidWrites.CMD_PRESET, FiioHidWrites.encodePreset(0x01), "select factory")
        }
        assertThrows<IllegalStateException> {
            FiioHidWrites.send(session, permit, 0x02, byteArrayOf(0x01, 0x01, 0x00), "volume")
        }
        assertThat(dac.written).isEmpty()
        // And one it covers goes out as a SET frame with the CRC the DAC computes on its own.
        assertThat(FiioHidWrites.send(session, permit, FiioHidWrites.CMD_PRESET, FiioHidWrites.encodePreset(slot), "select")).isTrue()
        val frame = dac.written.single()
        assertThat(frame[1].toInt() and 0xFF).isEqualTo(0xAA)
        assertThat(frame[2].toInt()).isEqualTo(0x0A)
        val crcAt = 7 + frame[6].toInt()
        assertThat(frame[crcAt].toInt() and 0xFF).isEqualTo(FiioCrc8.maxim(frame, 1, crcAt))
        assertThat(frame[crcAt + 1].toInt() and 0xFF).isEqualTo(0xEE)
    }

    @Test
    fun `the SET frame has the sources' layout, the CRC is real or zero, and a band round-trips through the decoder`() {
        val band = DacEqBand.of(3, FiioFilterType.LOW_SHELF, 250, -1.5, 0.71)
        assertThat(FiioHidWrites.encodeBand(band)).isEqualTo(byteArrayOf(3, 0xFF.toByte(), 0xF1.toByte(), 0, 0xFA.toByte(), 0, 71, 1))
        val frame = FiioHidWrites.setFrame(0x17, FiioHidWrites.encodeGain(-31), FiioCrcMode.MAXIM)
        assertThat(frame.copyOfRange(0, 8)).isEqualTo(byteArrayOf(0xAA.toByte(), 0x0A, 0, 0, 0x17, 2, 0xFF.toByte(), 0xE1.toByte()))
        assertThat(frame[8].toInt() and 0xFF).isEqualTo(FiioCrc8.maxim(frame, 0, 8))
        assertThat(frame[9].toInt() and 0xFF).isEqualTo(0xEE)
        assertThat(FiioHidWrites.setFrame(0x17, FiioHidWrites.encodeGain(-31), FiioCrcMode.ZERO)[8].toInt()).isEqualTo(0)
        val decoded = (FiioEqReader.decodeBand(3, FiioHidWrites.encodeBand(band)) as EqRead.Read).value
        assertThat(decoded).isEqualTo(band)
    }

    // ── Where SET frames and permits exist ───────────────────────────────────────────────────────────────

    private val fiioMain = File("src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio")

    @Test
    fun `a SET frame exists in one file only, and the read half has none`() {
        val sources = fiioMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertThat(sources).isNotEmpty()
        // B31 P1.1: the reply parser now *recognises* the SET head, because the DAC's acknowledgement of a SET carries it; it does not
        // build one. A frame is built (`fun setFrame`) in one file only, and the read half still has no way to make one.
        val builders = sources.filter { Regex("fun setFrame").containsMatchIn(it.readText()) }
        assertThat(builders.map { it.name }).containsExactly("FiioHidWrites.kt")
        val withSetHead = sources.filter { Regex("0xAA|SetHead|SET_HEAD").containsMatchIn(it.readText()) }
        assertThat(withSetHead.map { it.name }).containsExactly("FiioHidWrites.kt", "FiioHidReplies.kt")
        val replies = File(fiioMain, "FiioHidReplies.kt").readText()
        assertThat(replies).doesNotContain("outputReport(")
        assertThat(replies).doesNotContain("wire.write(")
        // Every GET the session sends still passes the read-only guard.
        assertThat(File(fiioMain, "FiioHidSession.kt").readText()).contains("FiioHidFrames.requireReadOnly(")
        assertThat(File(fiioMain, "FiioHidProbe.kt").readText()).contains("FiioHidFrames.requireReadOnly(")
        // The attach controller cannot reach the write half at all.
        val controller = File(fiioMain, "DacEqController.kt").readText()
        for (forbidden in listOf("FiioHidWrites", "DacEqWriter", "WritePermit", "DacEqWriteGate")) {
            assertThat(controller.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")).doesNotContain(forbidden)
        }
    }

    @Test
    fun `a permit is made by the gate and by nothing else, and a SET is sent through send and nothing else`() {
        val roots = listOf(File("src/main/java"), File("src/debug/java"))
        val kotlin = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        val permitMakers = kotlin.filter { Regex("""\bWritePermit\(""").containsMatchIn(it.readText()) }
        assertThat(permitMakers.map { it.name }).containsExactly("DacEqWriteGate.kt")
        val senders = kotlin.filter { it.readText().contains("FiioHidWrites.send(") }
        assertThat(senders.map { it.name }).containsExactly("DacEqWriter.kt")
        val writeCalls = kotlin.filter { Regex("""session\.wire\.write\(|\bwire\.write\(""").containsMatchIn(it.readText()) }
        assertThat(writeCalls.map { it.name }).containsExactly("FiioHidSession.kt", "FiioHidWrites.kt")
    }

    @Test
    fun `no volume command is anywhere in the write half`() {
        val writes = File(fiioMain, "FiioHidWrites.kt").readText() + File(fiioMain, "DacEqWriter.kt").readText() +
            File(fiioMain, "DacEqWriteGate.kt").readText()
        assertThat(writes).doesNotContain("0x02, 0x01")
        assertThat(writes).doesNotContain("volume_limit")
        assertThat(Regex("""CMD_VOLUME|setVolume|volume\s*=""").containsMatchIn(writes)).isFalse()
    }
}
