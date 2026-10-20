package com.lostf1sh.pixelplayeross.boundary

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lostf1sh.pixelplayeross.BuildConfig
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqPreGainSenseSwitch
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteSwitch
import com.lostf1sh.pixelplayeross.data.dac.fiio.PreGainSense
import com.lostf1sh.pixelplayeross.data.service.player.NativeEngineDebugSwitch
import com.lostf1sh.pixelplayeross.data.service.player.RateSwitchPrerollSetting
import com.lostf1sh.pixelplayeross.presentation.settings.MaintenanceToolsPolicy
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B35 (R6): the release build shows no debug surface. The same class runs in both variants and each asserts the answer of its own build
 * from the real `BuildConfig.DEBUG`, so the debug run proves the checks are not vacuous (the thing they look for is there) and the release
 * run (`./gradlew :app:testReleaseUnitTest --tests '*ReleaseSurfaceTest'`) proves it is gone:
 *
 * - the debug source set's classes (the `SET_*` receiver, the noise mark and its provider, the button-sheet activity) are not in the release;
 * - the test crash row is not offered, "Marcar ruido ahora" is wired from a hook that only the debug provider installs, and the persisted
 *   debug switches (the DAC EQ write override, the forced native engine, the rate-switch preroll, the pre-gain sense) ignore what is stored
 *   in their files and refuse to be set.
 *
 * What the release *artifact* contains is a separate check, `tools/release-surface/check_release_surface.py`, run on the built bundle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReleaseSurfaceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val debugBuild = BuildConfig.DEBUG

    private val switches = listOf(
        DacEqWriteSwitch::class.java,
        DacEqPreGainSenseSwitch::class.java,
        NativeEngineDebugSwitch::class.java,
        RateSwitchPrerollSetting::class.java,
    )

    /** The process-wide singletons cache what they read once; each test starts them over. */
    private fun forgetSingletons() {
        for (type in switches) type.getDeclaredField("shared").apply { isAccessible = true }.set(null, null)
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before
    fun fresh() {
        forgetSingletons()
    }

    @After
    fun clean() {
        forgetSingletons()
        for (name in listOf(DacEqWriteSwitch.PREFERENCES, NativeEngineDebugSwitch.PREFERENCES, RateSwitchPrerollSetting.PREFERENCES)) {
            prefs(name).edit().clear().commit()
        }
    }

    @Test
    fun `the debug source set exists in a debug build and in no other`() {
        val debugOnly = listOf(
            "com.lostf1sh.pixelplayeross.dap.admin.DapDebugPolicyReceiver",   // every SET_* override and the read-only probes
            "com.lostf1sh.pixelplayeross.dap.admin.NoiseMark",                // "Marcar ruido ahora"
            "com.lostf1sh.pixelplayeross.dap.admin.DebugHooksProvider",       // the only thing that installs the hook the row needs
            "com.lostf1sh.pixelplayeross.ui.ds.DapButtonSheetActivity",
        )
        for (name in debugOnly) {
            val present = runCatching { Class.forName(name) }.isSuccess
            assertThat(present).isEqualTo(debugBuild)
        }
    }

    @Test
    fun `the test crash is offered in a debug build and in no other, whatever the page asks`() {
        assertThat(MaintenanceToolsPolicy.offersTestCrash()).isEqualTo(debugBuild)
        assertThat(MaintenanceToolsPolicy.offersTestCrash(debugBuild = false)).isFalse()
    }

    @Test
    fun `a stored DAC EQ write override opens the gate in a debug build and never in another`() {
        prefs(DacEqWriteSwitch.PREFERENCES).edit().putBoolean(DacEqWriteSwitch.KEY, true).commit()

        val switch = DacEqWriteSwitch.shared(context)
        assertThat(switch.isAllowed()).isEqualTo(debugBuild)

        switch.set(false)
        switch.set(true)                                                    // somebody asks for it
        assertThat(switch.isAllowed()).isEqualTo(debugBuild)
        if (!debugBuild) {
            // refused, not stored: the file still holds only what was put there by hand
            assertThat(prefs(DacEqWriteSwitch.PREFERENCES).getBoolean(DacEqWriteSwitch.KEY, false)).isTrue()
            switch.set(false)
            assertThat(prefs(DacEqWriteSwitch.PREFERENCES).getBoolean(DacEqWriteSwitch.KEY, false)).isTrue()
        }
    }

    @Test
    fun `a stored forced native engine is the plan B of a debug build and never of another`() {
        prefs(NativeEngineDebugSwitch.PREFERENCES).edit().putBoolean(NativeEngineDebugSwitch.KEY, true).commit()

        val switch = NativeEngineDebugSwitch.shared(context)
        assertThat(switch.isForced()).isEqualTo(debugBuild)
        switch.set(true)
        assertThat(switch.isForced()).isEqualTo(debugBuild)
    }

    @Test
    fun `a stored rate-switch preroll is applied in a debug build and reads zero in another`() {
        prefs(RateSwitchPrerollSetting.PREFERENCES).edit().putInt(RateSwitchPrerollSetting.KEY, 500).commit()

        val setting = RateSwitchPrerollSetting.shared(context)
        assertThat(setting.ms()).isEqualTo(if (debugBuild) 500 else 0)
        assertThat(setting.set(250)).isEqualTo(debugBuild)
        assertThat(setting.ms()).isEqualTo(if (debugBuild) 250 else 0)
    }

    @Test
    fun `a stored pre-gain sense answers in a debug build and is ignored in another`() {
        prefs(DacEqWriteSwitch.PREFERENCES).edit().putString(DacEqPreGainSenseSwitch.KEY, "boost").commit()

        val switch = DacEqPreGainSenseSwitch.shared(context)
        assertThat(switch.override()).isEqualTo(if (debugBuild) PreGainSense.BOOST else null)
        switch.set(PreGainSense.ATTENUATION)
        assertThat(switch.override()).isEqualTo(if (debugBuild) PreGainSense.ATTENUATION else null)
    }

    // ── the name (B35-fix1 R3): the debug app is another app and says so ─────────────────────────────────────────────────────────

    @Test
    fun `the app is named Polylute in a release and Polylute Debug in a debug build, in every language`() {
        val expected = if (debugBuild) "Polylute Debug" else "Polylute"
        for (tag in listOf("en", "es", "sv")) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            assertWithMessage(tag).that(localized.getString(R.string.app_name)).isEqualTo(expected)
        }
        assertThat(context.packageManager.getApplicationLabel(context.applicationInfo).toString()).isEqualTo(expected)
    }

    @Test
    fun `the name comes from one string that only the debug source set overrides, and no language outranks it`() {
        fun appName(file: File) = Regex("""<string name="app_name"[^>]*>([^<]*)</string>""").find(file.readText())?.groupValues?.get(1)
        assertThat(appName(File(app, "main/res/values/strings.xml"))).isEqualTo("Polylute")
        assertThat(appName(File(app, "debug/res/values/strings.xml"))).isEqualTo("Polylute Debug")
        // a localized app_name would outrank the default one the debug source set overrides, and the debug app would be named after the release
        for (set in listOf("main", "debug")) {
            File(app, "$set/res").listFiles { dir -> dir.isDirectory && dir.name.startsWith("values-") }.orEmpty().forEach { dir ->
                dir.listFiles().orEmpty().filter { it.extension == "xml" }.forEach {
                    assertWithMessage("${it.path} defines app_name").that(it.readText()).doesNotContain("name=\"app_name\"")
                }
            }
        }
        assertThat(File(app, "main/AndroidManifest.xml").readText()).contains("android:label=\"@string/app_name\"")
        assertThat(File(app, "release/AndroidManifest.xml").takeIf { it.isFile }?.readText().orEmpty()).doesNotContain("Polylute Debug")
    }

    // ── the sources, in either variant: what a release is made of ────────────────────────────────────

    private val app = File("src")

    @Test
    fun `no action of the debug bridge is spelled in the main manifest or the main sources`() {
        val actions = Regex("""android:name="(com\.lostf1sh\.pixelplayeross\.debug\.[A-Z_]+)"""")
            .findAll(File(app, "debug/AndroidManifest.xml").readText()).map { it.groupValues[1] }.toList()
        assertThat(actions).isNotEmpty()
        val main = File(app, "main/AndroidManifest.xml").readText() +
            File(app, "main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (action in actions) assertThat(main).doesNotContain(action)
        for (variant in listOf("release", "benchmark")) {
            val manifest = File(app, "$variant/AndroidManifest.xml")
            if (manifest.isFile) {
                assertThat(manifest.readText()).doesNotContain("pixelplayeross.debug.")
                assertThat(manifest.readText()).doesNotContain("DapDebugPolicyReceiver")
            }
        }
        assertThat(File(app, "main/AndroidManifest.xml").readText()).doesNotContain("DapDebugPolicyReceiver")
    }

    @Test
    fun `the main source set never refers to a class of the debug source set`() {
        val debugClasses = File(app, "debug/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("""^(?:internal |private )?(?:class|object|interface)\s+(\w+)""", RegexOption.MULTILINE)
                    .findAll(file.readText()).map { it.groupValues[1] }
            }.toSet()
        assertThat(debugClasses).containsAtLeast("DapDebugPolicyReceiver", "NoiseMark", "DebugHooksProvider", "DapButtonSheetActivity")
        val main = File(app, "main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        for (file in main) {
            val code = file.readLines().filterNot { it.trim().let { line -> line.startsWith("*") || line.startsWith("//") || line.startsWith("/*") } }
                .joinToString("\n") { it.substringBefore("//") }
            for (name in debugClasses) {
                assertWithMessage("${file.name} refers to $name").that(Regex("""\b$name\b""").containsMatchIn(code)).isFalse()
            }
        }
    }
}
