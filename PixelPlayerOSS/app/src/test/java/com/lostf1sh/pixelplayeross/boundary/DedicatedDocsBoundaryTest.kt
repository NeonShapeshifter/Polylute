package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B39-A11: what the README and the app's Dedicated guide tell someone before they run `dpm`: who Dedicated is for, why the accounts go
 * (a security rule, only for the moment of the command), that Samsung's Secure Folder and work profiles are extra users whose data goes
 * with them, the exact command for the release and the debug build, and how to leave.
 */
class DedicatedDocsBoundaryTest {
    private val readme = File("../../README.md").readText()
    private val res = File("src/main/res")
    private val gradle = File("build.gradle.kts").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun guideStrings(dir: String) = File(res, "$dir/strings_home_mode.xml").readText()

    @Test
    fun `the readme says who Dedicated is for and what to use instead on a daily phone`() {
        assertThat(readme).contains("Is Dedicated for you?")
        assertThat(readme).contains("ideally a spare one")
        assertThat(readme).contains("use App mode, or Pinned")
    }

    @Test
    fun `the readme explains why accounts go, and that it is only for the command`() {
        assertThat(readme).contains("looks unused")
        assertThat(readme).contains("security rule")
        assertThat(readme).contains("only at the moment of the command")
        assertThat(readme).contains("add the accounts back right after")
    }

    @Test
    fun `the readme names Secure Folder and work profiles as extra users and says deleting them deletes their data`() {
        assertThat(readme).contains("Secure Folder")
        assertThat(readme).contains("work profiles count as extra users")
        assertThat(readme).contains("deleting them deletes the data inside")
    }

    @Test
    fun `the readme's two commands are the ones this build's ids give`() {
        // the ids come from the build, the receiver from the manifest: the commands in the readme must be what the app itself would show
        assertThat(gradle).contains("applicationId = \"app.polylute\"")
        assertThat(gradle).contains("applicationIdSuffix = \".debug\"")
        assertThat(manifest).contains("android:name=\".dap.admin.DapDeviceAdminReceiver\"")
        val receiver = "com.lostf1sh.pixelplayeross.dap.admin.DapDeviceAdminReceiver"
        assertThat(readme).contains("adb shell dpm set-device-owner app.polylute/$receiver")
        assertThat(readme).contains("adb shell dpm set-device-owner app.polylute.debug/$receiver")
    }

    @Test
    fun `the readme says how to leave`() {
        assertThat(readme).contains("Stop being the device owner")
        assertThat(readme).contains("No reset is needed")
    }

    @Test
    fun `the in-app guide has the same four things in English, Spanish and Swedish`() {
        listOf("values", "values-es", "values-sv").forEach { dir ->
            val text = guideStrings(dir)
            listOf("home_mode_guide_who", "home_mode_guide_why_clear", "home_mode_guide_extra_users", "home_mode_guide_leave").forEach { key ->
                assertThat(text).contains("name=\"$key\"")
            }
        }
        assertThat(guideStrings("values")).contains("Secure Folder")
        assertThat(guideStrings("values-es")).contains("Carpeta segura")
        assertThat(guideStrings("values-sv")).contains("Säker mapp")
    }

    @Test
    fun `the guide page shows them to a phone that is not a device owner yet`() {
        val page = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/settings/HomeModeSettings.kt").readText()
        val guide = page.substringAfter("if (!product.isDeviceOwner) {").substringBefore("} else {")
        listOf("home_mode_guide_who", "home_mode_guide_why_clear", "home_mode_guide_extra_users", "home_mode_guide_leave").forEach {
            assertThat(guide).contains("R.string.$it")
        }
    }
}
