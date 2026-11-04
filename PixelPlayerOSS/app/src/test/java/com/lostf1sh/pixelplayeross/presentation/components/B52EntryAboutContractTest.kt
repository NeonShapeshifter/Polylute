package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import com.google.common.truth.Truth.assertThat
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.lostf1sh.pixelplayeross.data.musicinfo.Description
import com.lostf1sh.pixelplayeross.data.musicinfo.ArtistPhoto
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "es-w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B52EntryAboutContractTest {
    @get:Rule val compose = createComposeRule()
    private val about = MusicInfoAboutState(Description("Texto de ejemplo. ".repeat(40), "Mareas", "https://es.wikipedia.org/wiki/Ejemplo", "es"))
    @Test fun `read more less and attribution are visible without opening credits`() {
        compose.setContent { DapTheme(darkTheme=true) { MusicInfoAbout(about,mountInputBridge=false) } }
        compose.onNodeWithText("De Wikipedia · CC BY-SA 4.0").assertIsDisplayed()
        compose.onNodeWithText("Leer más").performClick()
        compose.onNodeWithText("Leer menos").assertExists()
    }
    @Test fun `credits separate text photo and identification`() {
        compose.setContent { DapTheme(darkTheme=true) { MusicInfoAbout(about,mountInputBridge=false) } }
        compose.onNodeWithContentDescription("Créditos y licencias").performClick()
        compose.onNodeWithText("TEXTO").assertExists()
        compose.onNodeWithText("IDENTIFICACIÓN").assertExists()
        // F3 has a 150 dp dialog offset and 24 dp content padding.
        assertThat(compose.onNodeWithText("Créditos").getUnclippedBoundsInRoot().top.value)
            .isWithin(0.5f).of(174f)
        compose.onNodeWithText("https://es.wikipedia.org/wiki/Ejemplo").assertExists()
    }
    @Test fun `all page menu actions use the new flow name and delete wording`() {
        compose.setContent { DapTheme(darkTheme=true) { Column { MusicInfoMenuItems({}, {}, {}) } } }
        compose.onNodeWithText("Descargar información musical…").assertExists()
        compose.onNodeWithText("Borrar información descargada").assertExists()
    }
}
