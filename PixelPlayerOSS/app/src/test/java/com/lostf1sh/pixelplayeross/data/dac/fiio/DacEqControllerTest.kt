package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B30-P1.4: the DAC's EQ is read once per attachment, after Direct is up, off the main thread, and never writes. Real time and a
 * real single-thread dispatcher (as in the app): the settle is a few ms here.
 */
class DacEqControllerTest {
    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "DacHid-test") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
    }

    private fun attached(path: String = "/dev/bus/usb/001/005") = AttachedFiio(
        attachKey = path, identityKey = "dac-model", dacProfileId = "profile-1", displayName = "SNOWSKY Melody",
        connectionPath = path, profile = melody,
    )

    private class FakeOpener(val dac: FakeFiioDac) : FiioHidOpener {
        var opens = 0
        val threads = mutableListOf<String>()
        var unavailable: String? = null
        var failWith: Exception? = null

        /** Called with the 1-based number of the open, before the session runs: lets a test change what the DAC holds between looks. */
        var onOpen: ((Int) -> Unit)? = null
        override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> {
            opens++
            onOpen?.invoke(opens)
            threads += Thread.currentThread().name
            unavailable?.let { return HidUse.Unavailable(it) }
            failWith?.let { throw it }
            return HidUse.Ran(block(FiioHidSession(dac, attached.profile, log)))
        }
    }

    private class Rig(
        val attached: MutableStateFlow<AttachedFiio?>,
        val direct: MutableStateFlow<Boolean>,
        val opener: FakeOpener,
        val controller: DacEqController,
    )

    private fun rig(dac: FakeFiioDac = FakeFiioDac(melody, activePreset = 0xF0), settleMs: Long = 10, confirmMs: Long = 10): Rig {
        val attachedFlow = MutableStateFlow<AttachedFiio?>(null)
        val directFlow = MutableStateFlow(false)
        val opener = FakeOpener(dac)
        val controller = DacEqController(
            attached = attachedFlow,
            directActive = directFlow,
            access = DacEqHidAccess(opener, executor.asCoroutineDispatcher()),
            clock = { 123L },
            settleMs = settleMs,
            confirmMs = confirmMs,
        )
        controller.start(scope)
        return Rig(attachedFlow, directFlow, opener, controller)
    }

    private fun Rig.awaitKnown() = runBlocking { withTimeout(5_000) { controller.readout.first { it is DacEqReadout.Known } } }

    @Test
    fun `nothing is read before Direct is up, and the label is unknown meanwhile`() {
        val r = rig()
        r.attached.value = attached()
        runBlocking { delay(150) }
        assertThat(r.opener.opens).isEqualTo(0)
        assertThat(r.controller.readout.value).isInstanceOf(DacEqReadout.Pending::class.java)
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.UNKNOWN)
    }

    @Test
    fun `B41 - the DAC's EQ is offered for writing only while a model this build can write is attached`() {
        val r = rig(FakeFiioDac(melody, activePreset = 0xF0))
        assertThat(r.controller.writable.value).isFalse()
        r.attached.value = attached()
        runBlocking { withTimeout(5_000) { r.controller.writable.first { it } } }
        assertThat(r.controller.writable.value).isTrue()
        r.attached.value = null
        runBlocking { withTimeout(5_000) { r.controller.writable.first { !it } } }
        assertThat(r.controller.writable.value).isFalse()
    }

    @Test
    fun `B41 - a model with no slot of ours is read only, so it is never offered`() {
        val readOnly = FiioHidProfiles.all.first { !it.canBeWritten }
        val r = rig(FakeFiioDac(readOnly, activePreset = 0x0A))
        r.attached.value = AttachedFiio(
            attachKey = "/dev/bus/usb/001/006", identityKey = "ro", dacProfileId = "profile-ro", displayName = readOnly.displayName,
            connectionPath = "/dev/bus/usb/001/006", profile = readOnly,
        )
        runBlocking { withTimeout(5_000) { r.controller.readout.first { it is DacEqReadout.Pending } } }
        assertThat(r.controller.writable.value).isFalse()
    }

    @Test
    fun `no DAC the app can ask means nothing to say`() {
        val r = rig()
        runBlocking { delay(50) }
        assertThat(r.controller.readout.value).isEqualTo(DacEqReadout.NotApplicable)
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.NOT_APPLICABLE)
        assertThat(r.opener.opens).isEqualTo(0)
    }

    @Test
    fun `once Direct is up the EQ is read once and labelled`() {
        val r = rig(FakeFiioDac(melody, activePreset = 0xF0))
        r.attached.value = attached()
        r.direct.value = true
        val known = r.awaitKnown() as DacEqReadout.Known
        assertThat(known.state.verdict).isEqualTo(DacEqVerdict.OFF)
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.OFF)
        // B40-F2: a reading that says "the EQ does nothing" is believed on a second look, so a closed EQ takes two opens
        assertThat(r.opener.opens).isEqualTo(2)
    }

    @Test
    fun `a reading that shapes the sound is published at once, on one look`() {
        val dac = FakeFiioDac(melody, activePreset = 0xA2)
        dac.setSlot(0xA2, 0, (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], 2.0, 1.0) })
        dac.selectOnDevice(0xA2)
        val r = rig(dac)
        r.attached.value = attached()
        r.direct.value = true
        val known = r.awaitKnown() as DacEqReadout.Known
        assertThat(known.state.verdict).isEqualTo(DacEqVerdict.ON)
        assertThat(r.opener.opens).isEqualTo(1)
    }

    @Test
    fun `B40-F2 - a DAC that powers up at its defaults and then loads what it holds is never labelled off`() {
        // the S22: the Melody is read a moment after it is plugged in, before it has loaded its preset: Close EQ, no gain, flat bands
        val dac = FakeFiioDac(melody, activePreset = 0xF0)
        val curve = (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], 3.0, 1.0) }
        val r = rig(dac, confirmMs = 400)
        val labels = java.util.Collections.synchronizedList(mutableListOf<DacEqLabel>())
        val watcher = scope.launch { r.controller.label.collect { labels += it } }
        r.attached.value = attached()
        r.direct.value = true
        // first look: the defaults. Before the second look the firmware finishes loading its saved curve.
        runBlocking { withTimeout(5_000) { while (r.opener.opens < 1) delay(5) } }
        dac.setSlot(0xA2, 0, curve)
        dac.selectOnDevice(0xA2)
        val known = runBlocking { withTimeout(5_000) { r.controller.readout.first { it is DacEqReadout.Known } } } as DacEqReadout.Known
        watcher.cancel()
        assertThat(known.state.verdict).isEqualTo(DacEqVerdict.ON)
        assertThat(labels).doesNotContain(DacEqLabel.OFF)
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.ON)
    }

    @Test
    fun `B40-F2 - while the second look is awaited the label is unknown, not off`() {
        val r = rig(FakeFiioDac(melody, activePreset = 0xF0), confirmMs = 600)
        r.attached.value = attached()
        r.direct.value = true
        runBlocking { withTimeout(5_000) { while (r.opener.opens < 1) delay(5) } }
        runBlocking { delay(150) }
        assertThat(r.controller.readout.value).isInstanceOf(DacEqReadout.Pending::class.java)
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.UNKNOWN)
        r.awaitKnown()
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.OFF)
    }

    @Test
    fun `B40-F2 - readings that keep changing are not readable, and claim nothing`() {
        // every look finds the DAC in a different quiet state (Close EQ, then a flat user slot, then Close EQ again)
        val dac = FakeFiioDac(melody, activePreset = 0xF0)
        val r = rig(dac, confirmMs = 20)
        r.opener.onOpen = { n -> dac.selectOnDevice(if (n % 2 == 1) 0xF0 else 0xA0) }
        r.attached.value = attached()
        r.direct.value = true
        val readout = runBlocking { withTimeout(5_000) { r.controller.readout.first { it is DacEqReadout.Unreadable } } }
        assertThat((readout as DacEqReadout.Unreadable).why).contains("did not settle")
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.UNKNOWN)
        assertThat(r.opener.opens).isEqualTo(DacEqController.MAX_LOOKS)
    }

    @Test
    fun `B40-F2 - two looks that agree on a flat user preset are believed too`() {
        val dac = FakeFiioDac(melody, activePreset = 0xA0)
        val r = rig(dac, confirmMs = 20)
        r.attached.value = attached()
        r.direct.value = true
        val known = r.awaitKnown() as DacEqReadout.Known
        assertThat(known.state.verdict).isEqualTo(DacEqVerdict.FLAT)
        // B41: a flat user preset is told apart from a closed EQ
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.FLAT)
        assertThat(r.opener.opens).isEqualTo(2)
    }

    @Test
    fun `B40-F2 - asking again after a settled quiet reading is one look, not two`() {
        val r = rig(FakeFiioDac(melody, activePreset = 0xF0), confirmMs = 20)
        r.attached.value = attached()
        r.direct.value = true
        r.awaitKnown()
        val before = r.opener.opens
        runBlocking { r.controller.refresh() }
        assertThat(r.opener.opens).isEqualTo(before + 1)
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.OFF)
    }

    @Test
    fun `it reads once per attachment, not per song or per stream rebuild`() {
        val r = rig()
        r.attached.value = attached()
        r.direct.value = true
        r.awaitKnown()
        // Songs and rate changes: Direct flickers, the same DAC stays attached.
        repeat(5) {
            r.direct.value = false
            runBlocking { delay(30) }
            r.direct.value = true
            runBlocking { delay(60) }
        }
        assertThat(r.opener.opens).isEqualTo(2)   // the closed EQ was looked at twice; songs and rebuilds add nothing

        // Unplug and plug back in (a new USB path): a new attachment, a new read.
        r.attached.value = null
        runBlocking { delay(30) }
        assertThat(r.controller.readout.value).isEqualTo(DacEqReadout.NotApplicable)
        r.attached.value = attached("/dev/bus/usb/001/006")
        runBlocking { withTimeout(5_000) { r.controller.readout.first { it is DacEqReadout.Known } } }
        assertThat(r.opener.opens).isEqualTo(4)
    }

    @Test
    fun `the read runs on the HID thread and never on the caller's`() {
        val r = rig()
        r.attached.value = attached()
        r.direct.value = true
        r.awaitKnown()
        // (The coroutine debug agent appends " @coroutine#N" to the thread's name.)
        assertThat(r.opener.threads).isNotEmpty()
        assertThat(r.opener.threads.all { it.startsWith("DacHid-test") }).isTrue()
        assertThat(Thread.currentThread().name).doesNotContain("DacHid-test")
    }

    @Test
    fun `the attach read sends GETs and nothing else`() {
        val dac = FakeFiioDac(melody, activePreset = 0xA2)
        val r = rig(dac)
        r.attached.value = attached()
        r.direct.value = true
        r.awaitKnown()
        assertThat(dac.written).isNotEmpty()
        assertThat(dac.sets).isEmpty()
        assertThat(dac.written.all { (it[1].toInt() and 0xFF) == 0xBB }).isTrue()
    }

    @Test
    fun `Direct that goes away during the settle does not read, and reads when it is back`() {
        val r = rig(settleMs = 150)
        r.attached.value = attached()
        r.direct.value = true
        runBlocking { delay(40) }
        r.direct.value = false
        runBlocking { delay(250) }
        assertThat(r.opener.opens).isEqualTo(0)
        r.direct.value = true
        r.awaitKnown()
        assertThat(r.opener.opens).isEqualTo(2)
    }

    // ── HID failure: audio continues, the state is unknown, never Purity ─────────────────────────────────

    @Test
    fun `an interface that cannot be opened leaves the label unknown`() {
        val r = rig()
        r.opener.unavailable = "no USB permission for this DAC"
        r.attached.value = attached()
        r.direct.value = true
        val readout = runBlocking { withTimeout(5_000) { r.controller.readout.first { it is DacEqReadout.Unreadable } } }
        assertThat((readout as DacEqReadout.Unreadable).why).contains("permission")
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.UNKNOWN)
    }

    @Test
    fun `a USB error thrown in the read is swallowed into an unreadable state`() {
        val r = rig()
        r.opener.failWith = IOException("transfer failed")
        r.attached.value = attached()
        r.direct.value = true
        val readout = runBlocking { withTimeout(5_000) { r.controller.readout.first { it is DacEqReadout.Unreadable } } }
        assertThat((readout as DacEqReadout.Unreadable).why).contains("IOException")
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.UNKNOWN)
    }

    @Test
    fun `a DAC that says nothing or garbles its replies is never labelled Purity`() {
        val dac = FakeFiioDac(melody)
        dac.dropReplies = true
        val r = rig(dac)
        r.attached.value = attached()
        r.direct.value = true
        r.awaitKnown()
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.UNKNOWN)

        // The audio side of the account is untouched: the route is still Direct and pristine, only the word is withheld.
        val status = AudioStatus(
            route = com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState(
                actualBackend = com.lostf1sh.pixelplayeross.data.service.player.AudioBackend.USB_DIRECT,
                transition = com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition.DIRECT_ACTIVE,
                signalMode = com.decent.usbaudio.media3.DirectSignalMode.PRISTINE,
                usbDacPresent = true,
            ),
            dacEq = r.controller.label.value,
        )
        assertThat(status.directPristine).isTrue()
        assertThat(status.purityHolds).isFalse()
    }

    // ── Refresh and the label's follow-up ────────────────────────────────────────────────────────────────

    @Test
    fun `asking again reads again and the label follows what the DAC says now`() {
        val dac = FakeFiioDac(melody, activePreset = 0xF0)
        val r = rig(dac)
        r.attached.value = attached()
        r.direct.value = true
        r.awaitKnown()
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.OFF)

        // Someone selects Jazz on the DAC's own button.
        dac.setSlot(0xA2, 0, (0 until 10).map { DacEqBand.of(it, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[it], 2.0, 1.0) })
        dac.selectOnDevice(0xA2)
        runBlocking { r.controller.refresh() }
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.ON)
        // two looks at the closed EQ, one more for the changed one
        assertThat(r.opener.opens).isEqualTo(3)
    }

    @Test
    fun `the state the app reads back after its own write is what the labels follow`() {
        val dac = FakeFiioDac(melody, activePreset = 0xF0)
        val r = rig(dac)
        r.attached.value = attached()
        r.direct.value = true
        val known = r.awaitKnown() as DacEqReadout.Known
        val shaped = known.state.copy(
            activePreset = EqRead.Read(0xA2),
            bands = known.state.bands.mapIndexed { i, _ ->
                EqRead.Read(DacEqBand.of(i, FiioFilterType.PEAK, FakeFiioDac.FREQUENCIES[i], 1.5, 1.0))
            },
        )
        r.controller.publish(attached(), shaped)
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.ON)
        // A state for a DAC that is not the attached one is ignored.
        r.controller.publish(attached("/dev/bus/usb/009/009"), known.state)
        assertThat(r.controller.label.value).isEqualTo(DacEqLabel.ON)
    }
}
