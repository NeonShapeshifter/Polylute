package com.lostf1sh.pixelplayeross.data.service

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.*
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.lostf1sh.pixelplayeross.data.collections.auto.*
import com.lostf1sh.pixelplayeross.data.database.*
import com.lostf1sh.pixelplayeross.data.model.*
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.service.MediaBrowseCallerPolicy.Caller
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.presentation.utils.SearchGenreMatcher
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import io.mockk.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B69 (owner, 2026-11-22): the library is for Android Auto, the car, the system and Polylute. Nothing else.
 * Every other app still connects and controls playback, and gets no library content. The B64 harness (real callback of the service,
 * real catalog over a synthetic library), with a caller per row of the policy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class B69MediaBrowseCallerTest {
    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { c ->
        addListener({ runCatching { get() }.onSuccess { c.resume(it) }.onFailure { c.resumeWithException(it) } }, MoreExecutors.directExecutor())
    }
    private val dispatcher = UnconfinedTestDispatcher()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val music = mockk<MusicRepository>(relaxed = true)
    private val playlists = mockk<LocalPlaylistDao>(relaxed = true)
    private val collections = mockk<AutoCollectionRepository>(relaxed = true)
    private val preferences = mockk<UserPreferencesRepository>(relaxed = true)
    private val rows = (1L..300L).map { id -> Kit.song(id, title = "Private song %03d".format(id), album = "Private album", albumId = if (id <= 3) 7 else 8)
        .copy(contentUriString = "content://synthetic/$id", path = "/synthetic/$id.flac", mimeType = "audio/flac") }
    private val album = Album(7, "Private album", "Private artist", 2026, 0, null, 3)
    private val order = listOf(rows[2], rows[0], rows[1])
    private lateinit var service: MusicService
    private lateinit var callback: MediaLibraryService.MediaLibrarySession.Callback
    private val session = mockk<MediaLibraryService.MediaLibrarySession>(relaxed = true)
    private val player = mockk<Player>(relaxed = true)

    /** The signing check of the test: only uid [GENUINE_AUTO_UID] is Google's Android Auto, only [GENUINE_GOOGLE_UID] the Google app. */
    private val signatures = MediaBrowseCallerPolicy.SignatureCheck { pkg, uid ->
        (pkg == MediaBrowseCallerPolicy.GEARHEAD && uid == GENUINE_AUTO_UID) || (pkg == MediaBrowseCallerPolicy.GOOGLE_APP && uid == GENUINE_GOOGLE_UID)
    }
    private val ownUid get() = android.os.Process.myUid()

    private fun caller(pkg: String, uid: Int = 10_999, trusted: Boolean = false, legacy: Boolean = false): MediaSession.ControllerInfo =
        mockk(relaxed = true) {
            every { packageName } returns pkg
            every { this@mockk.uid } returns uid
            every { isTrusted } returns trusted
            every { connectionHints } returns Bundle.EMPTY
            every { controllerVersion } returns if (legacy) MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION else 1_010_000
        }

    private val unknown get() = caller("com.example.unknown")
    private val unknownLegacy get() = caller("com.example.unknown", legacy = true)
    private val fakeAuto get() = caller(MediaBrowseCallerPolicy.GEARHEAD, uid = 10_666)
    private val genuineAuto get() = caller(MediaBrowseCallerPolicy.GEARHEAD, uid = GENUINE_AUTO_UID, legacy = true)
    private val systemUi get() = caller("com.android.systemui", uid = 10_100, trusted = true, legacy = true)
    private val carMedia get() = caller(MediaBrowseCallerPolicy.CAR_MEDIA, uid = 10_200, trusted = true)
    private val self get() = caller(context.packageName, uid = ownUid)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { preferences.songsSortOptionFlow } returns flowOf("song_title_az")
        every { preferences.likedSongsSortOptionFlow } returns flowOf("liked_title_az")
        coEvery { music.getSongsPage(any(), any(), any(), any()) } answers { rows.drop(secondArg<Int>()).take(firstArg<Int>()) }
        coEvery { music.getFavoriteSongsPage(any(), any(), any(), any()) } answers { rows.take(6).drop(secondArg()).take(firstArg()) }
        coEvery { music.getAlbumsPage(any(), any(), any(), any(), any()) } answers { listOf(album).drop(secondArg()).take(firstArg()) }
        coEvery { music.getArtistsPage(any(), any(), any(), any()) } answers { listOf(Artist(11, "Private artist", 3)).drop(secondArg()).take(firstArg()) }
        coEvery { music.getAutoSourceSongsPage(any(), any(), any(), any()) } answers { order.drop(arg(3)).take(arg(2)) }
        every { music.getAlbumById(7) } returns flowOf(album)
        every { music.getSong(any()) } answers { flowOf(rows.firstOrNull { it.id == firstArg<String>() }) }
        every { music.getSongsByIds(any()) } answers { val ids = firstArg<List<String>>(); flowOf(rows.filter { it.id in ids }) }
        every { music.searchAll(any(), SearchFilterType.SONGS) } answers {
            val q = SearchGenreMatcher.fold(firstArg())
            flowOf(rows.filter { SearchGenreMatcher.fold(it.title).contains(q) }.take(100).map { SearchResultItem.SongItem(it) })
        }
        val cat = Kit.catalog(order)
        val collection = AutoCollection(AutoCollectionId.FORGOTTEN_FAVORITES, order.map { cat.item(it.libraryItemId)!! })
        every { collections.state } returns MutableStateFlow(AutoCollectionsState(true, listOf(collection), emptySet()))
        every { collections.homeMixes } returns MutableStateFlow(HomeMixes(true, collection.items, collection.items))
        coEvery { collections.ensureFresh(any()) } just Runs
        val playlist = PlaylistEntity("p/1", "Private playlist")
        coEvery { playlists.getPlaylistsPage(any(), any()) } answers { listOf(playlist).drop(secondArg()).take(firstArg()) }
        val catalog = AutoMediaCatalog(context, music, playlists, collections, preferences)
        every { player.currentMediaItem } returns MediaItemBuilder.build(rows[0])
        every { player.mediaItemCount } returns 1
        every { player.getMediaItemAt(any()) } returns MediaItemBuilder.build(rows[0])
        every { session.player } returns player
        every { session.isMediaNotificationController(any()) } returns false
        val original = MusicService().apply {
            autoCatalog = catalog
            musicRepository = music
            userPreferencesRepository = preferences
            engine = mockk(relaxed = true) { every { masterPlayer } returns player }
            browseSignatureCheck = signatures
        }
        service = spyk(original)
        every { service.packageName } returns context.packageName
        every { service.applicationContext } returns context
        every { service.grantUriPermission(any(), any(), any()) } just Runs
        callback = service.createSessionCallback()
    }
    @After fun tearDown() { Dispatchers.resetMain(); unmockkAll() }

    // ── the policy ────────────────────────────────────────────────────────────────────────────────

    private fun classify(pkg: String, uid: Int, trusted: Boolean) =
        MediaBrowseCallerPolicy.classify(pkg, uid, trusted, appPackage = "app.polylute", appUid = 10_001, signatures = signatures)

    @Test fun thePolicyTable() {
        val table = listOf(
            Triple(Triple("app.polylute", 10_001, false), Caller.SELF, "ours"),
            Triple(Triple("app.polylute", 10_777, false), Caller.OTHER, "our package name from another uid"),
            Triple(Triple("com.android.systemui", 10_100, true), Caller.TRUSTED_SYSTEM, "trusted"),
            Triple(Triple(MediaBrowseCallerPolicy.GEARHEAD, GENUINE_AUTO_UID, false), Caller.ANDROID_AUTO, "gearhead, good certificate"),
            Triple(Triple(MediaBrowseCallerPolicy.GEARHEAD, 10_666, false), Caller.OTHER, "gearhead, wrong certificate"),
            Triple(Triple(MediaBrowseCallerPolicy.GEARHEAD, 10_666, true), Caller.TRUSTED_SYSTEM, "gearhead, wrong certificate, trusted"),
            Triple(Triple(MediaBrowseCallerPolicy.CAR_MEDIA, 10_200, true), Caller.CAR_MEDIA_CENTER, "AAOS, trusted"),
            Triple(Triple(MediaBrowseCallerPolicy.CAR_MEDIA, 10_200, false), Caller.OTHER, "AAOS, untrusted"),
            Triple(Triple("com.example.unknown", 10_999, false), Caller.OTHER, "an unknown app"),
            // owner 11-23: the Google app (Assistant voice requests on the phone), by its certificate only
            Triple(Triple(MediaBrowseCallerPolicy.GOOGLE_APP, GENUINE_GOOGLE_UID, false), Caller.GOOGLE_ASSISTANT, "Google app, good certificate"),
            Triple(Triple(MediaBrowseCallerPolicy.GOOGLE_APP, 10_667, false), Caller.OTHER, "Google app name, wrong certificate"),
        )
        for ((facts, expected, name) in table) {
            val (pkg, uid, trusted) = facts
            val got = classify(pkg, uid, trusted)
            println("B69 policy: $name -> $got (browse=${got.mayBrowse}, car=${got.isAutoHost})")
            assertThat(got).isEqualTo(expected)
        }
        assertThat(Caller.entries.filter { it.mayBrowse })
            .containsExactly(Caller.SELF, Caller.TRUSTED_SYSTEM, Caller.ANDROID_AUTO, Caller.CAR_MEDIA_CENTER, Caller.GOOGLE_ASSISTANT)
        assertThat(Caller.entries.filter { it.isAutoHost }).containsExactly(Caller.ANDROID_AUTO, Caller.CAR_MEDIA_CENTER)
    }

    @Test fun theProductionCheckAsksThePackageManagerAboutTheCallersUid() {
        assertThat(MediaBrowseCallerPolicy.GEARHEAD_RELEASE_DIGESTS).hasSize(2)
        assertThat(MediaBrowseCallerPolicy.GEARHEAD_RELEASE_DIGESTS.map { it.size }).containsExactly(32, 32)
        val good = MediaBrowseCallerPolicy.GEARHEAD_RELEASE_DIGESTS[1]
        val pm = mockk<PackageManager>()
        every { pm.getPackagesForUid(GENUINE_AUTO_UID) } returns arrayOf(MediaBrowseCallerPolicy.GEARHEAD)
        every { pm.getPackagesForUid(10_666) } returns arrayOf(MediaBrowseCallerPolicy.GEARHEAD)
        every { pm.getPackagesForUid(10_999) } returns arrayOf("com.example.unknown")
        every { pm.hasSigningCertificate(GENUINE_AUTO_UID, any<ByteArray>(), PackageManager.CERT_INPUT_SHA256) } answers { secondArg<ByteArray>().contentEquals(good) }
        every { pm.hasSigningCertificate(10_666, any<ByteArray>(), PackageManager.CERT_INPUT_SHA256) } returns false
        every { pm.hasSigningCertificate(10_999, any<ByteArray>(), PackageManager.CERT_INPUT_SHA256) } returns true
        val check = MediaBrowseCallerPolicy.packageManagerCheck(pm)
        assertThat(check.isGenuine(MediaBrowseCallerPolicy.GEARHEAD, GENUINE_AUTO_UID)).isTrue()   // the rotated key
        assertThat(check.isGenuine(MediaBrowseCallerPolicy.GEARHEAD, 10_666)).isFalse()           // sideloaded, wrong certificate
        assertThat(check.isGenuine(MediaBrowseCallerPolicy.GEARHEAD, 10_999)).isFalse()           // a name that is not that uid's
        every { pm.getPackagesForUid(GENUINE_AUTO_UID) } throws SecurityException("not visible")
        assertThat(check.isGenuine(MediaBrowseCallerPolicy.GEARHEAD, GENUINE_AUTO_UID)).isFalse()
    }

    @Test fun theGoogleAppIsCheckedAgainstItsOwnCertificateOnly() {
        assertThat(MediaBrowseCallerPolicy.GOOGLE_APP_RELEASE_DIGESTS.map { it.size }).containsExactly(32)
        val google = MediaBrowseCallerPolicy.GOOGLE_APP_RELEASE_DIGESTS[0]
        val auto = MediaBrowseCallerPolicy.GEARHEAD_RELEASE_DIGESTS[0]
        val pm = mockk<PackageManager>()
        every { pm.getPackagesForUid(GENUINE_GOOGLE_UID) } returns arrayOf(MediaBrowseCallerPolicy.GOOGLE_APP)
        every { pm.getPackagesForUid(10_668) } returns arrayOf(MediaBrowseCallerPolicy.GOOGLE_APP)
        every { pm.getPackagesForUid(10_999) } returns arrayOf("com.example.unknown")
        every { pm.hasSigningCertificate(GENUINE_GOOGLE_UID, any<ByteArray>(), PackageManager.CERT_INPUT_SHA256) } answers { secondArg<ByteArray>().contentEquals(google) }
        // signed with Android Auto's key but named as the Google app: each package has its own key
        every { pm.hasSigningCertificate(10_668, any<ByteArray>(), PackageManager.CERT_INPUT_SHA256) } answers { secondArg<ByteArray>().contentEquals(auto) }
        every { pm.hasSigningCertificate(10_999, any<ByteArray>(), PackageManager.CERT_INPUT_SHA256) } returns true
        val check = MediaBrowseCallerPolicy.packageManagerCheck(pm)
        assertThat(check.isGenuine(MediaBrowseCallerPolicy.GOOGLE_APP, GENUINE_GOOGLE_UID)).isTrue()
        assertThat(check.isGenuine(MediaBrowseCallerPolicy.GOOGLE_APP, 10_668)).isFalse()
        // a package with no known key is never genuine, whatever it is signed with
        assertThat(check.isGenuine("com.example.unknown", 10_999)).isFalse()
    }

    // ── the callbacks ─────────────────────────────────────────────────────────────────────────────

    @Test fun anUnknownAppIsRefusedEveryLibraryCallback() = runTest(dispatcher) {
        for (browser in listOf(unknown, fakeAuto)) {
            val root = callback.onGetLibraryRoot(session, browser, null).await()
            assertThat(root.resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
            val recent = callback.onGetLibraryRoot(session, browser, MediaLibraryService.LibraryParams.Builder().setRecent(true).build()).await()
            assertThat(recent.resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
            for (parent in listOf("polylute", "home", "playlists", "albums", "artists", "songs", "album:7", "polylute_recent")) {
                assertThat(callback.onGetChildren(session, browser, parent, 0, 100, null).await().resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
            }
            assertThat(callback.onGetItem(session, browser, "album:7").await().resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
            assertThat(callback.onSearch(session, browser, "private", null).await().resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
            assertThat(callback.onGetSearchResult(session, browser, "private", 0, 50, null).await().resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
            println("B69 after: ${browser.packageName} uid=${browser.uid} root=${root.resultCode} children/item/search refused")
        }
        coVerify(exactly = 0) { music.getSongsPage(any(), any(), any(), any()) }
        verify(exactly = 0) { music.searchAll(any(), any()) }
        verify(exactly = 0) { service.grantUriPermission(any(), any(), any()) }
    }

    @Test fun aLegacyBrowserThatMayNotBrowseGetsAnEmptyRootSoItCanStillConnect() = runTest(dispatcher) {
        val root = callback.onGetLibraryRoot(session, unknownLegacy, null).await()
        assertThat(root.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(root.value!!.mediaId).isEqualTo(MediaBrowseCallerPolicy.EMPTY_ROOT_ID)
        assertThat(root.value!!.mediaMetadata.isBrowsable).isFalse()
        assertThat(root.value!!.mediaMetadata.isPlayable).isFalse()
        assertThat(callback.onGetChildren(session, unknownLegacy, MediaBrowseCallerPolicy.EMPTY_ROOT_ID, 0, 100, null).await().resultCode)
            .isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
        assertThat(callback.onGetChildren(session, unknownLegacy, "polylute", 0, 100, null).await().resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
    }

    @Test fun guessedIdsAreNotResolvedForAnUnknownApp() = runTest(dispatcher) {
        val requests = listOf("42", "album:7", "songs/page:1/song:100", "collection:forgotten_favorites")
        for (id in requests) {
            val set = runCatching { callback.onSetMediaItems(session, unknown, mutableListOf(MediaItem.Builder().setMediaId(id).build()), 0, 0).await() }
            assertThat(rootCause(set.exceptionOrNull())).isInstanceOf(UnsupportedOperationException::class.java)
            val add = runCatching { callback.onAddMediaItems(session, unknown, mutableListOf(MediaItem.Builder().setMediaId(id).build())).await() }
            assertThat(rootCause(add.exceptionOrNull())).isInstanceOf(UnsupportedOperationException::class.java)
        }
        val voice = MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("private song").build()).build()
        assertThat(rootCause(runCatching { callback.onSetMediaItems(session, unknown, mutableListOf(voice), 0, 0).await() }.exceptionOrNull()))
            .isInstanceOf(UnsupportedOperationException::class.java)
        verify(exactly = 0) { music.getSongsByIds(any()) }
        verify(exactly = 0) { music.searchAll(any(), any()) }
    }

    @Test fun theAllowedCallersStillBrowseSearchAndResolve() = runTest(dispatcher) {
        for (browser in listOf(genuineAuto, systemUi, carMedia, self)) {
            val root = callback.onGetLibraryRoot(session, browser, null).await()
            assertThat(root.value!!.mediaId).isEqualTo("polylute")
            val tabs = callback.onGetChildren(session, browser, "polylute", 0, 100, null).await().value!!
            assertThat(tabs.map { it.mediaId }).containsExactly("home", "playlists", "albums", "artists").inOrder()
            assertThat(callback.onGetChildren(session, browser, "songs", 0, 100, null).await().value).hasSize(100)
            assertThat(callback.onGetItem(session, browser, "album:7").await().resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
            assertThat(callback.onSearch(session, browser, "private song 01", null).await().resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
            assertThat(callback.onGetSearchResult(session, browser, "private song 01", 0, 50, null).await().value).isNotEmpty()
            val album = callback.onSetMediaItems(session, browser, mutableListOf(MediaItem.Builder().setMediaId("album:7").build()), 0, 0).await()
            assertThat(album.mediaItems.map { it.mediaId }).containsExactlyElementsIn(order.map { it.id }).inOrder()
            val raw = callback.onAddMediaItems(session, browser, mutableListOf(MediaItem.Builder().setMediaId("42").build())).await()
            assertThat(raw.single().mediaMetadata.title.toString()).isEqualTo("Private song 042")
            println("B69 after: ${browser.packageName} (${service.browseCaller(browser)}) browses, searches and resolves")
        }
        // System UI's playback-resumption card (a legacy browser with the recent hint) still works.
        val recentParams = MediaLibraryService.LibraryParams.Builder().setRecent(true).build()
        assertThat(callback.onGetLibraryRoot(session, systemUi, recentParams).await().value!!.mediaId).isEqualTo("polylute_recent")
        assertThat(callback.onGetChildren(session, systemUi, "polylute_recent", 0, 10, recentParams).await().value).hasSize(1)
    }

    // ── onConnect ─────────────────────────────────────────────────────────────────────────────────

    private fun libraryCodes(result: MediaSession.ConnectionResult) =
        result.availableSessionCommands.commands.map { it.commandCode }.filter { it in MediaBrowseCallerPolicy.LIBRARY_COMMAND_CODES }.toSet()

    private fun customActions(result: MediaSession.ConnectionResult) =
        result.availableSessionCommands.commands.mapNotNull { it.customAction.takeIf(String::isNotEmpty) }.toSet()

    private val playbackControl = listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_STOP)

    @Test fun onConnectCommandsPerCaller() {
        val all = MediaBrowseCallerPolicy.LIBRARY_COMMAND_CODES
        val rows = listOf(
            "unknown (Media3)" to unknown, "unknown (legacy MediaBrowserCompat)" to unknownLegacy, "sideloaded gearhead" to fakeAuto,
            "Android Auto" to genuineAuto, "System UI" to systemUi, "AAOS media center" to carMedia, "Polylute" to self,
        )
        val results = rows.associate { (name, c) -> name to callback.onConnect(session, c) }
        results.forEach { (name, r) ->
            println("B69 onConnect $name: accepted=${r.isAccepted} library=${libraryCodes(r).sorted()} custom=${customActions(r).sorted()} " +
                "timeline=${r.availablePlayerCommands.contains(Player.COMMAND_GET_TIMELINE)} playPause=${r.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE)}")
            assertThat(r.isAccepted).isTrue()
            // Everybody keeps playback control: play, pause, skip, seek, the current song.
            playbackControl.forEach { assertThat(r.availablePlayerCommands.contains(it)).isTrue() }
        }
        assertThat(libraryCodes(results.getValue("unknown (Media3)"))).isEmpty()
        assertThat(libraryCodes(results.getValue("sideloaded gearhead"))).isEmpty()
        assertThat(libraryCodes(results.getValue("unknown (legacy MediaBrowserCompat)"))).containsExactly(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
        for (name in listOf("Android Auto", "System UI", "AAOS media center", "Polylute")) assertThat(libraryCodes(results.getValue(name))).isEqualTo(all)
        // The car commands follow the same trust: the sideloaded name gets none of them.
        assertThat(customActions(results.getValue("sideloaded gearhead"))).isEmpty()
        assertThat(customActions(results.getValue("unknown (Media3)"))).isEmpty()
        assertThat(customActions(results.getValue("Android Auto"))).isEqualTo(AutoHostPolicy.commands)
        assertThat(customActions(results.getValue("AAOS media center"))).isEqualTo(AutoHostPolicy.commands)
        // The queue (B19-S4 / B64, unchanged): only Polylute's own, non-notification controller gets it; nobody else had it before either.
        rows.filter { it.first != "Polylute" }.forEach { (name, _) ->
            assertThat(results.getValue(name).availablePlayerCommands.contains(Player.COMMAND_GET_TIMELINE)).isFalse()
        }
        assertThat(results.getValue("Polylute").availablePlayerCommands.contains(Player.COMMAND_GET_TIMELINE)).isTrue()
    }

    @Test fun theMediaNotificationControllerKeepsItsCommandsAndStillHasNoQueue() {
        val notification = self
        every { session.isMediaNotificationController(notification) } returns true
        val r = callback.onConnect(session, notification)
        assertThat(libraryCodes(r)).isEqualTo(MediaBrowseCallerPolicy.LIBRARY_COMMAND_CODES)
        assertThat(customActions(r)).contains(MusicNotificationProvider.CUSTOM_COMMAND_CLOSE_PLAYER)
        assertThat(r.availablePlayerCommands.contains(Player.COMMAND_GET_TIMELINE)).isFalse()
        playbackControl.forEach { assertThat(r.availablePlayerCommands.contains(it)).isTrue() }
    }

    @Test fun aCarCommandFromTheSideloadedNameIsRefused() = runTest(dispatcher) {
        val shuffle = SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_TOGGLE_SHUFFLE, Bundle.EMPTY)
        assertThat(callback.onCustomCommand(session, fakeAuto, shuffle, Bundle.EMPTY).await().resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
        assertThat(callback.onCustomCommand(session, unknown, shuffle, Bundle.EMPTY).await().resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
    }

    @Test fun privacyTextSaysWhoMayBrowse() {
        val privacy = File("../PRIVACY.md").readText()
        val section = privacy.substringAfter("## Android Auto and other media controllers").substringBefore("\n## ")
        assertThat(section).contains("signing certificate")
        assertThat(section).contains("Any other app")
        assertThat(section).doesNotContain("Polylute does not limit who browses")
    }

    private fun rootCause(t: Throwable?): Throwable? = generateSequence(t) { it.cause }.lastOrNull()

    private companion object {
        const val GENUINE_AUTO_UID = 10_555
        const val GENUINE_GOOGLE_UID = 10_556
    }
}
