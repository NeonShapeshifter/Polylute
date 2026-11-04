package com.lostf1sh.pixelplayeross.data.preferences

import androidx.datastore.preferences.core.*
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.FolderSource
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class B51FolderPreferencesTest {
    @Test fun `compact tree and safe folder covers default on while source and list choices survive reopening`() = runTest {
        val dir = Files.createTempDirectory("b51-preferences").toFile()
        try {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { dir.resolve("prefs.preferences_pb") })
            val first = UserPreferencesRepository(store, Json)
            assertThat(first.foldersFlatListFlow.first()).isFalse()
            assertThat(first.folderCoversEnabledFlow.first()).isTrue()
            first.setFoldersSource(FolderSource.SD_CARD)
            first.setFoldersVolumeRoot("/storage/ABCD", FolderSource.SD_CARD)
            first.setFoldersFlatList(true)
            first.setFolderCoversEnabled(false)
            val reopened = UserPreferencesRepository(store, Json)
            assertThat(reopened.foldersSourceFlow.first()).isEqualTo(FolderSource.SD_CARD)
            assertThat(reopened.foldersFlatListFlow.first()).isTrue()
            assertThat(reopened.foldersVolumeRootFlow.first()).isEqualTo("/storage/ABCD")
            assertThat(reopened.folderCoversEnabledFlow.first()).isFalse()
            assertThat(store.data.first()[stringPreferencesKey("folders_source")]).isEqualTo(FolderSource.SD_CARD.storageKey)
            assertThat(store.data.first()[booleanPreferencesKey("is_folders_playlist_view")]).isNull()
        } finally { dir.deleteRecursively() }
    }
}
