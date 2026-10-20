package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * FIX-5: the Android-bound half of asking for the override with the screen off. The rules are in
 * `ScreenOffOverridePolicyTest`; the wiring (the media session's remote volume, the activity's
 * window flags, the one timed wake) is asserted at source level.
 */
class ScreenOffOverrideBoundaryTest {

    private val java = "java/com/lostf1sh/pixelplayeross"
    private fun source(relative: String): String =
        File("src/main", "$java/$relative").readText().replace("\r\n", "\n")

    private fun resource(relative: String): String =
        File("src/main", "res/$relative").readText().replace("\r\n", "\n")

    private val controller = source("data/volume/SafetyVolumeController.kt")
    private val remote = controller.substringAfter("override fun setLevel(level: Int) {").substringBefore("\n        }\n    }")

    @Test
    fun `a refused remote press is counted after it is reported and before the function returns, and nothing is written`() {
        val refused = remote.substringAfter("if (SafetyVolumePolicy.raiseRefusedByCeiling(").substringBefore("// AUD-126")
        assertThat(refused.indexOf("announceRefusal()")).isAtLeast(0)
        assertThat(refused.indexOf("noteRefusedRemotePress(singleStep = level == current.backend.rawLevel + 1)"))
            .isGreaterThan(refused.indexOf("announceRefusal()"))
        assertThat(refused.indexOf("return")).isGreaterThan(refused.indexOf("noteRefusedRemotePress("))
        assertThat(refused).doesNotContain("setRawLevel")
        assertThat(refused).doesNotContain("allowTemporaryOverride")
    }

    @Test
    fun `asking never writes a volume and never grants`() {
        val asking = controller.substringAfter("private fun noteRefusedRemotePress(").substringBefore("fun attachConfirmationHost(")
        for (forbidden in listOf("setRawLevel", "setBackendRaw", "allowTemporaryOverride", "temporaryOverride = true", "writeHardwareVolume")) {
            assertThat(asking).doesNotContain(forbidden)
        }
        // The override is still granted in one place only, and only by the confirmed gesture.
        assertThat(Regex("""temporaryOverride = true""").findAll(controller).count()).isEqualTo(1)
        assertThat(Regex("""SafetyOverridePolicy\.grant\(""").findAll(controller).count()).isEqualTo(1)
    }

    @Test
    fun `the confirmation is raised from exactly four places, two of them the new ones`() {
        // The in-app third blocked press, the platform volume clamped back three times (both existing),
        // the screen-off request, and the unlock that follows one.
        assertThat(Regex("""showOverrideConfirmation = true""").findAll(controller).count()).isEqualTo(4)
    }

    @Test
    fun `only the dedicated appliance turns the screen on, and it is read from the device and not from the activity`() {
        val request = controller.substringAfter("private fun requestOverrideConfirmation(").substringBefore("private fun openOverLockConfirmation()")
        // The mode is asked of the device (Device Owner + HOME + Lock Task), never inferred from a
        // resumed activity: with the screen off there is none (FIX-5b).
        assertThat(request).contains("dedicated = runCatching { productMode.isDedicatedActive() }.getOrDefault(false)")
        assertThat(request).contains("hostResumed = confirmationHost != null")
        assertThat(request).contains("ScreenOffOverridePolicy.Delivery.WAKE_THEN_SHOW -> wakeAndWaitForActivity(nowMs)")
        assertThat(request).contains("pendingRequest = PendingOverrideRequest(delivery, nowMs)")
        assertThat(controller).contains("ProductModeController(context)")
        assertThat(source("data/volume/OverrideConfirmationHost.kt")).doesNotContain("dedicated")
        // No activity is started from the background (a device owner is not exempt from that), and
        // no full-screen intent (calls and alarms only).
        for (text in listOf(controller, source("data/volume/OverrideScreenWaker.kt"), source("data/volume/SafetyMaxRefusalNotifier.kt"))) {
            assertThat(text).doesNotContain("startActivity(")
            assertThat(text).doesNotContain("setFullScreenIntent")
            assertThat(text).doesNotContain("fullScreenIntent")
        }
        assertThat(File("src/main/AndroidManifest.xml").readText()).doesNotContain("USE_FULL_SCREEN_INTENT")
    }

