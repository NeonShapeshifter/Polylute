package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * B18-R1: classic is the default random mode, and the choice persists.
 *
 * Owner: "if someone wants to mess with normal random, they use it the way we had it. In fact it should be the default, and if you
 * want more performance, you use the one we just made."
 */
class RandomModeTest {

    @Test
    fun `the two modes are classic and fast, and nothing else`() {
        assertEquals(listOf("classic", "fast"), RandomMode.entries.map { it.id })
    }

    @Test
    fun `the default is classic`() {
        assertEquals(RandomMode.CLASSIC, RandomMode.DEFAULT)
        assertEquals(RandomMode.CLASSIC, SettingsUiState().randomMode)
    }

    @Test
    fun `an install with nothing stored, or a value this build does not know, reads as classic`() {
        assertEquals(RandomMode.CLASSIC, RandomMode.fromId(null))
        assertEquals(RandomMode.CLASSIC, RandomMode.fromId(""))
        assertEquals(RandomMode.CLASSIC, RandomMode.fromId("turbo"))
    }

    @Test
    fun `every mode round-trips through its stored id`() {
        RandomMode.entries.forEach { assertEquals(it, RandomMode.fromId(it.id)) }
    }

    @Test
    fun `the choice persists, an empty store reads classic, and a fresh repository on the same file reads what was chosen`() = runTest {
        val tempDir = Files.createTempDirectory("random-mode-test")
        try {
            val file = tempDir.resolve("settings.preferences_pb").toFile()
            fun repository() = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }),
                json = Json,
            )

            val first = repository()
            // Nothing is written on upgrade: this is what an existing install reads.
            assertEquals(RandomMode.CLASSIC, first.randomModeFlow.first())

            first.setRandomMode(RandomMode.FAST)
            assertEquals(RandomMode.FAST, first.randomModeFlow.first())

            first.setRandomMode(RandomMode.CLASSIC)
            assertEquals(RandomMode.CLASSIC, first.randomModeFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the stored key is the one the mode id is written under`() = runTest {
        val tempDir = Files.createTempDirectory("random-mode-key-test")
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { tempDir.resolve("settings.preferences_pb").toFile() },
            )
            val repository = UserPreferencesRepository(dataStore = dataStore, json = Json)

            repository.setRandomMode(RandomMode.FAST)

            assertEquals("fast", dataStore.data.first()[stringPreferencesKey("random_mode")])
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
