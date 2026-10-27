package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import org.junit.Test

/**
 * B42: the ink measurement has teeth. The sheets pass it; this is a sheet that must not: a text in black (what the credits window drew
 * over the glass before its surface kept its content colour), one in a dark grey, one in a colour near the glass's own, and one in the theme's
 * own tertiary (what a sheet that took its ink from the theme and not from the glass would draw). The measurement names the first three as
 * under AA, names the last as made worse by the glass, and names nothing drawn in the app's ink.
 */
@OptIn(ExperimentalMaterial3Api::class)
class B42InkMeasureTest : B42SheetHarness() {

    private val themeTertiary get() = com.lostf1sh.pixelplayeross.ui.glass.B42Setup(cover, theme).look.palette.tertiaryText

    @Test
    fun `a text the glass makes unreadable is named, and the texts in the app's ink beside it are not`() {
        val r = render(file = "ink-teeth") {
            GlassModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.padding(24.dp)) {
                    Text("in the primary ink", color = Dap.primaryText)
                    Text("in the secondary ink", color = Dap.secondaryText)
                    Text("in the tertiary ink", color = Dap.tertiaryText)
                    Text("in black", color = Color.Black)
                    Text("in dark grey", color = Color(0xFF2A2A2A))
                    Text("in the glass's own colour", color = Color(0xFF14243A))
                    // the theme's own tertiary, not the glass's: a sheet that took its ink from the theme and not from the glass would draw this
                    Text("in the theme's own tertiary", color = themeTertiary)
                    Column(Modifier.height(120.dp)) {}
                }
            }
        }
        val underAa = r.findings.filter { it.contrast < it.needs }.map { it.text }
        val worse = r.madeWorse.map { it.text }
        println("B42 | under AA on the glass: $underAa; of them, made worse by the glass than the same sheet without it: $worse")
        // all three unreadable texts are named...
        assertThat(underAa).containsAtLeast("in black", "in dark grey", "in the glass's own colour")
        // ...none of the app's own ink is...
        assertThat(underAa).containsNoneOf("in the primary ink", "in the secondary ink", "in the tertiary ink")
        // ...and the theme's own tertiary, which reads on the opaque theme and not on the lighter glass, is one the glass made worse
        println("B42 | the theme's own tertiary on the glass: ${r.findings.first { it.text.startsWith("in the theme's own") }.contrast} against ${r.without.first { it.text.startsWith("in the theme's own") }.contrast} without it")
        assertThat(worse).contains("in the theme's own tertiary")
    }

    @Test
    fun `a sheet that paints an opaque surface of its own over its glass is not taken for glass`() {
        // what the settings option picker did before: its content filled itself with the theme's surface
        val opaque = render(file = "ink-teeth-opaque") {
            GlassModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.fillMaxWidth().background(Dap.surface).padding(24.dp)) {
                    Text("a sheet with a surface of its own", color = Dap.primaryText)
                    Column(Modifier.height(300.dp)) {}
                }
            }
        }
        val share = glassShare(opaque.window)
        println("B42 | rows with a gradient between the edges, a sheet with an opaque surface of its own: $share")
        assertThat(share).isLessThan(0.2f)
    }

    @Test
    fun `a sheet with nothing of its own over its glass is`() {
        val glass = render(file = "ink-teeth-glass") {
            GlassModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("a sheet on the glass", color = Dap.primaryText)
                    Column(Modifier.height(300.dp)) {}
                }
            }
        }
        val share = glassShare(glass.window)
        println("B42 | rows with a gradient between the edges, a sheet on the glass: $share")
        assertThat(share).isAtLeast(0.6f)
    }
}
