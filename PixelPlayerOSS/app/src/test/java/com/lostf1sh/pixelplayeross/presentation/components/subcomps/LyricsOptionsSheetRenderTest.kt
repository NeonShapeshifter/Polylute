package com.lostf1sh.pixelplayeross.presentation.components.subcomps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.RenderChecks
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B22, Part 4: the lyrics options sheet (`LyricsMenu.dc.html`): its three groups, the design's texts, and that every control
 * calls what it always called, in English, Spanish and Swedish at 360 dp without a clipped row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
open class LyricsOptionsSheetRenderTest {

    /** The palette the sheet is drawn in; the Light subclass runs every test again on the light theme. */
    protected open val palette: com.lostf1sh.pixelplayeross.ui.ds.DapPalette = com.lostf1sh.pixelplayeross.ui.ds.DapPalette.Graphite

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)

    private val calls = mutableListOf<String>()

    @Composable
    private fun Sheet(
        syncOffsetMs: Int? = 0,
        alignment: String = "center",
        hasTranslation: Boolean = true,
        canSearch: Boolean = true,
    ) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        androidx.compose.runtime.CompositionLocalProvider(com.lostf1sh.pixelplayeross.ui.ds.LocalDapPalette provides palette) {
        Box(Modifier.fillMaxSize().background(Dap.background)) {
            LyricsOptionsSheet(
                onDismissRequest = { calls += "dismiss" },
                sheetState = state,
                syncOffsetMs = syncOffsetMs,
                onSyncOffsetChange = { calls += "sync:$it" },
                lyricsAlignment = alignment,
                onLyricsAlignmentChange = { calls += "align:$it" },
                hasTranslatedLyrics = hasTranslation,
                showTranslation = false,
                onShowTranslationChange = { calls += "translation:$it" },
                hasRomanizedLyrics = false,
                showRomanization = false,
                onShowRomanizationChange = { calls += "romanization:$it" },
                keepScreenOn = false,
                onKeepScreenOnChange = { calls += "keepOn:$it" },
                canSearch = canSearch,
                onSearchLyrics = { calls += "search" },
                onImportLyrics = { calls += "import" },
                canSave = true,
                onSaveLyrics = { calls += "save" },
                canReset = true,
                onResetLyrics = { calls += "reset" },
                mountInputBridge = false,
            )
        }
        }
    }

    @Test
    fun `it has the design's three groups and rows, and every control calls its action`() {
        compose.setContent { Sheet() }
        compose.waitForIdle()
        for (id in listOf(
            R.string.lyrics_menu_title, R.string.lyrics_menu_section_song, R.string.lyrics_menu_section_reading, R.string.lyrics_menu_section_file,
            R.string.lyrics_menu_sync, R.string.lyrics_menu_sync_sub, R.string.lyrics_menu_alignment,
            R.string.lyrics_menu_align_left, R.string.lyrics_menu_align_center,
            R.string.lyrics_menu_translation, R.string.lyrics_menu_romanization, R.string.lyrics_menu_keep_screen_on,
            R.string.lyrics_menu_search, R.string.lyrics_menu_import, R.string.lyrics_menu_save,
        )) compose.onAllNodesWithText(s(id))[0].assertExists()

        // The offset steps by 0.1 s; the value is mono and shows the sign.
        compose.onNodeWithContentDescription(s(R.string.lyrics_menu_sync_earlier)).performClick()
        compose.onNodeWithContentDescription(s(R.string.lyrics_menu_sync_later)).performClick()
        compose.onNodeWithText(s(R.string.lyrics_menu_align_left)).performClick()
        compose.onNodeWithText(s(R.string.lyrics_menu_keep_screen_on)).performScrollTo()
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick() // translation
        compose.onNodeWithText(s(R.string.lyrics_menu_search)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.lyrics_menu_import)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.lyrics_menu_save)).performScrollTo().performClick()
        assertEquals(
            listOf("sync:-100", "sync:100", "align:left", "translation:true", "search", "import", "save"),
            calls,
        )
        RenderChecks.assertInsideWindow(compose, "lyrics options")
        RenderChecks.assertNoCutText(compose, "lyrics options")
    }

    @Test
    fun `without synced lyrics there is no sync row, and without online lyrics there is no search`() {
        compose.setContent { Sheet(syncOffsetMs = null, canSearch = false) }
        compose.waitForIdle()
        compose.onAllNodesWithText(s(R.string.lyrics_menu_sync)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.lyrics_menu_section_song)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.lyrics_menu_search)).assertCountEquals(0)
        compose.onNodeWithText(s(R.string.lyrics_menu_import)).assertExists()
    }

    @Test
    fun `translation and romanization are there but cannot be switched when the lyrics have none`() {
        compose.setContent { Sheet(hasTranslation = false) }
        compose.waitForIdle()
        compose.onAllNodes(isToggleable())[0].assertIsNotEnabled()
        compose.onAllNodes(isToggleable())[2].assertIsEnabled() // keep the screen on
    }

    @Test
    fun `the offset is written with its sign and one decimal`() {
        assertEquals("+0.0 s".replace('.', java.text.DecimalFormatSymbols.getInstance().decimalSeparator), formatSyncOffset(0))
        assertEquals("-0.3 s".replace('.', java.text.DecimalFormatSymbols.getInstance().decimalSeparator), formatSyncOffset(-300))
    }
}

/** In Spanish. */
@OptIn(ExperimentalMaterial3Api::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-w360dp-h805dp-xxhdpi")
class LyricsOptionsSheetRenderSpanishTest : LyricsOptionsSheetRenderTest()

/** And in Swedish. */
@OptIn(ExperimentalMaterial3Api::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "sv-w360dp-h805dp-xxhdpi")
class LyricsOptionsSheetRenderSwedishTest : LyricsOptionsSheetRenderTest()

/** And on the light theme: every colour is a token, so nothing is white on white. */
@OptIn(ExperimentalMaterial3Api::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h805dp-xxhdpi")
class LyricsOptionsSheetRenderLightTest : LyricsOptionsSheetRenderTest() {
    override val palette = com.lostf1sh.pixelplayeross.ui.ds.DapPalette.Light
}
