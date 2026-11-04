package com.lostf1sh.pixelplayeross.presentation

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreference
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreferencesRepository
import com.lostf1sh.pixelplayeross.presentation.components.scoped.SheetThemeState
import com.lostf1sh.pixelplayeross.presentation.components.scoped.rememberSheetThemeState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemePair
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.dapMenuContainer
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B57 2.1: the full player, the queue and the DAP lock screen take their accent from "App colours". Grey cover + purple accent, in each of the three modes: "Theme and accent"
 * is purple, "From album art" and "Cover glass" are the cover's grey. Also the measured fill of the Library menu in "Theme and accent" (it carries the accent's tone).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B57PlayerAccentFollowsAppColoursTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `the player preference follows App colours in each of its three values`() = runTest {
        val dir = Files.createTempDirectory("b57-theme").toFile()
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("t.preferences_pb") })
            val repository = ThemePreferencesRepository(store)
            val key = stringPreferencesKey("app_color_source_v1")
            assertThat(repository.playerThemePreferenceFlow.first()).isEqualTo(ThemePreference.DEFAULT) // never chosen = Theme and accent
            for ((source, expected) in listOf(
                AppColorSource.THEME to ThemePreference.DEFAULT,
                AppColorSource.ALBUM_ART to ThemePreference.ALBUM_ART,
                AppColorSource.COVER_GLASS to ThemePreference.ALBUM_ART,
            )) {
                repository.setAppColorSource(source)
                assertThat(repository.playerThemePreferenceFlow.first()).isEqualTo(expected)
            }
            assertThat(key.name).isNotEmpty()
        } finally { dir.deleteRecursively() }
    }

    private val grey = Color(0xFF9E9E9E)
    private val greyScheme = darkColorScheme(primary = grey, primaryContainer = Color(0xFF3A3A3A), secondary = grey, tertiary = grey, surface = Color(0xFF121212))
    private val song = Song(
        id = "1", title = "Grey", artist = "A", artistId = 1, album = "B", albumId = 1, path = "", contentUriString = "", albumArtUriString = "content://cover/grey", duration = 1,
        mimeType = null, bitrate = null, sampleRate = null,
    )

    private var sheet: SheetThemeState? = null
    private var effectiveAccent: Color? = null
    private var appAccent: Color? = null

    @Composable private fun Probe(preference: String) {
        val state = rememberSheetThemeState(
            activePlayerSchemePair = ColorSchemePair(light = greyScheme, dark = greyScheme), isDarkTheme = true, playerThemePreference = preference,
            currentSong = song, themedAlbumArtUri = song.albumArtUriString, preparingSongId = null, systemColorScheme = darkColorScheme(), animateAlbumScheme = false,
        )
        sheet = state
        // what the full player does with it: the song's accent when it has one, else the app's (the purple the listener chose)
        appAccent = LocalDapAccent.current
        effectiveAccent = state.albumAccent ?: LocalDapAccent.current
    }

    private fun accentFor(preference: String): Color {
        compose.setContent { DapTheme(darkTheme = true, baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Purple) { Probe(preference) } }
        compose.waitForIdle()
        return effectiveAccent!!
    }

    @Test fun `Theme and accent shows the listener's purple over a grey cover`() {
        val accent = accentFor(ThemePreference.DEFAULT)
        assertThat(sheet!!.albumAccent).isNull()
        assertThat(accent.toArgb()).isEqualTo(appAccent!!.toArgb())
        assertThat(kotlin.math.abs(accent.blue - accent.green)).isGreaterThan(0.05f) // purple, not grey
    }

    @Test fun `From album art shows the cover's grey`() {
        val accent = accentFor(ThemePreference.ALBUM_ART)
        assertThat(sheet!!.albumAccent).isNotNull()
        assertThat(kotlin.math.abs(accent.red - accent.blue)).isLessThan(0.06f)
        assertThat(accent.toArgb()).isNotEqualTo(appAccent!!.toArgb())
    }

    @Test fun `the Library menu's fill in Theme and accent carries the purple tone, measured`() {
        var plain = 0
        var tinted = 0
        compose.setContent {
            DapTheme(darkTheme = true, baseTheme = DapBaseTheme.Graphite, accent = DapAccent.Purple) {
                tinted = dapMenuContainer().toArgb()
                plain = com.lostf1sh.pixelplayeross.ui.ds.Dap.surfaceRaised.toArgb()
            }
        }
        compose.waitForIdle()
        println("B57 2.1 measured Library menu container, Graphite + Purple, Theme and accent: #%08X (raised surface alone: #%08X)".format(tinted, plain))
        val tintedColor = Color(tinted)
        val plainColor = Color(plain)
        // purple is bluer and redder than the raised surface's neutral: the menu leans purple
        assertThat(tintedColor.blue - tintedColor.green).isGreaterThan(plainColor.blue - plainColor.green)
        assertThat(tinted).isNotEqualTo(plain)
    }
}
