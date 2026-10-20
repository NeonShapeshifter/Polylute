package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.dac.fiio.AttachedFiio
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBackup
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBackupStore
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqController
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqHidAccess
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabel
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqService
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqSlotSnapshot
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteSwitch
import com.lostf1sh.pixelplayeross.data.dac.fiio.FakeFiioDac
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioEqReader
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidOpener
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidProfiles
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidSession
import com.lostf1sh.pixelplayeross.data.dac.fiio.HidUse
import com.lostf1sh.pixelplayeross.data.dac.fiio.signedMelody
import com.lostf1sh.pixelplayeross.data.headphones.DacEqFilterDef
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPairDac
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPreset
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPresetCatalog
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B30-fix1 H2 (auditor R30-02): **an edit belongs to the attachment and the conditions it was made under.**
 *
 * The first version kept only the bands of an edit. When the debounce ended it took whichever DAC was attached **at that moment**
 * and wrote the edit to it as if the listener had just asked, and it did not look at Purity again: an edit made on DAC A, with A
 * unplugged and B plugged in before the 700 ms were over, was written to B (a backup made for B, USER 3 selected, six SETs); Purity turned on
 * during the debounce did not stop it either. Here two DACs and a screen are real, the DACs are fakes, and each scenario ends on what
 * reached **either** DAC.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DacEqEditOwnershipTest {
    private val melody = signedMelody
    private val slot = 0xA2
    private val dirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun TestScope.appScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }

    /** Opens the fake behind each connection path, and never any other: an absent path is an absent DAC. */
    private class FakeOpener : FiioHidOpener {
        val devices = mutableMapOf<String, FakeFiioDac>()
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> {
            val dac = devices[attached.connectionPath] ?: return HidUse.Unavailable("the DAC is not attached any more")
            return HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
        }
    }

    private class MemoryFlag(var value: Boolean) : DebugFlagStore {
        override fun read() = value
        override fun write(value: Boolean) { this.value = value }
    }

    private class MemoryStore(initial: HeadphoneProfileState) : DacEqPresetStore {
        private val flow = MutableStateFlow(initial)
        override val state: StateFlow<HeadphoneProfileState> = flow
        override suspend fun saveDacEqPreset(headphoneId: String, dac: DacEqPairDac, name: String, preGainTenthsDb: Int,
            accepted: List<DacEqFilterDef>, requested: List<DacEqFilterDef>?, presetId: String?): DacEqPreset? {
            flow.value = DacEqPresetCatalog.save(flow.value, headphoneId, dac, name, preGainTenthsDb, accepted, requested, presetId, 1L)
            return flow.value.profiles.firstOrNull { it.profileId == headphoneId }?.let { DacEqPresetCatalog.selectionFor(it, dac.profileId) }
        }
        override suspend fun selectDacEq(headphoneId: String, dacProfileId: String, presetId: String?, eqOn: Boolean) {
            flow.value = DacEqPresetCatalog.select(flow.value, headphoneId, dacProfileId, presetId, eqOn, 1L)
        }
        override suspend fun renameDacEqPreset(headphoneId: String, dacProfileId: String, presetId: String, name: String) {
            flow.value = DacEqPresetCatalog.rename(flow.value, headphoneId, dacProfileId, presetId, name, 1L)
        }
        override suspend fun deleteDacEqPreset(headphoneId: String, dacProfileId: String, presetId: String) {
            flow.value = DacEqPresetCatalog.delete(flow.value, headphoneId, dacProfileId, presetId, 1L)
        }
    }

    private fun directRoute() = AudioRouteState(
        actualBackend = AudioBackend.USB_DIRECT, desiredBackend = AudioBackend.USB_DIRECT,
        transition = AudioRouteTransition.DIRECT_ACTIVE, reason = AudioRouteReason.DIRECT_PRISTINE, usbDacPresent = true,
        signalMode = DirectSignalMode.PRISTINE,
    )

    private class Rig(
        val dacA: FakeFiioDac,
        val dacB: FakeFiioDac,
        val attA: AttachedFiio,
        val attB: AttachedFiio,
        val opener: FakeOpener,
        val attached: MutableStateFlow<AttachedFiio?>,
        val audio: MutableStateFlow<AudioStatus>,
        val flag: MemoryFlag,
        val store: DacEqBackupStore,
        val presets: MemoryStore,
        val service: DacEqService,
        val screen: DacEqScreenController,
    ) {
        val setsA get() = dacA.sets
        val setsB get() = dacB.sets
    }

    private fun seed(store: DacEqBackupStore, att: AttachedFiio, dac: FakeFiioDac) {
        val backup = DacEqBackup.of(att, FiioEqReader.read(FiioHidSession(dac, melody)), 1L)!!
        store.save(backup)
        val held = dac.slot(slot)
        store.update(backup.withSlot(DacEqSlotSnapshot(slot, held.preGainTenths, held.bands.toList())))
    }

    /**
     * @param immediateAttach the controller follows `attached` on an unconfined scope, so a DAC swapped **during** a write is seen by the
     *   controller at that instant (as it is on a device, where the USB broadcast arrives while the HID thread is busy), not at the next
     *   turn of the test scheduler. Direct is then not up, so no attach read runs on that scope.
     */
    private fun TestScope.rig(seedB: Boolean = false, immediateAttach: Boolean = false): Rig {
        val dacA = FakeFiioDac(melody)
        val dacB = FakeFiioDac(melody)
        val attA = AttachedFiio("path-a", "id-a", "dac-a", "Melody A", "path-a", melody)
        val attB = AttachedFiio("path-b", "id-b", "dac-b", "Melody B", "path-b", melody)
        val opener = FakeOpener().also { it.devices["path-a"] = dacA; it.devices["path-b"] = dacB }
        val attached = MutableStateFlow<AttachedFiio?>(attA)
        val direct = MutableStateFlow(!immediateAttach)
        val scope = appScope()
        val controller = DacEqController(attached, direct, DacEqHidAccess(opener, Dispatchers.Unconfined), { 0L }, settleMs = 10L, attachedNow = { attached.value })
        controller.start(if (immediateAttach) CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it } else scope)
        val store = DacEqBackupStore(Files.createTempDirectory("dac-eq-ownership").toFile().also { dirs += it })
        seed(store, attA, dacA)
        if (seedB) seed(store, attB, dacB)
        val flag = MemoryFlag(true)
        val service = DacEqService(
            controller, store, DacEqWriteSwitch(flag, debugBuild = true),
            elapsedMs = { 0L }, wallClockMs = { 1_700_000_000_000L }, pause = {},
        )
        val audio = MutableStateFlow(AudioStatus(route = directRoute(), sinkDirectActive = true))
        val profiles = HeadphoneProfileCatalog.createAndSelect(HeadphoneProfileState(), "TYGR", HeadphoneEqState(), 1L, "hp-tygr")
        val presets = MemoryStore(profiles)
        val screen = DacEqScreenController(scope, "hp-tygr", service, presets, audio, debounceMs = 700L)
        return Rig(dacA, dacB, attA, attB, opener, attached, audio, flag, store, presets, service, screen)
    }

    private fun purity() = AudioStatus(purityPreference = true, route = directRoute(), sinkDirectActive = true)

    private fun TestScope.opened(seedB: Boolean = false, immediateAttach: Boolean = false): Rig = rig(seedB, immediateAttach).also {
        advanceUntilIdle()
        assertThat(it.screen.mode.value).isEqualTo(ResonanceMode.DAC)
        assertThat(it.setsA).isEmpty()
    }

    // ── The edit goes where it was made, or nowhere ──────────────────────────────────────────────────────

    @Test
    fun `an edit made on one DAC while another is plugged in before the debounce ends writes nothing to either, and makes no backup for the new one`() = runTest {
        val r = opened()
        r.screen.editBand(4) { it.copy(gainTenthsDb = 20) }
        runCurrent()
        assertThat(r.screen.uiState.value.pendingWrite).isTrue()

        r.attached.value = r.attB            // A is unplugged and B plugged in, inside the 700 ms
        advanceTimeBy(800)
        advanceUntilIdle()

        assertThat(r.setsA).isEmpty()
        assertThat(r.setsB).isEmpty()
        assertThat(r.store.load("dac-b")).isNull()
        assertThat(r.screen.writesStarted).isEqualTo(0)
        val ui = r.screen.uiState.value
        assertThat(ui.error).isEqualTo(DacEqError.Dropped(DacEqDropReason.DAC_CHANGED))
        assertThat(ui.pendingWrite).isFalse()
        assertThat(ui.busy).isFalse()
    }

    @Test
    fun `an unplug and a plug of the same DAC inside the debounce is another attachment, and the edit is dropped`() = runTest {
        val r = opened()
        r.screen.editBand(4) { it.copy(gainTenthsDb = 20) }
        runCurrent()

        r.attached.value = null
        runCurrent()
        r.attached.value = r.attA            // the very same DAC, the very same path: a new attachment all the same
        advanceTimeBy(800)
        advanceUntilIdle()

        assertThat(r.setsA).isEmpty()
        assertThat(r.setsB).isEmpty()
        assertThat(r.screen.writesStarted).isEqualTo(0)
        assertThat(r.screen.uiState.value.error).isEqualTo(DacEqError.Dropped(DacEqDropReason.DAC_CHANGED))
    }

    @Test
    fun `Purity turned on inside the debounce stops the edit, nothing is sent and the screen says why`() = runTest {
        val r = opened()
        r.screen.editBand(4) { it.copy(gainTenthsDb = 20) }
        runCurrent()
        assertThat(r.screen.uiState.value.frozenByPurity).isFalse()

        r.audio.value = purity()
        advanceTimeBy(800)
        advanceUntilIdle()

        assertThat(r.setsA).isEmpty()
        assertThat(r.screen.writesStarted).isEqualTo(0)
        val ui = r.screen.uiState.value
        assertThat(ui.frozenByPurity).isTrue()
        assertThat(ui.error).isEqualTo(DacEqError.Dropped(DacEqDropReason.PURITY_ON))
        assertThat(ui.pendingWrite).isFalse()
        // And what Purity says about the DAC is still what the DAC read: closed.
        assertThat(r.service.controller.label.value).isEqualTo(DacEqLabel.OFF_OR_FLAT)
    }

    @Test
    fun `writing switched off inside the debounce stops the edit`() = runTest {
        val r = opened()
        r.screen.editBand(4) { it.copy(gainTenthsDb = 20) }
        runCurrent()
        r.service.writeSwitch.set(false)
        advanceTimeBy(800)
        advanceUntilIdle()
        assertThat(r.setsA).isEmpty()
        assertThat(r.screen.uiState.value.error).isEqualTo(DacEqError.Dropped(DacEqDropReason.WRITES_OFF))
    }

    @Test
    fun `a DAC plugged in after an edit began is edited from its own curve, not the old DAC's pending one`() = runTest {
        val r = opened(seedB = true)
        r.screen.editBand(4) { it.copy(gainTenthsDb = 20) }   // on A, still waiting
        runCurrent()
        r.attached.value = r.attB
        advanceUntilIdle()
        r.screen.editBand(2) { it.copy(gainTenthsDb = 10) }   // on B
        advanceTimeBy(800)
        advanceUntilIdle()

        assertThat(r.setsA).isEmpty()
        val held = r.dacB.slot(slot).bands
        assertThat(held[2].gainTenthsDb).isEqualTo(10)
        // A's band 5 was not carried over to B.
        assertThat(held[4].gainTenthsDb).isEqualTo(0)
        assertThat(r.screen.writesStarted).isEqualTo(1)
        assertThat(r.screen.uiState.value.error).isNull()
    }

    // ── R30-F1-02 (B31): the base and the curve on screen come from the current attachment only ─────────

    @Test
    fun `a new edit on B made inside A's debounce starts from B's own curve and does not carry the band pending from A`() = runTest {
        val r = opened(seedB = true)
        r.screen.editBand(4) { it.copy(gainTenthsDb = 20) }   // on A, still waiting: its 700 ms have not run
        runCurrent()
        assertThat(r.screen.uiState.value.pendingWrite).isTrue()

        r.attached.value = r.attB
        runCurrent()                                           // B is attached; A's debounce has NOT expired
        // What is on screen is B's, not A's pending curve.
        val shown = r.screen.uiState.value
        assertThat(shown.pendingWrite).isFalse()
        assertThat(shown.bands[4].gainTenthsDb).isEqualTo(0)

        r.screen.editBand(2) { it.copy(gainTenthsDb = 10) }   // on B, before A's debounce is over
        advanceTimeBy(800)
        advanceUntilIdle()

        assertThat(r.setsA).isEmpty()
        val held = r.dacB.slot(slot).bands
        assertThat(held[2].gainTenthsDb).isEqualTo(10)
        // The auditor's probe left A's band 5 on B at +2.0 dB. It is not there.
        assertThat(held[4].gainTenthsDb).isEqualTo(0)
        assertThat(held.count { it.gainTenthsDb != 0 }).isEqualTo(1)
        assertThat(r.screen.writesStarted).isEqualTo(1)
    }

    @Test
    fun `the curve an off was taken from belongs to its DAC and is not shown for the next one`() = runTest {
        val r = opened(seedB = true)
        r.screen.editBand(4) { it.copy(gainTenthsDb = 20) }
        advanceTimeBy(800)
        advanceUntilIdle()
        assertThat(r.dacA.slot(slot).bands[4].gainTenthsDb).isEqualTo(20)
        // Turn the EQ off: the screen keeps A's curve to turn it on from.
        r.screen.setEnabled(false)
        advanceUntilIdle()
        assertThat(r.screen.uiState.value.eqOn).isFalse()
        assertThat(r.screen.uiState.value.bands[4].gainTenthsDb).isEqualTo(20)

        r.attached.value = r.attB
        advanceUntilIdle()
        // B is another DAC: it shows B's own (flat) curve, not the one A's "off" was taken from.
        assertThat(r.screen.uiState.value.bands[4].gainTenthsDb).isEqualTo(0)
    }

    @Test
    fun `an edit that is still current is written once, to its own DAC, as before`() = runTest {
        val r = opened()
        r.screen.editBand(4) { it.copy(gainTenthsDb = 20) }
        advanceTimeBy(800)
        advanceUntilIdle()
        assertThat(r.screen.writesStarted).isEqualTo(1)
        assertThat(r.dacA.slot(slot).bands[4].gainTenthsDb).isEqualTo(20)
        assertThat(r.setsB).isEmpty()
        assertThat(r.screen.uiState.value.error).isNull()
    }

    // ── What a write that did start comes to is recorded against its own DAC ────────────────────────────

    @Test
    fun `a write that began on one DAC and finished with another plugged in is recorded for the first, and does not touch the second's label`() = runTest {
        val r = opened(seedB = true, immediateAttach = true)
        r.screen.editBand(4) { it.copy(gainTenthsDb = 20) }
        // The first SET goes out on A; at that moment the listener swaps the DAC.
        var swapped = false
        r.dacA.onWrite = { report ->
            if (!swapped && (report[1].toInt() and 0xFF) == 0xAA) { swapped = true; r.attached.value = r.attB }
        }
        advanceTimeBy(800)
        advanceUntilIdle()

        assertThat(swapped).isTrue()
        // The write was started and is finished: A holds the curve, B was never written.
        assertThat(r.dacA.slot(slot).bands[4].gainTenthsDb).isEqualTo(20)
        assertThat(r.setsB).isEmpty()
        // The pair that was edited is A's: the selection (EQ on) is recorded for it, and B's pair has nothing.
        val selections = r.presets.state.value.profiles.single { it.profileId == "hp-tygr" }.dacEqSelections
        assertThat(selections.keys).containsExactly("dac-a")
        assertThat(selections.getValue("dac-a").eqOn).isTrue()
        // A's read-back is not the label of B, which is attached now.
        assertThat(r.service.controller.readout.value.attachedOrNull).isEqualTo(r.attB)
        assertThat(r.service.controller.label.value).isNotEqualTo(DacEqLabel.ON)
    }

    // ── The screen and the label source say the same thing after a failure ──────────────────────────────

    @Test
    fun `after a write that failed on a DAC that stopped answering the screen and the label source agree, and neither says off`() = runTest {
        val r = opened()
        var seen = 0
        // The second SET lands and then the DAC says nothing to any read: no read-back, no restore.
        r.dacA.afterSet = { d, _ -> if (++seen == 2) d.dropReplies = true }
        r.screen.editBand(4) { it.copy(gainTenthsDb = 20) }
        advanceTimeBy(800)
        advanceUntilIdle()

        val ui = r.screen.uiState.value
        val label = r.service.controller.label.value
        assertThat(ui.label).isEqualTo(label)
        assertThat(label).isNotEqualTo(DacEqLabel.OFF_OR_FLAT)
        assertThat(ui.error).isNotNull()
        assertThat(ui.busy).isFalse()
    }
}
