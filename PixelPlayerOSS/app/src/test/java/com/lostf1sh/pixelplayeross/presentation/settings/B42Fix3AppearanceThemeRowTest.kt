package com.lostf1sh.pixelplayeross.presentation.settings

import com.lostf1sh.pixelplayeross.data.preferences.LyricsMode
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B42-fix3 (item 7): **the Theme row is hidden in the two cover modes, and shown with "Theme and accent".**
 *
 * B41-fix1 (B1) gave the cover modes a Theme row of their own (Dark, Light, Follow the system). The owner: "there's no light/dark like before any
 * more: now it's the cover in full". The cover decides the lightness as well as the colours, the ink is always light and the app is drawn on
 * Graphite, so there is nothing for the row to say. The accent stays as one small row, "Accent without a cover" (it is what a song with no
 * cover wears), and with "Theme and accent" the five themes and the whole palette are what they were.
 *
 * The preference the row used to write (`cover_theme_choice_v1`) is **kept, unused**: nothing is deleted or migrated (`CoverThemeChoiceTest`
 * pins that it still round-trips). The sheet that lists a row's choices needs the app's Hilt graph, which a JVM test does not have, so the
 * row's own face (its label, its description, its value) is what is read from the page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h2400dp-xxhdpi")
class B42Fix3AppearanceThemeRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)

    private class Recorder : AppearanceSettingsActions {
        override fun onAppLanguageChange(value: String) {}
        override fun onAccentChange(value: DapAccent) {}
        override fun onAppColorSourceChange(value: String) {}
        override fun onCoverThemeChoiceChange(value: String) {}
        override fun onPlayerThemePreferenceChange(value: String) {}
        override fun onShowBookmarkButtonChange(value: Boolean) {}
        override fun onShowPlayerFileInfoChange(value: Boolean) {}
        override fun onAlbumArtQualityChange(value: com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality) {}
        override fun onLaunchTabChange(value: String) {}
        override fun onLibraryNavigationModeChange(value: String) {}
        override fun onThemeChoiceChange(choice: com.lostf1sh.pixelplayeross.ui.theme.DapAppearanceChoice) {}
        override fun onOpenPaletteStyle() {}
        override fun onCoverGlassMovingChange(value: Boolean) {}
        override fun onLyricsModeChange(value: LyricsMode) {}
        override fun onImmersiveLyricsEnabledChange(value: Boolean) {}
        override fun onImmersiveLyricsTimeoutChange(value: Long) {}
        override fun onLyricsAlignmentChange(value: String) {}
        override fun onShowLyricsTranslationChange(value: Boolean) {}
        override fun onShowLyricsRomanizationChange(value: Boolean) {}
        override fun onUseAnimatedLyricsChange(value: Boolean) {}
        override fun onAnimatedLyricsBlurEnabledChange(value: Boolean) {}
        override fun onAnimatedLyricsBlurStrengthChange(value: Float) {}
        override fun onDapLockScreenEnabledChange(value: Boolean) {}
    }

    private val actions = Recorder()

    private fun show(source: String, accent: DapAccent = DapAccent.Amber) {
        val state = AppearanceSettingsState.from(
            SettingsUiState(appColorSource = source, baseTheme = DapBaseTheme.Navy, appThemeMode = "dark", accent = accent),
        )
        compose.setContent {
            DapTheme(baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Sage) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                    AppearanceSettingsScreen(state, actions)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun the_theme_row_is_hidden_in_from_album_art() {
        show(AppColorSource.ALBUM_ART)
        assertEquals(0, count(s(R.string.setcat_theme_label)))
        assertEquals(0, count(s(R.string.setcat_theme_desc)))
        assertEquals(0, count(s(R.string.setcat_theme_option_navy)))
    }

    @Test
    fun the_theme_row_is_hidden_in_cover_glass() {
        show(AppColorSource.COVER_GLASS)
        assertEquals(0, count(s(R.string.setcat_theme_label)))
        assertEquals(0, count(s(R.string.setcat_theme_desc)))
        assertEquals(0, count(s(R.string.setcat_theme_option_navy)))
    }

    @Test
    fun the_theme_row_is_shown_with_theme_and_accent_with_the_theme_the_listener_has() {
        show(AppColorSource.THEME)
        assertEquals(1, count(s(R.string.setcat_theme_label)))
        assertEquals(1, count(s(R.string.setcat_theme_desc)))
        // the stored pair (Navy) is what the row shows
        assertEquals(1, count(s(R.string.setcat_theme_option_navy)))
    }

    @Test
    fun while_the_cover_decides_the_accent_is_one_small_row_not_the_palette() {
        show(AppColorSource.COVER_GLASS, accent = DapAccent.Amber)
        assertEquals(1, count(s(R.string.setcat_accent_without_cover_label)))
        // it names the accent in use and carries the one line that says what it is for
        assertEquals(1, count(DapAccent.Amber.label))
        assertEquals(1, count(s(R.string.setcat_accent_desc_album_art)))
        // not fifteen swatches, and not the palette's own title
        for (accent in DapAccent.entries) compose.onAllNodesWithContentDescription(accent.label).assertCountEquals(0)
        assertEquals(0, count(s(R.string.setcat_accent_label)))
    }

    @Test
    fun with_theme_and_accent_the_whole_palette_is_there_as_before() {
        show(AppColorSource.THEME)
        for (accent in DapAccent.entries) compose.onAllNodesWithContentDescription(accent.label).assertCountEquals(1)
        assertEquals(1, count(s(R.string.setcat_accent_label)))
        assertEquals(0, count(s(R.string.setcat_accent_without_cover_label)))
    }

    @Test
    fun the_background_motion_row_is_for_cover_glass_only() {
        show(AppColorSource.COVER_GLASS)
        assertEquals(1, count(s(R.string.setcat_background_motion_title)))
    }

    @Test
    fun the_background_motion_row_is_not_offered_for_from_album_art_which_does_not_move() {
        show(AppColorSource.ALBUM_ART)
        assertEquals(0, count(s(R.string.setcat_background_motion_title)))
    }

    // ── three languages ─────────────────────────────────────────────────────────────────────────────

    private fun stringsOf(folder: String): Map<String, String> {
        val text = File("src/main/res/$folder/strings_settings.xml").readText()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun the_words_of_the_colour_source_are_in_english_spanish_and_swedish_and_are_different_words() {
        val names = listOf("setcat_app_colors_desc", "setcat_accent_without_cover_label")
        val en = stringsOf("values")
        val es = stringsOf("values-es")
        val sv = stringsOf("values-sv")
        for (name in names) {
            val e = en[name]
            val sp = es[name]
            val sw = sv[name]
            assertTrue("$name is missing in English", !e.isNullOrBlank())
            assertTrue("$name is missing in Spanish", !sp.isNullOrBlank())
            assertTrue("$name is missing in Swedish", !sw.isNullOrBlank())
            assertTrue("$name reads the same in English and Spanish", e != sp)
            assertTrue("$name reads the same in English and Swedish", e != sw)
        }
        // the strings only the removed row used are gone from all three, together
        for (map in listOf(en, es, sv)) {
            assertTrue(map["setcat_theme_desc_cover"] == null)
            assertTrue(map["setcat_theme_option_dark"] == null)
        }
    }
}
