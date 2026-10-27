package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-003 / AUD-030 / AUD-031 / AUD-101, at the activity boundary.
 *
 * - The attach handler used `UsbAudioPermissionHelper`, which force-claimed the DAC the moment it was
 *   plugged in — detaching `snd-usb-audio` while the route was still Android — and ran a second,
 *   independent permission request beside the engine's. It now only refreshes the engine, which owns
 *   the one request path (`UsbPermissionCoordinator`) and claims only when Direct is chosen.
 * - The requester captured the activity and was never removed; it is now removed in `onDestroy` (only
 *   if it is still this activity's), and refuses unless the activity is resumed, so Lock Task is never
 *   suspended from the background or with the screen off. `onResume` retries a refused request.
 *
 * Source-level because the activity cannot be constructed on the JVM.
 */
class UsbPermissionOwnershipBoundaryTest {

    private val activity = File("src/main/java/com/lostf1sh/pixelplayeross/MainActivity.kt").readText()

    private fun body(signature: String): String {
        val start = activity.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val open = activity.indexOf('{', start)
        var depth = 0
        for (i in open until activity.length) {
            when (activity[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return activity.substring(open, i + 1)
            }
        }
        error("unbalanced $signature")
    }

    @Test
    fun `the attach handler neither claims the DAC nor asks for permission itself`() {
        val attach = body("private fun handleUsbAudioAttachIntent(")
        assertThat(attach).doesNotContain("UsbAudioPermissionHelper")
        assertThat(attach).doesNotContain("openDevice")
        assertThat(attach).contains("dualPlayerEngine.refreshUsbAudioDiagnostics()")
    }

    @Test
    fun `the requester only admits a resumed activity`() {
        assertThat(body("private fun installUsbPermissionRequester(")).contains("UsbPermissionCoordinator.requesterAdmits(")
    }

    @Test
    fun `the requester is removed on destroy and a refused request is retried on resume`() {
        assertThat(body("override fun onDestroy(")).contains("clearUsbPermissionRequester")
        assertThat(body("override fun onResume(")).contains("dualPlayerEngine.refreshUsbAudioDiagnostics()")
    }

    @Test
    fun `B40-P2 - a Dedicated device shows the dialog through the System page's lift, and always puts the containment back`() {
        val requester = body("private fun installUsbPermissionRequester(")
        // Launcher, Pinned and App mode keep today's path: the decision is still made from the mode and what Android reports
        assertThat(requester).contains("DapContainmentPolicy.needsLockTaskSuspensionForSystemDialog(")
        assertThat(requester).contains("showUsbPermissionDialogThroughLiftedLockTask(request)")
        val flow = body("private fun showUsbPermissionDialogThroughLiftedLockTask(")
        assertThat(flow).contains("lift = ::releaseLockTaskForMaintenance")           // the System page's two-step lift
        assertThat(flow).contains("returnToDedicatedModeNow()")                        // the Maintenance exit: also restores the allowlist
        assertThat(flow).contains("timeoutMs = UsbPermissionCoordinator.DEFAULT_TIMEOUT_MS")
        // answered (the engine tells us), dismissed (back in front after being behind the dialog), or the bound
        assertThat(requester).contains("DapSystemDialogLift.Outcome.ANSWERED")
        assertThat(body("override fun onResume(")).contains("DapSystemDialogLift.Outcome.DISMISSED")
        assertThat(body("override fun onPause(")).contains("usbDialogFlow?.pausedSinceShown = true")
    }
}
