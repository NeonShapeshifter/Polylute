package com.lostf1sh.pixelplayeross.data.volume

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbVolumeRange
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.dac.DacProfile
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.dac.UsbDacDescriptor
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfile
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileRepository
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneSafetyPolicy
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.HandoffVolumeVerdict
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import android.os.Looper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * B27-A3 (review R-03): Safety Max must not credit a volume write the DAC rejected. The review's probe, kept as a regression:
 * the **real** [SafetyVolumeController] over a USB device whose write is scripted, level 40 against a ceiling of 20.
 *
 * Only what exists in both B26 and B27 is observed (the published raw level and the handoff verdict), so the same file runs
 * unchanged on the tree from before the fix, where `a write the DAC refused` publishes the requested level and the verdict is RESUME.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SafetyVolumeWriteResultTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 2 dB per step on a DAC with 1 dB resolution: 512 units a step, 60 steps. */
    private val range = UsbVolumeRange(minUnits = 0, maxUnits = 60 * 512, resolutionUnits = 256)
    private fun units(step: Int) = step * 512

    private lateinit var usb: UsbAudioDevice
    private var generation = 1L
    private var writeAnswer: (Int) -> Int? = { it }
    private var readAnswer: () -> Int? = { units(40) }
    private val writes = mutableListOf<Int>()
    private lateinit var controller: SafetyVolumeController
    private lateinit var headphones: HeadphoneProfileRepository
    private val extraScopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        usb = mockk(relaxed = true)
        every { usb.openGeneration } answers { generation }
        every { usb.hasHardwareVolume } returns true
        every { usb.writeHardwareVolume(any(), any()) } answers { writes += firstArg<Int>(); writeAnswer(firstArg()) }
        every { usb.readHardwareVolume() } answers { readAnswer() }
        every { usb.readHardwareVolumeRange() } returns range
        singleton(usb)

        every { usb.openedDevicePath } returns null
        val engine = mockk<DualPlayerEngine>(relaxed = true)
        every { engine.gainModel } returns MutableStateFlow(DualPlayerEngine.GainModel(1f, null, 1f))
        // Direct is active: the controller's backend is the DAC's own Feature Unit
        every { engine.audioRouteState } returns MutableStateFlow(
            AudioRouteState(actualBackend = AudioBackend.USB_DIRECT, transition = AudioRouteTransition.DIRECT_ACTIVE),
        )
        every { engine.isPlayingFlow } returns MutableStateFlow(false)
        // one DAC, one headphone profile with Safety Max armed at step 20: what `reconcile` resolves its backend and ceiling from
        val descriptor = UsbDacDescriptor(0x3302, 0x0101, "SNOWSKY", "Melody", "M1", "/dev/bus/usb/001/002")
        val dac = DacProfile(
            profileId = "melody", deviceIdentityKey = descriptor.identityKey, identityScope = descriptor.identityScope,
            displayName = "Melody", detectedManufacturer = descriptor.manufacturer, detectedProduct = descriptor.product,
            vendorId = descriptor.vendorId, productId = descriptor.productId, serialNumber = descriptor.serialNumber,
            createdAtEpochMs = 1L, lastSeenEpochMs = 1L,
        )
        val dacs = mockk<DacProfileRepository>(relaxed = true)
        every { dacs.connected } returns MutableStateFlow(listOf(descriptor))
        every { dacs.profiles } returns MutableStateFlow(listOf(dac))
        val calibration = calibrationFor("dac:melody")
        headphones = mockk<HeadphoneProfileRepository>(relaxed = true)
        every { headphones.state } returns MutableStateFlow(
            HeadphoneProfileState(
                profiles = listOf(
                    HeadphoneProfile(
                        profileId = "profile", displayName = "Headphones", equalizer = HeadphoneEqState(), createdAtEpochMs = 1L, updatedAtEpochMs = 1L,
                        safetyMax = HeadphoneSafetyPolicy(enabled = true, calibrations = mapOf(calibration.storageKey to calibration)),
                    ),
                ),
                selectedProfileId = "profile",
            ),
        )
        controller = SafetyVolumeController(context, headphones, dacs, engine, CoroutineScope(Job().apply { cancel() }))
        publish(contextId = "dac:melody", raw = 40)
        controllerField("hardwareRange").set(controller, range)
    }

    @After
    fun tearDown() {
        extraScopes.forEach { it.cancel() }
        singleton(null)
    }

    // ── the cases of the review ────────────────────────────────────────────────────────────────────

    @Test
    fun `a write the DAC takes is credited, and a level at the ceiling resumes`() {
        writeAnswer = { it }

        controller.setRawLevel(20)

        assertThat(writes).containsExactly(units(20))
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(20)
        assertThat(HandoffResumeSafety.verdict(controller.state.value)).isEqualTo(HandoffVolumeVerdict.RESUME)
    }

    @Test
    fun `a write that returns nothing and a DAC that cannot be read back is not a reduction`() {
        writeAnswer = { null }
        readAnswer = { null }

        controller.setRawLevel(20)

        assertThat(writes).containsExactly(units(20))                    // asked once; never retried from here
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(40) // the last level that was known, not the one asked for
        assertThat(HandoffResumeSafety.verdict(controller.state.value)).isNotEqualTo(HandoffVolumeVerdict.RESUME)
    }

    @Test
    fun `the review's probe a reduction the DAC refused never gives the resume verdict`() {
        writeAnswer = { null }
        readAnswer = { null }

        controller.setRawLevel(20)

        // the verdict alone, before anything else is asserted: on B26 it is RESUME for a DAC that never confirmed 20
        assertThat(HandoffResumeSafety.verdict(controller.state.value)).isNotEqualTo(HandoffVolumeVerdict.RESUME)
    }

    @Test
    fun `a write that failed on one channel leaves the loudest one and the level read is the one published`() {
        writeAnswer = { null }                                            // a channel refused it
        readAnswer = { units(40) }                                        // and the DAC still reports the loud one

        controller.setRawLevel(20)

        assertThat(controller.state.value.backend.rawLevel).isEqualTo(40)
        assertThat(HandoffResumeSafety.verdict(controller.state.value)).isNotEqualTo(HandoffVolumeVerdict.RESUME)
    }

    @Test
    fun `a read-back higher than the level asked for is the level published`() {
        writeAnswer = { units(30) }

        controller.setRawLevel(20)

        assertThat(controller.state.value.backend.rawLevel).isEqualTo(30)
        assertThat(HandoffResumeSafety.verdict(controller.state.value)).isNotEqualTo(HandoffVolumeVerdict.RESUME)
    }

    @Test
    fun `a read-back between two steps counts as the louder one`() {
        writeAnswer = { units(20) + 100 }

        controller.setRawLevel(20)

        assertThat(controller.state.value.backend.rawLevel).isEqualTo(21)
        assertThat(HandoffResumeSafety.verdict(controller.state.value)).isNotEqualTo(HandoffVolumeVerdict.RESUME)
    }

    @Test
    fun `a different level than asked for, lower, is the level the DAC reported`() {
        writeAnswer = { units(10) }

        controller.setRawLevel(20)

        assertThat(controller.state.value.backend.rawLevel).isEqualTo(10)
    }

    @Test
    fun `a DAC that changes during the write gets nothing credited from a reading taken on the other`() {
        writeAnswer = {
            // the user swaps DACs while the control transfer is in flight: another output, another open
            generation += 1
            publish(contextId = "dac:other", raw = 7)
            it
        }

        controller.setRawLevel(20)

        val backend = controller.state.value.backend
        assertThat(backend.outputContextId).isEqualTo("dac:other")
        assertThat(backend.rawLevel).isEqualTo(7)
    }

    @Test
    fun `no request is retried by the controller, whatever the answer`() {
        writeAnswer = { null }
        readAnswer = { null }

        controller.setRawLevel(20)
        controller.setRawLevel(20)

        assertThat(writes).containsExactly(units(20), units(20))        // one per request, none of its own
    }

    // ── the ceiling clamp of a reconcile (the review names it apart: it published `rawLevel = clamp` with no write result) ─────

    @Test
    fun `the clamp of a reconcile publishes the level the DAC reported when it refused the write`() {
        readAnswer = { units(40) }
        writeAnswer = { null }

        controller.reconcile()

        assertThat(writes).containsExactly(units(20))                    // one clamp, asked once
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(40) // the DAC is still where it was
        assertThat(HandoffResumeSafety.verdict(controller.state.value)).isNotEqualTo(HandoffVolumeVerdict.RESUME)
    }

    @Test
    fun `the clamp of a reconcile that the DAC takes is published as the ceiling`() {
        readAnswer = { units(40) }
        writeAnswer = { it }

        controller.reconcile()

        assertThat(writes).containsExactly(units(20))
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(20)
        assertThat(HandoffResumeSafety.verdict(controller.state.value)).isEqualTo(HandoffVolumeVerdict.RESUME)
    }

    @Test
    fun `a refused clamp is asked again only by the next event, never by itself`() {
        readAnswer = { units(40) }
        writeAnswer = { null }

        controller.reconcile()
        assertThat(writes).hasSize(1)
        controller.reconcile()                                           // the next event: it reads the DAC again and asks again

        assertThat(writes).hasSize(2)
    }

    @Test
    fun `a native step above the ceiling stays unsafe through repeated reconciles`() {
        controller.reconcile()
        writeAnswer = { units(20) + range.resolutionUnits }
        readAnswer = { units(20) + range.resolutionUnits }
        controller.setRawLevel(20)
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(21)
        assertThat(HandoffResumeSafety.verdict(controller.state.value)).isEqualTo(HandoffVolumeVerdict.WAIT)
        repeat(4) {
            controller.reconcile()
            assertThat(controller.state.value.backend.rawLevel).isEqualTo(21)
            assertThat(controller.state.value.backend.levelVerified).isTrue()
            assertThat(HandoffResumeSafety.verdict(controller.state.value)).isEqualTo(HandoffVolumeVerdict.WAIT)
        }
    }

    @Test
    fun `an initial native step above the ceiling is never safe`() {
        readAnswer = { units(20) + range.resolutionUnits }
        writeAnswer = { null }
        controller.reconcile()
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(21)
        assertThat(HandoffResumeSafety.verdict(controller.state.value)).isEqualTo(HandoffVolumeVerdict.WAIT)
    }

    @Test
    fun `initial exact ceiling and lower native levels remain safe`() {
        for (rawUnits in listOf(units(20), units(19), units(19) + range.resolutionUnits)) {
            generation += 1
            readAnswer = { rawUnits }
            controller.reconcile()
            assertThat(HandoffResumeSafety.verdict(controller.state.value)).isEqualTo(HandoffVolumeVerdict.RESUME)
        }
        assertThat(writes).isEmpty()
    }

    @Test
    fun `a confirmed level above every ceiling never resumes after write or reconcile`() {
        // Every native level from 1 dB above the ceiling to the DAC maximum.
        for (native in units(20) + range.resolutionUnits..range.maxUnits step range.resolutionUnits) {
            generation += 1
            writeAnswer = { native }
            readAnswer = { native }
            controller.reconcile()
            controller.setRawLevel(20)
            repeat(3) {
                assertThat(HandoffResumeSafety.verdict(controller.state.value)).isEqualTo(HandoffVolumeVerdict.WAIT)
                controller.reconcile()
            }
        }
    }

    // ── B35 (RT-01): a level the DAC never confirmed is not a ceiling ───────────────────────────────────

    /** An active scope for the controller (its own is cancelled): eager, or on [scheduler] when a test must change the world before the save runs. */
    private fun activeScope(scheduler: TestCoroutineScheduler? = null) {
        val scope = CoroutineScope(SupervisorJob() + (if (scheduler == null) Dispatchers.Unconfined else StandardTestDispatcher(scheduler)))
        extraScopes += scope
        controllerField("appScope").set(controller, scope)
    }

    private val savedCalibrations = mutableListOf<SafetyMaxCalibration>()

    private fun recordCalibrations() {
        val captured = slot<SafetyMaxCalibration>()
        coEvery { headphones.calibrateSafetyMax(any(), capture(captured)) } answers { savedCalibrations += captured.captured }
    }

    /** Taps the button and delivers the answer the way the screen gets it (the callback is posted to the main looper). */
    private fun tap(): SafetyMaxCalibrationResult? {
        var answer: SafetyMaxCalibrationResult? = null
        controller.useCurrentAsSafetyMax { answer = it }
        shadowOf(Looper.getMainLooper()).idle()
        return answer
    }

    /**
     * The state of the review: the DAC read at [level] over a ceiling of 20, then a write that the DAC refused and that cannot be read back
     * either, so the published level is only the last one that was known. The controller's cache is as it is in production once a DAC has
     * been open and read at this connection (a first reconcile would have filled it).
     */
    private fun unverifiedAt(level: Int = 40, asked: Int = 20) {
        controllerField("hardwareCacheGeneration").set(controller, generation)
        controllerField("hardwareRangeCache").set(controller, range)
        controllerField("hardwareUnitsCache").set(controller, units(level))
        publish(contextId = "dac:melody", raw = level)
        writeAnswer = { null }
        readAnswer = { null }
        controller.setRawLevel(asked)
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(level)
        assertThat(controller.state.value.backend.levelVerified).isFalse()
        writes.clear()
    }

    private fun unverifiedAtForty() = unverifiedAt(40)

    @Test
    fun `a level the DAC never confirmed is refused and nothing is saved`() {
        activeScope()
        recordCalibrations()
        unverifiedAtForty()
        readAnswer = { null }                                            // and it cannot be read now either

        assertThat(tap()).isEqualTo(SafetyMaxCalibrationResult.LEVEL_UNCONFIRMED)

        coVerify(exactly = 0) { headphones.calibrateSafetyMax(any(), any()) }
        assertThat(savedCalibrations).isEmpty()
    }

    @Test
    fun `the same request saves once a reading confirms the level`() {
        activeScope()
        recordCalibrations()
        unverifiedAtForty()
        readAnswer = { units(20) }                                       // the DAC did take the write after all

        assertThat(tap()).isEqualTo(SafetyMaxCalibrationResult.SAVED)

        assertThat(savedCalibrations).hasSize(1)
        assertThat(savedCalibrations.single().ceilingRaw).isEqualTo(20)  // what the DAC reported, not the 40 that was known
        assertThat(savedCalibrations.single().outputContextId).isEqualTo("dac:melody")
        assertThat(controller.state.value.backend.levelVerified).isTrue()
    }

    @Test
    fun `an unconfirmed level that happens to be zero is not reported as a level of zero`() {
        activeScope()
        recordCalibrations()
        unverifiedAt(level = 0, asked = 0)                               // refused and unreadable: zero is only the last level known

        assertThat(tap()).isEqualTo(SafetyMaxCalibrationResult.LEVEL_UNCONFIRMED)
        assertThat(savedCalibrations).isEmpty()
    }

    @Test
    fun `a confirmed zero is still refused as zero`() {
        activeScope()
        recordCalibrations()
        readAnswer = { units(0) }
        controller.reconcile()
        assertThat(controller.state.value.backend.levelVerified).isTrue()

        assertThat(tap()).isEqualTo(SafetyMaxCalibrationResult.LEVEL_ZERO)
        assertThat(savedCalibrations).isEmpty()
    }

    @Test
    fun `a confirmed level is saved as before and the DAC is not written`() {
        activeScope()
        recordCalibrations()
        writeAnswer = { it }
        readAnswer = { units(20) }
        controller.setRawLevel(20)
        writes.clear()

        assertThat(tap()).isEqualTo(SafetyMaxCalibrationResult.SAVED)

        assertThat(savedCalibrations.single().ceilingRaw).isEqualTo(20)
        assertThat(savedCalibrations.single().backendMinRaw).isEqualTo(0)
        assertThat(savedCalibrations.single().backendMaxRaw).isEqualTo(60)
        assertThat(writes).isEmpty()
    }

    @Test
    fun `the DAC changing while its level is read gets nothing saved`() {
        activeScope()
        recordCalibrations()
        unverifiedAtForty()
        readAnswer = {
            // the listener swaps DACs while the control transfer is in flight: the reading is the other one's
            generation += 1
            units(20)
        }

        assertThat(tap()).isEqualTo(SafetyMaxCalibrationResult.OUTPUT_CHANGED)
        assertThat(savedCalibrations).isEmpty()
    }

    @Test
    fun `the output changing between the tap and the save gets nothing saved`() {
        val scheduler = TestCoroutineScheduler()
        activeScope(scheduler)
        recordCalibrations()
        writeAnswer = { it }
        readAnswer = { units(20) }
        controller.setRawLevel(20)                                       // a confirmed 20 on dac:melody

        var answer: SafetyMaxCalibrationResult? = null
        controller.useCurrentAsSafetyMax { answer = it }                 // queued: the save has not run
        publish(contextId = "dac:other", raw = 7)                        // another output is the current one by the time it does
        scheduler.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(answer).isEqualTo(SafetyMaxCalibrationResult.OUTPUT_CHANGED)
        assertThat(savedCalibrations).isEmpty()
    }

    @Test
    fun `the profile changing between the tap and the save gets nothing saved`() {
        val scheduler = TestCoroutineScheduler()
        activeScope(scheduler)
        recordCalibrations()
        writeAnswer = { it }
        readAnswer = { units(20) }
        controller.setRawLevel(20)

        var answer: SafetyMaxCalibrationResult? = null
        controller.useCurrentAsSafetyMax { answer = it }
        publish(contextId = "dac:melody", raw = 20, profileId = "other-profile")
        scheduler.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(answer).isEqualTo(SafetyMaxCalibrationResult.OUTPUT_CHANGED)
        assertThat(savedCalibrations).isEmpty()
    }

    @Test
    fun `the DAC reopening between the tap and the save gets nothing saved`() {
        val scheduler = TestCoroutineScheduler()
        activeScope(scheduler)
        recordCalibrations()
        writeAnswer = { it }
        readAnswer = { units(20) }
        controller.setRawLevel(20)

        var answer: SafetyMaxCalibrationResult? = null
        controller.useCurrentAsSafetyMax { answer = it }
        generation += 1                                                  // same context id, another connection
        scheduler.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(answer).isEqualTo(SafetyMaxCalibrationResult.OUTPUT_CHANGED)
        assertThat(savedCalibrations).isEmpty()
    }

    @Test
    fun `with no selected profile there is nothing to calibrate`() {
        activeScope()
        recordCalibrations()
        publish(contextId = "dac:melody", raw = 20, profileId = null)

        assertThat(tap()).isEqualTo(SafetyMaxCalibrationResult.NOT_AVAILABLE)
        assertThat(savedCalibrations).isEmpty()
    }

    // ── harness ────────────────────────────────────────────────────────────────────────────────────

    private fun calibrationFor(contextId: String) = SafetyMaxCalibration(
        outputContextId = contextId,
        backendKind = SafetyVolumeBackendKind.USB_HARDWARE_VOLUME,
        ceilingRaw = 20,
        backendMinRaw = 0,
        backendMaxRaw = 60,
        calibratedAtEpochMs = 1L,
    )

    /** The state the review starts from: level above the ceiling on a controllable DAC, Safety Max armed at 20. */
    private fun publish(contextId: String, raw: Int, profileId: String? = "profile", verified: Boolean = true) {
        val state = SafetyVolumeState(
            backend = VolumeBackendSnapshot(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, contextId, "DAC", raw, 0, 60, true, levelVerified = verified),
            selectedProfileId = profileId,
            safetyEnabled = true,
            calibration = calibrationFor(contextId),
        )
        @Suppress("UNCHECKED_CAST")
        (controllerField("_state").get(controller) as MutableStateFlow<SafetyVolumeState>).value = state
    }

    private fun controllerField(name: String) =
        SafetyVolumeController::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun singleton(device: UsbAudioDevice?) {
        UsbAudioDevice::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, device)
    }
}
