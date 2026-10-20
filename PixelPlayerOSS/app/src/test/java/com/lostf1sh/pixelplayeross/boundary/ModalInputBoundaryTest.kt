package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * The boundaries the modal input bridge must not cross.
 *
 * The bridge exists to give the app's authorities a second way in, and the risk of a change like
 * this is never that it fails to work — it is that it grows a second opinion: a second volume, a
 * second Back, a second gesture recogniser. These assertions are about what must stay absent, and
 * about the coverage that makes the fix complete rather than local.
 */
class ModalInputBoundaryTest {

    private fun source(relative: String) =
        root.resolve("src/main/java/com/lostf1sh/pixelplayeross/$relative").readText()

    private val bridge get() = source("presentation/components/ModalInputBridge.kt")
    private val activity get() = source("MainActivity.kt")
    private val authority get() = source("data/volume/SafetyVolumeController.kt")

    /**
     * The file with its comments removed.
     *
     * These assertions are about what the code does, not about what it says. The bridge's own KDoc
     * names the things it deliberately does not do — "it emits no `VolumeHudEvent` of its own" —
     * and a scan that could not tell prose from code would forbid the file from explaining itself.
     */
    private val bridgeCode get() = stripComments(bridge)

    private fun stripComments(text: String): String {
        val withoutBlocks = Regex("""/\*[\s\S]*?\*/""").replace(text, "")
        return withoutBlocks.lines().joinToString("\n") { it.substringBefore("//") }
    }

