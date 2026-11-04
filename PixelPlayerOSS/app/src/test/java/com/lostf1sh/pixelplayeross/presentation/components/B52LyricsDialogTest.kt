package com.lostf1sh.pixelplayeross.presentation.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint
import com.lostf1sh.pixelplayeross.ui.ds.*
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import dagger.hilt.android.EntryPointAccessors
import io.mockk.*
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B52LyricsDialogTest {
    @get:Rule val compose = createComposeRule()
    private var bridgeComposed = false

    @Before fun hiltAnswers() {
        val prefs = mockk<UserPreferencesRepository>()
        every { prefs.backNavigationModeFlow } returns flowOf(BackNavigationMode.DEFAULT)
        val point = object : ModalInputEntryPoint {
            override fun safetyVolumeController(): SafetyVolumeController {
                bridgeComposed = true
                return mockk(relaxed = true)
            }
            override fun userPreferencesRepository() = prefs
        }
        mockkStatic(EntryPointAccessors::class)
        every { EntryPointAccessors.fromApplication(ApplicationProvider.getApplicationContext<Context>(), ModalInputEntryPoint::class.java) } returns point
    }

    @After fun restoreHilt() = unmockkStatic(EntryPointAccessors::class)

    @Test fun `the lyrics folder alert composes the modal bridge and a Spanish title`() {
        compose.setContent {
            DapTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = Dap.background) { LyricsFolderPermission(true) { _, _ -> } }
            }
        }
        compose.onNodeWithText("Elegir carpeta").assertIsDisplayed()
        val dir = File(System.getenv("B52_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B52-gates/png-after").apply { mkdirs() }
        File(dir, "lyrics-folder-dialog.png").outputStream().use { B42Renders.screen(compose).compress(Bitmap.CompressFormat.PNG, 100, it) }
        org.junit.Assert.assertTrue("The dialog must mount the volume authority bridge", bridgeComposed)
        compose.onNodeWithText("Guardar letras junto a tu música").assertIsDisplayed()
    }

    @Test fun `the other B51 modal folder actions also composes the input bridge`() {
        compose.setContent {
            DapTheme(darkTheme = true) {
                com.lostf1sh.pixelplayeross.presentation.screens.FolderActionsSheet(
                    com.lostf1sh.pixelplayeross.data.model.MusicFolder("/music/Album", "Álbum"), {}, {}, {}
                )
            }
        }
        compose.onNodeWithText("Abrir carpeta").assertIsDisplayed()
        org.junit.Assert.assertTrue("Folder actions must mount the volume authority bridge", bridgeComposed)
    }
}
