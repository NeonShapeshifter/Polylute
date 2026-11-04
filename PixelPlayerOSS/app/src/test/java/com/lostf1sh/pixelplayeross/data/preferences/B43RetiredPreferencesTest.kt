package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.*
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class B43RetiredPreferencesTest {
    @Test fun `neutral and vibrant stay in storage but are ignored and the player follows App colours and reads the default cover palette`() = runTest {
        val dir = Files.createTempDirectory("b43-prefs").toFile()
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("test.preferences_pb") })
            val player = stringPreferencesKey("player_theme_preference_v2")
            val style = stringPreferencesKey("album_art_palette_style_v1")
            store.edit { it[player] = ThemePreference.DYNAMIC; it[style] = AlbumArtPaletteStyle.VIBRANT.storageKey }
            val repository = ThemePreferencesRepository(store)
            // B57 2.1 changes this assertion on purpose: the retired key is still ignored, but the player now follows "App colours" (unset = "Theme and accent" = the
            // listener's accent), so it no longer reads ALBUM_ART whatever is stored. `B57PlayerAccentFollowsAppColoursTest` covers the three modes.
            assertThat(repository.playerThemePreferenceFlow.first()).isEqualTo(ThemePreference.DEFAULT)
            assertThat(repository.albumArtPaletteStyleFlow.first()).isEqualTo(AlbumArtPaletteStyle.TONAL_SPOT)
            assertThat(store.data.first()[player]).isEqualTo(ThemePreference.DYNAMIC)
            assertThat(store.data.first()[style]).isEqualTo(AlbumArtPaletteStyle.VIBRANT.storageKey)
        } finally { dir.deleteRecursively() }
    }
}
