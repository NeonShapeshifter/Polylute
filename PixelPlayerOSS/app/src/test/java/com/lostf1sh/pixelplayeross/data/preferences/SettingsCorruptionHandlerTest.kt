package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * AUD-032: the shared `settings` DataStore had no corruption handler. A corrupt file threw
 * `CorruptionException` into eager `stateIn(appScope)` collectors — an uncaught crash, and in
 * Dedicated Mode the crashing app is HOME, so the device could loop until recovered over adb.
 *
 * Driven against a real DataStore on a temp file holding garbage, through the production handler.
 */
class SettingsCorruptionHandlerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `a corrupt settings file is replaced instead of crashing the reader`() = runTest {
        val file = Files.createTempFile("settings-corrupt", ".preferences_pb").toFile()
        file.writeBytes(byteArrayOf(0x0A, 0x7F, 0x13, 0x00, 0x42, 0x01, 0xFF.toByte()))
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = SettingsDataStore.corruptionHandler,
            scope = scope,
            produceFile = { file },
        )

        assertThat(store.data.first().asMap()).isEmpty()

        // And the store is writable again afterwards.
        val key = booleanPreferencesKey("recovered")
        store.edit { it[key] = true }
        assertThat(store.data.first()[key]).isTrue()
    }
}
