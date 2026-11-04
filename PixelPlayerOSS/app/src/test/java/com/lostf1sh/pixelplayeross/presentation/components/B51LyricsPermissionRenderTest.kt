package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.ds.*
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import java.io.File
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B51LyricsPermissionRenderTest {
    @get:Rule val compose = createComposeRule()
    @Before fun provideModalAuthority() {
        val prefs = io.mockk.mockk<com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository>()
        io.mockk.every { prefs.backNavigationModeFlow } returns kotlinx.coroutines.flow.flowOf(com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode.DEFAULT)
        val point = object : com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint {
            override fun safetyVolumeController() = io.mockk.mockk<com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController>(relaxed = true)
            override fun userPreferencesRepository() = prefs
        }
        io.mockk.mockkStatic(dagger.hilt.android.EntryPointAccessors::class)
        io.mockk.every {
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(),
                com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint::class.java)
        } returns point
    }
    @After fun restoreModalAuthority() = io.mockk.unmockkStatic(dagger.hilt.android.EntryPointAccessors::class)
    @Test fun `folder explanation has a choose action and declining saves in the app`() {
        var declined = false
        compose.setContent {
            DapTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = Dap.background) {
                    LyricsFolderPermission(true) { uri, _ -> declined = uri == null }
                }
            }
        }
        compose.onNodeWithText("Elegir carpeta").assertIsDisplayed()
        val image = B42Renders.screen(compose)
        val dir = File(System.getenv("B51_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B51-gates/png-after").apply { mkdirs() }
        File(dir, "lyrics-folder-permission.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Guardar en Polylute").performClick()
        assertThat(declined).isTrue()
    }
    @Test fun `library settings show granted folders and remove their persisted access`() {
        var grants = listOf("content://grant/music" to "/storage/ABCD/Music")
        io.mockk.mockkConstructor(com.lostf1sh.pixelplayeross.data.lyrics.AndroidLyricsStorage::class)
        io.mockk.every { anyConstructed<com.lostf1sh.pixelplayeross.data.lyrics.AndroidLyricsStorage>().grants() } answers { grants }
        io.mockk.every { anyConstructed<com.lostf1sh.pixelplayeross.data.lyrics.AndroidLyricsStorage>().dropTree(any()) } answers { grants = emptyList() }
        try {
            compose.setContent {
                DapTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize(), color = Dap.background) {
                        com.lostf1sh.pixelplayeross.presentation.settings.LyricsFolderGrants()
                    }
                }
            }
            compose.onNodeWithText("/storage/ABCD/Music").assertIsDisplayed()
            compose.onNodeWithText("Quitar permiso").performClick()
            compose.onNodeWithText("/storage/ABCD/Music").assertDoesNotExist()
            assertThat(grants).isEmpty()
        } finally { io.mockk.unmockkAll() }
    }

}
