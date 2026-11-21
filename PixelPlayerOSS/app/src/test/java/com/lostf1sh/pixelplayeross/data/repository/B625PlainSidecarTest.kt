package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.LyricsDao
import com.lostf1sh.pixelplayeross.data.database.LyricsEntity
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.utils.LyricsImportSecurity
import com.lostf1sh.pixelplayeross.utils.LyricsImportValidationResult
import com.lostf1sh.pixelplayeross.utils.syntheticPlainLrc
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B625PlainSidecarTest {
    @Test fun sidecarIsFoundShownAndPersistedAsLocalPlainLyrics() = runBlocking {
        val dir = Files.createTempDirectory("b625-sidecar-").toFile()
        try {
            val app = ApplicationProvider.getApplicationContext<Context>()
            val context = object : ContextWrapper(app) { override fun getFilesDir(): File = dir }
            val preferences = mockk<UserPreferencesRepository>(relaxed = true) {
                every { externalLyricsEnabledFlow } returns flowOf(false)
            }
            val dao = mockk<LyricsDao>(relaxed = true)
            val repository = LyricsRepositoryImpl(context, mockk(relaxed = true), dao, mockk(relaxed = true), preferences)
            File(dir, "synthetic.lrc").writeText(syntheticPlainLrc, Charsets.UTF_8)
            val song = Song.emptySong().copy(id = "625", title = "Synthetic", libraryItemId = "synthetic-identity", path = File(dir, "synthetic.flac").path)
            val result = repository.getLyrics(song, LyricsSourcePreference.LOCAL_FIRST)
            assertThat(result).isNotNull()
            assertThat(result!!.synced.isNullOrEmpty()).isTrue()
            assertThat(result.plain!!.first()).contains("äöü ß \\ Trennzeichen")
            assertThat(result.areFromRemote).isFalse()
            val row = slot<LyricsEntity>()
            coVerify { dao.insert(capture(row)) }
            assertThat(row.captured.isSynced).isFalse()
            assertThat(row.captured.source).isEqualTo("sidecar")
        } finally { dir.deleteRecursively() }
    }

    @Test fun anImportedPlainFileIsSavedAsManualUnsyncedLyrics() = runBlocking {
        val dao = mockk<LyricsDao>(relaxed = true)
        val repository = LyricsRepositoryImpl(ApplicationProvider.getApplicationContext<Context>(), mockk(relaxed = true), dao, mockk(relaxed = true), mockk(relaxed = true))
        val validation = LyricsImportSecurity.validateImportedLyricsFile("synthetic.lrc", "text/plain", syntheticPlainLrc.byteInputStream())
        assertThat(validation).isInstanceOf(LyricsImportValidationResult.Valid::class.java)
        repository.updateLyrics(625, (validation as LyricsImportValidationResult.Valid).value.sanitizedContent)
        val row = slot<LyricsEntity>()
        coVerify { dao.insert(capture(row)) }
        assertThat(row.captured.source).isEqualTo("manual")
        assertThat(row.captured.isSynced).isFalse()
    }
}
