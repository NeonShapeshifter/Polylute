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
 * B40-F5: immersive lyrics are back in 1.1. They were hidden in 1.0 (B32-Q7) behind one gate; the gate is kept, and open. What the page
 * does when the mode runs is pinned in `LyricsImmersiveRenderTest` and `ImmersiveLyricsPolicyTest`.
 */
class B40ImmersiveLyricsAvailableTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private val sources = listOf("src/main/java/com/lostf1sh/pixelplayeross", "app/src/main/java/com/lostf1sh/pixelplayeross")
        .map(::File).first { it.isDirectory }

    private fun source(relative: String) = File(sources, relative).readText()

    private fun repository(): Pair<UserPreferencesRepository, DataStore<Preferences>> {
        val file = Files.createTempFile("b40-immersive", ".preferences_pb").toFile()
        file.delete()
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return UserPreferencesRepository(dataStore = store, json = json) to store
    }

    @Test
    fun `immersive lyrics are offered in 1_1 and what is stored is what is in force`() {
        assertThat(ImmersiveLyrics.AVAILABLE).isTrue()
        assertThat(ImmersiveLyrics.effective(stored = true)).isTrue()
        assertThat(ImmersiveLyrics.effective(stored = false)).isFalse()
    }

    @Test
    fun `the gate is kept - closed it still answers off whatever is stored`() {
        assertThat(ImmersiveLyrics.effective(stored = true, available = false)).isFalse()
        assertThat(ImmersiveLyrics.effective(stored = false, available = false)).isFalse()
    }

    @Test
    fun `a listener who had it on in 1_0 gets it back unchanged, and a new one starts with it off at four seconds`() = runTest {
        val (prefs, store) = repository()
        assertThat(prefs.immersiveLyricsEnabledFlow.first()).isFalse()
        assertThat(prefs.immersiveLyricsTimeoutFlow.first()).isEqualTo(4000L)

        // what an install that had it on (and a six-second delay) holds in its file since 1.0
        store.edit { it[booleanPreferencesKey("immersive_lyrics_enabled")] = true }
        prefs.setImmersiveLyricsTimeout(6000L)
        assertThat(prefs.immersiveLyricsEnabledFlow.first()).isTrue()
        assertThat(prefs.immersiveLyricsTimeoutFlow.first()).isEqualTo(6000L)
    }

    @Test
    fun `the lyrics sheet still asks the gate before it starts the mode`() {
        val sheet = source("presentation/components/LyricsSheet.kt")
        assertThat(sheet).contains("ImmersiveLyrics.effective(immersiveLyricsEnabled)")
        assertThat(sheet).doesNotContain("if (immersiveLyricsEnabled &&")
    }

    @Test
    fun `the page draws the switch and its delay behind the gate, and the gate is open`() {
        // B41: how lyrics read is Appearance's now (it was Settings, Lyrics); the gate and its words are the same
        val page = source("presentation/settings/AppearanceSettings.kt")
        val gated = page.substringAfter("if (ImmersiveLyrics.AVAILABLE) {")
        assertThat(gated).contains("setcat_immersive_lyrics_title")
        assertThat(gated).contains("setcat_auto_hide_delay_label")
        assertThat(ImmersiveLyrics.AVAILABLE).isTrue()
    }

    @Test
    fun `the lyrics menu and the player still carry no second switch for it`() {
        // The one place that turns it on and off is Settings > Appearance > Lyrics (B41; it was Lyrics); "not this time" stays a view-model flag with no button.
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
}
