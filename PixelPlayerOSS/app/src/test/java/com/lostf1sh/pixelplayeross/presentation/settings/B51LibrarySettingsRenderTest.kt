package com.lostf1sh.pixelplayeross.presentation.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.worker.SyncProgress
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.*
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B51LibrarySettingsRenderTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `library offers the safe album folder cover switch enabled by default`() {
        val calls = mutableListOf<String>()
        val actions = Proxy.newProxyInstance(LibrarySettingsActions::class.java.classLoader, arrayOf(LibrarySettingsActions::class.java)) { _, method, arguments ->
            calls += method.name + "=" + arguments?.joinToString()
            null
        } as LibrarySettingsActions
        val state = LibrarySettingsState.from(SettingsUiState(), false, SyncProgress())
        compose.setContent {
            DapTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = Dap.background) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { LibrarySettingsScreen(state, actions) }
                }
            }
        }
        val image = B42Renders.screen(compose)
        val dir = File(System.getenv("B51_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B51-gates/png-after").apply { mkdirs() }
        File(dir, "library-settings.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onAllNodesWithText("Usar la carátula de la carpeta del álbum (cover.jpg…)")[1].performScrollTo().assertIsDisplayed()
        compose.onAllNodes(isToggleable())[0].assertIsOn().performClick()
        assertThat(calls).contains("onFolderCoversChange=false")
    }
}
