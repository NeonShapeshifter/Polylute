package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.dac.fiio.AttachedFiio
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBackup
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBackupStore
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBand
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqController
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqHidAccess
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqReadout
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqService
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqSlotSnapshot
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqVerdict
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteSwitch
import com.lostf1sh.pixelplayeross.data.dac.fiio.FakeFiioDac
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioEqReader
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioFilterType
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidOpener
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidProfile
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidProfiles
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioHidSession
import com.lostf1sh.pixelplayeross.data.dac.fiio.GateRefusal
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B30-P3: the Resonance screen with the DAC's own EQ, against a fake Melody. Opening it writes nothing; a drag is written once, at
 * its final value; a preset is applied only by a tap; the Purity lock freezes it; a read-back that differs is shown as an error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DacEqScreenControllerTest {
    private val melody = signedMelody
    private val dirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        dirs.forEach { it.deleteRecursively() }
    }

    /**
     * A scope on the test's scheduler that is **not** `backgroundScope`: `advanceUntilIdle` leaves a background scope's tasks alone,
     * and these collectors are what the tests are about. Cancelled after each test.
     */
    private fun TestScope.appScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        var opens = 0
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> {
            opens++
            return HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
        }
    }

    private class MemoryFlag(var value: Boolean) : DebugFlagStore {
        override fun read() = value
        override fun write(value: Boolean) { this.value = value }
    }

    /** The headphone profiles' preset store, in memory, through the same catalog the real repository uses. */
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

    private class Rig(
        val dac: FakeFiioDac,
        val service: DacEqService,
        val opener: FakeOpener,
        val attached: MutableStateFlow<AttachedFiio?>,
        val direct: MutableStateFlow<Boolean>,
        val audio: MutableStateFlow<AudioStatus>,
        val store: MemoryStore,
        val screen: DacEqScreenController,
        val profile: FiioHidProfile,
    )

    private fun directRoute(reason: AudioRouteReason = AudioRouteReason.DIRECT_PRISTINE) = AudioRouteState(
        actualBackend = AudioBackend.USB_DIRECT, desiredBackend = AudioBackend.USB_DIRECT,
        transition = AudioRouteTransition.DIRECT_ACTIVE, reason = reason, usbDacPresent = true, signalMode = DirectSignalMode.PRISTINE,
    )

    private fun androidRoute(reason: AudioRouteReason) = AudioRouteState(
        actualBackend = AudioBackend.ANDROID_AUDIO, transition = AudioRouteTransition.ANDROID_ACTIVE, reason = reason, usbDacPresent = true,
    )

    private fun TestScope.rig(
        activePreset: Int = 0xF0,
        override: Boolean = true,
        attach: Boolean = true,
        profileState: HeadphoneProfileState = profiles(),
        waitMs: Long = 4_000L,
        configure: (FakeFiioDac) -> Unit = {},
        dacProfile: FiioHidProfile = melody,
        inspected: Boolean = true,
    ): Rig {
        val dac = FakeFiioDac(melody, activePreset = activePreset).also(configure)
        val att = AttachedFiio("/dev/bus/usb/001/005", "dac-id", "dac-melody", "SNOWSKY Melody", "/dev/bus/usb/001/005", dacProfile)
        val attached = MutableStateFlow<AttachedFiio?>(if (attach) att else null)
        val direct = MutableStateFlow(true)
        val opener = FakeOpener(dac)
        val access = DacEqHidAccess(opener, Dispatchers.Unconfined)
        val scope = appScope()
        val controller = DacEqController(attached, direct, access, clock = { 0L }, settleMs = 10L, attachedNow = { attached.value })
        controller.start(scope)
        val dir = Files.createTempDirectory("dac-eq-screen").toFile().also { dirs += it }
        val backups = DacEqBackupStore(dir)
        if (inspected && dacProfile.canBeWritten) {
            // B30-fix1 H1: a write needs the app's slot to be in the backup (the reviewer's look at it came first).
            val state = FiioEqReader.read(FiioHidSession(dac, dacProfile))
            val slotId = dacProfile.appSlotId!!
            val held = dac.slot(slotId)
            // (a DAC that gives no answers has no backup to seed)
            DacEqBackup.of(att, state, 1_700_000_000_000L)?.let { backup ->
                backups.save(backup)
                backups.update(backup.withSlot(DacEqSlotSnapshot(slotId, held.preGainTenths, held.bands.toList())))
            }
        }
        val service = DacEqService(
            controller, backups, DacEqWriteSwitch(MemoryFlag(override), debugBuild = true),
            elapsedMs = { 0L }, wallClockMs = { 1_700_000_000_000L }, pause = {},
        )
        val audio = MutableStateFlow(AudioStatus(route = directRoute(), sinkDirectActive = true))
        val store = MemoryStore(profileState)
        val screen = DacEqScreenController(scope, "hp-tygr", service, store, audio, debounceMs = 700L, waitMs = waitMs)
        return Rig(dac, service, opener, attached, direct, audio, store, screen, dacProfile)
    }

    private fun profiles(): HeadphoneProfileState {
        var s = HeadphoneProfileCatalog.createAndSelect(HeadphoneProfileState(), "TYGR", HeadphoneEqState(), 1L, "hp-tygr")
        s = HeadphoneProfileCatalog.createAndSelect(s, "Studio", HeadphoneEqState(), 2L, "hp-studio")
        return s
    }

    private fun bands(vararg gains: Pair<Int, Double>): List<DacEqBand> {
        val map = gains.toMap()
        return (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], map[it] ?: 0.0, 0.71) }
    }

    // ── Opening the screen ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a DAC whose EQ was read and that may be written gets the DAC screen, and opening it writes nothing`() = runTest {
        val r = rig()
        advanceUntilIdle()
        assertThat(r.screen.mode.value).isEqualTo(ResonanceMode.DAC)
        assertThat(r.dac.sets).isEmpty()
        assertThat(r.screen.writesStarted).isEqualTo(0)
        val ui = r.screen.uiState.value
        assertThat(ui.verdict).isEqualTo(DacEqVerdict.OFF)
        assertThat(ui.dacName).isEqualTo("SNOWSKY Melody")
        assertThat(ui.bands).hasSize(10)
        assertThat(ui.eqOn).isFalse()
        assertThat(ui.canEdit).isTrue()
    }

    @Test
    fun `without the override an unverified DAC gets the software EQ as today`() = runTest {
        val r = rig(override = false)
        advanceUntilIdle()
        assertThat(r.screen.mode.value).isEqualTo(ResonanceMode.SOFTWARE)
        assertThat(r.dac.sets).isEmpty()
    }

    @Test
    fun `no DAC, or one the app cannot write, is the software screen`() = runTest {
        val none = rig(attach = false)
        advanceUntilIdle()
        assertThat(none.screen.mode.value).isEqualTo(ResonanceMode.SOFTWARE)

        val k13 = FiioHidProfiles.all.single { it.id == "fiio-k13-r2r" }
        val unwritable = rig(dacProfile = k13)
        advanceUntilIdle()
        assertThat(unwritable.screen.mode.value).isEqualTo(ResonanceMode.SOFTWARE)
    }

    @Test
    fun `while the first read has not come the screen waits, then falls back to the software EQ`() = runTest {
        val r = rig(configure = { it.dropReplies = true }, waitMs = 1_000L)
        // The read is under way but gets no answers; the first state is Pending until it fails.
        runCurrent()
        advanceTimeBy(1_500)
        advanceUntilIdle()
        assertThat(r.screen.mode.value).isEqualTo(ResonanceMode.SOFTWARE)
    }

    @Test
    fun `the decision is kept once made, even if the DAC is unplugged`() = runTest {
        val r = rig()
        advanceUntilIdle()
        assertThat(r.screen.mode.value).isEqualTo(ResonanceMode.DAC)
        r.attached.value = null
        advanceUntilIdle()
        assertThat(r.screen.mode.value).isEqualTo(ResonanceMode.DAC)
    }

    @Test
    fun `opening the screen reads the DAC at once when its EQ is still unread`() = runTest {
        val dac = FakeFiioDac(melody)
        val att = AttachedFiio("p", "id", "dac-melody", "SNOWSKY Melody", "p", melody)
        val attached = MutableStateFlow<AttachedFiio?>(att)
        val direct = MutableStateFlow(false) // Direct is not up: the attach read is not due
        val opener = FakeOpener(dac)
        val scope = appScope()
        val controller = DacEqController(attached, direct, DacEqHidAccess(opener, Dispatchers.Unconfined), { 0L }, settleMs = 10_000L,
            attachedNow = { attached.value })
        controller.start(scope)
        val dir = Files.createTempDirectory("dac-eq-screen").toFile().also { dirs += it }
        val service = DacEqService(controller, DacEqBackupStore(dir), DacEqWriteSwitch(MemoryFlag(true), true), { 0L }, { 1L }, {})
        runCurrent()
        assertThat(opener.opens).isEqualTo(0)
        val screen = DacEqScreenController(scope, "hp-tygr", service, MemoryStore(profiles()), MutableStateFlow(AudioStatus()))
        advanceUntilIdle()
        assertThat(opener.opens).isEqualTo(1)
        assertThat(screen.mode.value).isEqualTo(ResonanceMode.DAC)
        assertThat(dac.sets).isEmpty()
    }

    @Test
    fun `the DAC screen never creates the software EQ's view model or opens its editor`() {
        val base = "src/main/java/com/lostf1sh/pixelplayeross/presentation"
        for (file in listOf("viewmodel/DacEqScreenController.kt", "viewmodel/DacEqViewModel.kt", "screens/DacEqualizerScreen.kt")) {
            // Code only: the comments say why, by naming the very thing the code must not touch.
            val text = File("$base/$file").readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            assertThat(text).doesNotContain("EqualizerViewModel")
            assertThat(text).doesNotContain("setEditorOpen")
            assertThat(text).doesNotContain("HeadphoneGraphicEqController")
        }
        // The route host decides before the software view model can exist.
        val host = File("$base/screens/EqualizerRoute.kt").readText()
        assertThat(host.indexOf("DacEqViewModel")).isLessThan(host.indexOf("EqualizerScreen("))
    }

    // ── A drag is not a stream of writes ─────────────────────────────────────────────────────────────────

    @Test
    fun `forty edits within a drag are written once, at the final value`() = runTest {
        val r = rig(activePreset = 0xF0)
        advanceUntilIdle()
        // A drag: forty moves ten milliseconds apart, climbing to +10 dB and settling at +2.5 dB.
        for (step in 1..40) {
            r.screen.editBand(3) { it.copy(gainTenthsDb = (step * 5).coerceAtMost(100)) }
            advanceTimeBy(10)
        }
        r.screen.editBand(3) { it.copy(gainTenthsDb = 25) }
        // Nothing has reached the DAC while the finger is moving, or a moment after it stopped.
        advanceTimeBy(699)
        runCurrent()
        assertThat(r.screen.writesStarted).isEqualTo(0)
        assertThat(r.dac.sets).isEmpty()
        advanceTimeBy(5)
        advanceUntilIdle()
        assertThat(r.screen.writesStarted).isEqualTo(1)
        assertThat(r.dac.slot(0xA2).bands[3].gainTenthsDb).isEqualTo(25)
        // Band 4 was written once, with the final value.
        assertThat(r.dac.sets.count { it.first == 0x15 && (it.second[0].toInt() and 0xFF) == 3 }).isEqualTo(1)
        assertThat(r.screen.uiState.value.error).isNull()
        assertThat(r.screen.uiState.value.eqOn).isTrue()
    }

    @Test
    fun `an edit made while a write is running is written after it, as one more`() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.screen.editBand(2) { it.copy(gainTenthsDb = 20) }
        advanceTimeBy(701)
        // The writer runs on the Unconfined dispatcher: it has already finished. A second drag after it is a second write.
        advanceUntilIdle()
        r.screen.editBand(2) { it.copy(gainTenthsDb = 30) }
        advanceTimeBy(701)
        advanceUntilIdle()
        assertThat(r.screen.writesStarted).isEqualTo(2)
        assertThat(r.dac.slot(0xA2).bands[2].gainTenthsDb).isEqualTo(30)
    }

    @Test
    fun `the pre-gain on screen is the one the gate writes, and the net peak is at most 0 dB`() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.screen.editBand(4) { it.copy(gainTenthsDb = 60) }
        runCurrent()
        val ui = r.screen.uiState.value
        assertThat(ui.preGainTenthsDb).isLessThan(0)
        assertThat(ui.netPeakDb).isAtMost(0.0)
        advanceTimeBy(800)
        advanceUntilIdle()
        assertThat(r.dac.slot(0xA2).preGainTenths).isEqualTo(ui.preGainTenthsDb)
    }

    // ── Off and on ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `on writes the curve on screen and selects the app's slot, off closes the DAC's EQ`() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.screen.setEnabled(true)
        advanceUntilIdle()
        assertThat(r.dac.activePreset).isEqualTo(0xA2)
        assertThat(r.screen.uiState.value.eqOn).isTrue()
        r.screen.setEnabled(false)
        advanceUntilIdle()
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.screen.uiState.value.verdict).isEqualTo(DacEqVerdict.OFF)
        assertThat(r.screen.uiState.value.error).isNull()
    }

    // ── Presets: a recognised pair, silently ─────────────────────────────────────────────────────────────

    private fun withPreset(): HeadphoneProfileState = DacEqPresetCatalog.save(
        profiles(), "hp-tygr", DacEqPairDac("dac-melody", "snowsky-melody", "SNOWSKY Melody"), "Warm", -31,
        bands(2 to 3.0).map { DacEqFilterDef.of(it) }, null, null, 5L,
    ) { "preset-1" }

    @Test
    fun `a recognised pair shows its saved selection and writes nothing until it is tapped`() = runTest {
        val r = rig(profileState = withPreset())
        advanceUntilIdle()
        val ui = r.screen.uiState.value
        assertThat(ui.selectedPresetId).isEqualTo("preset-1")
        assertThat(ui.selectedPresetState).isEqualTo(SelectedPresetState.DIFFERS_FROM_DAC)
        assertThat(ui.presets.map { it.name }).containsExactly("Warm")
        assertThat(r.dac.sets).isEmpty()

        r.screen.selectPreset("preset-1")
        advanceUntilIdle()
        assertThat(r.dac.slot(0xA2).bands[2].gainTenthsDb).isEqualTo(30)
        assertThat(r.screen.uiState.value.selectedPresetState).isEqualTo(SelectedPresetState.MATCHES_DAC)
    }

    @Test
    fun `with the EQ off the curve on screen is the pair's selected preset, and turning the EQ on writes that, not flat`() = runTest {
        val r = rig(profileState = withPreset())
        advanceUntilIdle()
        val ui = r.screen.uiState.value
        assertThat(ui.eqOn).isFalse()
        assertThat(ui.bands[2].gainTenthsDb).isEqualTo(30)
        assertThat(r.dac.sets).isEmpty() // showing it writes nothing
        r.screen.setEnabled(true)
        advanceUntilIdle()
        assertThat(r.dac.slot(0xA2).bands[2].gainTenthsDb).isEqualTo(30)
        // The curve written is the preset's own, so the pair still has it selected, and it now matches the DAC.
        assertThat(r.screen.uiState.value.selectedPresetId).isEqualTo("preset-1")
        assertThat(r.screen.uiState.value.selectedPresetState).isEqualTo(SelectedPresetState.MATCHES_DAC)
    }

    @Test
    fun `a band edited after that makes the curve custom, with no preset selected for the pair`() = runTest {
        val r = rig(profileState = withPreset())
        advanceUntilIdle()
        r.screen.setEnabled(true)
        advanceUntilIdle()
        r.screen.editBand(5) { it.copy(gainTenthsDb = 15) }
        advanceTimeBy(800)
        advanceUntilIdle()
        assertThat(r.screen.uiState.value.selectedPresetId).isNull()
        // The preset itself is still there to be selected again.
        assertThat(r.screen.uiState.value.presets.map { it.name }).containsExactly("Warm")
    }

    @Test
    fun `saving while an edit is still waiting to be written saves nothing, rather than the old curve`() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.screen.setEnabled(true)
        advanceUntilIdle()
        r.screen.editBand(1) { it.copy(gainTenthsDb = 25) }
        runCurrent()
        r.screen.savePreset("Early")
        runCurrent()
        assertThat(r.store.state.value.profiles.single { it.profileId == "hp-tygr" }.dacEqPresets).isEmpty()
        advanceTimeBy(800)
        advanceUntilIdle()
        r.screen.savePreset("Late")
        advanceUntilIdle()
        val saved = r.store.state.value.profiles.single { it.profileId == "hp-tygr" }.dacEqPresets.single()
        assertThat(saved.name).isEqualTo("Late")
        assertThat(saved.filters[1].gainTenthsDb).isEqualTo(25)
    }

    @Test
    fun `an unknown pair shows no selection, and other pairs' presets are offered for reuse by choice only`() = runTest {
        val other = DacEqPresetCatalog.save(
            profiles(), "hp-tygr", DacEqPairDac("dac-other", "fiio-ka15", "FiiO KA15"), "Cold", 0,
            bands(5 to -2.0).map { DacEqFilterDef.of(it) }, null, null, 5L,
        ) { "p-other" }
        val r = rig(profileState = other)
        advanceUntilIdle()
        val ui = r.screen.uiState.value
        assertThat(ui.selectedPresetId).isNull()
        assertThat(ui.selectedPresetState).isEqualTo(SelectedPresetState.NONE)
        assertThat(ui.presets).isEmpty()
        assertThat(ui.reusable.map { it.preset.presetId }).containsExactly("p-other")
        assertThat(r.dac.sets).isEmpty()
        // Reusing it is a tap, and writes the filters the Melody can take.
        r.screen.reusePreset("p-other")
        advanceUntilIdle()
        assertThat(r.dac.slot(0xA2).bands[5].gainTenthsDb).isEqualTo(-20)
        // And it did not become the pair's own selection.
        assertThat(r.screen.uiState.value.selectedPresetId).isNull()
    }

    @Test
    fun `saving keeps what the DAC accepted, for this pair`() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.screen.editBand(1) { it.copy(gainTenthsDb = 25) }
        advanceTimeBy(800)
        advanceUntilIdle()
        r.screen.savePreset("Warm")
        advanceUntilIdle()
        val saved = r.store.state.value.profiles.single { it.profileId == "hp-tygr" }.dacEqPresets.single()
        assertThat(saved.name).isEqualTo("Warm")
        assertThat(saved.dacProfileId).isEqualTo("dac-melody")
        assertThat(saved.filters[1].gainTenthsDb).isEqualTo(25)
        assertThat(saved.preGainTenthsDb).isEqualTo(r.dac.slot(0xA2).preGainTenths)
        // The other headphones have nothing.
        assertThat(r.store.state.value.profiles.single { it.profileId == "hp-studio" }.dacEqPresets).isEmpty()
    }

    // ── Purity freezes it ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `under the Purity lock the DAC's EQ is read-only`() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.audio.value = AudioStatus(purityPreference = true, route = directRoute(), sinkDirectActive = true)
        runCurrent()
        assertThat(r.screen.uiState.value.frozenByPurity).isTrue()
        assertThat(r.screen.uiState.value.canEdit).isFalse()
        r.screen.editBand(3) { it.copy(gainTenthsDb = 20) }
        r.screen.setEnabled(true)
        r.screen.resetFlat()
        advanceTimeBy(2_000)
        advanceUntilIdle()
        assertThat(r.dac.sets).isEmpty()
        assertThat(r.screen.writesStarted).isEqualTo(0)
    }

    // ── Errors are shown ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a read-back that differs is shown as an error, and the DAC is put back`() = runTest {
        val r = rig(configure = { it.corruptBandOnWrite = 3 })
        advanceUntilIdle()
        r.screen.editBand(3) { it.copy(gainTenthsDb = 20) }
        advanceTimeBy(800)
        advanceUntilIdle()
        val error = r.screen.uiState.value.error
        assertThat(error).isEqualTo(DacEqError.ReadBackDiffers(restored = true))
        assertThat(r.dac.activePreset).isEqualTo(0xF0)
        assertThat(r.screen.uiState.value.eqOn).isFalse()
        // No "EQ applied" is claimed: the label is the DAC's real state.
        assertThat(r.service.controller.label.value.name).isEqualTo("OFF_OR_FLAT")
        r.screen.dismissError()
        runCurrent()
        assertThat(r.screen.uiState.value.error).isNull()
    }

    @Test
    fun `a curve the gate refuses is shown as an error with the reason, and nothing reaches the DAC`() = runTest {
        val r = rig()
        advanceUntilIdle()
        // Three overlapping +6 dB boosts need more pre-gain than the DAC has.
        for (i in 0..2) r.screen.editBand(i) { it.copy(gainTenthsDb = 60) }
        advanceTimeBy(800)
        advanceUntilIdle()
        assertThat(r.screen.uiState.value.error).isEqualTo(DacEqError.Refused(GateRefusal.NET_BOOST))
        assertThat(r.dac.sets.filter { it.first != 0x16 }).isEmpty()
        assertThat(r.screen.uiState.value.pendingWrite).isTrue() // the curve stays on screen to be changed
    }

    // ── P4: the route conflict ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `crossfade on with a DAC names the conflict, and the EQ is still reported as the DAC holds it`() = runTest {
        val r = rig(activePreset = 0xA2, configure = { dac ->
            dac.setSlot(0xA2, -30, bands(3 to 2.0))
        })
        advanceUntilIdle()
        r.audio.value = AudioStatus(route = androidRoute(AudioRouteReason.CROSSFADE_REQUIRES_ANDROID), dacEq = r.service.controller.label.value)
        runCurrent()
        val ui = r.screen.uiState.value
        assertThat(ui.conflict).isEqualTo(DacEqConflict.CROSSFADE)
        assertThat(ui.eqOn).isTrue() // the DAC's EQ lives in the DAC: it applies on this route too
        assertThat(ui.directActive).isFalse()
        r.audio.value = AudioStatus(route = androidRoute(AudioRouteReason.SPEED_REQUIRES_ANDROID))
        runCurrent()
        assertThat(r.screen.uiState.value.conflict).isEqualTo(DacEqConflict.SPEED)
        r.audio.value = AudioStatus(route = androidRoute(AudioRouteReason.ANDROID_EFFECT_REQUIRES_ANDROID))
        runCurrent()
        assertThat(r.screen.uiState.value.conflict).isEqualTo(DacEqConflict.ANDROID_EFFECT)
        // No conflict on Direct, or for a reason that is not a listening choice.
        r.audio.value = AudioStatus(route = directRoute())
        runCurrent()
        assertThat(r.screen.uiState.value.conflict).isNull()
        r.audio.value = AudioStatus(route = androidRoute(AudioRouteReason.NO_USB_DAC).copy(usbDacPresent = false))
        runCurrent()
        assertThat(r.screen.uiState.value.conflict).isNull()
    }

    @Test
    fun `the conflict never moves the route and never says the EQ is applied when it is not`() {
        // Pure reads of the route: the model has no way to change the route's policy.
        val model = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/DacEqScreenModel.kt").readText()
        for (forbidden in listOf("AudioRouteModel", "decideRoute", "setCrossfade", "userPreferencesRepository")) {
            assertThat(model.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")).doesNotContain(forbidden)
        }
    }
}
