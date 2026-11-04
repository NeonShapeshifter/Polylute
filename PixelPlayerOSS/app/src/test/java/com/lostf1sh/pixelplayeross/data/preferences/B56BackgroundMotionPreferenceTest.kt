package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** B56 phase 7.1: Background motion over the two keys the app stores; nothing renamed, nothing migrated. */
class B56BackgroundMotionPreferenceTest {
    @Test
    fun `an install with the old switch on is gentle, with it off still, and the keys are the ones it always had`() = runTest {
        val dir = Files.createTempDirectory("b56-motion")
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("s.preferences_pb").toFile() })
            val theme = ThemePreferencesRepository(store)
            assertThat(theme.backgroundMotionFlow.first()).isEqualTo(BackgroundMotion.GENTLE)           // nothing stored

            store.edit { it[booleanPreferencesKey("cover_glass_moving_v1")] = false }
            assertThat(theme.backgroundMotionFlow.first()).isEqualTo(BackgroundMotion.STILL)

            store.edit { it[booleanPreferencesKey("cover_glass_moving_v1")] = true }
            assertThat(theme.backgroundMotionFlow.first()).isEqualTo(BackgroundMotion.GENTLE)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `choosing a value writes the two keys, and the old switch keeps meaning moving or not`() = runTest {
        val dir = Files.createTempDirectory("b56-motion-set")
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("s.preferences_pb").toFile() })
            val theme = ThemePreferencesRepository(store)

            theme.setBackgroundMotion(BackgroundMotion.LIVELY)
            assertThat(theme.backgroundMotionFlow.first()).isEqualTo(BackgroundMotion.LIVELY)
            assertThat(theme.coverGlassMovingFlow.first()).isTrue()
            assertThat(theme.coverGlassLivelyFlow.first()).isTrue()

            theme.setBackgroundMotion(BackgroundMotion.STILL)
            assertThat(theme.backgroundMotionFlow.first()).isEqualTo(BackgroundMotion.STILL)
            assertThat(theme.coverGlassMovingFlow.first()).isFalse()

            theme.setBackgroundMotion(BackgroundMotion.GENTLE)
            assertThat(theme.backgroundMotionFlow.first()).isEqualTo(BackgroundMotion.GENTLE)
            assertThat(theme.coverGlassLivelyFlow.first()).isFalse()
        } finally { dir.toFile().deleteRecursively() }
    }
}
