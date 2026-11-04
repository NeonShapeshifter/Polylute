package com.lostf1sh.pixelplayeross.ui.glass

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.BackgroundMotion
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B56 phase 7.1 renders: the player background with Still, Gentle and Lively after the same 8 seconds of motion, light and dark. **The JVM cannot run AGSL** (Robolectric has no
 * `RuntimeShader`), so these are the classic drifting layers (the path under Android 13, which is also what Still draws on any Android): the same field, the same layers, scaled by
 * the setting. The live blobs of Android 13 and later are a device item.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xhdpi")
class B56BackgroundMotionRenderTest {
    @get:Rule val compose = createComposeRule()

    private val out: File
        get() = File(System.getenv("B56_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B56-gates/renders").apply { mkdirs() }

    private fun render(motion: BackgroundMotion, dark: Boolean, expectedPhase: Float) {
        val setup = B42Setup(B41Renders.saturated(), if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light)
        var clock: GlassClock? = null
        compose.setContent {
            val scene = rememberB42Scene(setup, 14f)
            clock = scene.clock
            scene.clock.motion = motion
            // what the ticker does for 8 seconds of the player being open: Still takes no step at all
            androidx.compose.runtime.LaunchedEffect(scene) { repeat(80) { scene.clock.step(0.1f) } }
            B42GlassApp(setup, scene, expansion = 1f) {}
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "motion-${motion.storageKey}-${if (dark) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Still stays where the clock was left (14 s); Gentle has run 8 s at speed 1; Lively 8 s at 1.8
        assertThat(clock!!.phase).isWithin(0.05f).of(expectedPhase)
    }

    @Test fun `dark - still holds the picture`() = render(BackgroundMotion.STILL, dark = true, expectedPhase = 14f)

    @Test fun `dark - gentle moves it`() = render(BackgroundMotion.GENTLE, dark = true, expectedPhase = 14f + 8f)

    @Test fun `dark - lively moves it further`() = render(BackgroundMotion.LIVELY, dark = true, expectedPhase = 14f + 8f * 1.8f)

    @Test fun `light - still holds the picture`() = render(BackgroundMotion.STILL, dark = false, expectedPhase = 14f)

    @Test fun `light - gentle moves it`() = render(BackgroundMotion.GENTLE, dark = false, expectedPhase = 14f + 8f)

    @Test fun `light - lively moves it further`() = render(BackgroundMotion.LIVELY, dark = false, expectedPhase = 14f + 8f * 1.8f)
}
