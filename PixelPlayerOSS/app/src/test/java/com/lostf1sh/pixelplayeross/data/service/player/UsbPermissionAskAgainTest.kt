package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B39-A8: on the S22 Polylute played through the Android route because the Melody's USB permission had never been accepted, and nothing
 * said so. The route already knew ([AudioRouteReason.USB_PERMISSION_REQUIRED]); the engine asked once per attachment and remembered a denial,
 * and no screen named it. Now the Audio page does, and can ask again.
 */
class UsbPermissionAskAgainTest {
    private fun status(present: Boolean?, granted: Boolean?) =
        AudioStatus(route = AudioRouteState(usbDacPresent = present, permissionGranted = granted))

    @Test
    fun `a DAC that is there and not permitted is a pending permission`() {
        assertThat(status(present = true, granted = false).usbPermissionPending).isTrue()
    }

    @Test
    fun `permitted, absent or not yet read are not`() {
        assertThat(status(present = true, granted = true).usbPermissionPending).isFalse()
        assertThat(status(present = false, granted = false).usbPermissionPending).isFalse()
        assertThat(status(present = true, granted = null).usbPermissionPending).isFalse()
        assertThat(status(present = null, granted = null).usbPermissionPending).isFalse()
    }

    @Test
    fun `asking again forgets the denial, drops a request that showed nothing, and asks through the activity that can show it`() {
        val engine = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt").readText().replace("\r\n", "\n")
        val ask = engine.substringAfter("fun askForUsbPermissionAgain() {").substringBefore("/** Removes [requester] if it is still the installed one")
        assertThat(ask).contains("permissionCoordinator.forget(device.deviceName)")
        assertThat(ask).contains("pendingPermissionCancels.remove(device.deviceName)?.invoke()")
        assertThat(ask).contains("requestUsbPermissionOnce(usb, device)")
        // an already allowed DAC is not asked about again
        assertThat(ask).contains("if (usb.hasPermission(device))")
    }
}
