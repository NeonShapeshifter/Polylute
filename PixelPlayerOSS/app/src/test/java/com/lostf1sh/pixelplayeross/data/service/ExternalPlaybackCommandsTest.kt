package com.lostf1sh.pixelplayeross.data.service

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ExternalPlayStart
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ExternalPlayStart.Outcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B13-5. A pause from the notification, a headset or a widget reaches the player as `USER_REQUEST`, the same reason as
 * the pause the engine makes itself when audio focus is refused, so the reason on the player's callback cannot tell
 * them apart and [ExternalPlayStart]'s retry could `play()` over the user's pause. The commands are now counted where they
 * enter the app, and a pause the app makes itself never passes through there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalPlaybackCommandsTest {

    private val app = "com.lostf1sh.pixelplayeross.debug"

    // ---- which commands count ---------------------------------------------------------------------

    @Test
    fun `a pause from the media notification counts, although it is the app's own package`() {
        assertThat(
            ExternalPlaybackCommands.countsAsUserCommand(Player.COMMAND_PLAY_PAUSE, app, app, isMediaNotificationController = true)
        ).isTrue()
    }

    @Test
    fun `a pause from a Bluetooth headset or the system UI counts`() {
        assertThat(
            ExternalPlaybackCommands.countsAsUserCommand(Player.COMMAND_PLAY_PAUSE, "com.android.bluetooth", app, false)
        ).isTrue()
        assertThat(
            ExternalPlaybackCommands.countsAsUserCommand(Player.COMMAND_PLAY_PAUSE, "com.android.systemui", app, false)
        ).isTrue()
    }

    @Test
    fun `a stop from outside counts`() {
        assertThat(
            ExternalPlaybackCommands.countsAsUserCommand(Player.COMMAND_STOP, "com.android.bluetooth", app, false)
        ).isTrue()
    }

    @Test
    fun `the app's own screens are not counted twice, their taps are recorded by the state holder`() {
        assertThat(
            ExternalPlaybackCommands.countsAsUserCommand(Player.COMMAND_PLAY_PAUSE, app, app, isMediaNotificationController = false)
        ).isFalse()
    }

    @Test
    fun `commands that are not play or pause do not count`() {
        for (command in listOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_BACK, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_GET_TIMELINE)) {
            assertThat(
                ExternalPlaybackCommands.countsAsUserCommand(command, "com.android.bluetooth", app, false)
            ).isFalse()
        }
    }

    @Test
    fun `noting a command moves the total by one`() {
        val before = ExternalPlaybackCommands.total
        ExternalPlaybackCommands.note()
        assertThat(ExternalPlaybackCommands.total).isEqualTo(before + 1)
    }

    // ---- the case the review found --------------------------------------------------------------------

    private class Platform(private val scope: TestScope, val grantsFocusFromMs: Long) {
        var denials = 0
        var playRequests = 0
        val playTimes = mutableListOf<Long>()
        fun play() {
            playRequests++
            playTimes += scope.currentTime
            if (scope.currentTime < grantsFocusFromMs) denials++
        }
    }

    @Test
    fun `a pause from the notification during the retries means there is no later play`() {
        runTest {
            val platform = Platform(this, grantsFocusFromMs = 5_000L)        // every request is refused
            val start = ExternalPlayStart(
                awaitLoaded = { true },
                awaitForeground = { false },
                stillCurrent = { true },
                focusDenials = { platform.denials },
                play = platform::play,
                pause = { delay(it) },
                // The same source the view model feeds it with: the holder's count includes the entry-point count.
                userCommands = { ExternalPlaybackCommands.total },
            )
            // The retries are spaced 300 ms apart; the user pauses from the notification between two of them.
            launch {
                delay(450)
                ExternalPlaybackCommands.note()
            }

            assertThat(start.run()).isEqualTo(Outcome.USER_INTERVENED)
            assertThat(platform.playTimes).isNotEmpty()
            assertThat(platform.playTimes.all { it < 450L }).isTrue()       // nothing after the pause
        }
    }

    @Test
    fun `without a command the retries still carry on until the platform grants focus`() {
        runTest {
            val platform = Platform(this, grantsFocusFromMs = 700L)
            val start = ExternalPlayStart(
                awaitLoaded = { true },
                awaitForeground = { false },
                stillCurrent = { true },
                focusDenials = { platform.denials },
                play = platform::play,
                pause = { delay(it) },
                userCommands = { ExternalPlaybackCommands.total },
            )
            assertThat(start.run()).isEqualTo(Outcome.PLAYING)
        }
    }

    @Test
    fun `the entry points are wired where the commands come in`() {
        val service = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/data/service/MusicService.kt").readText()
        // every controller's command passes the session before it runs
        val callback = service.substringAfter("override fun onPlayerCommandRequest(").substringBefore("override fun onCustomCommand(")
        assertThat(callback).contains("ExternalPlaybackCommands.countsAsUserCommand(")
        assertThat(callback).contains("ExternalPlaybackCommands.note()")
        assertThat(callback).contains("return super.onPlayerCommandRequest(session, controller, playerCommand)")
        // the widget's service intent is an entry point of its own
        val widget = service.substringAfter("PlayerActions.PLAY_PAUSE -> {").substringBefore("PlayerActions.NEXT -> {")
        assertThat(widget).contains("ExternalPlaybackCommands.note()")
        // and the state holder's count, which the VIEW retry reads, includes them
        val holder = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/PlaybackStateHolder.kt").readText()
        assertThat(holder.substringAfter("val userPlaybackCommandCount: Int").substringBefore("/** A pause from outside"))
            .contains("ExternalPlaybackCommands.total")
    }
}
