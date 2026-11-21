package com.decent.usbaudio

import java.io.File

/** Automatic technical evidence. No media names, paths, serials or PCM enter its records. */
object UsbDiagnosticJournal {
    const val DIRECTORY = "usb-journal"

    enum class Control(val code: Int) {
        VOLUME_READ(7), VOLUME_WRITE_BEGIN(8), VOLUME_WRITE(9), VOLUME_DROPPED(10),
        HID_BEGIN(11), HID_END(12),
    }

    /** A fixed vocabulary keeps device names, paths and caller-provided strings off disk. */
    fun control(connection: Long, generation: Long, operation: Control, origin: String,
                target: Int? = null, result: Int? = null, elapsedMs: Long = 0L) {
        val source = when (origin) {
            "key" -> 1
            "remote" -> 2
            "ui" -> 3
            "clamp" -> 4
            "ramp" -> 5
            "duck" -> 6
            "pristine" -> 7
            "reconcile" -> 8
            "open-restore" -> 9
            "hid" -> 10
            "debug-hid" -> 11
            else -> 0
        }
        runCatching {
            UsbAudioStream.nativeUsbJournalPlatform(longArrayOf(connection, generation, source.toLong(),
                (target ?: Int.MIN_VALUE).toLong(), (result ?: Int.MIN_VALUE).toLong(), elapsedMs), operation.code)
        }
    }

    fun configure(filesDirectory: File): Boolean = runCatching {
        UsbAudioStream.nativeConfigureUsbJournal(File(filesDirectory, DIRECTORY).absolutePath)
    }.getOrDefault(false)

    fun export(filesDirectory: File): String = runCatching {
        configure(filesDirectory)
        UsbAudioStream.nativeExportUsbJournal()
    }.getOrDefault("USB JOURNAL unavailable\n")

    /** Volume states are 1 verified, 0 unverified, -1 no hardware volume. Unknown level is MIN_VALUE. */
    fun platform(connection: Long, generation: Long, rate: Int, bits: Int, alt: Int,
                 level: Int, verified: Int, stream: Long = 0L, reason: Int, channels: Int = 0) {
        runCatching {
            UsbAudioStream.nativeUsbJournalPlatform(longArrayOf(connection, generation, rate.toLong(),
                bits.toLong(), alt.toLong(), level.toLong(), verified.toLong(), stream, channels.toLong()), reason)
        }
    }
}
