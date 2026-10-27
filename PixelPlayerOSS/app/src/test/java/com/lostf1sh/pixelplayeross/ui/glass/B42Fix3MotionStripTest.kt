package com.lostf1sh.pixelplayeross.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * B42-fix3 (item 7, "the motion"): a frame strip per cover, so the reviewer sees the travel. **Twelve frames, one second apart** (12 s of the glass in the
 * full player), of the real backdrop (the three layers with their transforms, under the player's scrim), for the five covers, laid out 6 x 2 in one PNG per cover.
 * The same test runs in the tree before this batch (`before/`, B42-fix2's motion: "ours barely moves") and after it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B42Fix3MotionStripTest {
    @get:Rule
    val compose = createComposeRule()

    private val variant = System.getenv("B42FIX3_VARIANT") ?: "after"

    private val root: File
        get() = File(System.getenv("B42FIX3_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B42-fix3-evidence").resolve(variant).apply { mkdirs() }

    private var scene: CoverGlassScene? = null

    private fun strip(cover: B41Renders.Cover) {
        val setup = B42Setup(cover, DapBaseTheme.Graphite)
        compose.setContent {
            val s = rememberB42Scene(setup, 0f)
            scene = s
            B42GlassApp(setup, s, expansion = 1f) {}
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val frames = ArrayList<Bitmap>()
        repeat(12) {
            frames += B42Renders.screen(compose)
            compose.runOnIdle { scene!!.clock.advance(1f) }
        }
        val w = 120
        val h = 268
        val sheet = Bitmap.createBitmap(w * 6 + 7 * 4, h * 2 + 3 * 4, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFF000000.toInt())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        frames.forEachIndexed { i, f ->
            val thumb = Bitmap.createScaledBitmap(f, w, h, true)
            canvas.drawBitmap(thumb, (4 + (i % 6) * (w + 4)).toFloat(), (4 + (i / 6) * (h + 4)).toFloat(), paint)
        }
        File(root, "motion-${cover.name}.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // not a still: the first and the last frame differ
        val a = frames.first()
        val b = frames.last()
        var changed = 0
        for (y in 0 until a.height step 16) for (x in 0 until a.width step 16) if (a.getPixel(x, y) != b.getPixel(x, y)) changed++
        // (a cover that is one colour, or none, has nothing to move across the screen)
        if (cover.name !in setOf("black", "white")) assertThat(changed).isGreaterThan(40)
    }

    @Test fun white() = strip(B42Fix3Covers.pureWhite())
    @Test fun black() = strip(B42Fix3Covers.pureBlack())
    @Test fun pastel() = strip(B42Fix3Covers.pastel())
    @Test fun saturated() = strip(B42Fix3Covers.saturated())
    @Test fun multicolour() = strip(B42Fix3Covers.multicolourOnBlack())

    @Suppress("unused")
    private val keep = mutableStateOf(0)
}
