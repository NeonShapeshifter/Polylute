package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.lostf1sh.pixelplayeross.data.offline.*
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B52ScreenContractTest {
    @get:Rule val compose = createComposeRule()
    private fun show(done: Boolean = false, paused: Boolean = false) {
        val pages = flowOf(PagingData.from(listOf(InfoArtist("lumen", "Lumen Vale"))))
        compose.setContent {
            DapTheme(darkTheme = true) {
                MusicInfoDownloadsContent(pages.collectAsLazyPagingItems(), "", {}, InfoQueue(paused = paused),
                    InfoProgress(5, 5, 0), InfoProgress(5, if (done) 5 else 2, 0),
                    InfoSelection(0, 0, null, 0), false, false, { flowOf(emptyList()) },
                    {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
            }
        }
        compose.waitForIdle()
    }
    @Test fun `three steps and type summary lead to advanced selection`() {
        show()
        compose.onNodeWithTag("info-steps").assertExists()
        compose.onNodeWithText("Elegir por artista o álbum").performClick()
        compose.onNodeWithText("Por artista o álbum").assertIsDisplayed()
        compose.onNodeWithText("Lumen Vale", useUnmergedTree = true).assertExists()
    }
    @Test fun `done replaces pause with accept`() {
        show(done = true)
        compose.onNodeWithText("Listo").assertIsDisplayed()
        compose.onNodeWithText("Aceptar").assertIsDisplayed()
        compose.onNodeWithText("Pausar").assertDoesNotExist()
    }
    @Test fun `pause explains finding continues and offers cancel`() {
        show(paused = true)
        compose.onNodeWithText("No se descarga nada hasta que reanudes. La búsqueda no se detiene.").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").assertIsDisplayed()
        compose.onNodeWithText("Reanudar").assertIsDisplayed()
    }
}