    private fun allSources(): String =
        root.resolve("src/main/java").walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }

    // ---- volume keys ------------------------------------------------------------------------

    @Test
    fun `the bridge contains no volume arithmetic and no Safety Max policy`() {
        // It routes a key and nothing else. Any of these appearing would mean a second place that
        // decides what a volume key means.
        listOf(
            "setStreamVolume",
            "adjustStreamVolume",
            "getStreamVolume",
            "rawLevel",
            "rawMax",
            "ceilingRaw",
            "temporaryOverride",
            "SafetyVolumePolicy",
            "SafetyOverrideGesture",
            "mustClamp",
            "requestedRaw",
            "setRawLevel",
        ).forEach { forbidden ->
            assertThat(bridgeCode).doesNotContain(forbidden)
        }
    }

    @Test
    fun `the bridge emits no readout state of its own`() {
        // The readout is driven by the authority's VolumeHudEvent. The bridge must not become a
        // second source of HUD state, or the shell readout would have two things to listen to.
        listOf("VolumeHudEvent", "hudEvent", "VolumeHudPolicy", "VolumeHudController", "MutableStateFlow")
            .forEach { forbidden -> assertThat(bridgeCode).doesNotContain(forbidden) }
    }

    @Test
    fun `activity and modal windows feed the same singleton authority`() {
        // MainActivity keeps the key path it always had...
        assertThat(activity).contains("safetyVolumeController.handleVolumeKey(event)")
        // ...and the bridge reaches the same controller through Hilt rather than constructing one.
        assertThat(bridge).contains("ModalInputEntryPoint")
        assertThat(bridge).contains("safetyVolumeController()")
        assertThat(bridge).doesNotContain("SafetyVolumeController(")
        // One authority, declared once.
        assertThat(source("di/ModalInputEntryPoint.kt"))
            .contains("fun safetyVolumeController(): SafetyVolumeController")
        assertThat(authority).contains("@Singleton")
    }

    @Test
    fun `the shell readout remains the only readout`() {
        val sources = allSources()
        assertThat(sources).doesNotContain("SecondVolumeHud")
        // The readout observes the authority's event, and the authority is the only emitter.
        assertThat(source("presentation/components/VolumeHudHost.kt"))
            .contains("controller.hudEvent.collect")
        val emitters = authority.lines().count { it.contains("_hudEvent.update") }
        assertThat(emitters).isEqualTo(1)
    }

    // ---- the edge Back gesture --------------------------------------------------------------

    @Test
    fun `the modal gesture is the same gesture, not a second one`() {
        // It must read the same activation region, the same thresholds and the same refusals as the
        // activity's gesture. A recogniser written again here is a recogniser that can disagree.
        assertThat(bridge).contains("EdgeBackGesturePolicy(")
        assertThat(bridge).contains("EdgeBackGesturePolicy.EdgeWidth")
        assertThat(bridge).contains("EdgeBackGesturePolicy.ActivationDistance")
        // And there is no hand-rolled movement arithmetic beside it.
        assertThat(bridgeCode).doesNotContain("abs(")
        assertThat(bridgeCode).doesNotContain("velocity")
    }

    @Test
    fun `the modal gesture follows the same Back mode as the activity's`() {
        // One preference, read on both sides of the window boundary, so Gestures-only means the
        // same thing over a sheet as it does over a screen.
        assertThat(bridge).contains("backNavigationModeFlow")
        assertThat(bridge).contains("edgeGestureEnabled")
        assertThat(bridge).contains("ModalInputEntryPoint")
    }

    @Test
    fun `the modal gesture performs the window's own Back`() {
        // The priority rule: while a sheet or dialog is in front, Back dismisses it. Calling the
        // activity's dispatcher here would pop the navigation graph out from under an open dialog,
        // which is not what Back means while one is up.
        assertThat(bridge).contains("ComponentDialog")
        assertThat(bridge).contains("onBackPressedDispatcher.onBackPressed()")
        assertThat(bridge).doesNotContain("DapBackAuthority")
    }

    @Test
    fun `the modal gesture consumes only what it has claimed`() {
        // An ordinary scroll, slider or sheet drag must reach the window untouched. The bridge takes
        // the gesture only once the policy claims it, and tells the view tree the gesture is over
        // rather than leaving a view holding a pointer that will never be lifted.
        assertThat(bridge).contains("EdgeBackDecision.PENDING")
        assertThat(bridge).contains("MotionEvent.ACTION_CANCEL")
        assertThat(bridge).contains("delegate.dispatchTouchEvent(event)")
    }

    // ---- installation -----------------------------------------------------------------------

    @Test
    fun `the bridge installs only inside an app-owned modal window`() {
        // The activity's own window is not bridged: the activity already owns both paths, and
        // wrapping it here as well would route one press, or one swipe, twice.
        assertThat(bridge).contains("(view.parent as? DialogWindowProvider)?.window")
        assertThat(bridge).contains("previous is ModalInputCallback")
        assertThat(bridge).contains("window.callback = previous")
    }

    @Test
    fun `every app-owned window routes its input through the bridge`() {
        // The fix is only consistent if it is everywhere. Counting the mount against the number of
        // windows the app opens makes a new sheet or dialog without the bridge a failing build
        // rather than a silent gap discovered on the device.
        //
        // The name is taken after the last dot so a fully-qualified call counts as the window it
        // is, and compared against a fixed set so that a call to one of the app's *own* composables
        // — several of which end in "Dialog" and open their window further down — is not counted
        // as a second site.
        val windowNames = setOf("ModalBottomSheet(", "Dialog(", "AlertDialog(", "BasicAlertDialog(")
        var sites = 0
        var mounts = 0
        root.resolve("src/main/java").walkTopDown()
            .filter { it.extension == "kt" && it.name != "ModalInputBridge.kt" }
            .forEach { file ->
                file.readLines().forEach { line ->
                    val trimmed = line.trim()
                    val named = trimmed.substringAfterLast('.') in windowNames
                    if (named && !trimmed.startsWith("fun ")) sites++
                    if (trimmed == "ModalInputBridge()") mounts++
                }
            }
        assertThat(sites).isAtLeast(60)
        assertThat(mounts).isEqualTo(sites)
    }

    @Test
    fun `the surfaces the gap was measured on are bridged`() {
        // Song Actions and Track Details are the two windows the volume gap was measured on, and
        // Track Details is where the Back gesture gap was reported. They must be covered by the
        // same mechanism as everything else, not by a special case.
        assertThat(source("presentation/components/SongInfoBottomSheet.kt"))
            .contains("ModalInputBridge()")
        assertThat(source("presentation/components/player/TrackDetailsHost.kt"))
            .contains("ModalInputBridge()")
        assertThat(source("presentation/components/UnifiedPlayerOverlaysLayer.kt"))
            .contains("ModalInputBridge()")
    }

    private companion object {
        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
    }
}
