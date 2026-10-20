package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * The shell rules this pass introduced, asserted against the sources that have to keep them.
 *
 * These are structural rather than behavioural, and deliberately so. What they protect is a
 * *layout* decision — that navigation honours its mode, that a header's action is one glyph or
 * an overflow and not both — and this project has no instrumentation harness that could observe a
 * composed screen. The assertions are kept narrow: they name the exact construct that would have to
 * come back for the rule to be broken, not the shape of the file around it.
 */
class GlobalShellBoundaryTest {

    private fun source(relative: String) =
        root.resolve("src/main/java/com/lostf1sh/pixelplayeross/$relative").readText()

    private val home get() = source("presentation/screens/HomeScreen.kt")
    private val settingsRoot get() = source("presentation/screens/SettingsScreen.kt")
    private val settingsCategory get() = source("presentation/screens/SettingsCategoryScreen.kt")
    private val library get() = source("presentation/screens/LibraryScreen.kt")

    @Test
    fun `Home reserves no top bar for its title and one action`() {
        // The wordmark and the gear cost a full row of every Home screen, forever, for two things
        // that fit on the page. The title is now the first item of the list it introduces.
        assertThat(home).doesNotContain("topBar = {")
        // The property is "the title is the first thing in Home's scrollable content, not a
        // reserved row". Until 14.0H that was expressed as the first LazyColumn item; Home is now
        // hosted by a retained Column (its scroll range is about one viewport and its sections are
        // bounded), so the same property is the first entry of the section list that host renders.
        assertThat(home).contains("add(\"home_title\" to secHomeTitle)")
        assertThat(home).contains(".verticalScroll(rememberScrollState())")
    }

    @Test
    fun `Home's settings action is a gear in the title row, not a word in a bar`() {
        // Frontend/motion session, 2026-09-26, by the owner's decision: the gear no longer floats.
        // Floating in a translucent box, the page showed through it as it scrolled and it sat off
        // the gutter. It is now the title's trailing icon button, so it scrolls with the title and
        // is below the status-bar inset the page already carries. What this still guards: a gear
        // glyph, the DS icon button (full 48 dp target), and no reserved top-bar row (see above).
        assertThat(home).contains("kind = DapIconKind.Gear")
        assertThat(home).contains("trailing = {\n                        DapIconButton(")
        assertThat(home).doesNotContain("DapOverlayAction(")
    }

    @Test
    fun `the pinned overlay actions keep a full touch target`() {
        // The class of defect this guards is a small glyph inside a larger decorative box: the
        // clickable region must be the whole target, not the drawn mark.
        val overlays = source("ui/ds/DapOverlays.kt")
        assertThat(overlays).contains(".size(DapSizes.MinTouchTarget)")
        assertThat(overlays).contains("contentDescription: String")
    }

    @Test
    fun `nested Settings separates mode-aware navigation from scrolling content`() {
        assertThat(settingsRoot).doesNotContain("DapTopBar(")
        assertThat(settingsCategory).doesNotContain("CollapsibleCommonTopBar(")
        assertThat(settingsCategory).doesNotContain("mainTopBar")
        assertThat(home).doesNotContain("DapTopBar(")
        assertThat(settingsRoot).contains("DapBackScaffold(")
        assertThat(settingsCategory).contains("DapBackScaffold(")
        val frame = source("presentation/components/DapBackScaffold.kt")
        assertThat(frame).contains("showsVisibleBackAffordance")
        assertThat(frame).contains("statusBarsPadding()")
        assertThat(frame).contains("back.back()")
    }

    @Test
    fun `a collapsed header cannot come back as a sticky mini title`() {
        // The collapsing category header was the other way a nested screen reserved permanent
        // height. Its removal is only real if the animation that drove it is gone too.
        assertThat(settingsCategory).doesNotContain("collapseFraction")
        assertThat(settingsCategory).doesNotContain("minTopBarHeight")
    }

    @Test
    fun `Library keeps its overflow rather than collapsing to one action`() {
        // Library genuinely has several secondary actions, so `•••` is the honest representation.
        // A single gear would tell the user there is one thing there when there are four.
        assertThat(library).contains("DapOverflowButton(")
        assertThat(library).doesNotContain("DapIconKind.Gear")
    }

    @Test
    fun `the app never asks the platform for its volume panel`() {
        // The readout is the app's own, on every surface and in every state where the app can change
        // a volume. `FLAG_SHOW_UI` is the flag that would hand that back to MagicOS, and it is not
        // used anywhere — including on the paths that predate the readout.
        val sources = root.resolve("src/main/java").walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        assertThat(sources).doesNotContain("FLAG_SHOW_UI")
        assertThat(sources).doesNotContain("FLAG_PLAY_SOUND")
        // The single stream write stays flagless: no UI, no sound, no vibration from the platform.
        assertThat(source("data/volume/SafetyVolumeController.kt"))
            .contains("setStreamVolume(AudioManager.STREAM_MUSIC, raw, 0)")
    }

    @Test
    fun `the volume readout renders the authority's level rather than one of its own`() {
        val host = source("presentation/components/VolumeHudHost.kt")
        // The numbers come from the controller's state, which reconciles against the platform; the
        // event carries only *that* a press happened.
        assertThat(host).contains("controller.state.collect")
        assertThat(host).contains("state.backend.rawLevel")
        assertThat(host).contains("state.backend.rawMax")
        // And nothing here keeps a level of its own to drift away from that, nor infers one.
        assertThat(host).doesNotContain("rawLevel + 1")
        assertThat(host).doesNotContain("mutableIntStateOf")
    }

    @Test
    fun `the volume readout does not depend on the activity recomposing to appear`() {
        // Measured on the device: a readout mounted inside the activity's composition never
        // appeared once another window of this app held focus. The window is added and removed by
        // the lifecycle-scoped collector instead, so nothing about its existence waits on a frame.
        val host = source("presentation/components/VolumeHudHost.kt")
        assertThat(host).contains("activity.windowManager.addView")
        assertThat(host).contains("activity.windowManager.removeViewImmediate")
        assertThat(host).contains("lifecycleScope")
        // A composable that adds its own window would put the readout back on the frame clock.
        assertThat(host).doesNotContain("Dialog(")
    }

    @Test
    fun `Back has one authority per window and the gesture is not a second one`() {
        // The gesture and the visible affordance both go through the shell's authority, which is
        // the dispatcher — the same entry point the system's own Back uses. A gesture that reached
        // for the nav graph itself would skip every modal layer in front of it.
        assertThat(source("presentation/navigation/DapEdgeBackGesture.kt"))
            .doesNotContain("popBackStack")
        assertThat(source("presentation/navigation/DapBackNavigation.kt"))
            .contains("dispatcher.onBackPressed()")

        // That authority belongs to the activity's window. A modal window has one of its own — the
        // dialog's — and Back there means "dismiss what is in front", not "pop the graph underneath
        // it". Exactly one file may call a window dispatcher directly, and it is the modal input
        // bridge calling the dialog's; every other path goes through the authority above.
        val dispatcherCallers = root.resolve("src/main/java").walkTopDown()
            .filter { it.extension == "kt" && it.readText().contains("onBackPressedDispatcher.onBackPressed()") }
            .map { it.name }
            .toList()
        assertThat(dispatcherCallers).containsExactly("ModalInputBridge.kt")
        assertThat(source("presentation/components/ModalInputBridge.kt"))
            .contains("dialog.onBackPressedDispatcher.onBackPressed()")
    }

    private companion object {
        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
    }
}
