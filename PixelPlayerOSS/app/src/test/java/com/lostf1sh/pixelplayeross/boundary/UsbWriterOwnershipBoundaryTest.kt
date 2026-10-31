package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/** The surviving writer handle cannot escape its owner or race a diagnostic read against release. */
class UsbWriterOwnershipBoundaryTest {
    private val stream = File("../../decent-player/libs/decent-usb-audio-driver/src/main/kotlin/com/decent/usbaudio/UsbAudioStream.kt")
        .readText().replace("\r\n", "\n")

    @Test
    fun `the writer handle is private and cross-thread reads are fenced from release`() {
        assertThat(stream).contains("@Volatile\n    private var nativeHandle: Long = 0L")
        val diagnostics = stream.substringAfter("val urbDiag: String").substringBefore("\n    }")
        assertThat(diagnostics).contains("synchronized(handleLock)")
        val release = stream.substringAfter("fun release(").substringBefore("\n    }")
        assertThat(release).contains("synchronized(handleLock)")
    }
}
