package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.MainCoroutineExtension
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.player.predictSkipPreviousCarouselIndex
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * AUD-116 (confirmed on the device, rec/prev.mp4): "previous" around 3 s flashed the previous track's
 * artwork for ~1 s while the audio restarted the current one. `previousSong()` delegated to
 * `MediaController.seekToPrevious()`, which masks the result locally with the controller's own
 * position — ~2 s behind the session — while the session decided with its real position.
 *
 * One threshold (3 s), one position (the engine's, in this process), one explicit action.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class PreviousSongTest {

    private val engine: DualPlayerEngine = mockk(relaxed = true)
    private val enginePlayer = mockk<Player>(relaxed = true)
    private val controller = mockk<MediaController>(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)
    private val item = MediaItem.Builder().setMediaId("ramona").build()

    init {
        every { appContext.getSystemService(Context.POWER_SERVICE) } returns mockk<PowerManager>(relaxed = true)
        every { engine.masterPlayer } returns enginePlayer
        every { controller.isConnected } returns true
        every { controller.currentMediaItem } returns item
        every { enginePlayer.currentMediaItem } returns item
        every { controller.hasPreviousMediaItem() } returns true
        every { enginePlayer.hasPreviousMediaItem() } returns true
    }

    private fun holder() = PlaybackStateHolder(
        dualPlayerEngine = engine,
        userPreferencesRepository = mockk<UserPreferencesRepository>(relaxed = true),
        queueStateHolder = mockk(relaxed = true),
        appContext = appContext,
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        dispatchers = TestDispatcherProvider(Dispatchers.Unconfined),
    ).also { it.setMediaController(controller) }

    @Test
    fun `past 3 s by the engine's clock it restarts, even when the controller lags behind`() {
        every { enginePlayer.currentPosition } returns 5_300L
        every { controller.currentPosition } returns 3_000L - 700L   // the measured ~2 s lag

        holder().previousSong()

        verify { controller.seekTo(0L) }
        verify { engine.notifyExternalSeekInitiated(0L) }
        verify(exactly = 0) { controller.seekToPrevious() }
        verify(exactly = 0) { controller.seekToPreviousMediaItem() }
    }

    @Test
    fun `within 3 s it goes to the previous item, explicitly`() {
        every { enginePlayer.currentPosition } returns 2_000L
        every { controller.currentPosition } returns 2_000L

        holder().previousSong()

        verify { controller.seekToPreviousMediaItem() }
        verify(exactly = 0) { controller.seekToPrevious() }
    }

    /**
     * Owner, build fix2: "pre 10 s cambia carátula y repite canción". The carousel predicted the
     * previous item with its own 10 s threshold while the action restarted at > 3 s.
     */
    @Test
    fun `at 5 s the carousel predicts no previous item, matching the restart`() {
        val queue = persistentListOf(song("a"), song("b"), song("c"))
        val predicted = predictSkipPreviousCarouselIndex(
            currentIndex = 2,
            queue = queue,
            currentPositionMs = 5_000L,
            repeatMode = Player.REPEAT_MODE_OFF,
        )
        assertThat(predicted).isNull()
        assertThat(PreviousTrackPolicy.restartsCurrent(positionMs = 5_000L, hasPrevious = true)).isTrue()
    }

    @Test
    fun `within 3 s both predict and take the previous item`() {
        val queue = persistentListOf(song("a"), song("b"), song("c"))
        assertThat(predictSkipPreviousCarouselIndex(2, queue, 2_000L, Player.REPEAT_MODE_OFF)).isEqualTo(1)
        assertThat(PreviousTrackPolicy.restartsCurrent(positionMs = 2_000L, hasPrevious = true)).isFalse()
    }

    @Test
    fun `the UI decides with the engine's position, like the action`() {
        every { enginePlayer.currentPosition } returns 5_300L
        every { controller.currentPosition } returns 2_300L
        assertThat(holder().previousDecisionPositionMs()).isEqualTo(5_300L)
    }

    private fun song(id: String) = Song(
        id = id,
        title = id,
        artist = "A",
        artistId = 0L,
        album = "Album",
        albumId = 0L,
        path = "/music/$id.mp3",
        contentUriString = "content://$id",
        albumArtUriString = null,
        duration = 100_000L,
        mimeType = null,
        bitrate = null,
        sampleRate = null,
    )
}
