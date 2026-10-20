package com.decent.usbaudio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log


/**
 * Manages the lifecycle of a USB Audio Class device for bit-perfect output.
 *
 * Responsibilities:
 * - Discover connected USB audio devices
 * - Request user permission via [UsbManager.requestPermission]
 * - Open the device and extract endpoint/interface info
 * - Provide the file descriptor and endpoint addresses to [UsbAudioStream]
 *
 * This class does NOT perform audio I/O — that's handled by the native layer
 * via [UsbAudioStream].
 *
 * @author DecentPlayer project
 */
class UsbAudioDevice private constructor(private val context: Context) {

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var currentDevice: UsbDevice? = null
    private var claimedInterface: UsbInterface? = null
    private var claimedControlInterface: UsbInterface? = null

    /**
     * The playback alt chosen by [openDevice], and its interface number. Every streaming parameter —
     * endpoint, maxPacket, container, the alt applied by [setAltSetting] — comes from it (AUD-006,
     * AUD-007, AUD-058). Null when nothing is open.
     */
    @Volatile
    var selectedPlaybackAlt: UacStreamingAlt? = null
        private set

    // ── Direct level memory (AUD-118) ─────────────────────────────────────
    //
    // The last level Direct played at, per physical DAC, kept by the driver itself so that every
    // claim can restore it whatever the host can resolve about the device at that instant.
    private val directLevels = context.getSharedPreferences("usb_direct_level", Context.MODE_PRIVATE)
    private var openDeviceKey: String? = null

    /** False between a claim and its restore: reads then show what Android left, not Direct's level. */
    @Volatile
    private var openLevelSettled = false

    /** AUD-126: logs every Feature Unit write and external change; restores after a reconfigure. */
    private val levelGuard = DacLevelGuard { Log.i(TAG, it) }

    /** Outcome of the last claim's level restore; the sink refuses Direct on UNVERIFIED. */
    @Volatile
    var openLevelCheck: OpenLevelCheck = OpenLevelCheck.NO_HARDWARE_VOLUME
        private set

    private fun rememberDirectLevel(units: Int) {
        val key = openDeviceKey ?: return
        directLevels.edit().putInt(key, units).apply()
    }

    private fun savedDirectLevel(): Int? {
        val key = openDeviceKey ?: return null
        return if (directLevels.contains(key)) directLevels.getInt(key, 0) else null
    }
    private var selectedStreamingInterfaceNumber = -1

    companion object {
        private const val TAG = "UsbAudioDevice"
        /** UAC2 Feature Unit control selector FU_VOLUME_CONTROL. */
        private const val FU_VOLUME_CONTROL = 0x02
        /** Short: these run on UI-driven paths, and a DAC that does not answer must not stall them. */
        private const val VOLUME_TIMEOUT_MS = 250
        private const val ACTION_USB_PERMISSION_SUFFIX = ".USB_AUDIO_PERMISSION"

        @Volatile
        private var instance: UsbAudioDevice? = null

        /**
         * Get the singleton instance. All callers share the same connection
         * share the same connection and fd, preventing ENODEV from competing opens.
         */
        fun getInstance(context: Context): UsbAudioDevice {
            return instance ?: synchronized(this) {
                instance ?: UsbAudioDevice(context.applicationContext).also { instance = it }
            }
        }
    }


    /**
     * Find the first connected USB audio output device.
     *
     * Scans all USB devices for one with an AudioStreaming interface
     * (class=1, subclass=2) that has an isochronous OUT endpoint.
     *
     * @return The USB device, or null if none found.
     */
    fun findUsbAudioDevice(): UsbDevice? {
        for (device in usbManager.deviceList.values) {
            if (isCompatibleUsbAudioDevice(device)) {
                Log.i(TAG, "Found USB audio device: ${device.productName} " +
                        "(vendor=0x${device.vendorId.toString(16)}, " +
                        "product=0x${device.productId.toString(16)})")
                return device
            }
        }
        Log.d(TAG, "No USB audio device found")
        return null
    }

