package com.lostf1sh.pixelplayeross.presentation.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.worker.SyncProgress
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B52SettingsContractTest {
    @get:Rule val compose = createComposeRule()
    private val actions = io.mockk.mockk<LibrarySettingsActions>(relaxed=true)
    @Before fun authority() {
        val prefs = io.mockk.mockk<com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository>()
        io.mockk.every { prefs.backNavigationModeFlow } returns kotlinx.coroutines.flow.flowOf(com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode.DEFAULT)
        val point = object : com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint {
            override fun safetyVolumeController() = io.mockk.mockk<com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController>(relaxed=true)
            override fun userPreferencesRepository() = prefs
        }
        io.mockk.mockkStatic(dagger.hilt.android.EntryPointAccessors::class)
        io.mockk.every { dagger.hilt.android.EntryPointAccessors.fromApplication(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(), com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint::class.java) } returns point
    }
    @After fun reset() = io.mockk.unmockkAll()
    @Test fun `delete all requires confirmation and preserves local files in its explanation`() {
        val state = LibrarySettingsState.from(SettingsUiState(musicInfoUsedBytes=14_600_000),false,SyncProgress())
        compose.setContent { DapTheme(darkTheme=true) { Column(Modifier.verticalScroll(rememberScrollState())) { LibrarySettingsScreen(state,actions) } } }
        compose.onNodeWithText("Borrar toda la información descargada").performScrollTo().performClick()
        compose.onNodeWithText("¿Borrar toda la información descargada?").assertIsDisplayed()
        // E3 has a 190 dp dialog offset and 24 dp content padding.
        assertThat(compose.onNodeWithText("¿Borrar toda la información descargada?").getUnclippedBoundsInRoot().top.value)
            .isWithin(0.5f).of(214f)
        compose.onNodeWithText("Lo que ya estaba en tus archivos de música no se toca.").assertIsDisplayed()
        io.mockk.verify(exactly=0) { actions.onDeleteMusicInfo(any()) }
        compose.onNodeWithText("Cancelar").performClick()
        io.mockk.verify(exactly=0) { actions.onDeleteMusicInfo(any()) }
    }
}
