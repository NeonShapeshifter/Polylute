package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B32-Q6: Maintenance without a PIN, and the System page that took its recovery half. The Lock Task and Device Owner calls are
 * Android-bound, so the activity's wiring is asserted at source level (as `MaintenanceReachabilityBoundaryTest` does for the entry);
 * the order of the lift is in `DapSystemSurfaceCoordinatorTest`, and the page and its actions are in `B32SystemPageTest`.
 */
class B32MaintenanceBoundaryTest {

    private val java = "java/com/lostf1sh/pixelplayeross"
    private fun source(relative: String): String =
        File("src/main", "$java/$relative").readText().replace("\r\n", "\n")

    private fun strings(language: String): List<Pair<String, String>> {
        val dir = File("src/main/res", language)
        val pairs = mutableListOf<Pair<String, String>>()
        dir.listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }!!.forEach { file ->
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText()).forEach { pairs += it.groupValues[1] to it.groupValues[2] }
        }
        return pairs
    }

    private val languages = listOf("values", "values-es", "values-sv")

    private val activity = source("MainActivity.kt")

    // --- no PIN anywhere ------------------------------------------------------------------------------------------------------

    @Test
    fun `no code reads, sets or asks for a maintenance PIN`() {
        val offenders = File("src/main/$java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val code = file.readText().lines().joinToString("\n") { it.substringBefore("//") }
                listOf("MaintenancePin", "MaintenanceEntryCounter", "MaintenanceTapResult", "authorizeEntry", "canDisplayRoute", "ENTRY_AUTHORIZED")
                    .any { code.contains(it) }
            }.map { it.name }.toList()
        assertThat(offenders).isEmpty()
        assertThat(File("src/main/$java/data/security/MaintenancePinStore.kt").exists()).isFalse()
        assertThat(File("src/main/$java/presentation/maintenance/MaintenanceEntryCounter.kt").exists()).isFalse()
    }

    @Test
    fun `no string asks for a PIN in any language`() {
        languages.forEach { language ->
            val names = strings(language).map { it.first }
            listOf(
                "maintenance_setup_title", "maintenance_setup_body", "maintenance_new_pin", "maintenance_confirm_pin", "maintenance_set_pin",
                "maintenance_pin", "maintenance_unlock_title", "maintenance_unlock", "maintenance_locked_seconds", "maintenance_incorrect_pin",
                "maintenance_security_unavailable", "maintenance_security_section", "maintenance_change_pin", "maintenance_pin_changed",
                "maintenance_taps_remaining",
            ).forEach { assertThat(names).doesNotContain(it) }
        }
    }

    @Test
    fun `the Maintenance route opens with nothing in front of it`() {
        val navigation = source("presentation/navigation/AppNavigation.kt")
        val route = navigation.substringAfter("Screen.Maintenance.route,").substringBefore("Screen.KnownDacs.route")
        assertThat(route).contains("MaintenanceScreen(")
        // it used to be "if the session says the entry was authorized, else pop"
        assertThat(route).doesNotContain("DapMaintenanceSession")
        assertThat(route).doesNotContain("if (")
        // and only the System page's wiring navigates to it
        val navigates = File("src/main/$java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("Screen.Maintenance.route") }.map { it.name }.toList()
        assertThat(navigates).containsExactly("SettingsActionsWiring.kt", "AppNavigation.kt", "DapSurfacePolicy.kt")
    }

    @Test
    fun `Maintenance lifts nothing and does nothing to the device or the library`() {
        val screen = source("presentation/screens/MaintenanceScreen.kt").lines().joinToString("\n") { it.substringBefore("//") }
        listOf(
            "enterAuthenticatedMaintenance", "exitAuthenticatedMaintenance", "launchAuthenticatedMaintenanceSystemFlow", "prepareHonorHomeRecovery",
            "openSystemSurface", "restoreHonorLauncher", "ACTION_SETTINGS", "ACTION_WIFI_SETTINGS", "ACTION_BLUETOOTH_SETTINGS",
            "rebuildDatabase", "resetAllLyrics", "purgeDormantItems", "applyPersistentHome",
        ).forEach { assertThat(screen).doesNotContain(it) }
    }

    // --- the lift and the restore: the Maintenance sequence, reached from the System page ------------------------------------------

    @Test
    fun `the activity opens a system surface through the coordinator with Maintenance's own lift and exit`() {
        val open = activity.substringAfter("override fun openSystemSurface(").substringBefore("override fun restoreHonorLauncher(")
        assertThat(open).contains("openThroughLiftedLockTask(onResult = onResult)")
        // B32-fix1 R2: the restore obligation is written into the window with the state change, not kept on the activity
        assertThat(open).contains("launchAuthenticatedMaintenanceSystemFlow(intent, restoreOnReturn = true)")

        val lifted = activity.substringAfter("private fun openThroughLiftedLockTask(").substringBefore("private fun scheduleMaintenanceExpiry()")
        assertThat(lifted).contains("DapSystemSurfaceCoordinator(")
        assertThat(lifted).contains("DapMaintenanceSession.begin()")
        assertThat(lifted).contains("liftLockTask = ::releaseLockTaskForMaintenance")
        assertThat(lifted).contains("putContainmentBack = ::exitAuthenticatedMaintenanceNow")
        // a lift that failed puts the containment back, as a failed Maintenance entry did
        assertThat(lifted).contains("restoreDedicatedModeAfterTransitionFailure()")
    }

    @Test
    fun `the HOME recovery lifts first and the policy it clears is never put back by the return`() {
        val restore = activity.substringAfter("override fun restoreHonorLauncher(").substringBefore("private fun openThroughLiftedLockTask(")
        // no external flow, no restore obligation: the window the recovery opened ends with the policy (prepareHonorHomeRecovery)
        assertThat(restore).doesNotContain("launchAuthenticatedMaintenanceSystemFlow")
        assertThat(restore).doesNotContain("restoreOnReturn")
        assertThat(restore).contains("prepareHonorHomeRecovery()")
        assertThat(restore).contains("openSelector()")
    }

    @Test
    fun `the app coming back closes the window with the Maintenance exit, before the containment is requested again`() {
        val reconcile = activity.substringAfter("private fun reconcileMaintenanceSessionOnResume() {").substringBefore("/**\n     * Opens one user-initiated Android confirmation")
        assertThat(reconcile).contains("DapMaintenanceSession.reconcileOnResume()")
        assertThat(reconcile).contains("WindowReturn.RESTORE_AFTER_SURFACE")
        assertThat(reconcile).contains("exitAuthenticatedMaintenance { }")
        val onResume = activity.substringAfter("override fun onResume() {").substringBefore("override fun onPause")
        assertThat(onResume.indexOf("reconcileMaintenanceSessionOnResume()")).isLessThan(onResume.indexOf("maybeEnterDedicatedMode()"))
    }

    @Test
    fun `the System page reaches the activity only through the host interface`() {
        assertThat(activity).contains("class MainActivity : ComponentActivity(), SystemSurfaceHost")
        val page = source("presentation/settings/SystemSettings.kt")
        assertThat(page).doesNotContain("MainActivity")
        val wiring = source("presentation/settings/SettingsActionsWiring.kt").substringAfter("internal class SystemSettingsActionsImpl(")
        listOf("Settings.ACTION_SETTINGS", "Settings.ACTION_WIFI_SETTINGS", "Settings.ACTION_BLUETOOTH_SETTINGS", "USB_SETTINGS_ACTION").forEach {
            assertThat(wiring).contains(it)
        }
        assertThat(wiring.substringBefore("override fun onRebuildDatabase()")).contains("surfaces.openSystemSurface(intent)")
    }

    // --- the rebrand ------------------------------------------------------------------------------------------------------------

    /** The strings a listener sees that named the app "PixelPlayer(OSS)" and now name it Polylute. */
    private val rebranded = listOf(
        "maintenance_package_version", "maintenance_persistent_home_pixelplayer", "dac_forget_message",
        "device_capabilities_report_share_title", "home_empty_placeholder_subtitle", "crash_report_share_subject",
        "backup_validation_format_unknown", "song_info_ringtone_permission_prompt", "presentation_batch_g_artist_info_body",
        "dap_device_admin_label",
    )

    @Test
    fun `no user-visible string this batch changed still says PixelPlayer, in any language`() {
        languages.forEach { language ->
            val byName = strings(language).toMap()
            rebranded.forEach { name ->
                val text = byName[name]
                assertThat(text).isNotNull()
                assertThat(text!!).doesNotContain("PixelPlayer")
            }
            assertThat(byName.getValue("maintenance_package_version")).contains("Polylute")
        }
    }

    @Test
    fun `the database rebuild row is named in the language of the page`() {
        assertThat(strings("values-es").toMap().getValue("presentation_batch_f_rebuild_database")).isEqualTo("Reconstruir base de datos")
        assertThat(strings("values").toMap().getValue("presentation_batch_f_rebuild_database")).isEqualTo("Rebuild database")
        assertThat(strings("values-sv").toMap().getValue("presentation_batch_f_rebuild_database")).isEqualTo("Bygg om databasen")
    }

    @Test
    fun `every string the System page asks for exists in all three languages`() {
        val names = listOf(
            "settings_category_system_title", "settings_category_system_subtitle", "system_maintenance_subtitle",
            "system_confirm_lyrics_title",
            "system_confirm_return_title", "system_confirm_return_body", "system_confirm_return_action", "system_return_done", "maintenance_regenerate_section",
        )
        languages.forEach { language ->
            val byName = strings(language).toMap()
            names.forEach { name -> assertThat(byName[name]).isNotEmpty() }
        }
    }
}
