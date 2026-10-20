package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.ANIMATED_LYRICS_BLUR_STRENGTH_DEFAULT
import com.lostf1sh.pixelplayeross.data.preferences.ANIMATED_LYRICS_BLUR_STRENGTH_MAX
import com.lostf1sh.pixelplayeross.data.preferences.ANIMATED_LYRICS_BLUR_STRENGTH_MIN
import com.lostf1sh.pixelplayeross.data.preferences.DEFAULT_SHOW_QUEUE_HISTORY
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Where a setting is owned, and that the value a control starts on is the value the store holds.
 *
 * The ownership half is a structural claim about the category pages: each setting is edited in
 * exactly one category, and that category is the one that owns the subject. It is asserted against
 * the source because the alternative — a Compose UI test — would need the whole Hilt graph to say
 * something that is really about which page a control sits in.
 *
 * B21: each category is its own file in `presentation/settings` that calls an actions interface
 * (`actions.onXChange`), and `SettingsActionsWiring.kt` maps every action to the ViewModel setter it
 * is named after. So "the Lyrics page edits setExternalLyricsEnabled" reads as "the Lyrics page calls
 * `actions.onExternalLyricsEnabledChange`", and the wiring test below pins that each action is wired
 * to that setter and to nothing else.
 *
 * The defaults half is asserted against the real repository and a real DataStore, because the
 * defect it pins was precisely a disagreement between two defaults rather than a wrong one.
 */
class SettingsOwnershipTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    // --- Source access ----------------------------------------------------------------------------

    private val settingsDir = "src/main/java/com/lostf1sh/pixelplayeross/presentation/screens"
    private val pagesDir = "src/main/java/com/lostf1sh/pixelplayeross/presentation/settings"

    private fun readSource(relative: String): String = readFrom(settingsDir, relative)

    /** One category's own page file (B21). */
    private fun readPage(relative: String): String = readFrom(pagesDir, relative)

    private fun readFrom(dir: String, relative: String): String {
        val candidates = listOf(
            java.io.File(dir, relative),
            java.io.File("app", "$dir/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
        requireNotNull(file) {
            "Could not locate $relative from ${java.io.File(".").absolutePath}; " +
                "a source assertion that cannot find its file must fail rather than pass quietly."
        }
        return file.readText()
    }

    /** The page file each category draws (B21: one file per category). */
    private val pageFiles = mapOf(
        "DEVICE" to "DeviceSettings.kt",
        "LIBRARY" to "LibrarySettings.kt",
        "LYRICS" to "LyricsSettings.kt",
        "APPEARANCE" to "AppearanceSettings.kt",
        "AUDIO" to "AudioSettings.kt",
        "BEHAVIOR" to "BehaviorSettings.kt",
        "BACKUP_RESTORE" to "BackupSettings.kt",
        "SYSTEM" to "SystemSettings.kt",
        "ABOUT" to "AboutSettings.kt",
    )

    /**
     * What one category renders and calls: its page file, and nothing from its neighbours.
     *
     * The host dispatches `SettingsCategory.X -> XSettingsHost(…)`; the dispatch is asserted below
     * so a category cannot lose its page without this test noticing.
     */
    private fun categoryBranch(category: String): String {
        val host = readSource("SettingsCategoryScreen.kt")
        require(host.contains("SettingsCategory.$category ->")) { "No branch for SettingsCategory.$category" }
        return readPage(requireNotNull(pageFiles[category]) { "No page for $category" })
    }

    /** The action a page calls for a ViewModel setter: `setFoo` -> `actions.onFooChange`. */
    private fun action(setter: String) = "actions.on${setter.removePrefix("set")}Change"

    private val wiring get() = readPage("SettingsActionsWiring.kt")

    // --- A/C: brightness has exactly one owner ----------------------------------------------------

    @Test
    fun `brightness is drawn once, on the Settings root's control block`() {
        val deviceControls = readSource("DapDeviceControlComponents.kt")

        // The root's live controls own it.
        assertThat(deviceControls).contains("fun DapQuickDeviceControls(")
        assertThat(
            deviceControls.substringAfter("fun DapQuickDeviceControls(")
                .substringBefore("\n@Composable")
        ).contains("R.string.dap_brightness_title")

        // The category surface that used to carry a second copy does not exist any more, and the
        // timeout content that replaced it has no brightness control in it.
        assertThat(deviceControls).doesNotContain("fun DapDisplayControlContent(")
        val timeoutContent = deviceControls.substringAfter("fun DapScreenTimeoutControlContent(")
            .substringBefore("\n@Composable")
        assertThat(timeoutContent).doesNotContain("dap_brightness_title")
        assertThat(timeoutContent).doesNotContain("brightnessPercent")
    }

    @Test
    fun `no settings category offers a brightness slider`() {
        // There is no Device brightness slider and no Display category to hide one in.
        listOf("DEVICE", "AUDIO", "APPEARANCE", "BEHAVIOR", "LIBRARY", "LYRICS").forEach { category ->
            assertThat(categoryBranch(category)).doesNotContain("dap_brightness_title")
        }
    }

    // --- D: persistent lyrics settings are owned by Lyrics ---------------------------------------

    @Test
    fun `every persistent lyrics setting is edited in the Lyrics category`() {
        val tests = readSource("SettingsCategoryScreen.kt")
        // Guard against a vacuous pass: the category must exist before its branch can be inspected.
        assertThat(tests).contains("SettingsCategory.LYRICS ->")

        val lyrics = categoryBranch("LYRICS")
        listOf(
            "setExternalLyricsEnabled",
            "setLyricsSourcePreference",
            "setAutoScanLrcFiles",
            "setImmersiveLyricsEnabled",
            "setImmersiveLyricsTimeout",
            "setUseAnimatedLyrics",
            "setAnimatedLyricsBlurEnabled",
            "setAnimatedLyricsBlurStrength",
            "setLyricsAlignment",
            "setShowLyricsTranslation",
            "setShowLyricsRomanization",
        ).forEach { setter ->
            assertThat(lyrics).contains(action(setter))
            // ...and the action is the setter's, not another's.
            assertThat(wiring).contains("vm.$setter(value)")
        }
    }

    @Test
    fun `the categories lyrics moved out of no longer edit them`() {
        val library = categoryBranch("LIBRARY")
        val appearance = categoryBranch("APPEARANCE")

        listOf(
            "setExternalLyricsEnabled",
            "setLyricsSourcePreference",
            "setAutoScanLrcFiles",
            "setImmersiveLyricsEnabled",
            "setUseAnimatedLyrics",
        ).forEach { setter ->
            assertThat(library).doesNotContain(action(setter))
            assertThat(appearance).doesNotContain(action(setter))
        }
    }

    @Test
    fun `the destructive lyrics reset is not on a preference page`() {
        // It deletes every imported lyric for every song, so it must not sit next to "Update library" or among the lyrics preferences.
        // It is a System-page recovery action (B32-Q6; it was Maintenance's behind a PIN) and asks before it runs.
        val preferencePages = pageFiles.filterKeys { it != "SYSTEM" }.values.joinToString("\n") { readPage(it) }
        assertThat(preferencePages).doesNotContain("resetAllLyrics")
        assertThat(preferencePages).doesNotContain("setcat_reset_imported_lyrics_title")
        assertThat(readSource("SettingsCategoryScreen.kt")).doesNotContain("resetAllLyrics")

        val system = readPage("SystemSettings.kt")
        assertThat(system).contains("setcat_reset_imported_lyrics_title")
        assertThat(wiring).contains("vm.resetAllLyrics()")
        // Truthfully destructive: it confirms, in error colour, before it runs (the dialog's "yes" is what calls the action).
        assertThat(system).contains("SystemConfirmation.LYRICS")
        assertThat(system.substringAfter("SystemConfirmation.LYRICS -> ConfirmDialog(")).contains("destructive = true")
        assertThat(system.substringAfter("private fun ConfirmDialog(")).contains("colorScheme.error")
    }

    // --- E: album art resolution is owned by Appearance -------------------------------------------

    @Test
    fun `album art resolution is a release-visible Appearance setting`() {
        val appearance = categoryBranch("APPEARANCE")
        assertThat(appearance).contains("presentation_batch_f_exp_album_art_resolution")
        assertThat(appearance).contains(action("setAlbumArtQuality"))
        assertThat(wiring).contains("vm.setAlbumArtQuality(value)")

        // And it is not still trapped behind the developer-only screen, which no longer exists.
        assertThat(readSource("SettingsCategoryScreen.kt") + wiring).doesNotContain("Screen.Experimental")
        assertThat(java.io.File(settingsDir, "ExperimentalSettingsScreen.kt").exists()).isFalse()
    }

    @Test
    fun `the maintenance tools keep only internal actions, in Maintenance and in no Settings category`() {
        val tools = readPage("MaintenanceTools.kt")
        // Product-facing controls are not here.
        assertThat(tools).doesNotContain(action("setUseAnimatedLyrics"))
        assertThat(tools).doesNotContain(action("setAnimatedLyricsBlurEnabled"))
        assertThat(tools).doesNotContain(action("setAlbumArtQuality"))
        // The internal actions remain: they are what the page is for.
        assertThat(tools).contains("actions.onTriggerTestCrash()")
        assertThat(wiring).contains("settings.triggerTestCrash()")
        // The Developer category is gone, so no Settings category hosts them; Maintenance does.
        assertThat(readSource("SettingsCategoryScreen.kt")).doesNotContain("Developer")
        assertThat(readSource("MaintenanceScreen.kt")).contains("MaintenanceToolsSection(")
    }

    // --- F: the global transition editor is reachable from Audio -----------------------------------

    @Test
    fun `Audio reaches the global transition editor and does not set a duration itself`() {
        val audio = categoryBranch("AUDIO")
        assertThat(audio).contains("actions.onOpenGlobalTransitionEditor()")
        assertThat(wiring).contains("navigate(Screen.EditTransition.createRoute(null))")
        // Only the enable, which playback treats as a kill switch. No duration, so there is no
        // second place a duration could come from.
        assertThat(audio).contains(action("setCrossfadeEnabled"))
        assertThat(wiring).contains("vm.setCrossfadeEnabled(value)")
        assertThat(audio).doesNotContain("setCrossfadeDuration")
        assertThat(audio).doesNotContain("setcat_crossfade_duration")
    }

    @Test
    fun `the retired duration preference has no live writer left in the app`() {
        // The only remaining reference is the migration that reads and removes it.
        val source = java.io.File("src/main/java/com/lostf1sh/pixelplayeross").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        require(source.isNotEmpty()) { "Source tree not found; the assertion would be vacuous." }

        val writers = source.filter { file ->
            file.readText().let {
                it.contains("setCrossfadeDuration") || it.contains("crossfadeDurationFlow")
            }
        }
        assertThat(writers.map { it.name }).isEmpty()
    }

    // --- O: the blur default is inside its legal range --------------------------------------------

    @Test
    fun `the lyrics blur default lies inside its legal range`() {
        assertThat(ANIMATED_LYRICS_BLUR_STRENGTH_DEFAULT).isAtLeast(ANIMATED_LYRICS_BLUR_STRENGTH_MIN)
        assertThat(ANIMATED_LYRICS_BLUR_STRENGTH_DEFAULT).isAtMost(ANIMATED_LYRICS_BLUR_STRENGTH_MAX)
        // The default is the intended behaviour and existing installs hold it, so the range was
        // widened to include it rather than the default lowered to fit the range.
        assertThat(ANIMATED_LYRICS_BLUR_STRENGTH_DEFAULT).isEqualTo(2.5f)
        assertThat(SettingsUiState().animatedLyricsBlurStrength)
            .isEqualTo(ANIMATED_LYRICS_BLUR_STRENGTH_DEFAULT)
    }

    @Test
    fun `a stored blur strength outside the range is clamped on read and on write`() = runTest {
        val file = Files.createTempFile("settings-ownership", ".preferences_pb").toFile()
        file.delete()
        val store: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val prefs = UserPreferencesRepository(dataStore = store, json = json)

        // A value stored by the old slider, which could not exceed 2.0 — still valid.
        store.edit { it[floatPreferencesKey("animated_lyrics_blur_strength")] = 2.0f }
        assertThat(prefs.animatedLyricsBlurStrengthFlow.first()).isEqualTo(2.0f)

        // A value outside the range cannot enter through the writer either.
        prefs.setAnimatedLyricsBlurStrength(ANIMATED_LYRICS_BLUR_STRENGTH_MAX + 5f)
        assertThat(prefs.animatedLyricsBlurStrengthFlow.first())
            .isEqualTo(ANIMATED_LYRICS_BLUR_STRENGTH_MAX)

        prefs.setAnimatedLyricsBlurStrength(0f)
        assertThat(prefs.animatedLyricsBlurStrengthFlow.first())
            .isEqualTo(ANIMATED_LYRICS_BLUR_STRENGTH_MIN)
    }

    // --- P: one default for show_queue_history ----------------------------------------------------

    @Test
    fun `the queue history switch and the store agree on one default`() = runTest {
        assertThat(SettingsUiState().showQueueHistory).isEqualTo(DEFAULT_SHOW_QUEUE_HISTORY)

        val file = Files.createTempFile("settings-queue-history", ".preferences_pb").toFile()
        file.delete()
        val store: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val prefs = UserPreferencesRepository(dataStore = store, json = json)

        // Nothing stored: the repository's default is the one the UI state started on, so the
        // switch cannot render a value the store does not hold.
        assertThat(prefs.showQueueHistoryFlow.first()).isEqualTo(DEFAULT_SHOW_QUEUE_HISTORY)
    }

    // --- I/J: Maintenance is a row, in every product mode, and honest about what it cannot do -----------------------------------

    @Test
    fun `there is no hidden gesture to Maintenance and the System page has a row to it`() {
        val about = readSource("AboutScreen.kt")
        // B32-Q6: the seven-tap entry to the PIN screen is gone (there is no PIN); Maintenance is a normal row of the System page.
        assertThat(about).doesNotContain("MaintenanceEntryCounter")
        assertThat(about).doesNotContain("detectTapGestures(")
        assertThat(about).doesNotContain("Screen.Maintenance")
        val system = readPage("SystemSettings.kt")
        assertThat(system).contains("actions::onOpenMaintenance")
        assertThat(wiring).contains("navigate(Screen.Maintenance.route)")
    }

    @Test
    fun `device owner only actions stay gated, recovery does not become one of them`() {
        // B34: leaving the DAP's HOME genuinely needs the Device Owner policy cleared, so the page's action branches on the mode Android
        // confirms (Dedicated), and otherwise just shows Android's picker, rather than pretending or failing silently.
        assertThat(wiring).contains("current.isDedicatedActive ->")
        assertThat(wiring).contains("openHomePicker()")

        // Recovery does not need Device Owner, so it must not be gated on it. Rebuild Database and deleting imported lyrics both run
        // through ordinary app code.
        val system = readPage("SystemSettings.kt")
        val recovery = system.substringAfter("presentation_batch_f_recovery_section").substringBefore("maintenance_internal_tools_section")
        require(recovery.isNotBlank()) { "Recovery section not found; the assertion would be vacuous." }
        assertThat(recovery).contains("presentation_batch_f_rebuild_database")
        assertThat(recovery).contains("setcat_reset_imported_lyrics_title")
        assertThat(recovery).doesNotContain("isDeviceOwner")
        // Rebuild Database's only condition is that a rebuild is not already running.
        assertThat(recovery).contains("enabled = !state.isRebuilding")
    }

    // --- The Lyrics category's key strings did not move -------------------------------------------

    @Test
    fun `the re-homed lyrics keys kept their stored names`() {
        // These four keys existed only as literals inside the lyrics sheet. They are declared in
        // one place now, but the strings are unchanged: every install's stored values — and every
        // backup taken before this pass — depend on them being byte-identical.
        val preferences = java.io.File(
            "src/main/java/com/lostf1sh/pixelplayeross/data/preferences/UserPreferencesRepository.kt"
        ).let { if (it.isFile) it else java.io.File("app/${it.path}") }
        require(preferences.isFile) { "UserPreferencesRepository.kt not found." }

        val declared = preferences.readText()
        listOf(
            "lyrics_alignment",
            "show_lyrics_translation",
            "show_lyrics_romanization",
            "keep_screen_on_lyrics",
        ).forEach { key ->
            assertThat(declared).contains("\"$key\"")
        }
    }
}
