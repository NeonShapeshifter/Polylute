package com.lostf1sh.pixelplayeross.data.dac.fiio

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B30-fix1 H2 (auditor R30-02, the opener): **a connection path names one attachment.** The opener used to fall back to any device of
 * the same profile when the path it was given was no longer there, so a write authorised for one Melody could open another. Here the
 * real opener runs over a mocked `UsbManager`; it stops at `claimInterface` (returns false), so no session, transfer or native call is
 * ever made.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UsbFiioHidOpenerAttachmentTest {
    private val melody = FiioHidProfiles.all.single { it.id == "snowsky-melody" }

    private class Fake(val manager: UsbManager, val context: Context, val devices: Map<String, UsbDevice>, val connection: UsbDeviceConnection)

    private fun fake(vararg names: String): Fake {
        val context = mockk<Context>()
        val manager = mockk<UsbManager>()
        val connection = mockk<UsbDeviceConnection>()
        val iface = mockk<UsbInterface>()
        val input = mockk<UsbEndpoint>()
        val output = mockk<UsbEndpoint>()
        val devices = names.associateWith { name ->
            mockk<UsbDevice>().also { d ->
                every { d.deviceName } returns name
                every { d.vendorId } returns FiioHidProfiles.VENDOR_ID
                every { d.productId } returns 0x0126
                every { d.productName } returns "SNOWSKY Melody"
                every { d.interfaceCount } returns 1
                every { d.getInterface(0) } returns iface
                every { manager.hasPermission(d) } returns true
                every { manager.openDevice(d) } returns connection
            }
        }
        every { context.getSystemService(UsbManager::class.java) } returns manager
        every { manager.deviceList } returns HashMap(devices)
        every { iface.interfaceClass } returns UsbConstants.USB_CLASS_HID
        every { iface.id } returns 3
        every { iface.endpointCount } returns 2
        every { iface.getEndpoint(0) } returns input
        every { iface.getEndpoint(1) } returns output
        every { input.type } returns UsbConstants.USB_ENDPOINT_XFER_INT
        every { input.direction } returns UsbConstants.USB_DIR_IN
        every { input.address } returns 0x81
        every { output.type } returns UsbConstants.USB_ENDPOINT_XFER_INT
        every { output.direction } returns UsbConstants.USB_DIR_OUT
        every { output.address } returns 0x02
        every { connection.claimInterface(iface, true) } returns false // stop before any session, transfer or native call
        every { connection.close() } returns Unit
        return Fake(manager, context, devices, connection)
    }

    private fun attached(path: String?) = AttachedFiio("key", "serial", "profile", "Melody", path, melody)

    private fun open(f: Fake, attachment: AttachedFiio) =
        UsbFiioHidOpener(f.context).use(attachment, {}) { error("must not enter a hardware session") }

    @Test
    fun `a missing explicit path never opens another unit with the same profile`() {
        val f = fake("path-b")
        val result = open(f, attached("path-a"))
        assertThat(result).isEqualTo(HidUse.Unavailable("the DAC is not attached any more"))
        verify(exactly = 0) { f.manager.openDevice(any()) }
    }

    @Test
    fun `the path asked for is the one opened, even with another unit of the same profile attached`() {
        val f = fake("path-a", "path-b")
        open(f, attached("path-b"))
        verify(exactly = 1) { f.manager.openDevice(f.devices.getValue("path-b")) }
        verify(exactly = 0) { f.manager.openDevice(f.devices.getValue("path-a")) }
    }

    @Test
    fun `without a path a read finds the only device of its profile, and with two it opens neither`() {
        val one = fake("path-a")
        open(one, attached(null))
        verify(exactly = 1) { one.manager.openDevice(one.devices.getValue("path-a")) }

        val two = fake("path-a", "path-b")
        val result = open(two, attached(null))
        assertThat(result).isInstanceOf(HidUse.Unavailable::class.java)
        verify(exactly = 0) { two.manager.openDevice(any()) }
    }
}
