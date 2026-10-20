package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.dac.fiio.AttachedFiio
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBackupStore
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqController
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqHidAccess
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqService
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteSwitch
import com.lostf1sh.pixelplayeross.data.dac.fiio.FakeFiioDac
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidOpener
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidProfiles
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidSession
import com.lostf1sh.pixelplayeross.data.dac.fiio.GateRefusal
import com.lostf1sh.pixelplayeross.data.dac.fiio.HidUse
import com.lostf1sh.pixelplayeross.data.dac.fiio.RecordedMelody
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
import com.decent.usbaudio.media3.DirectSignalMode
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
 * B31 P1.3 / P1.4: the Resonance screen **offers** to take USER 3 over (never a popup on connect, never by itself), lists the DAC's own
 * presets by name from the inventory, and selects one on a tap (a pointer move; asked first while music plays). The real controller
 * runs over a fake Melody that holds what the owner's held ([RecordedMelody]) and the real, unconfirmed row of the profile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DacEqScreenTakeOverTest {
    private val real = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val dirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        dirs.forEach { it.deleteRecursively() }
    }

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> =
            HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
    }

    private class MemoryFlag(var value: Boolean) : DebugFlagStore {
        override fun read() = value
        override fun write(value: Boolean) { this.value = value }
    }

    private class NoPresets(initial: HeadphoneProfileState) : DacEqPresetStore {
        override val state: StateFlow<HeadphoneProfileState> = MutableStateFlow(initial)
        override suspend fun saveDacEqPreset(headphoneId: String, dac: DacEqPairDac, name: String, preGainTenthsDb: Int,
            accepted: List<DacEqFilterDef>, requested: List<DacEqFilterDef>?, presetId: String?): DacEqPreset? = null
        override suspend fun selectDacEq(headphoneId: String, dacProfileId: String, presetId: String?, eqOn: Boolean) = Unit
        override suspend fun renameDacEqPreset(headphoneId: String, dacProfileId: String, presetId: String, name: String) = Unit
        override suspend fun deleteDacEqPreset(headphoneId: String, dacProfileId: String, presetId: String) = Unit
    }

    private fun directRoute() = AudioRouteState(
        actualBackend = AudioBackend.USB_DIRECT, desiredBackend = AudioBackend.USB_DIRECT,
        transition = AudioRouteTransition.DIRECT_ACTIVE, reason = AudioRouteReason.DIRECT_PRISTINE, usbDacPresent = true,
        signalMode = DirectSignalMode.PRISTINE,
    )

    private class Rig(val dac: FakeFiioDac, val screen: DacEqScreenController, val service: DacEqService, val att: AttachedFiio) {
        var playing = false
        val ui get() = screen.uiState.value
        val sets get() = dac.sets
    }

    private fun TestScope.opened(): Rig {
        val dac = RecordedMelody.fakeDac(0xF0, real)
        val att = AttachedFiio("path-a", "id-a", "dac-a", "Melody A", "path-a", real)
        val opener = FakeOpener(dac)
        val attached = MutableStateFlow<AttachedFiio?>(att)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }
        val controller = DacEqController(attached, MutableStateFlow(true), DacEqHidAccess(opener, Dispatchers.Unconfined), { 0L }, settleMs = 10L, attachedNow = { attached.value })
        controller.start(scope)
        val dir = Files.createTempDirectory("dac-eq-screen-takeover").toFile().also { dirs += it }
        lateinit var rig: Rig
        val service = DacEqService(
            controller, DacEqBackupStore(dir), DacEqWriteSwitch(MemoryFlag(true), debugBuild = true),
            elapsedMs = { 0L }, wallClockMs = { 1_700_000_000_000L }, pause = {},
            playbackActive = { rig.playing }, ioDispatcher = Dispatchers.Unconfined,
        )
        val profiles = HeadphoneProfileCatalog.createAndSelect(HeadphoneProfileState(), "TYGR", HeadphoneEqState(), 1L, "hp-tygr")
        val screen = DacEqScreenController(scope, "hp-tygr", service, NoPresets(profiles), MutableStateFlow(com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus(route = directRoute(), sinkDirectActive = true)), debounceMs = 700L)
        rig = Rig(dac, screen, service, att)
        advanceUntilIdle()
        return rig
    }

    @Test
    fun `the take-over is on offer when the screen opens, and nothing was sent`() = runTest {
        val r = opened()
        assertThat(r.screen.mode.value).isEqualTo(ResonanceMode.DAC)
        assertThat(r.ui.takeOverOffered).isTrue()
        assertThat(r.ui.slotOwned).isFalse()
        assertThat(r.sets).isEmpty()
        assertThat(r.ui.appSlotName).isEqualTo("USER 3")
        // The DAC's presets are listed by name; none has been read, and the screen says so.
        assertThat(r.ui.dacPresets.map { it.name }).containsAtLeast("Jazz", "USER 1", "USER 3", "Close EQ")
        assertThat(r.ui.dacPresets.filter { !it.isClosed }.all { !it.readFromDac }).isTrue()
        assertThat(r.ui.presetsRead).isFalse()
    }

    @Test
    fun `a tap while music plays sends nothing and says why`() = runTest {
        val r = opened()
        r.playing = true
        r.screen.takeOver()
        advanceUntilIdle()
        assertThat(r.sets).isEmpty()
        assertThat(r.ui.error).isEqualTo(DacEqError.Refused(GateRefusal.PLAYBACK_NOT_PAUSED))
        assertThat(r.ui.takeOverOffered).isTrue()
        assertThat(r.ui.operation).isNull()
    }

    @Test
    fun `paused, the take-over runs, the slot becomes the app's and the card goes`() = runTest {
        val r = opened()
        r.screen.takeOver()
        advanceUntilIdle()
        assertThat(r.ui.error).isNull()
        assertThat(r.ui.operation).isNull()
        assertThat(r.ui.slotOwned).isTrue()
        assertThat(r.ui.takeOverOffered).isFalse()
        assertThat(r.dac.slot(0xA2).preGainTenths).isEqualTo(0)
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        // The slot is the app's now, and no longer a locked preset in the list.
        assertThat(r.ui.dacPresets.single { it.id == 0xA2 }.locked).isFalse()
    }

    @Test
    fun `reading the DAC's presets lists each by name with what it holds, locks the ones that could play too loud, and selects a safe one on a tap`() = runTest {
        val r = opened()
        r.screen.readDacPresets()
        advanceUntilIdle()
        assertThat(r.ui.error).isNull()
        assertThat(r.ui.presetsRead).isTrue()
        assertThat(r.sets.map { it.first }.toSet()).containsExactly(0x16) // pointer moves only
        val rows = r.ui.dacPresets.associateBy { it.id }
        // USER 3 holds the factory curve (+8.0 dB register under ten bands): while the sense is unknown it could play 10.7 dB too loud.
        assertThat(rows.getValue(0xA2).locked).isTrue()
        assertThat(rows.getValue(0xA2).preGainTenthsDb).isEqualTo(80)
        assertThat(rows.getValue(0xA2).selectable).isFalse()
        // USER 1 is flat and free; Close EQ is the DAC's own bypass.
        assertThat(rows.getValue(0xA0).selectable).isTrue()
        assertThat(rows.getValue(0xF0).isActive).isTrue()
        r.dac.written.clear()
        r.screen.selectDacPreset(0xA2) // locked: ignored, nothing sent
        advanceUntilIdle()
        assertThat(r.sets).isEmpty()
        r.screen.selectDacPreset(0xA0)
        advanceUntilIdle()
        assertThat(r.dac.activePreset).isEqualTo(0xA0)
        assertThat(r.sets.map { it.first }.toSet()).containsExactly(0x16)
        assertThat(r.ui.dacPresets.single { it.id == 0xA0 }.isActive).isTrue()
    }

    @Test
    fun `while music plays a tap on a preset asks first, and sends the select only after the yes`() = runTest {
        val r = opened()
        r.screen.readDacPresets()
        advanceUntilIdle()
        r.dac.written.clear()
        r.playing = true
        r.screen.selectDacPreset(0xA0)
        advanceUntilIdle()
        assertThat(r.sets).isEmpty()
        assertThat(r.ui.confirmSelect?.id).isEqualTo(0xA0)
        r.screen.dismissConfirmSelect()
        advanceUntilIdle()
        assertThat(r.ui.confirmSelect).isNull()
        assertThat(r.sets).isEmpty()
        r.screen.selectDacPreset(0xA0)
        advanceUntilIdle()
        r.screen.selectDacPreset(0xA0, confirmed = true)
        advanceUntilIdle()
        assertThat(r.dac.activePreset).isEqualTo(0xA0)
        assertThat(r.ui.error).isNull()
    }

    @Test
    fun `reading the presets is refused while music plays, with nothing sent`() = runTest {
        val r = opened()
        r.playing = true
        r.screen.readDacPresets()
        advanceUntilIdle()
        assertThat(r.sets).isEmpty()
        assertThat(r.ui.error).isEqualTo(DacEqError.Refused(GateRefusal.PLAYBACK_NOT_PAUSED))
    }
}
