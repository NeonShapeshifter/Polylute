package com.lostf1sh.pixelplayeross.presentation.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.glass.B41Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42GlassApp
import com.lostf1sh.pixelplayeross.ui.glass.B42HiltShim
import com.lostf1sh.pixelplayeross.ui.glass.B42Ink
import com.lostf1sh.pixelplayeross.ui.glass.B42Pages
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import com.lostf1sh.pixelplayeross.ui.glass.B42Setup
import com.lostf1sh.pixelplayeross.ui.glass.rememberB42Scene
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B42: what every render of a sheet shares. One sheet, the real one, in its own window over the real glass, saved as a picture and measured:
 * every text in the sheet's window is checked against the pixels under it, and a text whose contrast on the lightest or darkest ground around
 * it is under AA (4.5, or 3 for large text) fails the test and is named.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
abstract class B42SheetHarness {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val hilt = B42HiltShim()

    protected open val theme: DapBaseTheme = DapBaseTheme.Graphite
    private var glassOn by androidx.compose.runtime.mutableStateOf(true)
    protected open val cover: B41Renders.Cover = B41Renders.saturated()

    /** What is behind the sheet: Home, or the full player. */
    protected enum class Behind { HOME, PLAYER }

    protected fun context() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    /** What one render of a sheet found: its window, every text measured on the glass and without it, and what the glass made worse. */
    protected class Rendered(
        val window: Bitmap,
        val findings: List<B42Ink.Finding>,
        val without: List<B42Ink.Finding>,
        val madeWorse: List<B42Ink.Finding>,
    )

    /**
     * Shows [sheet] (and runs [open] after the first frame, for a sheet a tap opens), saves `<file>.png`, measures the ink on the glass and
     * again with the glass turned off, and returns what it found without judging it.
     */
    protected fun render(
        behind: Behind = Behind.HOME,
        cover: B41Renders.Cover = this.cover,
        file: String,
        open: () -> Unit = {},
        sheet: @Composable () -> Unit,
    ): Rendered {
        val setup = B42Setup(cover, theme)
        compose.setContent {
            val scene = rememberB42Scene(setup, 14f)
            B42GlassApp(setup, scene, expansion = if (behind == Behind.PLAYER) 1f else 0f, glassOn = glassOn) {
                if (behind == Behind.PLAYER) B42Pages.Player(cover) else B42Pages.Home(cover)
                sheet()
            }
        }
        compose.waitForIdle()
        open()
        val window = B42Renders.sheetWindow(compose)
        B42Renders.save(compose, file)
        val exempt = setOf(setup.palette.disabledText, setup.look.palette.disabledText)
        val findings = B42Ink.measure(compose, window, exempt)
        // the same sheet with the glass turned off: what is under AA there too is the design's own, not the glass's
        compose.runOnIdle { glassOn = false }
        val without = B42Ink.measure(compose, B42Renders.sheetWindow(compose), exempt)
        return Rendered(window, findings, without, B42Ink.regressions(findings, without))
    }

    /** How much of a sheet window's height shows a left-to-right gradient at its edges: the share of rows whose two edge pixels differ. */
    protected fun glassShare(window: Bitmap): Float {
        var top = 0
        while (top < window.height && B42Renders.isScrimOnly(window.getPixel(window.width / 2, top))) top++
        var rows = 0
        var gradient = 0
        for (y in (top + 70) until window.height) {
            val left = window.getPixel(3, y)
            val right = window.getPixel(window.width - 4, y)
            rows++
            val d = maxOf(Math.abs(((left shr 16) and 0xFF) - ((right shr 16) and 0xFF)), Math.abs(((left shr 8) and 0xFF) - ((right shr 8) and 0xFF)), Math.abs((left and 0xFF) - (right and 0xFF)))
            if ((left ushr 24) >= 250 && (right ushr 24) >= 250 && d >= 5) gradient++
        }
        return if (rows == 0) 0f else gradient.toFloat() / rows
    }

    /** [render], and the sheet must have texts and the glass must have made none of them worse than the same sheet without it. */
    protected fun shows(
        name: String,
        behind: Behind = Behind.HOME,
        cover: B41Renders.Cover = this.cover,
        file: String = "sheet-$name-${theme.name.lowercase()}",
        open: () -> Unit = {},
        sheet: @Composable () -> Unit,
    ): Bitmap {
        val r = render(behind, cover, file, open, sheet)
        // a sheet that paints an opaque surface of its own over its glass would pass the ink check and still not be glass: the saturated cover
        // runs red on the left to blue on the right, so a glass sheet differs between its two edges in most rows, an opaque one in none
        if (cover.name == "saturated") {
            val shown = glassShare(r.window)
            println("B42 | $file: ${"%.0f".format(shown * 100)} % of the sheet's rows differ between its left and right edge (the glass)")
            assertThat(shown).isAtLeast(0.6f)
        }
        val failing = B42Ink.report(r.madeWorse)
        val alsoBelow = B42Ink.alsoBelowWithout(r.findings, r.without).joinToString("; ") { "'${it.text}' %.2f".format(it.contrast) }
        println(
            "B42 | $file: ${r.findings.size} texts measured, lowest contrast ${r.findings.minOfOrNull { it.contrast }}; made worse by the glass: " +
                "${failing.ifEmpty { "none" }}; under AA with no glass too: ${alsoBelow.ifEmpty { "none" }}"
        )
        assertThat(r.findings).isNotEmpty()
        // the same sheet both ways: every text measured without the glass is measured on it too. (B42-fix1: a text can be measured only on the glass
        // when it is the design system's disabled grey, which is exempt, without the glass and the glass's own ink on it: the Details' notes.)
        val onGlass = r.findings.map { it.text }.toMutableList()
        for (text in r.without.map { it.text }) check(onGlass.remove(text)) { "'$text' is measured without the glass and not on it" }
        if (onGlass.isNotEmpty()) println("B42 | $file: measured only on the glass (the glass's own ink where the plain theme has its disabled grey): ${onGlass.joinToString("; ")}")
        assertThat(failing).isEmpty()
        return r.window
    }
}
