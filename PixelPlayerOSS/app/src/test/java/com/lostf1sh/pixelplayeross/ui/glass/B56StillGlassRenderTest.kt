package com.lostf1sh.pixelplayeross.ui.glass

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B56 phase 7.1: **Still is exactly today's glass.** This file uses nothing the batch added, so the same file runs in the tree before the batch and in this one, and each writes
 * `bg-still-<theme>-<before|after>.png`; the gate compares the two files byte for byte (`B56-gates/phase7-1-still-identical.log`). The player background is the real cover glass over
 * a generated cover, at a fixed phase of its drift, in the dark theme and the light one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B56StillGlassRenderTest {
    @get:Rule val compose = createComposeRule()

    private val phase = if (File("").absoluteFile.path.contains("B56-before")) "before" else "after"
    private val out: File
        get() = File(System.getenv("B56_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B56-gates/renders").apply { mkdirs() }

    private fun render(name: String, cover: B41Renders.Cover, dark: Boolean) {
        val setup = B42Setup(cover, if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light)
        compose.setContent {
            val scene = rememberB42Scene(setup, 14f)
            scene.clock.motion = com.lostf1sh.pixelplayeross.data.preferences.BackgroundMotion.STILL   // the one line the before copy's version of this file does not have
            B42GlassApp(setup, scene, expansion = 1f) {}
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "bg-still-$name-$phase.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `still glass over a saturated cover in the dark theme`() = render("dark", B41Renders.saturated(), dark = true)

    @Test fun `still glass over a saturated cover in the light theme`() = render("light", B41Renders.saturated(), dark = false)
}
