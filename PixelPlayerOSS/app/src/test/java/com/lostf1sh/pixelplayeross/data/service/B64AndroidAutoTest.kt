package com.lostf1sh.pixelplayeross.data.service

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.*
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.collections.auto.*
import com.lostf1sh.pixelplayeross.data.database.*
import com.lostf1sh.pixelplayeross.data.model.*
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.presentation.utils.SearchGenreMatcher
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class B64AndroidAutoTest {
    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addListener({ runCatching { get() }.onSuccess { continuation.resume(it) }.onFailure { continuation.resumeWithException(it) } }, MoreExecutors.directExecutor())
        continuation.invokeOnCancellation { cancel(false) }
    }
    private val dispatcher = UnconfinedTestDispatcher()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val music = mockk<MusicRepository>(relaxed = true)
    private val playlists = mockk<LocalPlaylistDao>(relaxed = true)
    private val collections = mockk<AutoCollectionRepository>(relaxed = true)
    private val preferences = mockk<UserPreferencesRepository>(relaxed = true)
    private val rows = (1L..5_000L).map { id -> Kit.song(id, title = if(id == 1L) "Beyoncé" else "Song %04d".format(id), album = "Synthetic album", albumId = if(id <= 3) 7 else 8)
        .copy(contentUriString = "content://synthetic/$id", path = "/synthetic/$id.flac", mimeType = "audio/flac") }
    private val album = Album(7, "Synthetic album", "Synthetic artist", 2026, 0, null, 3)
    private val order = listOf(rows[2], rows[0], rows[1])
    private val pages = mutableListOf<Pair<Int,Int>>()
    private lateinit var catalog: AutoMediaCatalog
    private lateinit var service: MusicService
    private lateinit var callback: MediaLibraryService.MediaLibrarySession.Callback
    private val browser = mockk<MediaSession.ControllerInfo>(relaxed = true)
    private val session = mockk<MediaLibraryService.MediaLibrarySession>(relaxed = true)
    private val player = mockk<Player>(relaxed = true)
    private val saved by lazy { rows.take(3).map(MediaItemBuilder::build) }
    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { preferences.songsSortOptionFlow } returns flowOf("song_title_az")
        every { preferences.likedSongsSortOptionFlow } returns flowOf("liked_title_az")
        coEvery { music.getSongsPage(any(), any(), any(), any()) } answers { val n=firstArg<Int>(); val offset=secondArg<Int>(); pages += n to offset; rows.drop(offset).take(n) }
        coEvery { music.getFavoriteSongsPage(any(), any(), any(), any()) } answers { rows.take(6).drop(secondArg()).take(firstArg()) }
        coEvery { music.getAlbumsPage(any(), any(), any(), any(), any()) } answers { listOf(album).drop(secondArg()).take(firstArg()) }
        coEvery { music.getArtistsPage(any(), any(), any(), any()) } answers { listOf(Artist(11,"Synthetic artist",3)).drop(secondArg()).take(firstArg()) }
        coEvery { music.getAutoSourceSongsPage(any(), any(), any(), any()) } answers { order.drop(arg(3)).take(arg(2)) }
        every { music.getAlbumById(7) } returns flowOf(album)
        every { music.getArtistById(any()) } returns flowOf(Artist(11,"Synthetic artist",3))
        every { music.getSong(any()) } answers { flowOf(rows.firstOrNull { it.id == firstArg<String>() }) }
        every { music.getSongsByIds(any()) } answers { val ids=firstArg<List<String>>(); flowOf(rows.filter { it.id in ids }) }
        every { music.searchAll(any(), SearchFilterType.SONGS) } answers {
            val query=SearchGenreMatcher.fold(firstArg())
            flowOf(rows.filter { SearchGenreMatcher.fold(it.title).contains(query) }.take(100).map { SearchResultItem.SongItem(it) })
        }
        val cat = Kit.catalog(order)
        val collection = AutoCollection(AutoCollectionId.FORGOTTEN_FAVORITES, order.map { cat.item(it.libraryItemId)!! })
        every { collections.state } returns MutableStateFlow(AutoCollectionsState(true, listOf(collection, AutoCollection(AutoCollectionId.DAILY_MIX, collection.items)), emptySet()))
        every { collections.homeMixes } returns MutableStateFlow(HomeMixes(true, collection.items, collection.items))
        coEvery { collections.ensureFresh(any()) } just Runs
        val playlist = PlaylistEntity("p/1", "Synthetic playlist")
        coEvery { playlists.getPlaylistsPage(any(), any()) } answers { listOf(playlist).drop(secondArg()).take(firstArg()) }
        coEvery { playlists.getPlaylistById(any()) } returns playlist
        coEvery { playlists.getPlaylistLocatorsResolvedPage(any(), any(), any()) } answers { order.map { it.id.toLong() }.drop(arg(2)).take(arg(1)) }
        coEvery { playlists.getPlaylistLocatorsResolved(any()) } returns order.map { it.id.toLong() }
        catalog = AutoMediaCatalog(context, music, playlists, collections, preferences)
        every { browser.packageName } returns "com.google.android.projection.gearhead"
        every { browser.connectionHints } returns Bundle.EMPTY
        every { browser.isTrusted } returns false
        every { player.mediaItemCount } returns 3
        every { player.currentMediaItem } returns saved[1]
        every { player.currentMediaItemIndex } returns 1
        every { player.currentPosition } returns 13_000L
        every { player.getMediaItemAt(any()) } answers { saved[firstArg()] }
        every { session.player } returns player
        every { session.isMediaNotificationController(any()) } returns false
        // Initialise before spying: Kotlin lazy delegates capture the original service instance.
        val original = MusicService().apply {
            autoCatalog = catalog
            musicRepository = music
            userPreferencesRepository = preferences
            engine = mockk(relaxed = true) { every { masterPlayer } returns player }
            // B69: Android Auto is recognised by its signing certificate; the harness's gearhead stands for a genuine one.
            browseSignatureCheck = MediaBrowseCallerPolicy.SignatureCheck { _, _ -> true }
        }
        service = spyk(original)
        every { service.packageName } returns context.packageName
        every { service.applicationContext } returns context
        every { service.grantUriPermission(any(), any(), any()) } just Runs
        callback = service.createSessionCallback()
    }
    @After fun tearDown() { Dispatchers.resetMain(); unmockkAll() }
    @Test fun rootHasFourTabsAndHomeContainsCollectionsAndSongs() = runTest(dispatcher) {
        val root = callback.onGetLibraryRoot(session,browser,null).await()
        assertThat(root.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(root.value!!.mediaId).isEqualTo("polylute")
        assertThat(root.params!!.extras.getInt(AutoMediaCatalog.BROWSABLE_STYLE)).isEqualTo(2)
        val tabs = callback.onGetChildren(session,browser,"polylute",0,100,null).await().value!!
        assertThat(tabs.map { it.mediaId }).containsExactly("home","playlists","albums","artists").inOrder()
        val home=callback.onGetChildren(session,browser,"home",0,100,null).await().value!!
        assertThat(home.map { it.mediaId }).containsAtLeast("collection:forgotten_favorites","collection:daily_mix","songs","liked")
        for(id in listOf("playlists","albums","artists")) {
            assertThat(callback.onGetChildren(session,browser,id,0,100,null).await().resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        }
    }
    @Test fun fiveThousandSongsUseBoundedPagesAndBrowsingDoesNotTouchRestore() = runTest(dispatcher) {
        every { service.engine.masterPlayer } throws AssertionError("Browsing must not touch restore/player")
        for((page,size,count,offset) in listOf(listOf(0,Int.MAX_VALUE,100,0),listOf(2,25,25,50),listOf(49,100,100,4900),listOf(50,100,0,5000))) {
            val started=System.nanoTime()
            val result=callback.onGetChildren(session,browser,"songs",page,size,null).await()
            assertThat(result.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
            assertThat(result.value).hasSize(count)
            assertThat(pages.last()).isEqualTo(size.coerceAtMost(100) to offset)
            println("B64 Auto 5000 page=$page requested=$size returned=${result.value!!.size} ms=${(System.nanoTime()-started)/1_000_000.0}")
        }
        assertThat(pages.all { it.first <= 100 }).isTrue()
        assertThat(callback.onGetChildren(session,browser,"songs",-1,20,null).await().resultCode).isNotEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(callback.onGetChildren(session,browser,"songs",Int.MAX_VALUE,100,null).await().resultCode).isNotEqualTo(LibraryResult.RESULT_SUCCESS)
    }
    @Test fun legacyHostsCanBrowsePastTheFirstHundredThroughStableMoreFolders() = runTest(dispatcher) {
        val first=callback.onGetChildren(session,browser,"songs",0,Int.MAX_VALUE,null).await().value!!
        assertThat(first).hasSize(100)
        assertThat(first.last().mediaId).isEqualTo("songs/page:1")
        val next=callback.onGetChildren(session,browser,first.last().mediaId,0,Int.MAX_VALUE,null).await().value!!
        assertThat(next.first().mediaId).isEqualTo("songs/page:1/song:100")
        assertThat(next.last().mediaId).isEqualTo("songs/page:2")
        val tapped=callback.onSetMediaItems(session,browser,mutableListOf(next.first()),0,0).await()
        assertThat(tapped.mediaItems).hasSize(5000)
        assertThat(tapped.startIndex).isEqualTo(99)
        assertThat(PlaybackOrigin.from(tapped.mediaItems[99])?.kind).isEqualTo("LIBRARY")
    }
    @Test fun idsRoundTripEncodedReferencesAndRejectMalformedValues() {
        for(id in listOf(AutoMediaId("polylute"),AutoMediaId("songs",songId="100",page=1),AutoMediaId("album","-42","123"),AutoMediaId("playlist","p:á /1","3"),AutoMediaId("collection","forgotten_favorites"),AutoMediaId("search","Beyoncé","1"))) {
            assertThat(AutoMediaId.parse(id.toString())).isEqualTo(id)
        }
        for(raw in listOf("album:bad","song:","nope","home:extra","album:7/song:bad","album:7/song:1/song:2")) assertThat(AutoMediaId.parse(raw)).isNull()
        assertThat(AutoPage.of(-1,100)).isNull(); assertThat(AutoPage.of(0,0)).isNull()
    }
    @Test fun artworkIsContentProviderBackedAndTheHostReceivesOnlyReadGrants() = runTest(dispatcher) {
        val items=callback.onGetChildren(session,browser,"album:7",0,50,null).await().value!!
        assertThat(items).hasSize(3)
        for(item in items) {
            val uri=item.mediaMetadata.artworkUri!!
            assertThat(uri.scheme).isEqualTo("content")
            assertThat(uri.authority).isEqualTo("${context.packageName}.artwork")
            assertThat(uri.toString()).doesNotContain("/synthetic/")
            verify { service.grantUriPermission("com.google.android.projection.gearhead",uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }
    @Test fun tapInAlbumPlaylistCollectionAndArtistExpandsTheDisplayedListAtTheTappedSong() = runTest(dispatcher) {
        for((id,kind,ref) in listOf(Triple("album:7", "ALBUM", "7"),Triple("playlist:p%2F1","PLAYLIST","p/1"),
            Triple("collection:forgotten_favorites","COLLECTION","forgotten_favorites"),Triple("artist:11","ARTIST","11"))) {
            val shown=callback.onGetChildren(session,browser,id,0,100,null).await().value!!
            val request=shown[1]
            val result=callback.onSetMediaItems(session,browser,mutableListOf(request),0,0).await()
            assertThat(result.mediaItems.map { it.mediaId }).containsExactlyElementsIn(order.map { it.id }).inOrder()
            assertThat(result.startIndex).isEqualTo(1)
            assertThat(result.mediaItems.map { PlaybackOrigin.from(it)?.source() }).containsExactlyElementsIn(List(3) { com.lostf1sh.pixelplayeross.data.stats.ListenSource(com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind.valueOf(kind),ref) })
            val whole=callback.onSetMediaItems(session,browser,mutableListOf(MediaItem.Builder().setMediaId(id).build()),0,0).await()
            assertThat(whole.startIndex).isEqualTo(0)
            assertThat(whole.mediaItems.map { it.mediaId }).containsExactlyElementsIn(order.map { it.id }).inOrder()
        }
    }
    @Test fun addMediaItemsAlsoExpandsASource() = runTest(dispatcher) {
        val items=callback.onAddMediaItems(session,browser,mutableListOf(MediaItem.Builder().setMediaId("album:7").build())).await()
        assertThat(items.map { it.mediaId }).containsExactlyElementsIn(order.map { it.id }).inOrder()
    }
    @Test fun ownQueueResolverPreservesEachRepeatedOccurrenceAndExternalRequestsCannotSupplyOrigin() = runTest(dispatcher) {
        val album = PlaybackOrigin("ALBUM", "7", "Synthetic album")
        val artist = PlaybackOrigin("ARTIST", "11", "Synthetic artist")
        val requests = mutableListOf(PlaybackOrigin.attach(saved[0], album), PlaybackOrigin.attach(saved[0], artist))
        every { browser.packageName } returns context.packageName
        every { browser.uid } returns android.os.Process.myUid() // B69: Polylute is its package and its uid
        val own = callback.onSetMediaItems(session, browser, requests, 1, 0).await()
        assertThat(own.startIndex).isEqualTo(1)
        assertThat(own.mediaItems.map(PlaybackOrigin::from)).containsExactly(album, artist).inOrder()
        every { browser.packageName } returns "com.google.android.projection.gearhead"
        val external = callback.onSetMediaItems(session, browser, requests, 1, 0).await()
        assertThat(external.mediaItems.map(PlaybackOrigin::from)).containsExactly(null, null)
    }
    @Test fun searchUsesTheSearchTabsRepositoryMatcherAndVoiceEmptyResumesTheQueue() = runTest(dispatcher) {
        assertThat(callback.onSearch(session,browser,"beyonce",null).await().resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        val found=callback.onGetSearchResult(session,browser,"beyonce",0,10,null).await().value!!
        assertThat(found.map { it.mediaMetadata.title.toString() }).containsExactly("Beyoncé")
        verify { music.searchAll("beyonce", SearchFilterType.SONGS) }
        val voice=MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("beyonce").build()).build()
        val result=callback.onSetMediaItems(session,browser,mutableListOf(voice),0,0).await()
        assertThat(result.mediaItems.map { it.mediaId }).containsExactly("1")
        val empty=voice.buildUpon().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("").build()).build()
        val resumed=callback.onSetMediaItems(session,browser,mutableListOf(empty),0,0).await()
        assertThat(resumed.mediaItems).containsExactlyElementsIn(saved).inOrder()
        assertThat(resumed.startIndex).isEqualTo(1)
        assertThat(resumed.startPositionMs).isEqualTo(13_000L)
    }
    @Test fun emptyVoiceQueryResumesTheExistingSavedOccurrence() = runTest(dispatcher) {
        val request=MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("").build()).build()
        val resumed=callback.onSetMediaItems(session,browser,mutableListOf(request),0,0).await()
        assertThat(resumed.mediaItems).containsExactlyElementsIn(saved).inOrder()
        assertThat(resumed.startIndex).isEqualTo(1)
        assertThat(resumed.startPositionMs).isEqualTo(13_000L)
    }
    @Test fun autoGetsOnlyItsThreeCustomCommandsAndNoTimeline() {
        val connection=callback.onConnect(session,browser)
        val commands=connection.availableSessionCommands!!.commands.map { it.customAction }
        assertThat(commands).containsAtLeastElementsIn(AutoHostPolicy.commands)
        assertThat(commands).doesNotContain(MusicNotificationProvider.CUSTOM_COMMAND_CLOSE_PLAYER)
        assertThat(connection.availablePlayerCommands!!.contains(Player.COMMAND_GET_TIMELINE)).isFalse()
        // B69: the car is MediaBrowseCallerPolicy's answer (B69MediaBrowseCallerTest has the whole table).
        assertThat(AutoHostPolicy.isAuto(service.browseCaller(controllerInfo("com.android.car.media", trusted = false)))).isFalse()
        assertThat(AutoHostPolicy.isAuto(service.browseCaller(controllerInfo("com.android.car.media", trusted = true)))).isTrue()
        assertThat(AutoHostPolicy.isAuto(service.browseCaller(controllerInfo("invented.test.host", trusted = false)))).isFalse()
    }
    private fun controllerInfo(packageName: String, trusted: Boolean): MediaSession.ControllerInfo = mockk(relaxed = true) {
        every { this@mockk.packageName } returns packageName
        every { isTrusted } returns trusted
    }
    @Test fun theRecentRootAndItsSingleItemRemainAvailable() = runTest(dispatcher) {
        val params=MediaLibraryService.LibraryParams.Builder().setRecent(true).build()
        val root=callback.onGetLibraryRoot(session,browser,params).await()
        assertThat(root.value!!.mediaId).isEqualTo("polylute_recent")
        val recent=callback.onGetChildren(session,browser,"polylute_recent",0,100,params).await()
        assertThat(recent.value).hasSize(1)
        assertThat(recent.value!!.first().mediaId).isEqualTo(saved[1].mediaId)
    }
    @Test fun manifestDeclaresMediaAndReusesTheMonochromeGlyphWithoutNewPermissions() {
        val manifest=File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).contains("com.google.android.gms.car.application")
        assertThat(manifest).contains("com.google.android.gms.car.notification.SmallIcon")
        assertThat(manifest).contains("@drawable/ic_stat_polylute")
        assertThat(manifest).contains("android.media.action.MEDIA_PLAY_FROM_SEARCH")
        assertThat(File("src/main/res/xml/automotive_app_desc.xml").readText()).contains("<uses name=\"media\"")
        assertThat(manifest).doesNotContain("android.permission.CAR_")
    }
}
