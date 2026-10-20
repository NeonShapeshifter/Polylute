package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.runtime.State
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.LyricsSearchResult
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioBookmarksViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.utils.ValidatedLyricsImport
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Test

/**
 * B21: every control of the full player reaches what it always reached: the sheet's own callback for the transport,
 * the queue and details entries; the PlayerViewModel (or bookmarks) method for the rest.
 */
class FullPlayerActionsWiringTest {

    private val player = mockk<PlayerViewModel>(relaxed = true)
    private val bookmarks = mockk<AudioBookmarksViewModel>(relaxed = true)
    private val calls = mutableListOf<String>()
    private val drags = mutableListOf<Float>()

    private val callbacks = PlayerSheetCallbacks(
        onPlayPause = { calls += "playPause" },
        onSeek = { calls += "seek:$it" },
        onNext = { calls += "next" },
        onPrevious = { calls += "previous" },
        onCollapse = { calls += "collapse" },
        onShowDetails = { calls += "details" },
        onQueueDragStart = { calls += "dragStart" },
        onQueueDrag = { drags += it },
        onQueueRelease = { total, velocity -> calls += "release:$total:$velocity" },
        onShuffleToggle = { calls += "shuffle" },
        onRepeatToggle = { calls += "repeat" },
        onFavoriteToggle = { calls += "favorite" },
    )
    private val actions = PlayerViewModelFullPlayerActions(player, bookmarks, state(callbacks))

    private fun <T> state(v: T) = object : State<T> { override val value: T = v }

    private fun song() = Song(
        id = "42", title = "La Costa Del Silencio", artist = "Mägo de Oz", artistId = 7L, album = "Gaia", albumId = 9L,
        path = "/music/a.flac", contentUriString = "content://a", albumArtUriString = null, duration = 280_000L,
        mimeType = "audio/flac", bitrate = 1003, sampleRate = 44100,
    )

    @Test
    fun `the transport and the details are the sheet's own callbacks`() {
        actions.onPlayPause()
        actions.onSeek(1234L)
        actions.onNext()
        actions.onPrevious()
        actions.onCollapse()
        actions.onShowDetails()
        actions.onQueueDragStart()
        actions.onQueueDrag(-3f)
        actions.onQueueRelease(-80f, -900f)
        actions.onShuffleToggle()
        actions.onRepeatToggle()
        actions.onFavoriteToggle()
        assertThat(calls).containsExactly(
            "playPause", "seek:1234", "next", "previous", "collapse", "details", "dragStart",
            "release:-80.0:-900.0", "shuffle", "repeat", "favorite",
        ).inOrder()
        assertThat(drags).containsExactly(-3f)
    }

    @Test
    fun `the plain skip actions are the sheet's next and previous, and the screen is what swaps in the coordinator`() {
        actions.onSkipNext()
        actions.onSkipPrevious()
        assertThat(calls).containsExactly("next", "previous").inOrder()
    }

    @Test
    fun `the sheet's callbacks are read when called, so a long-lived actions object calls the latest`() {
        var current = callbacks
        val live = object : State<PlayerSheetCallbacks> { override val value get() = current }
        val longLived = PlayerViewModelFullPlayerActions(player, bookmarks, live)
        longLived.onPlayPause()
        current = PlayerSheetCallbacks(
            onPlayPause = { calls += "playPause-2" }, onSeek = {}, onNext = {}, onPrevious = {}, onCollapse = {},
            onShowDetails = {}, onQueueDragStart = {}, onQueueDrag = {}, onQueueRelease = { _, _ -> }, onShuffleToggle = {},
            onRepeatToggle = {}, onFavoriteToggle = {},
        )
        longLived.onPlayPause()
        assertThat(calls).containsExactly("playPause", "playPause-2").inOrder()
    }

    @Test
    fun `the track controls reach the PlayerViewModel`() {
        val song = song()
        val queue = persistentListOf(song)
        actions.onPlayFromCarousel(song, queue, "All Songs", 0)
        actions.onOpenAlbum(9L)
        actions.onOpenArtist(7L)
        actions.onAddBookmark(song, "Chorus", 61_000L)
        verify(exactly = 1) { player.showAndPlaySong(song = song, contextSongs = queue, queueName = "All Songs", indexInQueue = 0) }
        verify(exactly = 1) { player.triggerAlbumNavigationFromPlayer(9L) }
        verify(exactly = 1) { player.triggerArtistNavigationFromPlayer(7L) }
        verify(exactly = 1) { bookmarks.addBookmark(song, "Chorus", 61_000L) }
    }

    @Test
    fun `the lyrics controls reach the PlayerViewModel`() {
        val song = song()
        val lyrics = mockk<Lyrics>()
        val result = mockk<LyricsSearchResult>()
        val validated = mockk<ValidatedLyricsImport>()
        actions.onEnsureLyricsResolved()
        actions.onFetchLyrics(true)
        actions.onAcceptLyricsResult(result)
        actions.onSearchLyricsManually("title", "artist")
        actions.onResetLyricsSearch()
        actions.onResetLyrics()
        actions.onLyricsSyncOffsetChange("42", -250)
        actions.onLyricsAlignmentChange("left")
        actions.onShowLyricsTranslationChange(false)
        actions.onShowLyricsRomanizationChange(true)
        actions.onImmersiveTemporarilyDisabledChange(true)
        actions.onSaveLyricsToFile(song, lyrics, true)
        actions.onImportLyricsFromFile(42L, validated)
        actions.onToast("hello")
        verify(exactly = 1) { player.ensureLyricsResolvedForCurrentSong() }
        verify(exactly = 1) { player.fetchLyricsForCurrentSong(true) }
        verify(exactly = 1) { player.acceptLyricsSearchResultForCurrentSong(result) }
        verify(exactly = 1) { player.searchLyricsManually("title", "artist") }
        verify(exactly = 1) { player.resetLyricsSearchState() }
        verify(exactly = 1) { player.resetLyricsForCurrentSong() }
        verify(exactly = 1) { player.setLyricsSyncOffset("42", -250) }
        verify(exactly = 1) { player.setLyricsAlignment("left") }
        verify(exactly = 1) { player.setShowLyricsTranslation(false) }
        verify(exactly = 1) { player.setShowLyricsRomanization(true) }
        verify(exactly = 1) { player.setImmersiveTemporarilyDisabled(true) }
        verify(exactly = 1) { player.saveLyricsToFile(song, lyrics, true) }
        verify(exactly = 1) { player.importLyricsFromFile(42L, validated) }
        verify(exactly = 1) { player.sendToast("hello") }
    }

    @Test
    fun `the previous-or-restart decision uses the engine's position, not the display tick`() {
        every { player.previousDecisionPositionMs() } returns 2_500L
        assertThat(actions.previousDecisionPositionMs()).isEqualTo(2_500L)
    }
}
