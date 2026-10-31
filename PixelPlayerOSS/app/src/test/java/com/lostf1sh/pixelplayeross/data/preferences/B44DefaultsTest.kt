package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.*
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

class B44DefaultsTest {
    @Test fun `new defaults prefer local lyrics scan sidecars and show genres as rows with remote lyrics off`() = runTest {
        val dir = Files.createTempDirectory("b44-defaults").toFile()
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("prefs.preferences_pb") })
            val repo = UserPreferencesRepository(store, Json)
            assertThat(repo.lyricsSourcePreferenceFlow.first()).isEqualTo(LyricsSourcePreference.LOCAL_FIRST)
            assertThat(repo.autoScanLrcFilesFlow.first()).isTrue()
            assertThat(repo.isGenreGridViewFlow.first()).isFalse()
            assertThat(repo.externalLyricsEnabledFlow.first()).isFalse()
        } finally { dir.deleteRecursively() }
    }

    @Test fun `stored choices keep their original keys and win over the changed defaults`() = runTest {
        val dir = Files.createTempDirectory("b44-stored").toFile()
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("prefs.preferences_pb") })
            store.edit { preferences ->
                preferences[stringPreferencesKey("lyrics_source_preference")] = LyricsSourcePreference.API_FIRST.name
                preferences[booleanPreferencesKey("auto_scan_lrc_files")] = false
                preferences[booleanPreferencesKey("is_genre_grid_view")] = true
                preferences[booleanPreferencesKey("external_lyrics_enabled")] = true
            }
            val repo = UserPreferencesRepository(store, Json)
            assertThat(repo.lyricsSourcePreferenceFlow.first()).isEqualTo(LyricsSourcePreference.API_FIRST)
            assertThat(repo.autoScanLrcFilesFlow.first()).isFalse()
            assertThat(repo.isGenreGridViewFlow.first()).isTrue()
            assertThat(repo.externalLyricsEnabledFlow.first()).isTrue()
        } finally { dir.deleteRecursively() }
    }
}
