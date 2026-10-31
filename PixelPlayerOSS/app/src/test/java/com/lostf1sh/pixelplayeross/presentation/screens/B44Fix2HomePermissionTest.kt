package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.LocalMusicAccessGranted
import com.lostf1sh.pixelplayeross.MusicAccessPrompt
import com.lostf1sh.pixelplayeross.ui.ds.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
open class B44Fix2HomePermissionTest {
    @get:Rule val compose = createComposeRule()
    @org.junit.Before fun exactViewport() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        if (app.resources.displayMetrics.densityDpi == 440) {
            val display = app.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
            org.robolectric.Shadows.shadowOf(display).apply {
                setWidth(1080)
                setHeight(2220)
                setRealWidth(1080)
                setRealHeight(2220)
            }
        }
    }
    @Test fun `Home offers permission without an overlapping refresh action`() {
        compose.setContent {
            DapTheme(darkTheme = true) {
                CompositionLocalProvider(LocalMusicAccessGranted provides false) {
                    Surface(Modifier.fillMaxWidth().requiredHeight(if (LocalDensity.current.density == 2.75f) (2220f / 2.75f).dp else 805.dp).testTag("home-fixture"), color = Dap.background) {
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().padding(top = 45.dp)) {
                                DapBrandHeader()
                                Spacer(Modifier.height(HomeLayout.SectionGap))
                                YourMixEmptyPlaceholder({})
                            }
                            MusicAccessPrompt({}, Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 72.dp))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        com.lostf1sh.pixelplayeross.ui.glass.B42Renders.settle(compose)
        val bitmap = compose.onNodeWithTag("home-fixture").captureToImage().asAndroidBitmap()
        val phase = if (File(".").canonicalPath.contains("B44-fix2-before")) "before" else "after"
        val folder = File("/home/ale/proyects/honor-dap-workspace/analysis/audit-2026-09-27/batches/B44-fix2-evidence/$phase").apply { mkdirs() }
        File(folder, "home-permission-${bitmap.width}x${bitmap.height}.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val cardAction = compose.onNodeWithTag("music-access-prompt").fetchSemanticsNode().boundsInRoot
        val refresh = compose.onAllNodesWithText("Refresh").fetchSemanticsNodes()
        if (refresh.isNotEmpty()) {
            val bounds = refresh.single().boundsInRoot
            println("B44-fix2 HOST Home permission action=$cardAction refresh=$bounds")
            assertThat(bounds.overlaps(cardAction)).isFalse()
        }
        // Permission is the useful first-run action. There must be no refresh hidden below it.
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.onNodeWithText("Allow music access").assertIsDisplayed()
    }
}

@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h808dp-440dpi")
class B44Fix2HomePermissionApi29ViewportTest : B44Fix2HomePermissionTest()
