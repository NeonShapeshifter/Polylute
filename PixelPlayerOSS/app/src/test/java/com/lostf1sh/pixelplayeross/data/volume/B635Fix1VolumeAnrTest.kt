package com.lostf1sh.pixelplayeross.data.volume

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.decent.usbaudio.UacVolumeTopology
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioDeviceInfo
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
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B635Fix1VolumeAnrTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job().apply { cancel() })
    private val writes = CopyOnWriteArrayList<Int>()
    private val transferEntered = CountDownLatch(1)
    private val unblock = CountDownLatch(1)
    @Volatile private var blockNextRead = false
    @Volatile private var level = 10 * 512
    @Volatile private var difference = 0
    private lateinit var device: UsbAudioDevice
    private lateinit var controller: SafetyVolumeController

    @Before
    fun configureDac() {
        val path = "/dev/bus/usb/007/001"
        val port = mockk<UsbDevice>(relaxed = true)
        val iface = mockk<UsbInterface>()
        val endpoint = mockk<UsbEndpoint>()
        every { port.deviceName } returns path
        every { port.interfaceCount } returns 1
        every { port.getInterface(0) } returns iface
        every { iface.interfaceClass } returns 1
        every { iface.interfaceSubclass } returns 2
        every { iface.interfaceProtocol } returns 0x20
        every { iface.endpointCount } returns 1
        every { iface.getEndpoint(0) } returns endpoint
        every { endpoint.type } returns 1
        every { endpoint.direction } returns 0
        shadowOf(context.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(port, true)
        device = UsbAudioDevice::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        val connection = mockk<UsbDeviceConnection>()
        every { connection.controlTransfer(any(), any(), any(), any(), any(), any(), any()) } answers {
            val data = arg<ByteArray>(4)
            when (firstArg<Int>()) {
                0x21 -> {
                    val target = (data[0].toInt() and 255) or ((data[1].toInt() and 255) shl 8)
                    writes += target / 512
                    level = target + difference
                }
                0xA1 -> if (secondArg<Int>() == 2) {
                    byteArrayOf(1, 0, 0, 0, 0, 120, 0, 1).copyInto(data)
                } else {
                    if (blockNextRead) {
                        blockNextRead = false
                        transferEntered.countDown()
                        unblock.await(10, TimeUnit.SECONDS)
                    }
                    data[0] = level.toByte()
                    data[1] = (level shr 8).toByte()
                }
            }
            data.size
        }
        val info = mockk<UsbAudioDeviceInfo>()
        every { info.audioControlInterfaceId } returns 0
        putDeviceField("connection", connection)
        putDeviceField("currentDevice", port)
        putDeviceField("cachedDeviceInfo", info)
        putDeviceField("openGeneration", 1L)
        putDeviceField("volumeTopology", UacVolumeTopology(1, listOf(0)))
        singleton().set(null, device)

        val descriptor = UsbDacDescriptor(0x3302, 0x0101, "Test", "DAC", "synthetic", path)
        val dac = DacProfile(
            profileId = "dac", deviceIdentityKey = descriptor.identityKey, identityScope = descriptor.identityScope,
            displayName = "DAC", detectedManufacturer = descriptor.manufacturer, detectedProduct = descriptor.product,
            vendorId = descriptor.vendorId, productId = descriptor.productId, serialNumber = descriptor.serialNumber,
            createdAtEpochMs = 1L, lastSeenEpochMs = 1L,
        )
        val dacs = mockk<DacProfileRepository>()
        every { dacs.connected } returns MutableStateFlow(listOf(descriptor))
        every { dacs.profiles } returns MutableStateFlow(listOf(dac))
        val calibration = SafetyMaxCalibration("dac:dac", SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, 20, 0, 60, 1L)
        val profiles = mockk<HeadphoneProfileRepository>()
        every { profiles.state } returns MutableStateFlow(HeadphoneProfileState(
            profiles = listOf(HeadphoneProfile(
                profileId = "headphones", displayName = "Headphones", equalizer = HeadphoneEqState(),
                createdAtEpochMs = 1L, updatedAtEpochMs = 1L,
                safetyMax = HeadphoneSafetyPolicy(enabled = true, calibrations = mapOf(calibration.storageKey to calibration)),
            )),
            selectedProfileId = "headphones",
        ))
        val engine = mockk<DualPlayerEngine>()
        every { engine.gainModel } returns MutableStateFlow(DualPlayerEngine.GainModel(1f, null, 1f))
        every { engine.audioRouteState } returns MutableStateFlow(
            AudioRouteState(actualBackend = AudioBackend.USB_DIRECT, transition = AudioRouteTransition.DIRECT_ACTIVE),
        )
        every { engine.isPlayingFlow } returns MutableStateFlow(false)
        controller = SafetyVolumeController(context, profiles, dacs, engine, scope)
        controller.reconcile()
        awaitVolume()
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(10)
    }

    @After
    fun releaseDac() {
        unblock.countDown()
        awaitVolume()
        singleton().set(null, null)
    }

    @Test
    fun key_returns_before_blocked_usb_and_shows_request_then_readback() {
        difference = -512
        // Load the key and volume paths before timing the dispatch of an actual blocked transfer.
        assertThat(controller.handleVolumeKey(volumeUp())).isTrue()
        awaitVolume()
        val event = volumeUp()
        blockNextRead = true
        var consumed = false
        inFrame { consumed = controller.handleVolumeKey(event) }
        assertThat(consumed).isTrue()
        assertThat(transferEntered.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(controller.hudEvent.value.interactionId).isGreaterThan(0L)
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(11)
        assertThat(controller.state.value.backend.levelVerified).isFalse()
        unblock.countDown()
        awaitVolume()
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(10)
        assertThat(controller.state.value.backend.levelVerified).isTrue()
    }

    @Test
    fun slider_returns_before_blocked_usb_and_corrects_its_requested_level() {
        difference = -512
        blockNextRead = true
        inFrame { controller.setRawLevel(18) }
        assertThat(transferEntered.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(18)
        unblock.countDown()
        awaitVolume()
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(17)
        assertThat(controller.state.value.backend.levelVerified).isTrue()
    }

    @Test
    fun audio_device_callback_returns_before_blocked_usb() {
        val callback = SafetyVolumeController::class.java.getDeclaredField("deviceCallback").apply { isAccessible = true }
            .get(controller) as AudioDeviceCallback
        putDeviceField("openGeneration", 2L)
        level = 12 * 512
        blockNextRead = true
        inFrame { callback.onAudioDevicesAdded(emptyArray()) }
        assertThat(transferEntered.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(10)
        unblock.countDown()
        awaitVolume()
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(12)
    }

    @Test
    fun three_seconds_of_held_keys_keep_only_the_latest_pending_target() {
        val event = volumeUp()
        blockNextRead = true
        inFrame { controller.handleVolumeKey(event) }
        assertThat(transferEntered.await(2, TimeUnit.SECONDS)).isTrue()
        repeat(24) { repeat ->
            ShadowSystemClock.advanceBy(125, TimeUnit.MILLISECONDS)
            assertThat(controller.handleVolumeKey(volumeUp(repeat + 1))).isTrue()
        }
        assertThat(writes).isEmpty()
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(20)
        unblock.countDown()
        awaitVolume()
        assertThat(writes).containsExactly(11, 20).inOrder()
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(20)
    }

    private fun inFrame(action: () -> Unit) {
        assertThat(Thread.currentThread()).isEqualTo(Looper.getMainLooper().thread)
        val started = System.nanoTime()
        action()
        assertThat(System.nanoTime() - started).isLessThan(16_000_000L)
    }

    private fun awaitVolume() {
        // The original implementation is synchronous. Run these same assertions on both trees.
        val companion = UsbAudioDevice.Companion
        val executor = companion.javaClass.methods.firstOrNull { it.name == "getVolumeExecutor" }?.invoke(companion) as? ExecutorService
        repeat(2) { executor?.submit {}?.get(3, TimeUnit.SECONDS) }
    }

    private fun volumeUp(repeat: Int = 0): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, repeat)
    }

    private fun singleton() = UsbAudioDevice::class.java.getDeclaredField("instance").apply { isAccessible = true }

    private fun putDeviceField(name: String, value: Any) {
        UsbAudioDevice::class.java.getDeclaredField(name).apply { isAccessible = true }.set(device, value)
    }
}
