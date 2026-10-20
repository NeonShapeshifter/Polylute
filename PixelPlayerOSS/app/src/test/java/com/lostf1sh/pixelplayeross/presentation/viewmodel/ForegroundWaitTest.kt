package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ForegroundWait.Via
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B12a-5: the cold VIEW's foreground wait. Device timeline: `loaded=2179 foreground=5184 play=5334` — the song was
 * in the player at 2.2 s and the wait for "foreground" then ate the whole 3 s cap although the overlay was on screen.
 * The wait now ends on the process lifecycle being RESUMED or on the activity manager saying the process is the top
 * app, whichever comes first, and still never earlier than the app really is in front (FIX-1b).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundWaitTest {

    private fun TestScope.wait(lifecycleFromMs: Long, processFromMs: Long) = ForegroundWait(
        lifecycleResumed = { currentTime >= lifecycleFromMs },
        processInForeground = { currentTime >= processFromMs },
        pause = { delay(it) },
    )

    private val never = Long.MAX_VALUE

    @Test
    fun `in front by either signal when asked, there is no wait at all`() = runTest {
        assertThat(wait(0, never).await().via).isEqualTo(Via.ALREADY)
        assertThat(wait(never, 0).await().via).isEqualTo(Via.ALREADY)
        assertThat(wait(0, 0).await().waited).isFalse()
        assertThat(currentTime).isEqualTo(0L)
    }

    @Test
    fun `the process state ends the wait when the lifecycle has not caught up`() = runTest {
        val result = wait(lifecycleFromMs = never, processFromMs = 100).await()
        assertThat(result.via).isEqualTo(Via.PROCESS_STATE)
        assertThat(result.waited).isTrue()
        assertThat(result.waitedMs).isAtMost(100L + ForegroundWait.POLL_MS)
        assertThat(currentTime).isAtMost(100L + ForegroundWait.POLL_MS)
    }

    @Test
    fun `the lifecycle ends it when it comes first`() = runTest {
        assertThat(wait(lifecycleFromMs = 200, processFromMs = never).await().via).isEqualTo(Via.LIFECYCLE)
    }

    @Test
    fun `when both signals arrive together the lifecycle is the one reported, as the original rule was built on it`() = runTest {
        assertThat(wait(lifecycleFromMs = 120, processFromMs = 120).await().via).isEqualTo(Via.LIFECYCLE)
    }

    @Test
    fun `an app that is not in front is waited for up to the cap and no sooner - the FIX-1b rule`() = runTest {
        val result = wait(lifecycleFromMs = never, processFromMs = never).await()
        assertThat(result.via).isEqualTo(Via.TIMEOUT)
        assertThat(result.waitedMs).isEqualTo(ExternalPlayStart.FOREGROUND_WAIT_MS)
        assertThat(currentTime).isEqualTo(ExternalPlayStart.FOREGROUND_WAIT_MS)
    }

    @Test
    fun `an app that comes to the front late, within the cap, is not asked before it does`() = runTest {
        val result = wait(lifecycleFromMs = 2_000, processFromMs = 2_500).await()
        assertThat(result.via).isEqualTo(Via.LIFECYCLE)
        assertThat(currentTime).isAtLeast(2_000L)
        assertThat(currentTime).isLessThan(2_000L + ForegroundWait.POLL_MS + 1)
    }

    @Test
    fun `the cold VIEW of the device log plays at about 2_4 s, not at 5_3 s, and a refusal is still retried`() = runTest {
        // The song is loaded at 2 179 ms; the process state says "top app" at 2 200 ms; the lifecycle never does.
        val loadedAt = 2_179L
        val topFrom = 2_200L
        var denials = 0
        val playTimes = mutableListOf<Long>()
        // The platform refuses focus until 2 500 ms (the process state lags the activity by a few frames).
        val start = ExternalPlayStart(
            awaitLoaded = { delay(loadedAt); true },
            awaitForeground = {
                ForegroundWait({ false }, { currentTime >= topFrom }, { delay(it) }).await().waited
            },
            stillCurrent = { true },
            focusDenials = { denials },
            play = { playTimes += currentTime; if (currentTime < 2_500L) denials++ },
            pause = { delay(it) },
        )
        assertThat(start.run()).isEqualTo(ExternalPlayStart.Outcome.PLAYING)
        // not the old behaviour: 2 179 + 3 000 (cap) + 150 = 5 329 for the first request
        assertThat(playTimes.first()).isLessThan(2_500L)
        assertThat(playTimes.first()).isAtLeast(topFrom)
        // the first request was refused (before 2 500 ms) and the retry, 300 ms later, went through
        assertThat(playTimes).hasSize(2)
        assertThat(playTimes.last()).isAtLeast(2_500L)
        assertThat(playTimes.last()).isLessThan(3_500L)
    }
}
