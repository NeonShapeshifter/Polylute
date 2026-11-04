package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** B56 phase 7.2: "Animated covers" is stored only once the listener chooses; until then the mode's default stands. */
class B56AnimatedCoversPreferenceTest {
    @Test
    fun `nothing is stored until the listener chooses, then the choice wins in either mode`() = runTest {
        val dir = Files.createTempDirectory("b56-animated")
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("s.preferences_pb").toFile() })
            val theme = ThemePreferencesRepository(store)
            assertThat(theme.animatedCoversStoredFlow.first()).isNull()
            // B57 5 changes this on purpose: off by default in BOTH modes (it was on in app mode)
            assertThat(AnimatedCovers.effective(theme.animatedCoversStoredFlow.first(), dapMode = false)).isFalse()
            assertThat(AnimatedCovers.effective(theme.animatedCoversStoredFlow.first(), dapMode = true)).isFalse()

            theme.setAnimatedCovers(true)
            assertThat(AnimatedCovers.effective(theme.animatedCoversStoredFlow.first(), dapMode = true)).isTrue()
            theme.setAnimatedCovers(false)
            assertThat(AnimatedCovers.effective(theme.animatedCoversStoredFlow.first(), dapMode = false)).isFalse()
            assertThat(booleanPreferencesKey("animated_covers_v1").name).isEqualTo("animated_covers_v1")
        } finally { dir.toFile().deleteRecursively() }
    }
}
