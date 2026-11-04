package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** B56 phase 3.4 (#24): three lyrics modes replace the "Lyrics under the cover" switch; the old boolean is read once. */
class B56LyricsModeTest {
    @Test
    fun `the three modes say what the player draws`() {
        assertThat(LyricsMode.OVER_COVER.showsLinesOnPlayer).isTrue()
        assertThat(LyricsMode.OVER_COVER.hasLyricsPage).isTrue()
        assertThat(LyricsMode.FULL_PAGE.showsLinesOnPlayer).isFalse()
        assertThat(LyricsMode.FULL_PAGE.hasLyricsPage).isTrue()
        assertThat(LyricsMode.OFF.showsLinesOnPlayer).isFalse()
        assertThat(LyricsMode.OFF.hasLyricsPage).isFalse()
    }

    @Test
    fun `the old boolean maps once, true to over the cover and false to full page`() {
        assertThat(LyricsMode.fromLegacyBoolean(true)).isEqualTo(LyricsMode.OVER_COVER)
        assertThat(LyricsMode.fromLegacyBoolean(false)).isEqualTo(LyricsMode.FULL_PAGE)
        assertThat(LyricsMode.fromLegacyBoolean(null)).isEqualTo(LyricsMode.OVER_COVER)
    }

    @Test
    fun `storage keys round trip and an unknown key is nothing`() {
        for (mode in LyricsMode.entries) assertThat(LyricsMode.fromStorageKey(mode.storageKey)).isEqualTo(mode)
        assertThat(LyricsMode.fromStorageKey("diagonal")).isNull()
        assertThat(LyricsMode.fromStorageKey(null)).isNull()
    }

    private fun store(scope: kotlinx.coroutines.CoroutineScope, dir: java.nio.file.Path) =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { dir.resolve("s.preferences_pb").toFile() })

    @Test
    fun `an install with the old switch on opens on over the cover, with it off on full page, and a nothing stored one on the default`() = runTest {
        val dir = Files.createTempDirectory("b56-lyrics-mode")
        try {
            val store = store(backgroundScope, dir)
            val user = UserPreferencesRepository(dataStore = store, json = Json)
            assertThat(user.lyricsModeFlow.first()).isEqualTo(LyricsMode.OVER_COVER)

            store.edit { it[booleanPreferencesKey("lyrics_under_cover_v1")] = false }
            assertThat(user.lyricsModeFlow.first()).isEqualTo(LyricsMode.FULL_PAGE)

            store.edit { it[booleanPreferencesKey("lyrics_under_cover_v1")] = true }
            assertThat(user.lyricsModeFlow.first()).isEqualTo(LyricsMode.OVER_COVER)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `once a mode is chosen the new key rules and the old boolean is not read again`() = runTest {
        val dir = Files.createTempDirectory("b56-lyrics-mode-set")
        try {
            val store = store(backgroundScope, dir)
            val user = UserPreferencesRepository(dataStore = store, json = Json)
            store.edit { it[booleanPreferencesKey("lyrics_under_cover_v1")] = false }

            user.setLyricsMode(LyricsMode.OFF)
            assertThat(user.lyricsModeFlow.first()).isEqualTo(LyricsMode.OFF)
            assertThat(store.data.first()[stringPreferencesKey("lyrics_mode_v1")]).isEqualTo("off")

            store.edit { it[booleanPreferencesKey("lyrics_under_cover_v1")] = true }       // a restored old backup does not undo the choice
            assertThat(user.lyricsModeFlow.first()).isEqualTo(LyricsMode.OFF)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
