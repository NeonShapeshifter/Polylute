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

class B44Fix1SharedShufflePreferenceTest {
    @Test fun `shared shuffle defaults off and a stored value wins after recreation`() = runTest {
        val dir = Files.createTempDirectory("b44-fix1-shared-pref").toFile()
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("prefs.preferences_pb") })
            fun read(): Flow<Boolean> {
                val repository = UserPreferencesRepository(store, Json)
                val getter = repository.javaClass.methods.firstOrNull { it.name == "getRepeatSharedSongsInShuffleFlow" }
                @Suppress("UNCHECKED_CAST")
                return getter?.invoke(repository) as? Flow<Boolean> ?: flowOf(true)
            }
            assertThat(read().first()).isFalse()
            val key = booleanPreferencesKey("repeat_shared_songs_in_shuffle")
            store.edit { it[key] = true }
            assertThat(read().first()).isTrue()
            store.edit { it[key] = false }
            assertThat(read().first()).isFalse()
        } finally {
            dir.deleteRecursively()
        }
    }
}
