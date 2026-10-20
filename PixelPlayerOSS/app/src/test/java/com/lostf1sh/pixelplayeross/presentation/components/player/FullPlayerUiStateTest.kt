package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.CarouselStyle
import com.lostf1sh.pixelplayeross.data.preferences.FullPlayerLoadingTweaks
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaybackAudioMetadata
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test

/**
 * B21: what a song change must not recompose. The surfaces take one slice of the state each; Compose skips a surface
 * whose slice compares equal to the last one. So the claim "a song change does not recompose the controls, the sheet
 * environment, or the lyrics layer" is a claim about equality, and it is checked here on the state the host builds.
 *
 * The providers (`isPlayingProvider`, the position, the duration) are the same lambdas across a song change: they are
 * remembered by the sheet, read by the surface that draws them, and are what keeps fast-changing values out of the
 * slices altogether.
 */
class FullPlayerUiStateTest {

    private fun song(id: String, title: String = "Song $id", artistId: Long = 1L) = Song(
        id = id, title = title, artist = "Artist", artistId = artistId, album = "Album", albumId = 3L, path = "/m/$id.flac",
        contentUriString = "content://$id", albumArtUriString = null, duration = 200_000L, mimeType = "audio/flac", bitrate = 900, sampleRate = 44100,
    )

    private val env = PlayerSheetEnv(
        loadingTweaks = FullPlayerLoadingTweaks(),
        isSheetDragGestureActive = false,
        currentSheetState = PlayerSheetState.COLLAPSED,
        allowRealtimeUpdates = true,
        expansionFractionProvider = { 0f },
    )
    private val playing: () -> Boolean = { true }
    private val ready: () -> Boolean = { true }
    private val position: () -> Long = { 0L }
    private val duration: () -> Long = { 200_000L }
    private val lyrics = PlayerLyricsState(
        searchState = LyricsSearchUiState.Idle,
        stablePlayerStateFlow = MutableStateFlow(StablePlayerState()),
        playbackPositionFlow = MutableStateFlow(0L),
        externalLyricsEnabled = false, lyricsSyncOffset = 0, immersiveLyricsEnabled = false, immersiveLyricsTimeout = 4000L,
        isImmersiveTemporarilyDisabled = false, isShuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF, isFavorite = false,
        lyricsProvider = { null }, lyricsResolveCompletedProvider = { false },
    )

    private fun state(
        song: Song,
        index: Int = 0,
        isFavorite: Boolean = false,
        shuffle: Boolean = false,
        repeat: Int = Player.REPEAT_MODE_OFF,
        metadata: PlaybackAudioMetadata = PlaybackAudioMetadata(),
    ) = buildFullPlayerUiState(
        env = env, song = song, queue = persistentListOf(song), queueSourceName = "All Songs", mediaItemIndex = index,
        carouselStyle = CarouselStyle.NO_PEEK, artists = persistentListOf(), albumArtQuality = AlbumArtQuality.MEDIUM,
        audioMetadata = metadata, showFileInfo = true, isFavorite = isFavorite, isBuffering = false,
        isShuffleEnabled = shuffle, repeatMode = repeat, isPlayingProvider = playing, playWhenReadyProvider = ready,
        currentPositionProvider = position, totalDurationProvider = duration, lyrics = lyrics, isLandscape = false,
    )

    @Test
    fun `a song change leaves the controls, the environment and the lyrics layer equal, so those surfaces are skipped`() {
        val before = state(song("1"), index = 0)
        val after = state(song("2"), index = 1)
        assertThat(after.controls).isEqualTo(before.controls)
        assertThat(after.env).isEqualTo(before.env)
        assertThat(after.lyrics).isEqualTo(before.lyrics)
        assertThat(after.isLandscape).isEqualTo(before.isLandscape)
    }

    @Test
    fun `a song change moves exactly the surfaces that show the song`() {
        val before = state(song("1"), index = 0)
        val after = state(song("2"), index = 1)
        assertThat(after.cover).isNotEqualTo(before.cover)
        assertThat(after.trackInfo).isNotEqualTo(before.trackInfo)
        assertThat(after.progress).isNotEqualTo(before.progress)
    }

    @Test
    fun `the heart moves the track info and nothing else`() {
        val a = state(song("1"))
        val b = state(song("1"), isFavorite = true)
        assertThat(b.trackInfo).isNotEqualTo(a.trackInfo)
        assertThat(b.cover).isEqualTo(a.cover)
        assertThat(b.progress).isEqualTo(a.progress)
        assertThat(b.controls).isEqualTo(a.controls)
    }

    @Test
    fun `shuffle and repeat move the controls and nothing that shows the song`() {
        val a = state(song("1"))
        val b = state(song("1"), shuffle = true, repeat = Player.REPEAT_MODE_ALL)
        assertThat(b.controls).isNotEqualTo(a.controls)
        assertThat(b.cover).isEqualTo(a.cover)
        assertThat(b.trackInfo).isEqualTo(a.trackInfo)
        assertThat(b.progress).isEqualTo(a.progress)
    }

    @Test
    fun `the decoder naming the stream moves the progress surface only - the technical line is its own`() {
        val a = state(song("1"))
        val b = state(song("1"), metadata = PlaybackAudioMetadata(mediaId = "1", mimeType = "audio/flac", sampleRate = 96000))
        assertThat(b.progress).isNotEqualTo(a.progress)
        assertThat(b.cover).isEqualTo(a.cover)
        assertThat(b.trackInfo).isEqualTo(a.trackInfo)
        assertThat(b.controls).isEqualTo(a.controls)
        // And it is only ever offered for the song it names.
        assertThat(b.progress.runtimeMediaId).isEqualTo("1")
    }

    @Test
    fun `the queue source name travels with the cover, which is what the carousel plays from`() {
        val s = song("1")
        val st = state(s)
        assertThat(st.cover.queueSourceName).isEqualTo("All Songs")
        assertThat(st.cover.queue).containsExactly(s)
    }

    @Test
    fun `the skip prediction is the rule it was, now in the coordinator's file`() {
        val queue = persistentListOf(song("1"), song("2"), song("3"))
        // Next from the middle is the next index; from the last it wraps only with repeat-all.
        assertThat(predictSkipNextCarouselIndex(1, queue, Player.REPEAT_MODE_OFF)).isEqualTo(2)
        assertThat(predictSkipNextCarouselIndex(2, queue, Player.REPEAT_MODE_OFF)).isNull()
        assertThat(predictSkipNextCarouselIndex(2, queue, Player.REPEAT_MODE_ALL)).isEqualTo(0)
        // Previous after 3 s restarts the current song: the artwork does not move.
        assertThat(predictSkipPreviousCarouselIndex(1, queue, currentPositionMs = 10_000L, repeatMode = Player.REPEAT_MODE_OFF)).isNull()
        assertThat(predictSkipPreviousCarouselIndex(1, queue, currentPositionMs = 500L, repeatMode = Player.REPEAT_MODE_OFF)).isEqualTo(0)
        assertThat(resolveQueueIndex(queue, "3", currentMediaItemIndex = 2)).isEqualTo(2)
        assertThat(resolveQueueIndex(queue, "3", currentMediaItemIndex = 0)).isEqualTo(2)
        assertThat(resolveQueueIndex(queue, "9", currentMediaItemIndex = 0)).isNull()
    }
}
