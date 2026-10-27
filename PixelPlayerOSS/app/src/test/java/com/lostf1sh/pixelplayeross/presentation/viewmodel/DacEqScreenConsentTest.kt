package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.dac.fiio.AttachedFiio
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBackupStore
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBand
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqConsentStore
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqController
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqHidAccess
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqService
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqVerdict
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteAvailability
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteConsent
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteSwitch
import com.lostf1sh.pixelplayeross.data.dac.fiio.FakeFiioDac
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioFilterType
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidOpener
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidProfiles
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidSession
import com.lostf1sh.pixelplayeross.data.dac.fiio.HidUse
import com.lostf1sh.pixelplayeross.data.headphones.DacEqFilterDef
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPairDac
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPreset
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPresetStore
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileCatalog
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileState
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.service.player.DebugFlagStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B40-F2: the listener's permission to change the DAC's EQ, from the software Resonance screen's offer to the DAC's own screen and back.
 * The Melody's profile is **not verified** here and the debug override is off (this is the release listener's situation): before the
 * permission the screen is the software one and offers the DAC's EQ; with it, the DAC's screen; taken back, the software one again.
 * Nothing is written by any of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DacEqScreenConsentTest {
    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val dirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        dirs.forEach { it.deleteRecursively() }
    }

    private class MemoryConsent : DacEqConsentStore {
        val map = mutableMapOf<String, Long>()
        override fun read(profileId: String): Long? = map[profileId]
        override fun write(profileId: String, atMs: Long?) { if (atMs == null) map.remove(profileId) else map[profileId] = atMs }
    }

    private class MemoryFlag(var value: Boolean) : DebugFlagStore {
        override fun read() = value
        override fun write(value: Boolean) { this.value = value }
    }

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> =
            HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
    }

    private class MemoryStore(initial: HeadphoneProfileState) : DacEqPresetStore {
        private val flow = MutableStateFlow(initial)
        override val state: StateFlow<HeadphoneProfileState> = flow
        var softwareEqOffFor: String? = null
        override suspend fun saveDacEqPreset(headphoneId: String, dac: DacEqPairDac, name: String, preGainTenthsDb: Int,
            accepted: List<DacEqFilterDef>, requested: List<DacEqFilterDef>?, presetId: String?): DacEqPreset? = null
        override suspend fun selectDacEq(headphoneId: String, dacProfileId: String, presetId: String?, eqOn: Boolean) = Unit
        override suspend fun renameDacEqPreset(headphoneId: String, dacProfileId: String, presetId: String, name: String) = Unit
        override suspend fun deleteDacEqPreset(headphoneId: String, dacProfileId: String, presetId: String) = Unit
        override suspend fun turnSoftwareEqOff(headphoneId: String) { softwareEqOffFor = headphoneId }
    }

    private class Rig(
        val dac: FakeFiioDac,
        val consent: MemoryConsent,
        val service: DacEqService,
        val store: MemoryStore,
        val screen: DacEqScreenController,
        val softwareOff: MutableList<String>,
    )

    private fun TestScope.appScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }

    private fun TestScope.rig(activePreset: Int = 0xF0, softwareEqOn: Boolean = false, configure: (FakeFiioDac) -> Unit = {}): Rig {
        val dac = FakeFiioDac(melody, activePreset = activePreset).also(configure)
        val att = AttachedFiio("/dev/bus/usb/001/005", "dac-id", "dac-melody", "SNOWSKY Melody", "/dev/bus/usb/001/005", melody)
        val attached = MutableStateFlow<AttachedFiio?>(att)
        val scope = appScope()
        val controller = DacEqController(
            attached, MutableStateFlow(true), DacEqHidAccess(FakeOpener(dac), Dispatchers.Unconfined),
            clock = { 0L }, settleMs = 10L, attachedNow = { attached.value },
        )
        controller.start(scope)
        val dir = Files.createTempDirectory("dac-eq-consent").toFile().also { dirs += it }
        val consent = MemoryConsent()
        val service = DacEqService(
            controller, DacEqBackupStore(dir), DacEqWriteSwitch(MemoryFlag(false), debugBuild = true),
            consent = DacEqWriteConsent(consent),
            elapsedMs = { 0L }, wallClockMs = { 1_700_000_000_000L }, pause = {},
        )
        val audio = MutableStateFlow(
            com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus(
                route = AudioRouteState(
                    actualBackend = AudioBackend.USB_DIRECT, desiredBackend = AudioBackend.USB_DIRECT,
                    transition = AudioRouteTransition.DIRECT_ACTIVE, reason = AudioRouteReason.DIRECT_PRISTINE,
                    usbDacPresent = true, signalMode = DirectSignalMode.PRISTINE,
                ),
                sinkDirectActive = true,
            ),
        )
        var profiles = HeadphoneProfileCatalog.createAndSelect(HeadphoneProfileState(), "TYGR", HeadphoneEqState(enabled = softwareEqOn), 1L, "hp-tygr")
        val store = MemoryStore(profiles)
        val softwareOff = mutableListOf<String>()
        val screen = DacEqScreenController(
            scope, "hp-tygr", service, store, audio, debounceMs = 700L, waitMs = 4_000L,
            turnSoftwareEqOff = { id -> softwareOff += id },
        )
        return Rig(dac, consent, service, store, screen, softwareOff)
    }

    @Test
    fun `before the permission an unverified Melody gets the software screen, which offers the DAC's own EQ`() = runTest {
        val r = rig()
        advanceUntilIdle()
        assertThat(r.service.availability()).isEqualTo(DacEqWriteAvailability.NEEDS_VERIFICATION)
        assertThat(r.screen.mode.value).isEqualTo(ResonanceMode.SOFTWARE)
        assertThat(r.screen.offer.value).isEqualTo(DacEqOffer("SNOWSKY Melody", "USER 3"))
        assertThat(r.dac.sets).isEmpty()
    }

    @Test
    fun `saying yes turns the screen into the DAC's, writes nothing, and opens editing`() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.screen.grantConsent()
        advanceUntilIdle()
        assertThat(r.service.availability()).isEqualTo(DacEqWriteAvailability.USER_CONFIRMED)
        assertThat(r.consent.map).containsKey("snowsky-melody")
        assertThat(r.screen.mode.value).isEqualTo(ResonanceMode.DAC)
        assertThat(r.screen.offer.value).isNull()
        assertThat(r.dac.sets).isEmpty()                       // the yes writes nothing
        assertThat(r.screen.writesStarted).isEqualTo(0)
        val ui = r.screen.uiState.value
        assertThat(ui.consentGiven).isTrue()
        assertThat(ui.availability.canWrite).isTrue()
        assertThat(ui.debugOverride).isFalse()                 // it is the listener's permission, not the reviewer's override
    }

    @Test
    fun `taking the permission back returns to the software screen and the offer, with the DAC untouched`() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.screen.grantConsent()
        advanceUntilIdle()
        r.screen.withdrawConsent()
        advanceUntilIdle()
        assertThat(r.consent.map).isEmpty()
        assertThat(r.screen.mode.value).isEqualTo(ResonanceMode.SOFTWARE)
        assertThat(r.screen.offer.value).isNotNull()
        assertThat(r.dac.sets).isEmpty()
    }

    @Test
    fun `the permission is kept per model and survives a restart`() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.screen.grantConsent()
        // a new consent object over the same storage: the next launch
        assertThat(DacEqWriteConsent(r.consent).isGiven("snowsky-melody")).isTrue()
        assertThat(DacEqWriteConsent(r.consent).isGiven("fiio-k13-r2r")).isFalse()
    }

    @Test
    fun `a model the app cannot write has nothing to offer and nothing to allow`() = runTest {
        val k13 = FiioHidProfiles.all.single { it.id == "fiio-k13-r2r" }
        val att = AttachedFiio("/dev/bus/usb/001/006", "k13", "dac-k13", "FiiO K13 R2R", "/dev/bus/usb/001/006", k13)
        val attached = MutableStateFlow<AttachedFiio?>(att)
        val dac = FakeFiioDac(k13, activePreset = 0xF0)
        val controller = DacEqController(
            attached, MutableStateFlow(true), DacEqHidAccess(FakeOpener(dac), Dispatchers.Unconfined),
            clock = { 0L }, settleMs = 10L, attachedNow = { attached.value },
        )
        controller.start(appScope())
        val dir = Files.createTempDirectory("dac-eq-consent-k13").toFile().also { dirs += it }
        val service = DacEqService(
            controller, DacEqBackupStore(dir), DacEqWriteSwitch(MemoryFlag(false), debugBuild = true),
            consent = DacEqWriteConsent(MemoryConsent()), elapsedMs = { 0L }, wallClockMs = { 0L }, pause = {},
        )
        advanceUntilIdle()
        assertThat(service.availability()).isEqualTo(DacEqWriteAvailability.READ_ONLY_MODEL)
        assertThat(service.giveConsent(att)).isFalse()
    }

    // ── the same correction twice ────────────────────────────────────────────────────────────────

    private fun TestScope.curved(r: Rig) {
        r.dac.setSlot(0xA2, 0, (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], 2.0, 1.0) })
        r.dac.selectOnDevice(0xA2)
    }

    @Test
    fun `when the DAC shapes the sound and the headphones' software EQ is on too, the screen says so`() = runTest {
        val r = rig(softwareEqOn = true) { dac ->
            dac.setSlot(0xA2, 0, (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], 2.0, 1.0) })
            dac.selectOnDevice(0xA2)
        }
        advanceUntilIdle()
        r.screen.grantConsent()
        advanceUntilIdle()
        val ui = r.screen.uiState.value
        assertThat(ui.verdict).isEqualTo(DacEqVerdict.ON)
        assertThat(ui.softwareEqAlsoOn).isTrue()
        r.screen.turnOffSoftwareEq()
        advanceUntilIdle()
        assertThat(r.softwareOff).containsExactly("hp-tygr")
    }

    @Test
    fun `with the software EQ off, or the DAC's EQ off, nothing is said`() = runTest {
        val softwareOff = rig(softwareEqOn = false) { dac ->
            dac.setSlot(0xA2, 0, (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], 2.0, 1.0) })
            dac.selectOnDevice(0xA2)
        }
        advanceUntilIdle()
        softwareOff.screen.grantConsent()
        advanceUntilIdle()
        assertThat(softwareOff.screen.uiState.value.softwareEqAlsoOn).isFalse()

        val dacOff = rig(softwareEqOn = true)
        advanceUntilIdle()
        dacOff.screen.grantConsent()
        advanceUntilIdle()
        assertThat(dacOff.screen.uiState.value.softwareEqAlsoOn).isFalse()
    }
}
