package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-026: the descriptor's serial is readable only once permission is granted. The engine's own
 * permission path (the one Lock Task uses) went straight to `onUsbAudioDeviceReady` without a
 * re-scan, so a DAC whose profile was promoted to its serial stayed unidentified: no Safety context,
 * no headphone association, no open guard. `MainActivity`'s attach path already re-scans; this pins
 * the engine path to the same order.
 *
 * Source-level because the engine cannot be constructed on the JVM (ExoPlayer, UsbManager).
 */
class UsbPermissionGrantBoundaryTest {

    private val engine = File(
        "src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt"
    ).readText()

    @Test
    fun `a granted permission re-scans the DAC descriptors before announcing the device ready`() {
        val body = engine.substringAfter("private fun requestUsbPermissionOnce(")
            .substringBefore("fun setUsbPermissionRequester(")

        assertThat(body).containsMatch(
            """if \(granted\)\s*\{(?:\s*//[^\n]*)*\s*dacProfiles\.reconcile\(\)\s*onUsbAudioDeviceReady\(\)"""
        )
    }
}