    @Test
    fun `the wake is requested after the request is recorded, and dropped if the activity never comes`() {
        val wait = controller.substringAfter("private fun wakeAndWaitForActivity(").substringBefore("private fun openOverLockConfirmation()")
        // Recorded first: the activity can resume the instant the screen comes on, and must find it.
        assertThat(wait.indexOf("pendingRequest = PendingOverrideRequest(ScreenOffOverridePolicy.Delivery.WAKE_THEN_SHOW, nowMs)"))
            .isAtLeast(0)
        assertThat(wait.indexOf("pendingRequest = PendingOverrideRequest(")).isLessThan(wait.indexOf("overrideScreenWaker.wake("))
        assertThat(wait).contains("overrideScreenWaker.wake(ScreenOffOverridePolicy.WAKE_GRACE_MS + WAKE_MARGIN_MS)")
        // Nothing is started from the background: the activity is waited for.
        assertThat(wait).doesNotContain("startActivity")
        // If it never comes the pending request goes and so does the wake.
        assertThat(wait).contains("delay(ScreenOffOverridePolicy.WAKE_GRACE_MS)")
        assertThat(wait).contains("pendingRequest = null")
        assertThat(wait).contains("overrideScreenWaker.release()")
    }

    @Test
    fun `the activity answers the pending request when it resumes, and a pause or a flapping resume does not lose it`() {
        val attach = controller.substringAfter("fun attachConfirmationHost(").substringBefore("fun detachConfirmationHost(")
        assertThat(attach).contains("ScreenOffOverridePolicy.showWhenAppComesUp(")
        assertThat(attach).contains("show && pending.delivery == ScreenOffOverridePolicy.Delivery.WAKE_THEN_SHOW -> openOverLockConfirmation()")
        // A confirmation that is open gets its window flags back on a re-resume instead of being closed.
        assertThat(attach.substringBefore("val pending")).contains("if (overLockActive) {")
        assertThat(attach.substringBefore("val pending")).contains("host.setOverLockScreen(true)")
        // The flags follow the controller's truth, never a leftover from before.
        assertThat(attach).contains("host.setOverLockScreen(false)")
        // Too late or no longer the case: the screen woken for nothing is let go.
        assertThat(attach).contains("overrideScreenWaker.release()")
        // Leaving the front does not cancel an open confirmation by itself: its own timeout closes it.
        val detach = controller.substringAfter("fun detachConfirmationHost(").substringBefore("\n    }\n")
        assertThat(detach).doesNotContain("cancelOverrideDialog")
        // And the activity puts its flags back every time it pauses.
        val pause = source("MainActivity.kt").substringAfter("override fun onPause() {").substringBefore("super.onPause()")
        assertThat(pause).contains("overrideConfirmationHost.setOverLockScreen(false)")
    }

    @Test
    fun `the confirmation closes by itself and puts the window and the wake back`() {
        val open = controller.substringAfter("private fun openOverLockConfirmation()").substringBefore("/** Puts back what")
        assertThat(open).contains("overrideScreenWaker.wake(ScreenOffOverridePolicy.CONFIRM_TIMEOUT_MS + WAKE_MARGIN_MS)")
        assertThat(open).contains("delay(ScreenOffOverridePolicy.CONFIRM_TIMEOUT_MS)")
        assertThat(open).contains("cancelOverrideDialog()")
        val close = controller.substringAfter("private fun closeOverLock()").substringBefore("fun attachConfirmationHost(")
        assertThat(close).contains("overrideScreenWaker.release()")
        assertThat(close).contains("confirmationHost?.setOverLockScreen(false)")
        // Every way out goes through it: cancel, accept, Safety Max switched off, the activity leaving.
        val cancel = controller.substringAfter("fun cancelOverrideDialog() {").substringBefore("\n    }\n")
        assertThat(cancel).contains("closeOverLock()")
        val allow = controller.substringAfter("fun allowTemporaryOverride() {").substringBefore("\n    }\n")
        assertThat(allow).contains("closeOverLock()")
        val toggle = controller.substringAfter("fun setSafetyEnabled(enabled: Boolean) {").substringBefore("\n    }\n")
        assertThat(toggle).contains("closeOverLock()")
    }

