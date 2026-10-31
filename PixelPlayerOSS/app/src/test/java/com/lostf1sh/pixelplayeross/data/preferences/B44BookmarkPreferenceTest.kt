package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.*
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

class B44BookmarkPreferenceTest {
    @Test fun `bookmark defaults on and a stored off value wins after recreating the repository`() = runTest {
        val dir = Files.createTempDirectory("b44-bookmark").toFile()
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("prefs.preferences_pb") })
            fun read(): Flow<Boolean> {
                val repository = UserPreferencesRepository(store, Json)
                val getter = repository.javaClass.methods.firstOrNull { it.name == "getShowBookmarkButtonFlow" }
                @Suppress("UNCHECKED_CAST")
                return getter?.invoke(repository) as? Flow<Boolean> ?: flowOf(true)
            }
            assertThat(read().first()).isTrue()
            val key = booleanPreferencesKey("show_player_bookmark_button")
            store.edit { it[key] = false }
            assertThat(read().first()).isFalse()
            assertThat(store.data.first()[key]).isFalse()
        } finally {
            dir.deleteRecursively()
        }
    }
}
