package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * The product has one orientation.
 *
 * It is declared on the windows rather than enforced from code, so there is nothing for a screen, a
 * preference or a mode to disagree about. These assertions are about that being true of *every*
 * activity, including the ones that are easy to forget because they are opened by another app.
 */
class PortraitOnlyBoundaryTest {

    private val manifest get() = root.resolve("src/main/AndroidManifest.xml").readText()

    private fun source(relative: String) =
        root.resolve("src/main/java/com/lostf1sh/pixelplayeross/$relative").readText()

    private fun allSources(): String =
        root.resolve("src/main/java").walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }

    /** Each `<activity …>` declaration, as written. */
    private fun activityDeclarations(): List<String> =
        manifest.split("<activity").drop(1).map { "<activity" + it.substringBefore(">") }

    @Test
    fun `every activity the app declares is portrait`() {
        val declarations = activityDeclarations()

        assertThat(declarations).isNotEmpty()
        declarations.forEach { declaration ->
            assertThat(declaration).contains("android:screenOrientation=\"portrait\"")
        }
    }

    @Test
    fun `the product activity and the external player are both covered`() {
        // The external player is opened by another app's VIEW/SEND intent and is a face of the same
        // product, so it is the one most easily left out of a rule stated as "the main activity".
        val declarations = activityDeclarations()
        val byName = declarations.associateBy { it.substringAfter("android:name=\"").substringBefore("\"") }

        assertThat(byName.keys).containsAtLeast(".MainActivity", ".ExternalPlayerActivity")
        assertThat(byName[".MainActivity"]).contains("portrait")
        assertThat(byName[".ExternalPlayerActivity"]).contains("portrait")
    }

    @Test
    fun `the declaration is portrait and not one of the sensor variants`() {
        // `sensorPortrait` would add a second, upside-down portrait; `user` and `unspecified` would
        // hand the decision back to the device setting, which is the behaviour being removed.
        assertThat(manifest).doesNotContain("screenOrientation=\"sensorPortrait\"")
        assertThat(manifest).doesNotContain("screenOrientation=\"user\"")
        assertThat(manifest).doesNotContain("screenOrientation=\"unspecified\"")
        assertThat(manifest).doesNotContain("screenOrientation=\"fullSensor\"")
        assertThat(manifest).doesNotContain("screenOrientation=\"sensor\"")
    }

    @Test
    fun `nothing forces orientation at runtime`() {
        // If the windows are declared portrait, no screen needs to ask for it — and a screen that
        // did would be a per-screen hack for a rule that belongs to the window.
        val sources = allSources()
        assertThat(sources).doesNotContain("requestedOrientation")
        assertThat(sources).doesNotContain("setRequestedOrientation")
        assertThat(sources).doesNotContain("SCREEN_ORIENTATION_")
        assertThat(sources).doesNotContain("ActivityInfo.SCREEN_ORIENTATION")
    }

    @Test
    fun `no preference can re-enable auto-rotation`() {
        // There is no orientation setting and no Auto option, so there is nothing a stored
        // preference could switch back on.
        val sources = allSources()
        assertThat(sources).doesNotContain("autoRotateEnabled")
        assertThat(sources).doesNotContain("screenOrientationFlow")
        assertThat(sources).doesNotContain("orientationFlow")
        assertThat(sources).doesNotContain("setAutoRotate")

        // The one preference whose name contains "rotate" is the collage pattern cycle, which is
        // about artwork on Home and has nothing to do with the screen. It is deliberately left
        // alone; this pins that it stays a Home concern rather than being read as orientation.
        assertThat(sources).contains("collageAutoRotateFlow")
        assertThat(source("presentation/viewmodel/SettingsViewModel.kt"))
            .contains("collageAutoRotate = autoRotate")
    }

    @Test
    fun `the readout draws two fixed forms rather than a list of candidates`() {
        // The line used to be built as a longest-first candidate list and drawn by whichever entry
        // happened to measure narrow enough, which made what the reader saw depend on the frame.
        // B21: the readout is its own surface (PlayerTechnicalLine), drawn from the progress surface.
        val content = source("presentation/components/player/PlayerTechnicalLine.kt") +
            source("presentation/components/player/PlayerProgressSurface.kt")
        assertThat(content).doesNotContain("formatAudioMetaOptions")
        assertThat(content).doesNotContain("firstOrNull { candidate")
        assertThat(content).contains("chooseTechnicalLine(")

        val identity = source("presentation/components/player/SongTechnicalIdentity.kt")
        assertThat(identity).contains("val canonical")
        assertThat(identity).contains("val withoutBitDepth")
    }

    private companion object {
        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
    }
}
