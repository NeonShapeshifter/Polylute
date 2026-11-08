package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** B61 item 5 (#70): "Also on the lock screen" is off until the listener turns it on, and what they chose is kept; it is a key of its own, apart from "Animated covers". */
class B61AnimatedCoversOnLockPreferenceTest {
    @Test
    fun `off by default, then the choice is kept, and Animated covers is untouched by it`() = runTest {
        val dir = Files.createTempDirectory("b61-animated-lock")
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("s.preferences_pb").toFile() })
            val theme = ThemePreferencesRepository(store)
            assertThat(theme.animatedCoversOnLockFlow.first()).isFalse()
            theme.setAnimatedCoversOnLock(true)
            assertThat(theme.animatedCoversOnLockFlow.first()).isTrue()
            assertThat(theme.animatedCoversStoredFlow.first()).isNull()          // the other switch has not been touched
            theme.setAnimatedCoversOnLock(false)
            assertThat(theme.animatedCoversOnLockFlow.first()).isFalse()
        } finally { dir.toFile().deleteRecursively() }
    }
}
