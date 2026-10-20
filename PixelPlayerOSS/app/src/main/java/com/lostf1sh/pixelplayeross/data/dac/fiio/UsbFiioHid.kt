package com.lostf1sh.pixelplayeross.data.dac.fiio

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.decent.usbaudio.UsbAudioStream

/** The HID interface's two interrupt endpoints, through a `UsbDeviceConnection` that claimed the interface. */
class UsbFiioHidWire(
    private val connection: UsbDeviceConnection,
    private val hidIn: UsbEndpoint,
    private val hidOut: UsbEndpoint,
) : FiioHidWire {
    override val reportLength: Int = hidOut.maxPacketSize

    override fun write(report: ByteArray): Int = connection.bulkTransfer(hidOut, report, report.size, TX_TIMEOUT_MS)

    override fun read(timeoutMs: Int): ByteArray? {
        val buffer = ByteArray(hidIn.maxPacketSize)
        val read = connection.bulkTransfer(hidIn, buffer, buffer.size, timeoutMs)
        return if (read > 0) buffer.copyOf(read) else null
    }

    private companion object {
        const val TX_TIMEOUT_MS = 200
    }
}

/**
 * B30: opens an attached FiiO/Snowsky DAC's HID interface the way the F-01 probe does, and gives it back.
 *
 * Its own `UsbDeviceConnection`; it claims **only the HID interface** (forcing `usbhid` off it for the duration), so the audio
 * interfaces Direct holds are untouched. When the block returns, whatever happened in it, the interface is released and `usbhid`
 * is re-attached (`nativeReattachKernelDriverIfUnbound`: 1 = it was already bound).
 */
class UsbFiioHidOpener(context: Context) : FiioHidOpener {
    private val manager = context.getSystemService(UsbManager::class.java)

    override fun <T> use(attached: AttachedFiio, log: (String) -> Unit, block: (FiioHidSession) -> T): HidUse<T> {
        val usb = manager ?: return HidUse.Unavailable("no UsbManager")
        val profile = attached.profile
        // B30-fix1 H2: a connection path names one attachment. When it is given and no device has it any more, this fails closed:
        // another unit of the same model must never be opened in its place (a write was authorised for one DAC, not for "a Melody").
        // Only an attachment that never had a path (a read of the only DAC of its profile) is looked up by profile.
        val path = attached.connectionPath
        val device = if (path != null) {
            usb.deviceList.values.firstOrNull { it.deviceName == path }
        } else {
            usb.deviceList.values.filter { FiioHidProfiles.match(it.vendorId, it.productId, it.productName) == profile }.singleOrNull()
        } ?: return HidUse.Unavailable("the DAC is not attached any more")
        if (!usb.hasPermission(device)) return HidUse.Unavailable("no USB permission for this DAC")
        val hid = hidInterface(device, profile) ?: return HidUse.Unavailable("no HID interface")
        val hidIn = endpoint(hid, UsbConstants.USB_DIR_IN) ?: return HidUse.Unavailable("no interrupt IN endpoint")
        val hidOut = endpoint(hid, UsbConstants.USB_DIR_OUT) ?: return HidUse.Unavailable("no interrupt OUT endpoint")
        log("DAC_HID|device ${device.productName} vid=0x%04X pid=0x%04X profile=%s interface=%d IN=0x%02X OUT=0x%02X reportId=0x%02X".format(
            device.vendorId, device.productId, profile.id, hid.id, hidIn.address, hidOut.address, profile.reportId))
        val connection = usb.openDevice(device) ?: return HidUse.Unavailable("openDevice failed")
        try {
            if (!connection.claimInterface(hid, true)) return HidUse.Unavailable("claimInterface(force) failed")
            try {
                return HidUse.Ran(block(FiioHidSession(UsbFiioHidWire(connection, hidIn, hidOut), profile, log)))
            } finally {
                connection.releaseInterface(hid)
                val reattach = runCatching { UsbAudioStream.nativeReattachKernelDriverIfUnbound(connection.fileDescriptor, hid.id) }
                    .getOrDefault(-1)
                log("DAC_HID|released interface=${hid.id} reattach=$reattach (1=already bound)")
            }
        } finally {
            connection.close()
        }
    }

    private fun hidInterface(device: UsbDevice, profile: FiioHidProfile): UsbInterface? {
        val interfaces = (0 until device.interfaceCount).map(device::getInterface)
            .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
        return profile.hidInterfaceNumber?.let { number -> interfaces.firstOrNull { it.id == number } }
            ?: interfaces.firstOrNull()
    }

    private fun endpoint(iface: UsbInterface, direction: Int): UsbEndpoint? =
        (0 until iface.endpointCount).map(iface::getEndpoint)
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.direction == direction }
}
