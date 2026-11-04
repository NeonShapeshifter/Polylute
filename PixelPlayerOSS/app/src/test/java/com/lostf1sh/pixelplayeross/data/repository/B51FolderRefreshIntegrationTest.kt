package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.*
import com.lostf1sh.pixelplayeross.data.library.LibraryFolders
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoRepository
import com.lostf1sh.pixelplayeross.data.musicinfo.MusicInfoService
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ColorSchemeProcessor
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import io.mockk.*
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B51FolderRefreshIntegrationTest {
    @Test fun `refresh invalidates old palettes and album references when a folder image disappears`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("b51-refresh").toFile()
        val folder = root.resolve("Album").apply { mkdirs() }
        val audio = folder.resolve("song.flac").apply { writeText("no embedded art") }
        val cover = folder.resolve("cover.jpg").apply { writeBytes(ByteArray(2048) { 11 }) }
        val id = 515151L
        val dao = mockk<MusicDao>(relaxed = true)
        val prefs = mockk<UserPreferencesRepository>()
        val info = mockk<MusicInfoRepository>()
        val service = mockk<MusicInfoService>()
        val colors = mockk<ColorSchemeProcessor>(relaxed = true)
        try {
            val initial = AlbumArtUtils.refreshFolderArtwork(context, id, audio.path, null, true, true, false) { null }
            val song = Song.emptySong().copy(id = id.toString(), album = "Album", albumId = 7, path = audio.path, albumArtUriString = initial.uri)
                .toEntity(audio.path, folder.path)
            every { prefs.folderCoversEnabledFlow } returns flowOf(true)
            coEvery { prefs.currentDirectoryRules() } returns LibraryFolders.Rules(emptySet(), emptySet())
            coEvery { dao.getAllSongsList() } returns listOf(song)
            every { info.service } returns service
            coEvery { service.cachedCover(any(), any()) } returns null
            cover.delete()
            FolderArtworkRefresh(context, dao, prefs, info, colors).refresh()
            coVerify { colors.invalidateScheme(initial.uri!!) }
            coVerify { dao.updateSongAlbumArt(id, null) }
            coVerify { dao.refreshAlbumArtwork(listOf(7L)) }
            assertThat(AlbumArtUtils.getCachedAlbumArtFile(context, id).exists()).isFalse()
        } finally {
            AlbumArtUtils.clearCacheForSong(context, id)
            context.getSharedPreferences("folder_art_sources", Context.MODE_PRIVATE).edit().clear().commit()
            root.deleteRecursively()
            unmockkAll()
        }
    }
}