    @Test
    fun `a lowered level or another output breaks the sequence`() {
        assertThat(controller.substringAfter("if (leftCeiling || volumeLowered) {").substringBefore("}\n        val ceilingRaw"))
            .contains("screenOffGesture.reset()")
        assertThat(controller.substringAfter("if (safetyOverrideContextChanged(").substringBefore("lastProfileId = selected?.profileId"))
            .contains("screenOffGesture.reset()")
        assertThat(controller.substringAfter("fun setRawLevel(requested: Int, origin: String = \"ui\") {").substringBefore("\n    }\n"))
            // B27-A3: the lowering that breaks the sequence is the one the DAC reported, not the one that was asked for.
            .contains("if (published < current.backend.rawLevel) screenOffGesture.reset()")
    }

    @Test
    fun `the screen has to be off or locked, and the request a single step`() {
        assertThat(controller).contains("ScreenOffOverridePolicy.countsAsPress(singleStep, screenIsOffOrLocked())")
        val state = controller.substringAfter("private fun screenIsOffOrLocked(): Boolean {").substringBefore("\n    }\n")
        assertThat(state).contains("power?.isInteractive == false")
        assertThat(state).contains("keyguard?.isKeyguardLocked == true")
    }

    @Test
    fun `the activity is the host only while it is resumed and flags the window only on request`() {
        val activity = source("MainActivity.kt")
        assertThat(activity.substringAfter("override fun onResume() {").substringBefore("override fun onPause()"))
            .contains("safetyVolumeController.attachConfirmationHost(overrideConfirmationHost)")
        assertThat(activity.substringAfter("override fun onPause() {").substringBefore("}\n"))
            .contains("safetyVolumeController.detachConfirmationHost(overrideConfirmationHost)")
        assertThat(activity.substringAfter("override fun onDestroy() {").substringBefore("super.onDestroy()"))
            .contains("detachConfirmationHost(overrideConfirmationHost)")
        val host = activity.substringAfter("private val overrideConfirmationHost = object : OverrideConfirmationHost {").substringBefore("\n    }\n")
        assertThat(host).contains("setShowWhenLocked(enabled)")
        assertThat(host).contains("setTurnScreenOn(enabled)")
        // The flags are not set anywhere else: they are not a permanent property of the window.
        assertThat(Regex("""setShowWhenLocked\(""").findAll(activity).count()).isEqualTo(1)
        assertThat(Regex("""setTurnScreenOn\(""").findAll(activity).count()).isEqualTo(1)
    }

    @Test
    fun `from a locked screen only the temporary override or cancel is offered, never disabling Safety Max`() {
        val dialog = source("MainActivity.kt").substringAfter("if (safetyVolumeState.showOverrideConfirmation) {").substringBefore("\n                    }\n                }\n                }")
        assertThat(dialog).contains("if (!safetyVolumeState.overrideConfirmationOverLock) {")
        assertThat(dialog.substringAfter("if (!safetyVolumeState.overrideConfirmationOverLock) {").substringBefore("}"))
            .contains("setSafetyEnabled(false)")
        // The accept button is the same one in both cases, and the only one that grants.
        assertThat(dialog).contains("onClick = safetyVolumeController::allowTemporaryOverride")
        assertThat(dialog).contains("R.string.safety_max_override_closes_soon")
    }

    @Test
    fun `the one wake lock is timed and lives in one file`() {
        val waker = source("data/volume/OverrideScreenWaker.kt")
        assertThat(waker).contains("PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP")
        assertThat(waker).contains("acquire(timeoutMs)")
        assertThat(waker).contains("fun release()")
        assertThat(File("src/main/AndroidManifest.xml").readText()).contains("android.permission.WAKE_LOCK")
    }

    @Test
    fun `the new texts exist in both languages with the seconds as a placeholder`() {
        for (file in listOf("values/strings_safety_volume.xml", "values-es/strings_safety_volume.xml")) {
            val strings = resource(file)
            assertThat(Regex("""name="safety_max_override_closes_soon">[^<]*%1\${'$'}d""").containsMatchIn(strings)).isTrue()
        }
    }
}
