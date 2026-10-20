package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.ImmersiveLyrics
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B32-Q7: immersive lyrics are hidden in 1.0. No entry point is drawn (the Lyrics page's switch and its delay: the render half is in
 * `SettingsPagesRenderTest`; the lyrics menu and the player have none, pinned below), and a stored "on" does not start the mode: the
 * repository answers "off" while the feature is hidden, so a listener cannot be left in a mode whose switch has gone.
 */
class B32ImmersiveLyricsHiddenTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private val sources = listOf("src/main/java/com/lostf1sh/pixelplayeross", "app/src/main/java/com/lostf1sh/pixelplayeross")
        .map(::File).first { it.isDirectory }

    private fun source(relative: String) = File(sources, relative).readText()

    @Test
    fun `immersive lyrics are not available in 1_0`() {
        assertThat(ImmersiveLyrics.AVAILABLE).isFalse()
        assertThat(ImmersiveLyrics.effective(stored = true)).isFalse()
        assertThat(ImmersiveLyrics.effective(stored = false)).isFalse()
    }

    @Test
    fun `the code is kept - turning it available gives the stored choice back unchanged`() {
        assertThat(ImmersiveLyrics.effective(stored = true, available = true)).isTrue()
        assertThat(ImmersiveLyrics.effective(stored = false, available = true)).isFalse()
    }

    @Test
    fun `a stored on is read as off by the preference the player and the lyrics sheet read`() = runTest {
        val file = Files.createTempFile("b32-immersive", ".preferences_pb").toFile()
        file.delete()
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val prefs = UserPreferencesRepository(dataStore = store, json = json)

        // what an install that had it on holds in its file
        store.edit { it[booleanPreferencesKey("immersive_lyrics_enabled")] = true }
        assertThat(prefs.immersiveLyricsEnabledFlow.first()).isFalse()

        // the choice itself is not thrown away: the timeout and the stored switch are still in the file for 1.1
        prefs.setImmersiveLyricsTimeout(6000L)
        assertThat(prefs.immersiveLyricsTimeoutFlow.first()).isEqualTo(6000L)
        assertThat(store.data.first()[booleanPreferencesKey("immersive_lyrics_enabled")]).isTrue()
    }

    @Test
    fun `the lyrics sheet asks the gate before it starts the mode`() {
        val sheet = source("presentation/components/LyricsSheet.kt")
        assertThat(sheet).contains("ImmersiveLyrics.effective(immersiveLyricsEnabled)")
        assertThat(sheet).doesNotContain("if (immersiveLyricsEnabled &&")
    }

    @Test
    fun `no menu and no player button offers immersive lyrics`() {
        // The lyrics menu (B22) never carried the temporary switch, and the player's header and controls have no button for it.
        val offered = listOf(
            "presentation/components/subcomps/LyricsOptionsSheet.kt",
            "presentation/components/player/PlayerControlsSurface.kt",
            "presentation/components/player/PlayerTrackInfoSurface.kt",
            "ui/ds/DapChrome.kt",
        ).filter { relative ->
            val code = source(relative).lines().joinToString("\n") { it.substringBefore("//") }
            code.contains("onImmersiveTemporarilyDisabledChange") || code.contains("immersiveLyrics", ignoreCase = true)
        }
        assertThat(offered).isEmpty()
    }

    @Test
    fun `the Lyrics page draws the switch and its delay only behind the gate`() {
        val page = source("presentation/settings/LyricsSettings.kt")
        val gated = page.substringAfter("if (ImmersiveLyrics.AVAILABLE) {")
        assertThat(gated).contains("setcat_immersive_lyrics_title")
        assertThat(gated).contains("setcat_auto_hide_delay_label")
        // and nothing about it is drawn before the gate
        assertThat(page.substringBefore("if (ImmersiveLyrics.AVAILABLE) {")).doesNotContain("setcat_immersive_lyrics_title")
    }
}
