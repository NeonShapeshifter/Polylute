package com.lostf1sh.pixelplayeross.data.service.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
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

/** B40-F8: the limiter's switch is off until asked for, and what is asked for is what is kept. */
class ResonanceLimiterPreferenceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `off by default, on when asked, and kept under its own key`() = runTest {
        val file = Files.createTempFile("b40-limiter", ".preferences_pb").toFile().also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val prefs = UserPreferencesRepository(dataStore = store, json = Json { ignoreUnknownKeys = true })
        assertThat(prefs.resonanceLimiterEnabledFlow.first()).isFalse()
        prefs.setResonanceLimiterEnabled(true)
        assertThat(prefs.resonanceLimiterEnabledFlow.first()).isTrue()
        assertThat(store.data.first()[booleanPreferencesKey("resonance_limiter_enabled")]).isTrue()
        prefs.setResonanceLimiterEnabled(false)
        assertThat(prefs.resonanceLimiterEnabledFlow.first()).isFalse()
    }
}
