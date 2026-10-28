package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.*
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class B43RetiredPreferencesTest {
    @Test fun `neutral and vibrant stay in storage but the player reads the default cover palette`() = runTest {
        val dir = Files.createTempDirectory("b43-prefs").toFile()
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("test.preferences_pb") })
            val player = stringPreferencesKey("player_theme_preference_v2")
            val style = stringPreferencesKey("album_art_palette_style_v1")
            store.edit { it[player] = ThemePreference.DYNAMIC; it[style] = AlbumArtPaletteStyle.VIBRANT.storageKey }
            val repository = ThemePreferencesRepository(store)
            assertThat(repository.playerThemePreferenceFlow.first()).isEqualTo(ThemePreference.ALBUM_ART)
            assertThat(repository.albumArtPaletteStyleFlow.first()).isEqualTo(AlbumArtPaletteStyle.TONAL_SPOT)
            assertThat(store.data.first()[player]).isEqualTo(ThemePreference.DYNAMIC)
            assertThat(store.data.first()[style]).isEqualTo(AlbumArtPaletteStyle.VIBRANT.storageKey)
        } finally { dir.deleteRecursively() }
    }
}
