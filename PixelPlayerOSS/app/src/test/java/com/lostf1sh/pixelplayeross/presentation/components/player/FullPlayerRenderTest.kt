package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.data.repository.LyricsSearchResult
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.utils.ValidatedLyricsImport
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B21: the full player, split into surfaces over one [FullPlayerUiState] and one [FullPlayerActions], still shows
 * what it showed (the header's three tabs, the title and artist, the times and the technical line, the transport and
 * the shuffle / repeat words), and every control still calls its action.
 *
 * The carousel is not drawn (an empty queue: it draws nothing for one), so no image loading is involved; the cover
 * surface's own contract is the state test.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h900dp-xxhdpi")
class FullPlayerRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = context.getString(id)

    private class Recorder : FullPlayerActions {
        val calls = mutableListOf<String>()
        override fun onPlayPause() { calls += "playPause" }
        override fun onSeek(positionMs: Long) { calls += "seek:$positionMs" }
        override fun onSkipNext() { calls += "skipNext" }
        override fun onSkipPrevious() { calls += "skipPrevious" }
        override fun onNext() { calls += "next" }
        override fun onPrevious() { calls += "previous" }
        override fun onShuffleToggle() { calls += "shuffle" }
        override fun onRepeatToggle() { calls += "repeat" }
        override fun onFavoriteToggle() { calls += "favorite" }
        override fun onCollapse() { calls += "collapse" }
        override fun onShowDetails() { calls += "details" }
        override fun onQueueDragStart() { calls += "dragStart" }
        override fun onQueueDrag(dragAmount: Float) { calls += "drag" }
        override fun onQueueRelease(totalDrag: Float, velocity: Float) { calls += "release" }
        override fun onPlayFromCarousel(song: Song, queue: ImmutableList<Song>, queueName: String, index: Int) { calls += "playFrom:$index" }
        override fun onOpenAlbum(albumId: Long) { calls += "album:$albumId" }
        override fun onOpenArtist(artistId: Long) { calls += "artist:$artistId" }
        override fun onAddBookmark(song: Song, title: String, positionMs: Long) { calls += "bookmark:$title" }
        override fun onEnsureLyricsResolved() { calls += "ensureLyrics" }
        override fun onFetchLyrics(forcePick: Boolean) { calls += "fetchLyrics:$forcePick" }
        override fun onAcceptLyricsResult(result: LyricsSearchResult) {}
        override fun onSearchLyricsManually(title: String, artist: String?) {}
        override fun onResetLyricsSearch() { calls += "resetLyricsSearch" }
        override fun onResetLyrics() {}
        override fun onLyricsSyncOffsetChange(songId: String, offsetMs: Int) {}
        override fun onLyricsAlignmentChange(alignment: String) {}
        override fun onShowLyricsTranslationChange(show: Boolean) {}
        override fun onShowLyricsRomanizationChange(show: Boolean) {}
        override fun onImmersiveTemporarilyDisabledChange(disabled: Boolean) {}
        override fun onSaveLyricsToFile(song: Song, lyrics: Lyrics, preferSynced: Boolean) {}
        override fun onImportLyricsFromFile(songId: Long, validated: ValidatedLyricsImport) {}
        override fun onToast(message: String) {}
        override fun previousDecisionPositionMs(): Long = 10_000L
    }

    private fun song(id: String, title: String, artist: String = "Mägo de Oz") = Song(
        id = id, title = title, artist = artist, artistId = 7L, album = "Gaia", albumId = 9L, path = "/m/$id.flac",
        contentUriString = "content://$id", albumArtUriString = null, duration = 280_000L, mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    private val env = PlayerSheetEnv(
        loadingTweaks = FullPlayerLoadingTweaks(delayAll = false),
        isSheetDragGestureActive = false,
        currentSheetState = PlayerSheetState.EXPANDED,
        allowRealtimeUpdates = false,
        expansionFractionProvider = { 1f },
    )

    private fun uiState(
        song: Song,
        isFavorite: Boolean = false,
        shuffle: Boolean = false,
        repeat: Int = Player.REPEAT_MODE_OFF,
        metadata: PlaybackAudioMetadata = PlaybackAudioMetadata(mediaId = song.id, mimeType = "audio/flac", sampleRate = 44100),
    ) = buildFullPlayerUiState(
        env = env, song = song, queue = persistentListOf(), queueSourceName = "All Songs", mediaItemIndex = 0,
        carouselStyle = CarouselStyle.NO_PEEK, artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
        audioMetadata = metadata, showFileInfo = true, isFavorite = isFavorite, isBuffering = false,
        isShuffleEnabled = shuffle, repeatMode = repeat, isPlayingProvider = { true }, playWhenReadyProvider = { true },
        currentPositionProvider = { 196_000L }, totalDurationProvider = { 280_000L },
        lyrics = PlayerLyricsState(
            searchState = LyricsSearchUiState.Idle,
            stablePlayerStateFlow = MutableStateFlow(StablePlayerState()),
            playbackPositionFlow = MutableStateFlow(0L),
            externalLyricsEnabled = false, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
            isImmersiveTemporarilyDisabled = false, isShuffleEnabled = shuffle, repeatMode = repeat, isFavorite = isFavorite,
            lyricsProvider = { null }, lyricsResolveCompletedProvider = { false },
        ),
        isLandscape = false,
    )

    private fun setPlayer(state: () -> FullPlayerUiState, actions: FullPlayerActions) = compose.setContent {
        CompositionLocalProvider(LocalMaterialTheme provides darkColorScheme()) {
            FullPlayerScreen(state(), actions)
        }
    }

    @Test
    fun `the player shows an icon-only header, the track, the times, the technical line and the transport`() {
        val actions = Recorder()
        setPlayer({ uiState(song("1", "La Costa Del Silencio")) }, actions)
        // B22: the header has no tabs and no words: collapse on the left, Lyrics / Details on the right. B33-U1: no Queue button (the queue is the sheet folded at the foot).
        for (cd in listOf(R.string.player_header_collapse, R.string.player_header_lyrics, R.string.player_header_details)) {
            compose.onAllNodesWithContentDescription(s(cd))[0].assertExists()
        }
        for (tab in listOf("Artwork", "Lyrics", "Details")) {
            compose.onAllNodesWithText(tab, ignoreCase = true).assertCountEquals(0)
        }
        compose.onAllNodesWithText("La Costa Del Silencio")[0].assertExists()
        compose.onAllNodesWithText("Mägo de Oz")[0].assertExists()
        // The transport is one row of five icons: no "Aleatorio / Repetir" words under it.
        for (cd in listOf(R.string.player_shuffle, R.string.lock_transport_previous, R.string.cd_pause, R.string.lock_transport_next, R.string.lock_transport_repeat)) {
            compose.onAllNodesWithContentDescription(s(cd))[0].assertExists()
        }
        compose.onAllNodesWithText(s(R.string.player_shuffle)).assertCountEquals(0)
        compose.onAllNodesWithText("Repeat").assertCountEquals(0)
        // The bookmark is beside the heart now, and the technical line is under the bar.
        compose.onAllNodesWithContentDescription(s(R.string.player_add_bookmark))[0].assertExists()
        compose.onAllNodesWithText("FLAC · 44.1 kHz")[0].assertExists()
        // The total time; the position is a tick of its own.
        compose.onAllNodesWithText("04:40")[0].assertExists()
    }

    @Test
    fun `every control calls its action`() {
        val actions = Recorder()
        setPlayer({ uiState(song("1", "La Costa Del Silencio")) }, actions)
        compose.onNodeWithContentDescription(s(R.string.player_header_details)).performClick()
        compose.onNodeWithContentDescription(s(R.string.player_header_collapse)).performClick()
        compose.onNodeWithContentDescription(s(R.string.player_shuffle)).performClick()
        compose.onNodeWithContentDescription(s(R.string.lock_transport_repeat)).performClick()
        compose.onNodeWithContentDescription(s(R.string.cd_add_to_favorites)).performClick()
        compose.onNodeWithContentDescription(s(R.string.cd_pause)).performClick()
        assertEquals(listOf("details", "collapse", "shuffle", "repeat", "favorite", "playPause"), actions.calls)
    }

    @Test
    fun `the heart, shuffle and repeat follow the state`() {
        setPlayer({ uiState(song("1", "Song"), isFavorite = true, shuffle = true, repeat = Player.REPEAT_MODE_ALL) }, Recorder())
        compose.onAllNodesWithContentDescription(s(R.string.cd_remove_from_favorites)).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(s(R.string.cd_add_to_favorites)).assertCountEquals(0)
        // Active is the accent and a dot, said to a screen reader as "selected" with its state.
        compose.onNodeWithContentDescription(s(R.string.player_shuffle)).assertIsSelected()
        compose.onNodeWithContentDescription(s(R.string.lock_transport_repeat)).assertIsSelected()
        compose.onNodeWithContentDescription(s(R.string.cd_remove_from_favorites)).assertIsSelected()
    }

    @Test
    fun `a song change redraws the track and leaves the transport as it was`() {
        var current by mutableStateOf(uiState(song("1", "First song")))
        val actions = Recorder()
        setPlayer({ current }, actions)
        compose.onAllNodesWithText("First song")[0].assertExists()
        current = uiState(song("2", "Second song"))
        compose.waitForIdle()
        compose.onAllNodesWithText("Second song")[0].assertExists()
        compose.onAllNodesWithText("First song").assertCountEquals(0)
        // The transport is the one it was: it still calls the same actions.
        compose.onNodeWithContentDescription(s(R.string.player_shuffle)).performClick()
        assertTrue(actions.calls.toString(), actions.calls == listOf("shuffle"))
    }

    /**
     * Compose's own tracing hook (what Perfetto's composition tracing uses): it is called when a composable *runs*, and
     * not when it is skipped. Counting its calls by name is a direct count of recompositions.
     */
    private class RecompositionCounter : CompositionTracer {
        val runs = mutableMapOf<String, Int>()
        override fun isTraceInProgress(): Boolean = true
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val name = info.substringBefore(" (").substringAfterLast('.')
            runs[name] = (runs[name] ?: 0) + 1
        }
        override fun traceEventEnd() {}
        fun count(name: String) = runs[name] ?: 0
    }

    @Test
    fun `a song change recomposes the surfaces that show the song, and not the controls`() {
        var current by mutableStateOf(uiState(song("1", "First song")))
        val actions = Recorder()
        setPlayer({ current }, actions)
        compose.waitForIdle()

        val counter = RecompositionCounter()
        Composer.setTracer(counter)
        try {
            // Warm the baseline: a recomposition with nothing changed runs none of the surfaces.
            current = current.copy()
            compose.waitForIdle()
            assertEquals(
                counter.runs.toString(), 0,
                counter.count("PlayerControlsSurface") + counter.count("PlayerTrackInfoSurface") + counter.count("DapPlayerHeader"),
            )
            counter.runs.clear()

            current = uiState(song("2", "Second song"))
            compose.waitForIdle()

            // The song changed: the surfaces that show it ran...
            assertTrue(counter.runs.toString(), counter.count("PlayerTrackInfoSurface") >= 1)
            assertTrue(counter.runs.toString(), counter.count("PlayerProgressSurface") >= 1)
            // ...and the transport, the technical-line-free controls surface, did not. Neither did the header (B22): its
            // four icons read nothing of the song.
            assertEquals(counter.runs.toString(), 0, counter.count("PlayerControlsSurface"))
            assertEquals(counter.runs.toString(), 0, counter.count("DapPlayerHeader"))
            println("B21/B22 | song change recompositions: ${counter.runs.filterKeys { it.startsWith("Player") || it.startsWith("FullPlayer") }}")
        } finally {
            Composer.setTracer(object : CompositionTracer {
                override fun isTraceInProgress() = false
                override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {}
                override fun traceEventEnd() {}
            })
        }
    }
}
