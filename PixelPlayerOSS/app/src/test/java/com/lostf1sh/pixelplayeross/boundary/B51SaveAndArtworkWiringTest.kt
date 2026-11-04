package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/** Guards the actual entry points, alongside the behavior tests for the platform adapters. */
class B51SaveAndArtworkWiringTest {
    private fun source(path: String): String = File("src/main/java/com/lostf1sh/pixelplayeross/$path").readText()
    @Test fun `player save uses the shared folder grant decision and the existing lyrics persistence`() {
        val vm = source("presentation/viewmodel/PlayerViewModel.kt")
        val save = vm.substringAfter("fun saveLyricsToFile(").substringBefore("private suspend fun performMetadataEdit(")
        assertThat(save).contains("lyricsFileSaver.save(")
        assertThat(save).contains("musicRepository.updateLyrics(id, content)")
        assertThat(save).contains("lyrics_saved_in_app")
        assertThat(save).doesNotContain("lrcFile.writeText")
    }
    @Test fun `every library refresh revisits folder covers even without changed MediaStore rows`() {
        val worker = source("data/worker/SyncWorker.kt")
        assertThat(worker).contains("folderArtworkRefresh?.refresh()")
        assertThat(worker.indexOf("folderArtworkRefresh?.refresh()")).isGreaterThan(worker.indexOf("musicDao.seedLibraryItemsFromProjections()"))
    }
}
