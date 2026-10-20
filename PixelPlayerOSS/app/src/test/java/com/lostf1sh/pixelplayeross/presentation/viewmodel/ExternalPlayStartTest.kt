package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ExternalPlayStart.Outcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * FIX-1b (B11-fix, device 2026-10-07): the VIEW is handed to the player in ~340 ms, Android 15's audio
 * hardening refuses focus because the activity is not yet the foreground, and the song ends paused at
 * 0. The engine answers a refusal with `playWhenReady = false`, so the VIEW has to ask again once the
 * app is in the foreground.
 *
 * The platform is a fake: it refuses a play until `grantsFocusFromMs` (virtual time), exactly what the
 * hardening does until the process counts as the foreground.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalPlayStartTest {

    private class Platform(
        private val scope: TestScope,
        /** The app is in the foreground (the lifecycle says RESUMED) from this time. */
        val foregroundFromMs: Long = 0L,
        /** The platform grants focus from this time; before it every request is refused. */
        val grantsFocusFromMs: Long = 0L,
    ) {
        var denials = 0
        var playRequests = 0
        var playing = false
        var current = true
        val playTimes = mutableListOf<Long>()

        suspend fun awaitForeground(): Boolean {
            if (scope.currentTime >= foregroundFromMs) return false
            delay(foregroundFromMs - scope.currentTime)
            return true
        }

        fun play() {
            playRequests++
            playTimes += scope.currentTime
            if (scope.currentTime >= grantsFocusFromMs) playing = true else {
                denials++
                playing = false
            }
        }
    }

    private fun TestScope.start(platform: Platform) = ExternalPlayStart(
        awaitLoaded = { true },
        awaitForeground = platform::awaitForeground,
        stillCurrent = { platform.current },
        focusDenials = { platform.denials },
        play = platform::play,
        pause = { delay(it) },
    )

    @Test
    fun `a play at 300 ms, before the app is the foreground, ends paused, which was the bug`() {
        runTest {
            val platform = Platform(this, foregroundFromMs = 600L, grantsFocusFromMs = 600L)
            delay(300)
            // What the VIEW did before this fix: play() the moment the song was loaded.
            platform.play()
            assertThat(platform.playing).isFalse()
            assertThat(platform.denials).isEqualTo(1)
        }
    }

    @Test
    fun `the play waits for the foreground and the song ends up playing`() {
        runTest {
            val platform = Platform(this, foregroundFromMs = 600L, grantsFocusFromMs = 600L)
            delay(300) // the song was loaded in ~300 ms, as before
            val outcome = start(platform).run()
            assertThat(outcome).isEqualTo(Outcome.PLAYING)
            assertThat(platform.playing).isTrue()
            // Never asked while it would have been refused.
            assertThat(platform.denials).isEqualTo(0)
            assertThat(platform.playRequests).isEqualTo(1)
            assertThat(platform.playTimes.single()).isAtLeast(600L)
            // Loading did not get slower: the wait is for the foreground, nothing else.
            assertThat(currentTime).isAtMost(600L + ExternalPlayStart.SETTLE_AFTER_WAIT_MS)
        }
    }

    @Test
    fun `a refusal after the foreground, while the process state catches up, is retried until granted`() {
        runTest {
            // The lifecycle already says RESUMED, but the platform still refuses for another 500 ms.
            val platform = Platform(this, foregroundFromMs = 0L, grantsFocusFromMs = 500L)
            delay(300)
            val outcome = start(platform).run()
            assertThat(outcome).isEqualTo(Outcome.PLAYING)
            assertThat(platform.playing).isTrue()
            assertThat(platform.denials).isEqualTo(1)
            assertThat(platform.playRequests).isEqualTo(2)
            assertThat(currentTime).isAtMost(300L + 2 * ExternalPlayStart.RETRY_SPACING_MS)
        }
    }

    @Test
    fun `a focus that is never granted is retried a bounded number of times, then reported`() {
        runTest {
            val platform = Platform(this, foregroundFromMs = 0L, grantsFocusFromMs = Long.MAX_VALUE)
            val outcome = start(platform).run()
            assertThat(outcome).isEqualTo(Outcome.GAVE_UP)
            assertThat(platform.playRequests).isEqualTo(ExternalPlayStart.MAX_RETRIES + 1)
            assertThat(platform.playing).isFalse()
            assertThat(currentTime).isEqualTo(ExternalPlayStart.MAX_RETRIES * ExternalPlayStart.RETRY_SPACING_MS)
        }
    }

    @Test
    fun `it never plays a song that has been replaced in the meantime`() {
        runTest {
            val platform = Platform(this, foregroundFromMs = 600L, grantsFocusFromMs = 600L)
            platform.current = false
            val outcome = start(platform).run()
            assertThat(outcome).isEqualTo(Outcome.REPLACED)
            assertThat(platform.playRequests).isEqualTo(0)
        }
    }

    @Test
    fun `replaced between two attempts stops the retries`() {
        runTest {
            val platform = Platform(this, foregroundFromMs = 0L, grantsFocusFromMs = Long.MAX_VALUE)
            val replacing = ExternalPlayStart(
                awaitLoaded = { true },
                awaitForeground = platform::awaitForeground,
                stillCurrent = { platform.playRequests < 2 },
                focusDenials = { platform.denials },
                play = platform::play,
                pause = { delay(it) },
            )
            assertThat(replacing.run()).isEqualTo(Outcome.REPLACED)
            assertThat(platform.playRequests).isEqualTo(2)
        }
    }

    @Test
    fun `on the phone, already in the foreground and granted, it plays at once with no delay at all`() {
        runTest {
            val platform = Platform(this, foregroundFromMs = 0L, grantsFocusFromMs = 0L)
            val outcome = start(platform).run()
            assertThat(outcome).isEqualTo(Outcome.PLAYING)
            assertThat(platform.playRequests).isEqualTo(1)
            // Opening a FLAC from a file manager on the S22 costs nothing extra.
            assertThat(currentTime).isEqualTo(0L)
        }
    }

    @Test
    fun `the foreground wait is bounded by the caller, and a late app is still asked`() {
        runTest {
            // The foreground never arrives within the wait: awaitForeground returns (true) at the bound.
            val platform = Platform(this, foregroundFromMs = 0L, grantsFocusFromMs = 0L)
            val outcome = ExternalPlayStart(
                awaitLoaded = { true },
                awaitForeground = { delay(ExternalPlayStart.FOREGROUND_WAIT_MS); true },
                stillCurrent = { true },
                focusDenials = { platform.denials },
                play = platform::play,
                pause = { delay(it) },
            ).run()
            assertThat(outcome).isEqualTo(Outcome.PLAYING)
            assertThat(currentTime).isEqualTo(ExternalPlayStart.FOREGROUND_WAIT_MS + ExternalPlayStart.SETTLE_AFTER_WAIT_MS)
        }
    }

    @Test
    fun `a load that never ran is superseded and nothing is played`() {
        runTest {
            val platform = Platform(this, foregroundFromMs = 0L, grantsFocusFromMs = 0L)
            val outcome = ExternalPlayStart(
                awaitLoaded = { false },
                awaitForeground = platform::awaitForeground,
                stillCurrent = { true },
                focusDenials = { platform.denials },
                play = platform::play,
                pause = { delay(it) },
            ).run()
            assertThat(outcome).isEqualTo(Outcome.SUPERSEDED)
            assertThat(platform.playRequests).isEqualTo(0)
        }
    }

    @Test
    fun `nothing is asked of the foreground or the player before the song is loaded`() {
        runTest {
            val platform = Platform(this, foregroundFromMs = 0L, grantsFocusFromMs = 0L)
            var foregroundAsked = false
            val outcome = ExternalPlayStart(
                awaitLoaded = { delay(5_800L); true },
                awaitForeground = { foregroundAsked = true; platform.awaitForeground() },
                stillCurrent = { true },
                focusDenials = { platform.denials },
                play = platform::play,
                pause = { delay(it) },
            ).run()
            assertThat(outcome).isEqualTo(Outcome.PLAYING)
            assertThat(foregroundAsked).isTrue()
            // It plays the moment the slow load is there, with no extra wait of its own.
            assertThat(platform.playTimes.single()).isEqualTo(5_800L)
        }
    }

    // ── B12a-8 #5: a user command after the request ends the retries ─────────────────────────────────

    @Test
    fun `a pause during the retries is not overridden by the next play`() {
        runTest {
            // Focus is refused until 2 s: the first play() is refused and the retries follow, 300 ms apart.
            val platform = Platform(this, foregroundFromMs = 0L, grantsFocusFromMs = 2_000L)
            var commands = 0
            val start = ExternalPlayStart(
                awaitLoaded = { true },
                awaitForeground = platform::awaitForeground,
                stillCurrent = { true },
                focusDenials = { platform.denials },
                play = platform::play,
                pause = { delay(it) },
                userCommands = { commands },
            )
            launch { delay(450); commands++ }                       // the user pauses between the first and the third request
            val outcome = start.run()
            assertThat(outcome).isEqualTo(Outcome.USER_INTERVENED)
            // the requests made before the pause were refused; none was made after it
            assertThat(platform.playTimes.all { it < 450 }).isTrue()
            assertThat(platform.playRequests).isEqualTo(platform.playTimes.size)
            assertThat(platform.playing).isFalse()                  // the pause stands
        }
    }

    @Test
    fun `a command given while the song was still loading means no play at all`() {
        runTest {
            val platform = Platform(this, foregroundFromMs = 0L, grantsFocusFromMs = 0L)
            var commands = 3                                          // commands from before the request are not "since the request"
            val start = ExternalPlayStart(
                awaitLoaded = { delay(500); true },
                awaitForeground = platform::awaitForeground,
                stillCurrent = { true },
                focusDenials = { platform.denials },
                play = platform::play,
                pause = { delay(it) },
                userCommands = { commands },
            )
            launch { delay(100); commands++ }
            assertThat(start.run()).isEqualTo(Outcome.USER_INTERVENED)
            assertThat(platform.playRequests).isEqualTo(0)
        }
    }

    @Test
    fun `with no command since the request the retries are unchanged`() {
        runTest {
            val platform = Platform(this, foregroundFromMs = 0L, grantsFocusFromMs = 700L)
            val start = ExternalPlayStart(
                awaitLoaded = { true },
                awaitForeground = platform::awaitForeground,
                stillCurrent = { true },
                focusDenials = { platform.denials },
                play = platform::play,
                pause = { delay(it) },
                userCommands = { 7 },                                 // a constant: whatever number, nothing happened since
            )
            assertThat(start.run()).isEqualTo(Outcome.PLAYING)
            assertThat(platform.playRequests).isAtLeast(2)            // refused, then granted
        }
    }

    @Test
    fun `the holder counts the taps and the pauses from outside, and the viewmodel feeds the start with the count`() {
        val holder = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/PlaybackStateHolder.kt").readText()
        // B13-5: the count the retry reads is the holder's own taps plus the commands counted where they enter the app
        assertThat(holder).contains("val userPlaybackCommandCount: Int")
        assertThat(holder).contains("userCommands.get() + com.lostf1sh.pixelplayeross.data.service.ExternalPlaybackCommands.total")
        assertThat(holder.substringAfter("fun noteUserPlayPause(playbackWanted: Boolean) {").substringBefore("\n    }\n"))
            .contains("userCommands.incrementAndGet()")
        val viewModel = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/PlayerViewModel.kt").readText()
        assertThat(viewModel).contains("userCommands = { playbackStateHolder.userPlaybackCommandCount },")
        // a pause that is not the engine's refusal (REMOTE: the notification; NOISY: unplugged) is the user's
        val listener = viewModel.substringAfter("override fun onPlayWhenReadyChanged(").substringBefore("override fun onMediaItemTransition(")
        assertThat(listener).contains("Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE")
        assertThat(listener).contains("Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY")
        assertThat(listener).contains("playbackStateHolder.noteExternalPause()")
    }
}
