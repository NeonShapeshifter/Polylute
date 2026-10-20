package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * F-07: the volume authority is Android-bound (AudioManager, the DAC, a claimed USB device), so the
 * wiring is asserted at source level, as the rest of this area is. The rules are in
 * `SafetyMaxRemoteRefusalTest`.
 */
class SafetyMaxRefusalBoundaryTest {

    private val java = "java/com/lostf1sh/pixelplayeross"
    private fun source(relative: String): String =
        File("src/main", "$java/$relative").readText().replace("\r\n", "\n")

    private val controller = source("data/volume/SafetyVolumeController.kt")

    @Test
    fun `the remote channel reports a refused raise and returns before writing`() {
        val setLevel = controller.substringAfter("override fun setLevel(level: Int) {").substringBefore("\n        }\n    }")
        val check = setLevel.indexOf("SafetyVolumePolicy.raiseRefusedByCeiling(")
        assertThat(check).isAtLeast(0)
        assertThat(setLevel).contains("announceRefusal()")
        // It must come before the write that would otherwise be a no-op at the ceiling.
        assertThat(check).isLessThan(setLevel.indexOf("setRawLevel(requested, origin = \"remote\")"))
        // The ceiling still applies to whatever remains.
        assertThat(setLevel).contains("setRawLevel(requested, origin = \"remote\")")
    }

    @Test
    fun `no path lifts the ceiling except the explicit override`() {
        // `temporaryOverride` becomes true in exactly one place: the confirmed gesture.
        assertThat(Regex("""temporaryOverride = true""").findAll(controller).count()).isEqualTo(1)
        assertThat(controller.substringAfter("fun allowTemporaryOverride()").substringBefore("\n    }\n"))
            .contains("temporaryOverride = true")
        // The remote lambda never reads or sets the override flag.
        val setLevel = controller.substringAfter("override fun setLevel(level: Int) {").substringBefore("\n        }\n    }")
        assertThat(setLevel).doesNotContain("allowTemporaryOverride")
        assertThat(setLevel).doesNotContain("temporaryOverride = true")
    }

    @Test
    fun `the platform volume being put back over the ceiling is reported too`() {
        val clamp = controller.substringAfter("if (clamp != null && backend.controllable) {").substringBefore("setBackendRaw(manager, backend, clamp, \"clamp\")")
        assertThat(clamp).contains("if (externalVolumeChange && selfWriteRaw != backend.rawLevel) announceRefusal()")
    }

    @Test
    fun `a refusal is rate limited so a burst of presses is one notice`() {
        assertThat(controller).contains("now - lastRefusalAtMs < REFUSAL_MIN_INTERVAL_MS")
        assertThat(SafetyMaxRefusalBoundaryConstants.minInterval(controller)).isAtLeast(1_000L)
    }

    @Test
    fun `the service turns a refusal into the lock-screen notice`() {
        val service = source("data/service/MusicService.kt")
        assertThat(service).contains("safetyVolumeController.get().refusals.collect { safetyRefusalNotifier.post() }")
        val notifier = source("data/volume/SafetyMaxRefusalNotifier.kt")
        assertThat(notifier).contains("R.string.safety_max_refused_notice")
        assertThat(notifier).contains("NotificationCompat.VISIBILITY_PUBLIC")
        assertThat(notifier).contains("NotificationManager.IMPORTANCE_LOW")
        // The notice names the limit; it must never touch the volume.
        assertThat(notifier).doesNotContain("setRawLevel")
        assertThat(notifier).doesNotContain("allowTemporaryOverride")
    }

    @Test
    fun `the notice says what the owner asked for, in both languages`() {
        val res = File("src/main/res")
        assertThat(File(res, "values/strings_safety_volume.xml").readText())
            .contains("<string name=\"safety_max_refused_notice\">Safety Max · unlock to go higher</string>")
        assertThat(File(res, "values-es/strings_safety_volume.xml").readText())
            .contains("<string name=\"safety_max_refused_notice\">Safety Max · desbloquea para subir más</string>")
    }
}

private object SafetyMaxRefusalBoundaryConstants {
    fun minInterval(controller: String): Long =
        Regex("""REFUSAL_MIN_INTERVAL_MS = ([\d_]+)L""").find(controller)!!.groupValues[1].replace("_", "").toLong()
}
