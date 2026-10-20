package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Behaviour, not source text: drives [FadingPlayer] through quick play/pause sequences against a
 * fake inner player and checks the level it leaves behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FadingPlayerVolumeTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private var volume = 0.8f
    private var playing = false
    /** Whether each write to the inner player was marked as a ramp step. */
    private val writesMarked = mutableListOf<Boolean>()

    private val inner = mockk<Player>(relaxed = true).also { player ->
        every { player.volume } answers { volume }
        every { player.setVolume(any()) } answers {
            volume = firstArg()
            writesMarked += FadingPlayer.isRampWrite
        }
        every { player.isPlaying } answers { playing }
        every { player.play() } answers { playing = true }
        every { player.pause() } answers { playing = false }
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `every fade write is marked as a ramp step`() {
        val player = FadingPlayer(inner, scope)
        player.play()
        scope.advanceUntilIdle()
        player.pause()
        scope.advanceUntilIdle()
        assertThat(writesMarked).isNotEmpty()
        assertThat(writesMarked).doesNotContain(false)
        assertThat(FadingPlayer.isRampWrite).isFalse()
    }

    @Test
    fun `a pause during the fade-in does not lower the next play's level`() {
        val player = FadingPlayer(inner, scope)
        player.play()
        scope.advanceTimeBy(100) // part-way up the ramp
        scope.runCurrent()
        assertThat(volume).isLessThan(0.8f)
        player.pause()
        scope.advanceUntilIdle()
        player.play()
        scope.advanceUntilIdle()
        assertThat(volume).isWithin(1e-4f).of(0.8f)
    }

    @Test
    fun `a play during the pause ramp restores the level`() {
        val player = FadingPlayer(inner, scope)
        player.play()
        scope.advanceUntilIdle()
        player.pause()
        scope.advanceTimeBy(20) // part-way down the ramp, still playing
        scope.runCurrent()
        assertThat(volume).isLessThan(0.8f)
        player.play()
        scope.advanceUntilIdle()
        assertThat(volume).isWithin(1e-4f).of(0.8f)
    }

    @Test
    fun `repeated quick play-pause cycles never ratchet the level down`() {
        val player = FadingPlayer(inner, scope)
        repeat(10) {
            player.play()
            scope.advanceTimeBy(60)
            scope.runCurrent()
            player.pause()
            scope.advanceTimeBy(15)
            scope.runCurrent()
        }
        player.play()
        scope.advanceUntilIdle()
        assertThat(volume).isWithin(1e-4f).of(0.8f)
    }
}