    /**
     * True only for a UAC2 AudioStreaming interface with an isochronous OUT endpoint. UAC1 devices
     * are left to Android: this driver cannot set their rate, and claiming them detaches
     * `snd-usb-audio` for nothing (AUD-005).
     */
    fun isCompatibleUsbAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val hasIsoOut = (0 until iface.endpointCount).any { endpointIndex ->
                val endpoint = iface.getEndpoint(endpointIndex)
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT
            }
            if (UacCompatibility.isDirectStreamingInterface(
                    iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol, hasIsoOut,
                )) return true
        }
        return false
    }

    /**
     * Check if we already have permission to access the device.
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * Request permission from the user to access the USB device.
     *
     * The answer is matched to [device] through `EXTRA_DEVICE`, and each device has its own request
     * code, so concurrent requests cannot take each other's answer (AUD-031).
     *
     * @param device   The USB device to request access for.
     * @param callback Called with true if permission granted, false otherwise.
     * @return Unregisters the result receiver; for a request that is never answered (under Lock Task
     *         the dialog may not show), so the receiver does not stay registered forever.
     */
    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit): () -> Unit {
        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "Permission already granted for ${device.productName}")
            callback(true)
            return {}
        }

        val intent = Intent(context.packageName + ACTION_USB_PERMISSION_SUFFIX)
        intent.setPackage(context.packageName)
        val permissionIntent = PendingIntent.getBroadcast(
                context, device.deviceName.hashCode(),
                intent,
                PendingIntent.FLAG_MUTABLE
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != context.packageName + ACTION_USB_PERMISSION_SUFFIX) return
                @Suppress("DEPRECATION")
                val answered = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (answered != null && answered.deviceName != device.deviceName) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.i(TAG, "USB permission result: granted=$granted for ${device.productName}")
                runCatching { context.unregisterReceiver(this) }
                callback(granted)
            }
        }

        val filter = IntentFilter(context.packageName + ACTION_USB_PERMISSION_SUFFIX)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        usbManager.requestPermission(device, permissionIntent)
        Log.i(TAG, "Permission requested for ${device.productName}")
        return { runCatching { context.unregisterReceiver(receiver) } }
    }

    /**
     * Open the USB device and extract all information needed for audio I/O.
     *
     * Finds the AudioStreaming interface, locates the isochronous OUT and
     * feedback IN endpoints, and returns everything the native layer needs.
     *
     * @param device The USB audio device to open.
     * @return Device info with fd and endpoint addresses, or null on failure.
     */
    /**
     * The playback alts of [device], read from its raw descriptors **without claiming anything**
     * (R4-1): the sink decides whether a track's format fits before it takes the DAC from
     * snd-usb-audio. Reuses the open connection for this device; otherwise opens a connection only
     * long enough to read the descriptors, which does not detach any kernel driver.
     */
    fun readPlaybackAlts(device: UsbDevice): List<UacStreamingAlt> {
        val open = connection
        if (open != null && currentDevice?.deviceName == device.deviceName) {
            return UacStreamingDescriptors.parse(open.rawDescriptors)
        }
        val conn = usbManager.openDevice(device) ?: return emptyList()
        return try {
            UacStreamingDescriptors.parse(conn.rawDescriptors)
        } finally {
            conn.close()
        }
    }

    /** Cached device info from the last successful openDevice() call. */
    private var cachedDeviceInfo: UsbAudioDeviceInfo? = null

    fun openDevice(device: UsbDevice): UsbAudioDeviceInfo? {
        // Return cached info if already open with valid connection
        val cached = cachedDeviceInfo
        if (cached != null && connection != null) {
            Log.i(TAG, "Device already open, reusing fd=${cached.fd}")
            return cached
        }
        // Close any stale connection before opening new
        closeDevice()
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.e(TAG, "Failed to open device ${device.productName}")
            return null
        }

        // Find the AudioStreaming interface and its endpoints
        var streamingInterface: UsbInterface? = null
        var endpointOut = -1
        var endpointFeedback = -1
        var maxPacketSize = 0
        var altSettingCount = 0

        // Count alternate settings for the streaming interface
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2) {
                altSettingCount++

                // Look for endpoints in non-zero alt settings
                if (iface.endpointCount > 0 && streamingInterface == null) {
                    streamingInterface = iface

                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        when {
                            // Isochronous OUT endpoint (audio data)
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                                    ep.direction == UsbConstants.USB_DIR_OUT -> {
                                endpointOut = ep.address
                                maxPacketSize = ep.maxPacketSize
                                Log.i(TAG, "Found ISO OUT endpoint: address=0x${ep.address.toString(16)}, " +
                                        "maxPacket=$maxPacketSize, interval=${ep.interval}")
                            }
                            // Isochronous IN endpoint (feedback)
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                                    ep.direction == UsbConstants.USB_DIR_IN -> {
                                endpointFeedback = ep.address
                                Log.i(TAG, "Found ISO IN (feedback) endpoint: address=0x${ep.address.toString(16)}, " +
                                        "interval=${ep.interval}")
                            }
                        }
                    }
                }
            }
        }

        if (streamingInterface == null || endpointOut < 0) {
            Log.e(TAG, "No suitable AudioStreaming interface/endpoint found")
            conn.close()
            return null
        }

        // Checked before any claim: a device without a UAC2 Clock Source cannot be driven, and a
        // forced claim would detach snd-usb-audio and leave it dead to Android (AUD-005).
        if (!UacCompatibility.hasClockSource(conn.rawDescriptors)) {
            Log.e(TAG, "No UAC2 Clock Source; leaving ${device.productName} to Android")
            conn.close()
            return null
        }

        // The alt that will be streamed decides the endpoint, maxPacket and container — not the
        // first AS interface with endpoints (AUD-006). Capture and non-PCM alts are excluded, and
        // the interface is identified by number (AUD-007, AUD-058). Chosen before any claim.
        val selected = UacStreamingDescriptors.selectForPlayback(
            UacStreamingDescriptors.parse(conn.rawDescriptors)
        )
        if (selected == null) {
            Log.e(TAG, "No PCM playback alt setting; leaving ${device.productName} to Android")
            conn.close()
            return null
        }
        streamingInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull {
                it.id == selected.interfaceNumber && it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    it.interfaceSubclass == 2
            } ?: streamingInterface
        endpointOut = selected.endpointOutAddress
        endpointFeedback = selected.feedbackEndpointAddress
        maxPacketSize = selected.maxPacketBytes
        Log.i(TAG, "Selected playback alt: iface=${selected.interfaceNumber} alt=${selected.altSetting} " +
                "container=${selected.containerBits}bit resolution=${selected.bitResolution}bit " +
                "channels=${selected.channels} maxPacket=${selected.maxPacketBytes} " +
                "epOut=0x${selected.endpointOutAddress.toString(16)}")

        // Claim the AudioControl interface (0) with force=true to disconnect kernel driver
        val controlInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 }

        if (controlInterface != null) {
            val claimed = conn.claimInterface(controlInterface, true)
            Log.i(TAG, "Claimed AudioControl interface ${controlInterface.id} force=true: $claimed")
            if (claimed) claimedControlInterface = controlInterface
        }

        // Claim the AudioStreaming interface with force=true to disconnect kernel driver (snd-usb-audio)
        // NOTE: We claim the zero-bandwidth alt setting (alt=0). The actual streaming alt setting
        // will be activated later via setInterface() which allocates USB bandwidth.
        val claimed = conn.claimInterface(streamingInterface, true)
        Log.i(TAG, "Claimed AudioStreaming interface ${streamingInterface.id} force=true: $claimed " +
                "(alt=${streamingInterface.alternateSetting}, endpoints=${streamingInterface.endpointCount})")
        if (!claimed) {
            Log.e(TAG, "Failed to claim streaming interface — kernel driver may still be active")
            conn.close()
            return null
        }
        claimedInterface = streamingInterface

        // Force alt=0 to stop any streaming left by kernel driver
        val zeroAlt = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                        it.interfaceSubclass == 2 && it.alternateSetting == 0 &&
                        it.id == selected.interfaceNumber }
        if (zeroAlt != null) {
            conn.setInterface(zeroAlt)
            Log.i(TAG, "Reset streaming to alt=0 (zero-bandwidth)")
        }
        Thread.sleep(100)

        // Log all available alt settings for debugging
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2) {
                Log.d(TAG, "  AudioStreaming alt=${iface.alternateSetting}: " +
                        "id=${iface.id}, endpoints=${iface.endpointCount}")
            }
        }

        val fd = conn.fileDescriptor
        val interfaceId = streamingInterface.id

        Log.i(TAG, "Device opened: ${device.productName}, fd=$fd, " +
                "iface=$interfaceId, epOut=0x${endpointOut.toString(16)}, " +
                "epFb=0x${endpointFeedback.toString(16)}, " +
                "maxPacket=$maxPacketSize, altSettings=$altSettingCount")

        connection = conn
        currentDevice = device
        openLevelSettled = false
        openDeviceKey = DirectOpenLevelPolicy.deviceKey(device.vendorId, device.productId,
            runCatching { device.serialNumber }.getOrNull())

        // Auto-detect Clock Source ID and best alt setting from USB descriptors
        val clockSourceId = parseClockSourceId(conn)
        // The container (bSubslotSize), not the resolution, is the transport's bytes per sample.
        val bestAlt = selected.altSetting
        val bestBits = selected.containerBits
        selectedPlaybackAlt = selected
        selectedStreamingInterfaceNumber = selected.interfaceNumber
        Log.i(TAG, "Auto-detected: clockSourceId=0x${clockSourceId.toString(16)}, " +
                "bestAlt=$bestAlt, bestBits=$bestBits")
        volumeTopology = conn.rawDescriptors?.let(UacFeatureUnitParser::findOutputVolume)
        Log.i(TAG, "Hardware volume: $volumeTopology")

        val info = UsbAudioDeviceInfo(
                connection = conn,
                fd = fd,
                deviceName = device.productName ?: "USB Audio Device",
                audioControlInterfaceId = controlInterface?.id ?: -1,
                interfaceId = interfaceId,
                endpointOutAddress = endpointOut,
                endpointFeedbackAddress = endpointFeedback,
                maxPacketSize = maxPacketSize,
                altSettingCount = altSettingCount,
                clockSourceId = clockSourceId,
                bestAltSetting = bestAlt,
                bestBitDepth = bestBits
        )
        cachedDeviceInfo = info
        openGeneration++
        restoreDirectLevelOnOpen()
        return info
    }

    /**
     * Perform a USB device reset via native ioctl, then close and reopen.
     * This clears any stale clock/endpoint state left by the kernel driver.
     * After reset, the DAC reinitializes and will accept our SET_CUR.
     */
    fun resetAndReopen() {
        val conn = connection ?: return
        val fd = conn.fileDescriptor

        Log.i(TAG, "Performing REAL USBDEVFS_RESET on fd=$fd...")

        // Real USB port reset via native ioctl — resets DAC clock state
        val ret = UsbAudioStream.nativeUsbReset(fd)
        Log.i(TAG, "USBDEVFS_RESET result: $ret")

        // Reset releases all interface claims. The fd remains valid.
        // Clear cache so openDevice re-claims, but KEEP the connection
        // so the same fd is reused (native claims are on this fd).
        cachedDeviceInfo = null
        claimedInterface = null
        claimedControlInterface = null
        // DO NOT close connection — the fd from reset+native claim must be reused
        // The next openDevice() will see connection != null and skip re-opening
    }

    /**
     * Parse raw USB descriptors to find the UAC2 Clock Source entity ID.
     * This is the entity that controls the DAC's sample rate.
     *
     * Scans the AudioControl interface descriptors for a CLOCK_SOURCE
     * descriptor (bDescriptorSubtype = 0x0A) and returns its bClockID.
     *
     * @return Clock Source entity ID, or -1 if not found.
     */
    private fun parseClockSourceId(conn: UsbDeviceConnection): Int {
        val id = UacCompatibility.clockSourceId(conn.rawDescriptors)
        if (id >= 0) {
            Log.i(TAG, "parseClockSourceId: found CLOCK_SOURCE bClockID=0x${id.toString(16)}")
        } else {
            Log.w(TAG, "parseClockSourceId: no CLOCK_SOURCE descriptor found")
        }
        return id
    }

    /**
     * Parse raw USB descriptors to find the best (highest bit depth) alt setting
     * for the AudioStreaming interface.
     *
     * Scans AS Format Type I descriptors (CS_INTERFACE 0x02) for bBitResolution
     * and returns the alt setting with the highest value.
     *
     * @return Pair(altSetting, bitDepth), or Pair(1, 16) as default.
     */
    /** Parsed alt setting: (altNumber, bitResolution) */
    private var parsedAltSettings: List<Pair<Int, Int>> = emptyList()

    private fun parseBestAltSetting(conn: UsbDeviceConnection): Pair<Int, Int> {
        val raw = conn.rawDescriptors ?: return Pair(1, 16)
        val altSettings = mutableListOf<Pair<Int, Int>>()

        var i = 0
        var currentAlt = 0
        var inAudioStreaming = false
        var bestAlt = 1
        var bestBits = 16

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            // Interface descriptor (0x04)
            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                val bAlternateSetting = raw[i + 3].toInt() and 0xFF
                inAudioStreaming = (bInterfaceClass == 1 && bInterfaceSubClass == 2)
                if (inAudioStreaming) currentAlt = bAlternateSetting
            }

            // CS_INTERFACE (0x24) in AudioStreaming — Format Type I (subtype 0x02)
            if (inAudioStreaming && bDescriptorType == 0x24 && bLength >= 6) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                if (bDescriptorSubtype == 0x02) {
                    val bSubslotSize = raw[i + 4].toInt() and 0xFF
                    val bBitResolution = raw[i + 5].toInt() and 0xFF
                    Log.i(TAG, "parseBestAltSetting: alt=$currentAlt subslotSize=$bSubslotSize bitResolution=$bBitResolution")

                    if (currentAlt > 0) {
                        altSettings.add(Pair(currentAlt, bBitResolution))
                    }
                    if (bBitResolution > bestBits && currentAlt > 0) {
                        bestBits = bBitResolution
                        bestAlt = currentAlt
                    }
                }
            }

            i += bLength
        }

        parsedAltSettings = altSettings
        Log.i(TAG, "parseBestAltSetting: best alt=$bestAlt bits=$bestBits, all=$altSettings")
        return Pair(bestAlt, bestBits)
    }

    /**
     * Find the alt setting that matches the given source bit depth exactly.
     * If no exact match, returns the next higher bit depth.
     * Fallback: returns the best (highest) alt setting.
     *
     * @return Pair(altSetting, bitDepth)
     */
    fun findAltSettingForBitDepth(targetBitDepth: Int): Pair<Int, Int> {
        if (parsedAltSettings.isEmpty()) {
            val info = cachedDeviceInfo ?: return Pair(1, 16)
            return Pair(info.bestAltSetting, info.bestBitDepth)
        }

        // Exact match
        val exact = parsedAltSettings.firstOrNull { it.second == targetBitDepth }
        if (exact != null) {
            Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): exact match alt=${exact.first}")
            return exact
        }

        // Next higher
        val higher = parsedAltSettings
                .filter { it.second > targetBitDepth }
                .minByOrNull { it.second }
        if (higher != null) {
            Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): next higher alt=${higher.first} bits=${higher.second}")
            return higher
        }

        // Fallback to best
        val best = parsedAltSettings.maxByOrNull { it.second } ?: Pair(1, 16)
        Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): fallback to best alt=${best.first} bits=${best.second}")
        return best
    }

    /**
     * Close the USB device and release all resources.
     */
    // ── Hardware volume (UAC2 Feature Unit) ─────────────────────────

    @Volatile
    private var volumeTopology: UacVolumeTopology? = null

    /** Incremented on every successful open; a DAC reset or replug is a new volume session. */
    @Volatile
    var openGeneration: Long = 0L
        private set

    /**
     * Supplies the level a freshly opened DAC may play at, given its range and current level.
     *
     * Called synchronously at the end of [openDevice], before any stream exists, because a DAC can
     * come out of a reset at its own default — which may be 0 dB. The guard can only ever lower:
     * [applyVolumeGuard] ignores any answer above the current level. Returning null leaves the DAC
     * untouched.
     *
     * **Lock order (B16-G1).** The device's own monitor (this instance: every `@Synchronized` volume
     * method below) is the only lock on the DAC's volume, and the host's arbiter of it uses *this* monitor
     * rather than one of its own. The guard runs **under the monitor** when it is called from
     * [restoreLevelAfterReconfigure] (every rate, alt or clock change) and without it when it is called from
     * [openDevice]. So a guard must take no lock another thread can hold while it waits for this monitor;
     * entering this monitor again (the host tells its arbiter) is fine, it is re-entrant. The host's guard
     * reads published state only; see `SafetyVolumeController.hardwareOpenTarget` and `DacVolumeArbiter`.
     * The other callbacks of this class ([releaseListener], [releasedDriversListener]) run outside the monitor.
     */
    @Volatile
    var volumeGuard: ((range: UsbVolumeRange, currentUnits: Int) -> Int?)? = null

    val hasHardwareVolume: Boolean
        get() = connection != null && volumeTopology != null

    /** `UsbDevice.deviceName` of the device currently open for Direct, or null. */
    val openedDevicePath: String?
        get() = currentDevice?.deviceName

    private fun volumeWIndex(topology: UacVolumeTopology): Int? {
        val controlInterfaceId = cachedDeviceInfo?.audioControlInterfaceId ?: return null
        if (controlInterfaceId < 0) return null
        return (topology.unitId shl 8) or controlInterfaceId
    }

    /**
     * Reads the volume RANGE, intersected across every controlled channel.
     *
     * UAC2 RANGE payload: wNumSubRanges, then MIN/MAX/RES triplets of signed 16-bit 1/256 dB.
     * Only the first sub-range is used; multi-segment volume curves are rare and the outer bounds
     * of the first segment are still true limits.
     */
    @Synchronized
    fun readHardwareVolumeRange(): UsbVolumeRange? {
        val conn = connection ?: return null
        val topology = volumeTopology ?: return null
        val wIndex = volumeWIndex(topology) ?: return null
        var result: UsbVolumeRange? = null
        for (channel in topology.channels) {
            val payload = ByteArray(8)
            val ret = conn.controlTransfer(
                0xA1,
                0x02,    // RANGE
                (FU_VOLUME_CONTROL shl 8) or channel,
                wIndex,
                payload,
                payload.size,
                VOLUME_TIMEOUT_MS,
            )
            if (ret < payload.size || payload.le16u(0) < 1) {
                Log.w(TAG, "readHardwareVolumeRange: ch=$channel ret=$ret")
                return null
            }
            val range = UsbVolumeRange(payload.le16s(2), payload.le16s(4), payload.le16s(6))
            if (!range.isValid) {
                Log.w(TAG, "readHardwareVolumeRange: ch=$channel invalid $range")
                return null
            }
            result = result?.let {
                UsbVolumeRange(
                    maxOf(it.minUnits, range.minUnits),
                    minOf(it.maxUnits, range.maxUnits),
                    maxOf(it.resolutionUnits, range.resolutionUnits),
                )
            } ?: range
        }
        return result?.takeIf { it.isValid }
    }

    /**
     * Reads the current level: the loudest of the controlled channels, or null if any read fails.
     *
     * The loudest, because it is what the listener hears and what "never raise" has to be measured
     * against. A partial read is not an answer.
     */
    @Synchronized
    fun readHardwareVolume(): Int? {
        val loudest = readHardwareVolumeUnits() ?: return null
        levelGuard.onRead("read", loudest)
        // A stable read is remembered as the Direct level; a read before the open restore is what
        // the Android route left behind, often near 0 dB, and must not become the saved level. Nor
        // one between a reconfigure and its restore (AUD-126): the DAC's firmware reset, not ours.
        if (openLevelSettled) rememberDirectLevel(loudest)
        return loudest
    }

    /** The Feature Unit transfers of [readHardwareVolume], with no logging or remembering. */
    @Synchronized
    private fun readHardwareVolumeUnits(): Int? {
        val conn = connection ?: return null
        val topology = volumeTopology ?: return null
        val wIndex = volumeWIndex(topology) ?: return null
        var loudest: Int? = null
        for (channel in topology.channels) {
            val data = ByteArray(2)
            val ret = conn.controlTransfer(
                0xA1,
                0x01,    // GET_CUR
                (FU_VOLUME_CONTROL shl 8) or channel,
                wIndex,
                data,
                data.size,
                VOLUME_TIMEOUT_MS,
            )
            if (ret != data.size) {
                Log.w(TAG, "readHardwareVolume: ch=$channel ret=$ret")
                return null
            }
            val units = data.le16s(0)
            loudest = loudest?.let { maxOf(it, units) } ?: units
        }
        return loudest
    }

    /**
     * Writes [units] to every controlled channel, clamped to the DAC's own range, then reads back.
     *
     * @return the level the DAC reports afterwards, or null if any transfer failed.
     */
    @Synchronized
    fun writeHardwareVolume(units: Int, origin: String = "host"): Int? {
        if (connection == null || volumeTopology == null) return null
        val range = readHardwareVolumeRange() ?: return null
        val target = range.clamp(units)
        val readBack = writeFeatureUnit(target)
        // Every write, with its origin (AUD-126).
        levelGuard.onWrite(origin, units, target, readBack)
        // Our own write is the Direct level from now on (AUD-118).
        readBack?.let(::rememberDirectLevel)
        return readBack
    }

    /** SET_CUR of [target] on every controlled channel, then the level read back; null on failure. */
    @Synchronized
    private fun writeFeatureUnit(target: Int): Int? {
        val conn = connection ?: return null
        val topology = volumeTopology ?: return null
        val wIndex = volumeWIndex(topology) ?: return null
        val data = byteArrayOf((target and 0xFF).toByte(), ((target shr 8) and 0xFF).toByte())
        for (channel in topology.channels) {
            val ret = conn.controlTransfer(
                0x21,
                0x01,    // SET_CUR
                (FU_VOLUME_CONTROL shl 8) or channel,
                wIndex,
                data,
                data.size,
                VOLUME_TIMEOUT_MS,
            )
            if (ret != data.size) {
                Log.w(TAG, "writeHardwareVolume: ch=$channel target=$target ret=$ret")
                return null
            }
        }
        return readHardwareVolumeUnits()
    }

    /**
     * AUD-126: after every stream reconfiguration (alt, clock, rate) on the open device, before the
     * first packet. The SNOWSKY Melody's firmware puts its Feature Unit back to 0 dB on these, and the
     * claim-time restore ([restoreDirectLevelOnOpen]) never saw them ("Device already open,
     * reusing"). Same rule: the quietest of the saved Direct level and [volumeGuard]'s answer, never
     * raising. UNVERIFIED means nothing may play.
     *
     * Holds this device's monitor for the whole of it, [volumeGuard] included (B16-G1: see there for what that
     * asks of the guard).
     */
    @Synchronized
    fun restoreLevelAfterReconfigure(reason: String): OpenLevelCheck {
        if (volumeTopology == null) {
            openLevelSettled = true
            return OpenLevelCheck.NO_HARDWARE_VOLUME
        }
        val range = readHardwareVolumeRange()
        val current = readHardwareVolumeUnits()
        val guardTarget = if (range == null || current == null) null else volumeGuard?.let { guard ->
            runCatching { guard(range, current) }
                .onFailure { Log.e(TAG, "volumeGuard failed", it) }
                .getOrNull()
        }
        val port = object : FeatureUnitPort {
            override fun readRange(): UsbVolumeRange? = range
            override fun readUnits(): Int? = readHardwareVolumeUnits()
            override fun writeUnits(units: Int): Int? = range?.let { writeFeatureUnit(it.clamp(units)) }
        }
        val check = levelGuard.restoreAfterReconfigure(port, savedDirectLevel(), guardTarget, reason)
        openLevelSettled = check != OpenLevelCheck.UNVERIFIED
        return check
    }

    /**
     * Runs on every claim, before [openDevice] returns and therefore before any audio (AUD-118).
     * Restores the Feature Unit to the quietest of this DAC's saved Direct level and [volumeGuard]'s
     * ceiling. Keyed by the device being opened, never by what the host can resolve right now.
     * Never raises. If the level cannot be read or the restore does not take, [openLevelCheck] is
     * UNVERIFIED and the sink refuses Direct rather than play at an unknown level.
     */
    private fun restoreDirectLevelOnOpen() {
        openLevelSettled = false
        if (volumeTopology == null) {
            openLevelCheck = OpenLevelCheck.NO_HARDWARE_VOLUME
            openLevelSettled = true
            Log.i(TAG, "DAC opened key=$openDeviceKey: no hardware volume (fixed output)")
            return
        }
        val range = readHardwareVolumeRange()
        val current = readHardwareVolume()
        if (range == null || current == null) {
            openLevelCheck = OpenLevelCheck.UNVERIFIED
            Log.e(TAG, "DAC opened key=$openDeviceKey current=unreadable target=unknown — level UNVERIFIED")
            return
        }
        val saved = savedDirectLevel()
        // What the guard answered: the lower of the saved level and Safety's ceiling — not the ceiling
        // itself, which the host logs apart (`safetyCeiling=`). It was printed as `ceiling=`, and read
        // as the ceiling (AUD-126).
        val guardTarget = volumeGuard?.let { guard ->
            runCatching { guard(range, current) }
                .onFailure { Log.e(TAG, "volumeGuard failed", it) }
                .getOrNull()
        }
        val target = DirectOpenLevelPolicy.target(current, saved, guardTarget)?.let(range::clamp)
        Log.i(TAG, "DAC opened key=$openDeviceKey current=$current (${dbOf(current)} dB) " +
                "savedDirect=${saved ?: "none"} guardTarget=${guardTarget ?: "none"} " +
                "target=${target?.let { "$it (${dbOf(it)} dB)" } ?: "unchanged"}")
        if (target != null && target < current) {
            val readBack = writeHardwareVolume(target, "open-restore")
            if (readBack == null || readBack > target) {
                openLevelCheck = OpenLevelCheck.UNVERIFIED
                Log.e(TAG, "DAC opened key=$openDeviceKey: restore to $target failed (readBack=$readBack) — level UNVERIFIED")
                return
            }
        }
        openLevelCheck = OpenLevelCheck.VERIFIED
        openLevelSettled = true
    }

    private fun dbOf(units: Int): Float = units / UsbVolumeRange.UNITS_PER_DB.toFloat()

    private fun ByteArray.le16u(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.le16s(offset: Int): Int = le16u(offset).toShort().toInt()

    /**
     * Gives the DAC back to Android: releases both claimed interfaces, re-binds the kernel audio
     * driver the forced claim detached, then closes. [closeDevice] alone kept `snd-usb-audio`
     * detached, so the Android path had no USB output until a replug (AUD-003). No-op when nothing is
     * open. Whether the platform re-registers the output is observed by the host, not assumed here.
     */
    /**
     * Diagnostic dump for AUD-119: every Feature Unit of the AudioControl interface with its
     * `bmaControls` per channel, a GET_CUR of the volume of each channel that has one, and whether
     * the interface has an interrupt endpoint. The GET_CURs need the AudioControl interface claimed,
     * i.e. Direct active; otherwise only the descriptor part is reported. Read-only: nothing is set.
     */
    @Synchronized
    fun dumpVolumeControls(): String {
        val conn = connection
        val raw = conn?.rawDescriptors
            ?: return "DAC_VOLUME|unavailable: no open Direct connection (start Direct playback first)"
        val controlInterfaceId = cachedDeviceInfo?.audioControlInterfaceId ?: -1
        val lines = mutableListOf<String>()
        lines += "DAC_VOLUME|device=${currentDevice?.productName} key=$openDeviceKey " +
            "acInterface=$controlInterfaceId appTopology=$volumeTopology " +
            "interruptEndpoint=${UacFeatureUnitParser.audioControlInterruptEndpoint(raw)?.let { "0x" + it.toString(16) } ?: "none"}"
        for (unit in UacFeatureUnitParser.describeAll(raw)) {
            val controls = unit.controlsPerChannel.mapIndexed { ch, c -> "ch$ch=0x${c.toString(16)}" }.joinToString(",")
            lines += "DAC_VOLUME|FU id=${unit.unitId} source=${unit.sourceId} bmaControls=[$controls]"
            for (channel in unit.volumeChannels) {
                val value = if (controlInterfaceId < 0) null else {
                    val data = ByteArray(2)
                    val ret = conn.controlTransfer(
                        0xA1, 0x01, (FU_VOLUME_CONTROL shl 8) or channel,
                        (unit.unitId shl 8) or controlInterfaceId, data, data.size, VOLUME_TIMEOUT_MS,
                    )
                    if (ret == data.size) data.le16s(0) else null
                }
                lines += "DAC_VOLUME|FU id=${unit.unitId} ch=$channel GET_CUR=" +
                    (value?.let { "$it (${dbOf(it)} dB)" } ?: "failed")
            }
        }
        return lines.joinToString("\n")
    }

    /** Told after every hand-back to Android, from whichever caller made it (AUD-003). */
    @Volatile
    var releaseListener: (() -> Unit)? = null

    /** Told at the end of each hand-back which kernel driver holds (control, streaming); null = none. */
    @Volatile
    var releasedDriversListener: ((controlDriver: String?, streamingDriver: String?) -> Unit)? = null

    fun releaseToAndroid() {
        val conn = connection ?: return
        releaseListener?.invoke()
        val streaming = claimedInterface
        val control = claimedControlInterface?.takeIf { it.id != streaming?.id }
        val streamingId = streaming?.id ?: selectedStreamingInterfaceNumber
        val controlId = claimedControlInterface?.id ?: cachedDeviceInfo?.audioControlInterfaceId ?: -1
        // Streaming first, then control. Measured on the HONOR: releasing the control interface lets
        // snd-usb-audio rebind to it immediately, and if the streaming interface is still claimed at
        // that moment the card is created without a playback stream — a dead USB route (AUD-003).
        streaming?.let { conn.releaseInterface(streaming) }
        control?.let { conn.releaseInterface(control) }
        claimedInterface = null
        claimedControlInterface = null
        val fd = conn.fileDescriptor
        listOfNotNull(control, streaming).forEach { iface ->
            val ret = runCatching { UsbAudioStream.nativeReattachKernelDriverIfUnbound(fd, iface.id) }
                .getOrDefault(-1)
            Log.i(TAG, "releaseToAndroid: kernel driver iface=${iface.id} ret=$ret (1=already bound)")
        }
        val driverOf = { ifno: Int ->
            if (ifno < 0) null else runCatching { UsbAudioStream.nativeKernelDriverName(fd, ifno) }.getOrNull()
        }
        val controlDriver = driverOf(controlId)
        val streamingDriver = driverOf(streamingId)
        Log.i(TAG, "releaseToAndroid: drivers control[$controlId]=$controlDriver streaming[$streamingId]=$streamingDriver")
        closeDevice()
        releasedDriversListener?.invoke(controlDriver, streamingDriver)
    }

    fun closeDevice() {
        volumeTopology = null
        cachedDeviceInfo = null
        claimedInterface?.let { iface ->
            connection?.releaseInterface(iface)
            claimedInterface = null
        }
        claimedControlInterface?.let { iface ->
            connection?.releaseInterface(iface)
            claimedControlInterface = null
        }
        connection?.close()
        connection = null
        currentDevice = null
        selectedPlaybackAlt = null
        selectedStreamingInterfaceNumber = -1
        Log.i(TAG, "USB device closed")
    }

    /**
     * Set the sample rate on a UAC2 Clock Source entity via SET_CUR control transfer.
     *
     * Uses the Clock Source entity and AudioControl interface parsed from the
     * device's actual UAC2 descriptor graph. It never guesses entity IDs.
     *
     * UAC2 SET_CUR format:
     *   bmRequestType = 0x21 (Host-to-Device, Class, Interface)
     *   bRequest = 0x01 (SET_CUR)
     *   wValue = (CS_SAM_FREQ_CONTROL << 8) | 0 = 0x0100
     *   wIndex = (clockSourceEntityId << 8) | audioControlInterfaceNumber
     *   data = 4-byte LE sample rate
     */
    fun setSampleRate(sampleRateHz: Int): Boolean {
        val conn = connection ?: return false
        // AUD-126: the DAC may reset its Feature Unit here; reads until the restore are not Direct's level.
        openLevelSettled = false

        val data = ByteArray(4)
        data[0] = (sampleRateHz and 0xFF).toByte()
        data[1] = ((sampleRateHz shr 8) and 0xFF).toByte()
        data[2] = ((sampleRateHz shr 16) and 0xFF).toByte()
        data[3] = ((sampleRateHz shr 24) and 0xFF).toByte()

        val deviceInfo = cachedDeviceInfo
        val clockSourceId = deviceInfo?.clockSourceId ?: -1
        val controlInterfaceId = deviceInfo?.audioControlInterfaceId ?: -1
        if (clockSourceId <= 0 || controlInterfaceId < 0) {
            Log.w(TAG, "setSampleRate($sampleRateHz Hz): UAC2 Clock Source topology unavailable")
            return false
        }

        val wIndex = (clockSourceId shl 8) or controlInterfaceId
        val ret = conn.controlTransfer(
                0x21,    // bmRequestType: Host-to-Device, Class, Interface
                0x01,    // bRequest: SET_CUR
                0x0100,  // wValue: CS_SAM_FREQ_CONTROL
                wIndex,
                data,
                data.size,
                1000     // timeout ms
        )
        val complete = ret == data.size
        Log.i(TAG, "setSampleRate($sampleRateHz Hz): complete=$complete " +
                "clockSourceId=0x${clockSourceId.toString(16)} " +
                "wIndex=0x${wIndex.toString(16)} ret=$ret expected=${data.size}")
        return complete
    }

    /**
     * Read the current sample rate from the DAC via UAC2 GET_CUR.
     * This verifies whether our SET_CUR actually took effect.
     */
    fun readSampleRate(): Int {
        val conn = connection ?: return -1
        val data = ByteArray(4)

        val deviceInfo = cachedDeviceInfo
        val clockSourceId = deviceInfo?.clockSourceId ?: -1
        val controlInterfaceId = deviceInfo?.audioControlInterfaceId ?: -1
        if (clockSourceId <= 0 || controlInterfaceId < 0) {
            Log.w(TAG, "readSampleRate: UAC2 Clock Source topology unavailable")
            return -1
        }

        val wIndex = (clockSourceId shl 8) or controlInterfaceId
        val ret = conn.controlTransfer(
                0xA1,    // bmRequestType: Device-to-Host, Class, Interface
                0x01,    // bRequest: GET_CUR
                0x0100,  // wValue: CS_SAM_FREQ_CONTROL
                wIndex,
                data,
                data.size,
                1000
        )
        if (ret != data.size) {
            Log.w(TAG, "readSampleRate: incomplete GET_CUR (ret=$ret expected=${data.size})")
            return -1
        }
        val rate = (data[0].toInt() and 0xFF) or
                ((data[1].toInt() and 0xFF) shl 8) or
                ((data[2].toInt() and 0xFF) shl 16) or
                ((data[3].toInt() and 0xFF) shl 24)
        Log.i(TAG, "readSampleRate: GET_CUR clockSourceId=0x${clockSourceId.toString(16)} " +
                "returned $rate Hz (raw=${data.joinToString(",") { "0x${(it.toInt() and 0xFF).toString(16)}" }})")
        return rate
    }

    /**
     * Read the UAC2 Clock Source Frequency Control RANGE descriptor.
     *
     * The first response contains wNumSubRanges. Each frequency sub-range is
     * MIN(4 bytes), MAX(4 bytes), RES(4 bytes), all little-endian hertz.
     */
    fun readSampleRateRanges(): List<UsbSampleRateRange> {
        val conn = connection ?: return emptyList()
        val clockSourceId = cachedDeviceInfo?.clockSourceId ?: return emptyList()
        val controlInterfaceId = cachedDeviceInfo?.audioControlInterfaceId ?: return emptyList()
        if (clockSourceId <= 0 || controlInterfaceId < 0) return emptyList()

        val wIndex = (clockSourceId shl 8) or controlInterfaceId
        val countBuffer = ByteArray(2)
        val countResult = conn.controlTransfer(
            0xA1,
            0x02,    // RANGE
            0x0100,  // CS_SAM_FREQ_CONTROL
            wIndex,
            countBuffer,
            countBuffer.size,
            1000,
        )
        if (countResult < 2) {
            Log.w(TAG, "readSampleRateRanges: RANGE count failed (wIndex=0x${wIndex.toString(16)}, ret=$countResult)")
            return emptyList()
        }

        val count = (countBuffer[0].toInt() and 0xFF) or
            ((countBuffer[1].toInt() and 0xFF) shl 8)
        if (count <= 0 || count > 64) {
            Log.w(TAG, "readSampleRateRanges: invalid sub-range count=$count")
            return emptyList()
        }

        val payload = ByteArray(2 + count * 12)
        val result = conn.controlTransfer(
            0xA1,
            0x02,    // RANGE
            0x0100,  // CS_SAM_FREQ_CONTROL
            wIndex,
            payload,
            payload.size,
            1000,
        )
        if (result < payload.size) {
            Log.w(TAG, "readSampleRateRanges: RANGE payload failed (expected=${payload.size}, ret=$result)")
            return emptyList()
        }

        fun readLe32(offset: Int): Int =
            (payload[offset].toInt() and 0xFF) or
                ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                ((payload[offset + 2].toInt() and 0xFF) shl 16) or
                ((payload[offset + 3].toInt() and 0xFF) shl 24)

        val ranges = (0 until count).map { index ->
            val offset = 2 + index * 12
            UsbSampleRateRange(
                minHz = readLe32(offset),
                maxHz = readLe32(offset + 4),
                resolutionHz = readLe32(offset + 8),
            )
        }
        Log.i(TAG, "readSampleRateRanges: clockSourceId=0x${clockSourceId.toString(16)} " +
            "interface=$controlInterfaceId ranges=${ranges.joinToString()}")
        return ranges
    }

    /**
     * Read the CLOCK_VALID control from the DAC via UAC2 GET_CUR.
     * This checks whether the Clock Source entity's clock is locked and stable
     * after a sample rate change. Standard practice per UAC2 spec: verify clock after SET_CUR before proceeding.
     *
     * UAC2 spec: Clock Source descriptor, CS = 0x02 (CUR_CLOCK_VALID_CONTROL)
     * Returns: true if clock is valid, false if not or on error.
     */
    fun readClockValid(): Boolean {
        val conn = connection ?: return false
        val data = ByteArray(1)

        val deviceInfo = cachedDeviceInfo
        val clockSourceId = deviceInfo?.clockSourceId ?: -1
        val controlInterfaceId = deviceInfo?.audioControlInterfaceId ?: -1
        if (clockSourceId <= 0 || controlInterfaceId < 0) {
            Log.w(TAG, "readClockValid: UAC2 Clock Source topology unavailable")
            return false
        }

        val wIndex = (clockSourceId shl 8) or controlInterfaceId
        val ret = conn.controlTransfer(
                0xA1,    // bmRequestType: Device-to-Host, Class, Interface
                0x01,    // bRequest: GET_CUR
                0x0200,  // wValue: CS=0x02 (CLOCK_VALID_CONTROL), CN=0x00
                wIndex,
                data,
                data.size,
                1000
        )
        if (ret != data.size) {
            Log.w(TAG, "readClockValid: incomplete GET_CUR (ret=$ret expected=${data.size})")
            return false
        }
        val valid = data[0].toInt() and 0x01
        Log.i(TAG, "readClockValid: clockSourceId=0x${clockSourceId.toString(16)} valid=$valid")
        return valid == 1
    }

    /**
     * Set the alternate setting on the streaming interface via Java API.
     * This may properly allocate USB bandwidth, which the native ioctl might not.
     */
    fun setAltSetting(altSetting: Int): Boolean {
        val conn = connection ?: return false
        val device = currentDevice ?: return false
        // AUD-126: as for the clock — the level is settled again by restoreLevelAfterReconfigure.
        openLevelSettled = false

        // Find the UsbInterface with the matching alt setting
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // The selected playback interface only: a capture interface can share alt numbers.
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2 &&
                iface.id == selectedStreamingInterfaceNumber &&
                iface.alternateSetting == altSetting) {
                val result = conn.setInterface(iface)
                Log.i(TAG, "setAltSetting($altSetting) via Java API: $result " +
                        "(iface id=${iface.id}, endpoints=${iface.endpointCount})")
                return result
            }
        }

        Log.w(TAG, "setAltSetting($altSetting): no matching UsbInterface found, " +
                "trying all AudioStreaming interfaces...")

        // Fallback: try any AudioStreaming interface with matching alt
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2) {
                Log.d(TAG, "  interface $i: id=${iface.id} alt=${iface.alternateSetting} " +
                        "endpoints=${iface.endpointCount}")
            }
        }

        return false
    }

}
