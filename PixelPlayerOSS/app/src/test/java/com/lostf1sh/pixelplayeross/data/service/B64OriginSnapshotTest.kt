package com.lostf1sh.pixelplayeross.data.service

import android.app.Application
import android.util.Log
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.*
import com.lostf1sh.pixelplayeross.data.preferences.PlaybackSnapshotStore
import com.lostf1sh.pixelplayeross.data.stats.*
import com.lostf1sh.pixelplayeross.presentation.navigation.listeningDestination
import com.lostf1sh.pixelplayeross.presentation.viewmodel.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B64OriginSnapshotTest {
    private val origin = PlaybackOrigin.of(ListenSource(ListenSourceKind.COLLECTION, "forgotten_favorites"), "Forgotten favorites")!!
    private val item = PlaybackOrigin.attach(MediaItem.Builder().setMediaId("42").setUri("content://synthetic/42").build(), origin)
    @Suppress("UNCHECKED_CAST")
    private fun capture(service: MusicService, items: List<MediaItem>): List<PlaybackQueueItemSnapshot> =
        (MusicService::class.java.getDeclaredMethod("buildSnapshotItems", List::class.java).apply { isAccessible = true }
            .invoke(service, items) as Pair<List<PlaybackQueueItemSnapshot>, List<Int>>).first
    private fun restore(service: MusicService, item: PlaybackQueueItemSnapshot): MediaItem =
        MusicService::class.java.getDeclaredMethod("buildMediaItemFromSnapshot", PlaybackQueueItemSnapshot::class.java)
            .apply { isAccessible = true }.invoke(service, item) as MediaItem
    @Test fun serviceSnapshotAndDatastoreKeepSourceAndName() = runBlocking {
        val service = MusicService()
        val snapshot = PlaybackQueueSnapshot(capture(service, listOf(item)))
        val prefs = mutablePreferencesOf()
        val store = PlaybackSnapshotStore(Json)
        store.write(prefs, snapshot)
        val restored = restore(service, store.read(prefs)!!.items.single())
        assertThat(PlaybackOrigin.from(restored)).isEqualTo(origin)
        assertThat(listeningDestination(PlaybackOrigin.from(restored)?.source(), 9, "42") { true }.route)
            .isEqualTo("auto_collection/forgotten_favorites")
    }
    @Test fun oldSnapshotWithoutSourceFallsBackToAlbum() = runBlocking {
        val json = """{"items":[{"mediaId":"42","uri":"content://synthetic/42"}],"savedAtEpochMs":1}"""
        val snapshot = Json.decodeFromString<PlaybackQueueSnapshot>(json)
        val restored = restore(MusicService(), snapshot.items.single())
        assertThat(PlaybackOrigin.from(restored)).isNull()
        assertThat(listeningDestination(PlaybackOrigin.from(restored)?.source(), 9, "42") { true }.route).isEqualTo("album_detail/9")
    }
    @Test fun repeatedSongInTwoBlocksKeepsTheSourceOfEachOccurrenceAfterRestore() {
        val album = PlaybackOrigin("ALBUM", "7", "Album seven")
        val artist = PlaybackOrigin("ARTIST", "8", "Artist eight")
        val items = listOf(PlaybackOrigin.attach(item, album), PlaybackOrigin.attach(item, artist))
        val service = MusicService()
        val snapshots = capture(service, items)
        assertThat(snapshots.map { PlaybackOrigin.from(restore(service, it)) }).containsExactly(album, artist).inOrder()
    }
    @Test fun unknownFutureKindIsCompatibleAndFallsBack() {
        assertThat(PlaybackOrigin("FUTURE_KIND", "x").source()).isNull()
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun persistFailure(error: Exception): List<Pair<Int, String>> {
        val messages = mutableListOf<Pair<Int, String>>()
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val tree = object : Timber.Tree() { override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { messages += priority to message } }
            Timber.plant(tree)
            try {
                val service = MusicService()
                val player = mockk<Player>(relaxed = true)
                service.engine = mockk(relaxed = true) { every { masterPlayer } returns player }
                every { player.mediaItemCount } returns 1
                every { player.getMediaItemAt(0) } returns item
                every { player.currentMediaItem } returns item
                every { player.currentMediaItemIndex } returns 0
                service.userPreferencesRepository = mockk(relaxed = true)
                coEvery { service.userPreferencesRepository.setPlaybackQueueSnapshot(any()) } throws error
                val method = MusicService::class.java.declaredMethods.single { it.name == "persistPlaybackSnapshot" }.apply { isAccessible = true }
                val result = runCatching { suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                    try { method.invoke(service, null, continuation) }
                    catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                } }
                if (error is CancellationException) assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            } finally { Timber.uproot(tree); Dispatchers.resetMain() }
        }
        return messages
    }
    @Test fun cancellationIsDebugAndPropagates() {
        val messages = persistFailure(CancellationException("superseded"))
        assertThat(messages.any { it.first == Log.DEBUG && it.second.contains("persist cancelled") }).isTrue()
        assertThat(messages.none { it.first >= Log.WARN && it.second.contains("persist playback snapshot") }).isTrue()
    }
    @Test fun actualWriteFailureStaysWarning() {
        val messages = persistFailure(java.io.IOException("synthetic disk failure"))
        assertThat(messages.any { it.first == Log.WARN && it.second.contains("Failed to persist playback snapshot") }).isTrue()
    }
}
