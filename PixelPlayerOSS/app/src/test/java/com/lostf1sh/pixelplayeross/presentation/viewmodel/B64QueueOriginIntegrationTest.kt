package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.*
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.service.player.*
import com.lostf1sh.pixelplayeross.data.stats.Kit
import io.mockk.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reuses the established ViewModel dependency rig under Robolectric, so Bundle/MediaItem are real. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class B64QueueOriginIntegrationTest {
    private inline fun <reified T> PlayerViewModelTest.field(name: String): T =
        PlayerViewModelTest::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
    @Test fun blockLoaderAttachesEachAlbumsOwnSourceAndDisplayName() {
        val rig = PlayerViewModelTest()
        rig.setUp()
        try {
            val vm = rig.field<PlayerViewModel>("playerViewModel")
            val dispatcher = rig.field<TestDispatcher>("testDispatcher")
            val engine = rig.field<DualPlayerEngine>("mockDualPlayerEngine")
            val repository = rig.field<MusicRepository>("mockMusicRepository")
            val library = rig.field<LibraryStateHolder>("mockLibraryStateHolder")
            val songs = (1L..4L).map { id -> Kit.song(id, albumId = if(id < 3) 7 else 8, album = if(id < 3) "Seven" else "Eight", track = id.toInt()).copy(contentUriString = "content://synthetic/$id", path = "/synthetic/$id.flac") }
            val albums = (7L..8L).map { id -> Album(id = id, title = if(id == 7L) "Seven" else "Eight", artist = "Synthetic artist", year = 2026, dateAdded = 0, albumArtUriString = null, songCount = 2) }
            every { library.albums } returns MutableStateFlow(albums.toImmutableList())
            every { library.allSongsById } returns MutableStateFlow(songs.associateBy { it.id })
            albums.forEach { album -> every { repository.getSongsForAlbum(album.id) } returns flowOf(songs.filter { it.albumId == album.id }) }
            every { engine.queueLoadHold } returns QueueLoadSkipHold { 0L }
            val player = mockk<Player>(relaxed = true)
            every { engine.masterPlayer } returns player
            var loaded = emptyList<MediaItem>()
            every { player.setMediaItems(any<List<MediaItem>>(), any(), any()) } answers { loaded = firstArg() }
            dispatcher.scheduler.advanceUntilIdle()
            vm.shuffleRandomAlbum()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (loaded.size < 4 && System.nanoTime() < deadline) { dispatcher.scheduler.advanceUntilIdle(); Thread.sleep(10) }
            assertThat(loaded).hasSize(4)
            loaded.forEach { item ->
                val song = songs.single { it.id == item.mediaId }
                assertThat(PlaybackOrigin.from(item)).isEqualTo(PlaybackOrigin("ALBUM", song.albumId.toString(), song.album))
            }
        } finally { rig.tearDown() }
    }
}
