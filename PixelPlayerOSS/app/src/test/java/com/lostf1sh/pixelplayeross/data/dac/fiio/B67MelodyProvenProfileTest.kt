package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.player.DebugFlagStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B67 item 2: **the Melody's row says what was proven on the device.** On 2026-11-22, on the owner's HONOR with a Snowsky Melody, the reviewer's
 * listening check showed that a positive pre-gain makes it louder (BOOST), and the app's take-over, apply and read-back of all ten bands on its slot
 * (USER 3, 0xA2) matched. So the row is `verified = true, preGainSense = BOOST`; every other row stays unverified and UNCONFIRMED.
 *
 * Before B67 a release build (no debug override exists there) refused a curve that boosts on the Melody (`PRE_GAIN_SENSE_UNCONFIRMED`), and refused
 * every write without the listener's consent (`PROFILE_NOT_VERIFIED`). Here a release-like rig: the debug switches built with `debugBuild = false`
 * and a stored value they must ignore, no consent, a fake Melody that keeps the global gain in whole dB as the real one does.
 */
class B67MelodyProvenProfileTest {
    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val dirs = mutableListOf<File>()

    @AfterEach fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private class StoredFlag(var value: Boolean) : DebugFlagStore {
        override fun read() = value
        override fun write(value: Boolean) { this.value = value }
    }

    private class StoredText(var value: String?) : DebugTextStore {
        override fun read() = value
        override fun write(value: String?) { this.value = value }
    }

    private class Opener(val dac: FakeFiioDac) : FiioHidOpener {
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> =
            HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
    }

