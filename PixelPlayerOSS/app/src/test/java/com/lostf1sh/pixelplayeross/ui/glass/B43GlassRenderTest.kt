package com.lostf1sh.pixelplayeross.ui.glass

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.ui.ds.*
import java.io.File
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class B43GlassRenderTest {
    @get:Rule val compose = createComposeRule()
    private val palette = DapBaseTheme.Graphite.palette
    private val look = GlassBands.of(palette, Color.Cyan)
    private fun save(bitmap: Bitmap, name: String) {
        val dir = File(System.getenv("B43_EVIDENCE") ?: "/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B43-evidence", System.getenv("B43_VARIANT") ?: "after").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun `the grey magenta transition stays smooth after tone mapping and stretching`() {
        val field = GlassFieldMath.field(GlassFieldMath.gradientGrid(Color(0xFFEEEEEE), Color(0xFF65002B)), look.band, moves = false)
        val original = Bitmap.createBitmap(field.argb, field.size, field.size, Bitmap.Config.ARGB_8888)
        val rendered = Bitmap.createScaledBitmap(original, 720, 1600, true)
        save(rendered, "gradient-grey-magenta")
        var maximum = 0f
        for (y in 1 until rendered.height) for (x in 1 until rendered.width) {
            val p = GlassLuma.luminance(rendered.getPixel(x, y))
            maximum = maxOf(maximum, abs(p - GlassLuma.luminance(rendered.getPixel(x - 1, y))), abs(p - GlassLuma.luminance(rendered.getPixel(x, y - 1))))
        }
        var cellStep = 0f
        for (y in 1 until field.size) for (x in 1 until field.size) {
            val p = GlassLuma.luminance(field.argb[y * field.size + x])
            cellStep = maxOf(cellStep, abs(p - GlassLuma.luminance(field.argb[y * field.size + x - 1])), abs(p - GlassLuma.luminance(field.argb[(y - 1) * field.size + x])))
        }
        println("B43 field step = $cellStep")
        assertThat(cellStep).isAtMost(0.006f)
        println("B43 gradient maximum neighbouring luminance step = $maximum")
        assertThat(maximum).isAtMost(0.006f)
        for (ink in listOf(look.ink.primary, look.ink.secondary, look.ink.tertiary)) {
            assertThat(field.argb.minOf { GlassLuma.contrast(ink.luminance(), GlassLuma.luminance(it)) }).isAtLeast(4.5f)
        }
    }
    @Test fun `rows under navigation are unreadable while the field keeps its chroma`() {
        val shown = mutableStateOf(true)
        compose.setContent {
            Fix3Stage(B41Renders.saturated(), Fix3Mode.FROM_ALBUM_ART).App {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        repeat(45) { Text("Omar Courtz · 9 Songs · Prince Royce", color = if (shown.value) Color.White else Color.Transparent) }
                    }
                    DapBottomNav(listOf(DapNavItem(R.string.tab_home, DapIconKind.Home), DapNavItem(R.string.tab_library, DapIconKind.Library)),
                        0, {}, Modifier.align(Alignment.BottomCenter).height(90.dp))
                }
            }
        }
        settleFix3(compose)
        val withText = B42Renders.screen(compose); save(withText, "bottom-bar-rows")
        compose.runOnIdle { shown.value = false }; compose.waitForIdle()
        val empty = B42Renders.screen(compose)
        var contrast = 1f
        for (y in withText.height - 170 until withText.height - 10) for (x in 5 until withText.width - 5) {
            contrast = maxOf(contrast, GlassLuma.contrast(GlassLuma.luminance(withText.getPixel(x, y)), GlassLuma.luminance(empty.getPixel(x, y))))
        }
        println("B43 row contrast through bottom bar = $contrast")
        assertThat(contrast).isLessThan(1.5f)
    }
}
