package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B40-F2: the listener's permission lifts one requirement of the write gate, "the profile is verified", for a write the listener asks
 * for, and nothing else. Every other condition still holds, the reviewer's steps stay the reviewer's, and nothing automatic is let in.
 */
class DacEqConsentGateTest {
    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val slot = 0xA2
    private val dirs = mutableListOf<File>()

    @AfterEach
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private fun flat() = DacEqSlotSnapshot(
        slot, 0, (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], 0.0, 0.71) },
    )

    private fun state() = DacEqState(
        capability = DacEqCapability.of(melody), activePreset = EqRead.Read(0xF0), bandCount = EqRead.Read(10),
        preGainTenthsDb = EqRead.Read(0), eqSwitch = EqRead.Unknown("no answer"), bands = flat().bands.map { EqRead.Read(it) },
    )

    private fun context(consent: Boolean, override: Boolean = false, read: DacEqState = state(), backup: DacEqBackup? = DacEqBackup(
        "profile-1", "snowsky-melody", "SNOWSKY Melody", 1L, 0xF0, null, mapOf(0xF0 to flat().copy(presetId = 0xF0), slot to flat()),
    )) = WriteContext(melody, "profile-1", read, 100, backup, override, userConsent = consent)

    private fun curve(vararg gains: Pair<Int, Double>): List<DacEqBand> {
        val map = gains.toMap()
        return (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], map[it] ?: 0.0, 0.71) }
    }

    private val gate = DacEqWriteGate()

    private fun apply(initiator: WriteInitiator, ctx: WriteContext, bands: List<DacEqBand> = curve(3 to 2.0)) =
        gate.authorise(WriteRequest(WriteKind.APPLY_CURVE, initiator, slot, bands), ctx)

    private fun refusal(decision: GateDecision) = (decision as GateDecision.Refused).refusal

    @Test
    fun `an unverified Melody is refused without the permission, and a listener's write goes through with it`() {
        assertThat(melody.verified).isFalse()
        assertThat(refusal(apply(WriteInitiator.USER_ACTION, context(consent = false)))).isEqualTo(GateRefusal.PROFILE_NOT_VERIFIED)
        assertThat(apply(WriteInitiator.USER_ACTION, context(consent = true))).isInstanceOf(GateDecision.Authorised::class.java)
    }

    @Test
    fun `the permission is the listener's, so a reviewer's command and anything automatic do not ride on it`() {
        assertThat(refusal(apply(WriteInitiator.REVIEWER_COMMAND, context(consent = true)))).isEqualTo(GateRefusal.PROFILE_NOT_VERIFIED)
        assertThat(refusal(apply(WriteInitiator.AUTOMATIC, context(consent = true)))).isEqualTo(GateRefusal.NOT_USER_INITIATED)
        // the reviewer's own key still works as it did
        assertThat(apply(WriteInitiator.REVIEWER_COMMAND, context(consent = false, override = true))).isInstanceOf(GateDecision.Authorised::class.java)
    }

    @Test
    fun `the permission lifts nothing else - not the read, the backup, the limits or the net level`() {
        val incomplete = state().copy(preGainTenthsDb = EqRead.Unknown("none"))
        assertThat(refusal(apply(WriteInitiator.USER_ACTION, context(consent = true, read = incomplete)))).isEqualTo(GateRefusal.READ_NOT_WORKING)
        assertThat(refusal(apply(WriteInitiator.USER_ACTION, context(consent = true, backup = null)))).isEqualTo(GateRefusal.NO_BACKUP)
        val outOfLimits = (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], 30.0, 0.71) }
        assertThat(refusal(apply(WriteInitiator.USER_ACTION, context(consent = true), outOfLimits))).isEqualTo(GateRefusal.OUT_OF_LIMITS)
    }

    @Test
    fun `the reviewer's step stays the reviewer's even for a listener who allowed writes`() {
        val peek = gate.authorise(WriteRequest(WriteKind.PEEK_SLOT, WriteInitiator.USER_ACTION, slot), context(consent = true))
        assertThat(refusal(peek)).isEqualTo(GateRefusal.REVIEWER_ONLY)
    }

    @Test
    fun `a model the app cannot write stays read-only whatever was allowed`() {
        val k13 = FiioHidProfiles.all.single { it.id == "fiio-k13-r2r" }
        val ctx = WriteContext(k13, "k", state(), 100, null, false, userConsent = true)
        assertThat(refusal(apply(WriteInitiator.USER_ACTION, ctx))).isEqualTo(GateRefusal.PROFILE_CANNOT_BE_WRITTEN)
    }

    // ── end to end, against a fake Melody ────────────────────────────────────────────────────────

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> =
            HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
    }

    private fun writer(dac: FakeFiioDac, store: DacEqBackupStore, override: Boolean, consent: Boolean) = DacEqWriter(
        access = DacEqHidAccess(FakeOpener(dac), Dispatchers.Unconfined),
        gate = DacEqWriteGate(),
        backups = store,
        overrideAllowsWrites = { override },
        elapsedMs = { 0L },
        wallClockMs = { 1_700_000_000_000L },
        pause = {},
        userConsent = { consent },
    )

    @Test
    fun `with the permission and without the reviewer's override a listener's write is made, read back and held by the DAC`() {
        val dac = FakeFiioDac(melody, activePreset = 0xF0)
        val att = AttachedFiio("/dev/bus/usb/001/005", "dac-model", "profile-1", "SNOWSKY Melody", "/dev/bus/usb/001/005", melody)
        val store = DacEqBackupStore(Files.createTempDirectory("dac-eq-consent-gate").toFile().also { dirs += it })
        // the reviewer's look at the app's slot comes first (the gate refuses a slot nobody has looked at); that is the reviewer's, with the override
        check(runBlocking { writer(dac, store, override = true, consent = false).discoverSlot(att) } is DacEqWriteResult.Done)
        dac.written.clear()
        val listener = writer(dac, store, override = false, consent = true)
        // a cut: the sense of the global gain is unconfirmed on every row, so a curve that rises above 0 dB is refused (and the next test says so)
        val result = runBlocking { listener.applyCurve(att, curve(3 to -2.0), WriteInitiator.USER_ACTION) }
        assertThat(result).isInstanceOf(DacEqWriteResult.Done::class.java)
        val read = FiioEqReader.read(FiioHidSession(dac, melody))
        assertThat(read.activePreset).isEqualTo(EqRead.Read(slot))
        assertThat(read.readBands[3].gainTenthsDb).isEqualTo(-20)
    }

    @Test
    fun `a boost is still refused for a listener who allowed writes, while the sense of the global gain is unconfirmed`() {
        val dac = FakeFiioDac(melody, activePreset = 0xF0)
        val att = AttachedFiio("/dev/bus/usb/001/005", "dac-model", "profile-1", "SNOWSKY Melody", "/dev/bus/usb/001/005", melody)
        val store = DacEqBackupStore(Files.createTempDirectory("dac-eq-consent-gate3").toFile().also { dirs += it })
        check(runBlocking { writer(dac, store, override = true, consent = false).discoverSlot(att) } is DacEqWriteResult.Done)
        dac.written.clear()
        val result = runBlocking { writer(dac, store, override = false, consent = true).applyCurve(att, curve(3 to 2.0), WriteInitiator.USER_ACTION) }
        assertThat((result as DacEqWriteResult.Refused).refusal).isEqualTo(GateRefusal.PRE_GAIN_SENSE_UNCONFIRMED)
        assertThat(dac.sets).isEmpty()
    }

    @Test
    fun `without the permission the same write sends nothing`() {
        val dac = FakeFiioDac(melody, activePreset = 0xF0)
        val att = AttachedFiio("/dev/bus/usb/001/005", "dac-model", "profile-1", "SNOWSKY Melody", "/dev/bus/usb/001/005", melody)
        val store = DacEqBackupStore(Files.createTempDirectory("dac-eq-consent-gate2").toFile().also { dirs += it })
        check(runBlocking { writer(dac, store, override = true, consent = false).discoverSlot(att) } is DacEqWriteResult.Done)
        dac.written.clear()
        val result = runBlocking { writer(dac, store, override = false, consent = false).applyCurve(att, curve(3 to -2.0), WriteInitiator.USER_ACTION) }
        assertThat(result).isInstanceOf(DacEqWriteResult.Refused::class.java)
        assertThat((result as DacEqWriteResult.Refused).refusal).isEqualTo(GateRefusal.PROFILE_NOT_VERIFIED)
        assertThat(dac.sets).isEmpty()
    }

    @Test
    fun `the consent store keeps one yes per model and forgets it when it is taken back`() {
        val map = mutableMapOf<String, Long>()
        val store = object : DacEqConsentStore {
            override fun read(profileId: String) = map[profileId]
            override fun write(profileId: String, atMs: Long?) { if (atMs == null) map.remove(profileId) else map[profileId] = atMs }
        }
        val consent = DacEqWriteConsent(store)
        assertThat(consent.isGiven("snowsky-melody")).isFalse()
        consent.give("snowsky-melody", 5L)
        assertThat(consent.isGiven("snowsky-melody")).isTrue()
        assertThat(consent.isGiven("fiio-ka15")).isFalse()
        assertThat(DacEqWriteConsent(store).isGiven("snowsky-melody")).isTrue()   // the next launch
        consent.withdraw("snowsky-melody")
        assertThat(consent.isGiven("snowsky-melody")).isFalse()
        assertThat(DacEqWriteConsent(store).isGiven("snowsky-melody")).isFalse()
    }

    @Test
    fun `wiring - the debug override is still debug-only and the consent is given only from the screen's controller`() {
        val main = File("src/main/java/com/lostf1sh/pixelplayeross")
        val users = main.walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText().contains("giveConsent(") || it.readText().contains("consent.give(") }
            .map { it.name }.toSet()
        // the service defines it, the screen's controller calls it, the store implements it; no debug receiver or other path gives a yes
        assertThat(users).containsExactly("DacEqService.kt", "DacEqScreenController.kt")
    }
}