    private fun curve(vararg gains: Pair<Int, Double>, q: Double = 1.0): List<DacEqBand> {
        val map = gains.toMap()
        return (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], map[it] ?: 0.0, q) }
    }

    /** A release build: both debug switches hold a value a debug build would act on, and ignore it; the listener has not consented. */
    private inner class ReleaseRig {
        val writeSwitch = DacEqWriteSwitch(StoredFlag(true), debugBuild = false)
        val senseSwitch = DacEqPreGainSenseSwitch(StoredText("attenuation"), debugBuild = false)
        // The Melody as the owner's: USER 3 holds its factory curve, Close EQ selected; the global gain is kept in whole dB, towards 0 (11-22).
        val dac = RecordedMelody.fakeDac(0xF0, melody).also { it.storePreGain = { sent -> sent / 10 * 10 } }
        val log = mutableListOf<String>()
        val store = DacEqBackupStore(Files.createTempDirectory("dac-eq-b67").toFile().also { dirs += it })
        val att = AttachedFiio("path-a", "identity-a", "profile-a", "SNOWSKY Melody", "path-a", melody)
        val writer = DacEqWriter(
            DacEqHidAccess(Opener(dac), Dispatchers.Unconfined), DacEqWriteGate(), store, writeSwitch::isAllowed, { 0L }, { 1_700_000_000_000L },
            pause = {}, log = { log += it }, senseOf = senseSwitch::effective, userConsent = { false },
        )
        val turnOn = DacEqTurnOn(writer, store, playback = null, senseOf = senseSwitch::effective, log = { log += it })

        fun gainSets(): List<Int> = dac.sets.filter { it.first == FiioHidWrites.CMD_GAIN }
            .map { (_, data) -> ((data[0].toInt() shl 8) or (data[1].toInt() and 0xFF)).toShort().toInt() }
    }

    @Test
    fun `the Melody row is verified and BOOST`() {
        assertThat(melody.verified).isTrue()
        assertThat(melody.preGainSense).isEqualTo(PreGainSense.BOOST)
        assertThat(melody.canBeWritten).isTrue()
        assertThat(DacEqCapability.of(melody).verified).isTrue()
    }

    @Test
    fun `no other row is verified or knows its sense`() {
        val others = FiioHidProfiles.all.filter { it.id != "snowsky-melody" }
        assertThat(others).hasSize(FiioHidProfiles.all.size - 1)
        assertThat(others.filter { it.verified }).isEmpty()
        assertThat(others.map { it.preGainSense }.toSet()).containsExactly(PreGainSense.UNCONFIRMED)
        // and none of them can carry a write at all, verified or not
        assertThat(others.filter { it.canBeWritten }).isEmpty()
    }

    @Test
    fun `a release build turns a boost curve on, with a pre-gain that attenuates in whole dB, and the DAC holds it`() {
        val r = ReleaseRig()
        // nothing a debug build could use is in force
        assertThat(r.writeSwitch.isAllowed()).isFalse()
        assertThat(r.senseSwitch.override()).isNull()
        assertThat(r.senseSwitch.effective(melody)).isEqualTo(PreGainSense.BOOST)

        val boost = curve(4 to 3.0, 7 to 1.5)
        val attenuation = DacEqCurve.attenuationTenthsFor(boost)
        assertThat(attenuation).isGreaterThan(0)
        assertThat(attenuation % DacEqCurve.PRE_GAIN_STEP_TENTHS).isEqualTo(0) // whole dB (the 11-22 rule)

        val outcome = runBlocking { r.turnOn.turnOn(r.att, boost, WriteInitiator.USER_ACTION) }
        assertThat(outcome.result).isInstanceOf(DacEqWriteResult.Done::class.java)

        val slot = r.store.load(r.att.backupKey)!!.appSlotFor(melody)!!
        assertThat(melody.writableSlots.map { it.id }).contains(slot)
        // the last global gain the app sent is the curve's: negative under BOOST, whole dB, exactly the attenuation
        assertThat(r.gainSets().last()).isEqualTo(-attenuation)
        // every global gain the app sent is whole dB, so a DAC that keeps whole dB stores what it was sent and the read-back matches
        assertThat(r.gainSets().all { it % DacEqCurve.PRE_GAIN_STEP_TENTHS == 0 }).isTrue()
        assertThat(r.dac.activePreset).isEqualTo(slot)
        val held = r.dac.slot(slot)
        assertThat(held.preGainTenths).isEqualTo(-attenuation)
        assertThat(held.bands.map { it.gainTenthsDb }).isEqualTo(boost.map { it.gainTenthsDb })
        // the rule itself: at or below 0 dB net, on the reading the row gives
        assertThat(DacEqCurve.netPeakDb(boost, held.preGainTenths, PreGainSense.BOOST)).isAtMost(0.0)
        // the writer's log says what let the write through: the row, not an override or a consent
        val header = r.log.first { it.startsWith("DAC_HID|OP APPLY_CURVE") }
        assertThat(header).contains("verified=true")
        assertThat(header).contains("override=false")
        assertThat(header).contains("consent=false")
        assertThat(header).contains("preGainSense=BOOST")
    }

    @Test
    fun `a curve that would end above 0 dB net is still refused, and nothing is sent`() {
        val r = ReleaseRig()
        check(runBlocking { r.turnOn.turnOn(r.att, curve(4 to 2.0), WriteInitiator.USER_ACTION) }.result is DacEqWriteResult.Done)
        val sent = r.dac.sets.size
        val slotBefore = r.dac.slot(r.dac.activePreset).let { it.preGainTenths to it.bands.toList() }

        // three neighbouring bands at the limit: the peak needs more attenuation than the Melody's −12 dB global gain can give
        val tooLoud = curve(3 to 12.0, 4 to 12.0, 5 to 12.0, q = 0.5)
        assertThat(DacEqCurve.preGainTenthsFor(tooLoud, PreGainSense.BOOST)!!).isLessThan((melody.minGainDb * 10).toInt())
        val result = runBlocking { r.writer.applyCurve(r.att, tooLoud, WriteInitiator.USER_ACTION) }
        assertThat((result as DacEqWriteResult.Refused).refusal).isEqualTo(GateRefusal.NET_BOOST)
        assertThat(r.dac.sets.size).isEqualTo(sent)
        assertThat(r.dac.slot(r.dac.activePreset).let { it.preGainTenths to it.bands.toList() }).isEqualTo(slotBefore)
    }

    @Test
    fun `the gate's own check on the result refuses a pre-gain that leaves the net peak above 0 dB`() {
        // The gate computes the pre-gain itself, so a too-small one cannot come from a request; this pins the rule it checks on the result.
        val boost = curve(4 to 6.0)
        val needed = DacEqCurve.preGainTenthsFor(boost, PreGainSense.BOOST)!!
        assertThat(DacEqCurve.netPeakDb(boost, needed, PreGainSense.BOOST)).isAtMost(0.0)
        assertThat(DacEqCurve.netPeakDb(boost, needed + DacEqCurve.PRE_GAIN_STEP_TENTHS, PreGainSense.BOOST)).isGreaterThan(0.0)
    }
}
