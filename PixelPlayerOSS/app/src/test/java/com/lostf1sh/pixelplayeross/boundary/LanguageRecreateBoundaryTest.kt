package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B16-G3: the language picker no longer recreates the activity on top of the platform; the activity recreates itself, once.
 *
 * The device showed white bars where the system bars are after a language change (the platform's relaunch kept the window).
 * What is pinned here is the structure that makes the change a single, deliberate, window-replacing recreation. Source
 * level: an activity cannot run on the JVM, and there is no Robolectric in this project.
 */
class LanguageRecreateBoundaryTest {

    private val java = "java/com/lostf1sh/pixelplayeross"
    /** Source without its comments: what the code *does*, not what its documentation says it no longer does. */
    private fun code(source: String) = source.replace("\r\n", "\n")
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""//[^\n]*"""), "")

    private fun main(path: String) = code(File("src/main", path).readText())

    private val activity = main("$java/MainActivity.kt")
    private val picker = main("$java/presentation/settings/AppearanceSettings.kt")
    private val wiring = main("$java/presentation/settings/SettingsActionsWiring.kt")
    private val viewModel = main("$java/presentation/viewmodel/SettingsViewModel.kt")
    private val localeManager = main("$java/utils/AppLocaleManager.kt")
    private val manifest = File("src/main", "AndroidManifest.xml").readText()

    @Test
    fun `the language picker does not recreate the activity`() {
        assertThat(picker).doesNotContain("recreate()")
        assertThat(wiring).doesNotContain("recreate()")
        val row = picker.substringAfter("setcat_app_language_label").substringBefore("setcat_theme_label")
        assertThat(row).contains("actions.onAppLanguageChange(it)")
        assertThat(wiring).contains("override fun onAppLanguageChange(value: String) = vm.setAppLanguage(value)")
        assertThat(viewModel.substringAfter("fun setAppLanguage(").substringBefore("fun setDapLockScreenEnabled"))
            .doesNotContain("recreate")
    }

    @Test
    fun `nothing but the main activity's gate calls recreate`() {
        val callers = File("src/main/$java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""\brecreate\(\)""").containsMatchIn(code(it.readText())) }
            .map { it.name }
            .toList()
        assertThat(callers).containsExactly("MainActivity.kt")
        assertThat(Regex("""\brecreate\(\)""").findAll(activity).count()).isEqualTo(1)
        val gated = activity.substringAfter("private fun requestLanguageRecreate(").substringBefore("private fun onStoredLanguageChanged")
        assertThat(gated).contains("languageGate.shouldRecreate(now)")
        assertThat(gated.indexOf("shouldRecreate")).isLessThan(gated.indexOf("recreate()"))
    }

    @Test
    fun `every signal of a language change goes through that one gate`() {
        val configuration = activity.substringAfter("override fun onConfigurationChanged(").substringBefore("@OptIn")
        assertThat(configuration).contains("requestLanguageRecreate(\"configuration event\", newConfig)")
        val stored = activity.substringAfter("private fun onStoredLanguageChanged(").substringBefore("override fun onConfigurationChanged")
        assertThat(stored).contains("requestLanguageRecreate(")
        assertThat(activity).contains("AppLocaleManager.observeLanguage(this, ::onStoredLanguageChanged)")
        val grace = activity.substringAfter("private val languageGraceRecreate").substringBefore("private fun effectiveLanguage")
        assertThat(grace).contains("requestLanguageRecreate(")
    }

    @Test
    fun `the main activity handles locale and layout direction itself, so the platform does not relaunch it with its window kept`() {
        val declaration = manifest.substringAfter("android:name=\".MainActivity\"").substringBefore(">")
        val configChanges = Regex("""android:configChanges="([^"]*)"""").find(declaration)!!.groupValues[1].split("|")
        assertThat(configChanges).containsAtLeast("locale", "layoutDirection")
        // Already handled: the ones that were there are still there.
        assertThat(configChanges).containsAtLeast("orientation", "screenSize", "keyboardHidden", "screenLayout", "smallestScreenSize")
    }

    @Test
    fun `the gate is made from the language in force when the activity is created, and the observer is released`() {
        assertThat(activity).contains("languageGate = LanguageRecreateGate(effectiveLanguage(resources.configuration))")
        assertThat(activity.substringAfter("override fun onDestroy()").substringBefore("super.onDestroy()"))
            .contains("languageObserver?.close()")
        assertThat(activity.substringAfter("override fun onDestroy()").substringBefore("super.onDestroy()"))
            .contains("removeCallbacks(languageGraceRecreate)")
    }

    @Test
    fun `the platform locale is only ever set by the locale manager, which does not recreate anything`() {
        val setters = File("src/main/$java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && code(it.readText()).contains("setApplicationLocales") }
            .map { it.name }
            .toList()
        assertThat(setters).containsExactly("AppLocaleManager.kt")
        assertThat(localeManager).doesNotContain("recreate")
        assertThat(localeManager).contains("fun applyLanguage(context: Context, languageTag: String): Boolean")
    }

    @Test
    fun `the window's background follows the app's theme`() {
        assertThat(activity).contains("PixelPlayerWindowBackground()")
        val theme = main("$java/ui/theme/Theme.kt")
        assertThat(theme.substringAfter("fun PixelPlayerWindowBackground(")).contains("window.setBackgroundDrawable(argb.toDrawable())")
    }
}
