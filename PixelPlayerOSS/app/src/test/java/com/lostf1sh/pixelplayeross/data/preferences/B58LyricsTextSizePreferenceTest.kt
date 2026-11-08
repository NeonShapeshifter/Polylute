package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** B58 4 (#78): the lyrics text size is saved for the app, validated by the repository, and read again after a restart (a new repository over the same file). */
class B58LyricsTextSizePreferenceTest {
    @Test
    fun `the choice survives a restart and nonsense is refused`() = runTest {
        val dir = Files.createTempDirectory("b58-size")
        try {
            val file = dir.resolve("s.preferences_pb").toFile()
            val first = UserPreferencesRepository(dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }), json = Json)
            assertEquals("medium", first.lyricsTextSizeFlow.first())
            first.setLyricsTextSize("xlarge")
            first.setLyricsTextSize("gigantic")
            assertEquals("xlarge", first.lyricsTextSizeFlow.first())
            // a restart: the same file, read through another store and another repository
            val second = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()), produceFile = { dir.resolve("s2.preferences_pb").toFile().also { file.copyTo(it, overwrite = true) } }),
                json = Json,
            )
            assertEquals("xlarge", second.lyricsTextSizeFlow.first())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
