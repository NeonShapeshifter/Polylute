package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B40-F9c: the two permissions Google Play questions, gone; and every permission that is left is named, with its reason, in the privacy page.
 *
 * `ACCESS_FINE_LOCATION` (maxSdk 30) was checked, never asked for at run time, and used for one thing: the name of the Wi-Fi network on
 * Android 11 and older. `SCHEDULE_EXACT_ALARM` served the sleep timer, which already fell back to an inexact alarm whenever it was denied.
 */
class B40PermissionsBoundaryTest {
    private val project: File = listOf(File("."), File(".."), File("../.."))
        .map { it.canonicalFile }.first { File(it, "app/src/main").isDirectory || File(it, "src/main").isDirectory }
        .let { if (File(it, "app/src/main").isDirectory) it else it.parentFile }

    private val manifest = File(project, "app/src/main/AndroidManifest.xml").readText()
    private val privacy = File(project, "PRIVACY.md").readText()
    private val sources = File(project, "app/src/main/java")

    private fun permissions() = Regex("""<uses-permission[^>]*?android:name="android\.permission\.(\w+)"""", RegexOption.DOT_MATCHES_ALL)
        .findAll(manifest).map { it.groupValues[1] }.toList()

    @Test
    fun `the manifest asks for neither location nor an exact alarm`() {
        val declared = permissions()
        assertThat(declared).isNotEmpty()
        assertThat(declared).containsNoneOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION")
        assertThat(declared).containsNoneOf("SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM")
        assertThat(declared).containsNoneOf("ACCESS_BACKGROUND_LOCATION", "ACCESS_MEDIA_LOCATION")
    }

    @Test
    fun `no code looks at a location permission or asks for an exact alarm`() {
        val offenders = sources.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            val code = f.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            code.filter { Regex("""ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|SCHEDULE_EXACT_ALARM|USE_EXACT_ALARM|canScheduleExactAlarms|setExactAndAllowWhileIdle|\.setExact\(|setAlarmClock""").containsMatchIn(it) }
                .map { "${f.name}: ${it.trim()}" }
        }.toList()
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the Wi-Fi name is gone, so nothing reads the network's name`() {
        val offenders = sources.walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readText().let { "wifiName" in it || "readConnectedWifiSsid" in it || ".connectionInfo" in it } }.map { it.name }.toList()
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `every permission the manifest declares is named in the privacy page with a reason`() {
        val section = privacy.substringAfter("## Permissions")
        for (permission in permissions()) assertThat(section).contains("`$permission`")
        assertThat(section).contains("no location and for no exact alarm")
    }

    @Test
    fun `the privacy page names nothing the manifest does not declare`() {
        val section = privacy.substringAfter("## Permissions")
        val named = Regex("""`([A-Z_]{6,})`""").findAll(section).map { it.groupValues[1] }.toSet()
        assertThat(permissions()).containsAtLeastElementsIn(named.toList())
    }

    @Test
    fun `the sleep timer's alarm is the inexact one and both places that set it use it`() {
        val alarm = File(sources, "com/lostf1sh/pixelplayeross/data/service/SleepTimerAlarm.kt").readText()
        assertThat(alarm).contains("setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP")
        val holder = File(sources, "com/lostf1sh/pixelplayeross/presentation/viewmodel/SleepTimerStateHolder.kt").readText()
        assertThat(holder).contains("SleepTimerAlarm.schedule(")
        assertThat(holder).contains("SleepTimerAlarm.cancel(")
        assertThat(holder).doesNotContain("alarmManager")
        val service = File(sources, "com/lostf1sh/pixelplayeross/data/service/PlaybackTimerController.kt").readText()
        assertThat(service).contains("SleepTimerAlarm.schedule(context, triggerAtMillis)")
        assertThat(service).doesNotContain("AlarmManager.")
    }
}
