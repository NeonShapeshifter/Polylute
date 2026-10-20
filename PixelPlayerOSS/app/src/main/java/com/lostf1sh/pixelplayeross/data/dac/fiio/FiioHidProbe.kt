package com.lostf1sh.pixelplayeross.data.dac.fiio

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.decent.usbaudio.UsbAudioStream

/**
 * F-01 phase 1 debug probe: reads FiiO's HID control channel, raw, and never writes to the DAC.
 *
 * Uses its own connection and claims only the HID interface (forcing `usbhid` off it for the
 * duration), so the audio interfaces Direct holds are untouched and playback is not affected. Short
 * timeouts; the interface is released and `usbhid` re-attached at the end, whatever happened.
 */
class FiioHidProbe(context: Context, private val log: (String) -> Unit) {
    private val usbManager = context.getSystemService(UsbManager::class.java)

    /** Sends each GET in [FiioHidFrames.probeCommands] and logs every reply. */
    fun probe() = withHid { conn, profile, hidIn, hidOut ->
        if (hidOut == null) {
            log("DAC_HID|no interrupt OUT endpoint: nothing can be asked; use DAC_HID_LISTEN")
            return@withHid
        }
        for (command in FiioHidFrames.probeCommands) {
            val report = FiioHidFrames.requireReadOnly(
                FiioHidFrames.outputReport(profile.reportId, FiioHidFrames.getFrame(command.command, command.data), hidOut.maxPacketSize)
            )
            val sent = conn.bulkTransfer(hidOut, report, report.size, TX_TIMEOUT_MS)
            log("DAC_HID|TX ${command.name} ret=$sent [${FiioHidFrames.hex(report, 16)}]")
            repeat(3) { attempt ->
                val buffer = ByteArray(hidIn.maxPacketSize)
                val read = conn.bulkTransfer(hidIn, buffer, buffer.size, RX_TIMEOUT_MS)
                if (read <= 0) {
                    if (attempt == 0) log("DAC_HID|RX ${command.name} none (ret=$read)")
                    return@repeat
                }
                log("DAC_HID|RX ${command.name} #$attempt len=$read [${FiioHidFrames.hex(buffer, read)}]")
            }
        }
    }

    /** Listens to the IN endpoint for [seconds] without sending anything (buttons, notifications). */
    fun listen(seconds: Int) = withHid { conn, _, hidIn, _ ->
        val start = SystemClock.elapsedRealtime()
        val until = start + seconds.coerceIn(1, 120) * 1000L
        var reports = 0
        log("DAC_HID|LISTEN ${seconds}s on IN 0x%02X — press the DAC's buttons now".format(hidIn.address))
        while (SystemClock.elapsedRealtime() < until) {
            val buffer = ByteArray(hidIn.maxPacketSize)
            val read = conn.bulkTransfer(hidIn, buffer, buffer.size, LISTEN_POLL_MS)
            if (read > 0) {
                reports++
                log("DAC_HID|IN +${SystemClock.elapsedRealtime() - start}ms len=$read [${FiioHidFrames.hex(buffer, read)}]")
            }
        }
        log("DAC_HID|LISTEN done: $reports report(s)")
    }

    private fun withHid(block: (UsbDeviceConnection, FiioHidProfile, UsbEndpoint, UsbEndpoint?) -> Unit) {
        val manager = usbManager ?: return log("DAC_HID|no UsbManager")
        val device = manager.deviceList.values.firstOrNull { it.vendorId == FiioHidProfiles.VENDOR_ID }
            ?: return log("DAC_HID|no FiiO/Snowsky device (VID 0x2972) connected")
        val profile = FiioHidProfiles.match(device.vendorId, device.productId, device.productName)
        log("DAC_HID|device ${device.productName} vid=0x%04X pid=0x%04X profile=%s".format(
            device.vendorId, device.productId, profile?.id ?: "none"))
        if (profile == null) return log("DAC_HID|not in the profile table; not probed")
        if (!manager.hasPermission(device)) return log("DAC_HID|no USB permission for this device")
        val hid = hidInterface(device, profile) ?: return log("DAC_HID|no HID interface")
        val hidIn = endpoint(hid, UsbConstants.USB_DIR_IN) ?: return log("DAC_HID|no interrupt IN endpoint")
        val hidOut = endpoint(hid, UsbConstants.USB_DIR_OUT)
        log("DAC_HID|interface=${hid.id} IN=0x%02X OUT=%s reportId=0x%02X".format(
            hidIn.address, hidOut?.let { "0x%02X".format(it.address) } ?: "none", profile.reportId))
        val conn = manager.openDevice(device) ?: return log("DAC_HID|openDevice failed")
        try {
            if (!conn.claimInterface(hid, true)) return log("DAC_HID|claimInterface(force) failed")
            try {
                block(conn, profile, hidIn, hidOut)
            } finally {
                conn.releaseInterface(hid)
                // Hand the interface back to usbhid, as it was before the probe.
                val reattach = runCatching { UsbAudioStream.nativeReattachKernelDriverIfUnbound(conn.fileDescriptor, hid.id) }
                    .getOrDefault(-1)
                log("DAC_HID|released interface=${hid.id} reattach=$reattach (1=already bound)")
            }
        } finally {
            conn.close()
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

    private companion object {
        const val TX_TIMEOUT_MS = 200
        const val RX_TIMEOUT_MS = 300
        const val LISTEN_POLL_MS = 250
    }
}
