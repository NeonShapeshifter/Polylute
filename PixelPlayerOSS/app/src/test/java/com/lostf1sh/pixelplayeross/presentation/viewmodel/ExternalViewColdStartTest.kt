package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.service.RestoreQueuePolicy
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ExternalPlayStart.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

/**
 * FIX-1c (device 2026-10-07, process freshly started, 2 of 4 VIEWs ended paused):
 *
 * ```
 * External VIEW/SEND replaces the queue: player state=3 error=null items=1119   <- the restored queue
 * MediaController not available. Queuing playback action.
 * External VIEW/SEND handed to the player in 534 ms
 * External VIEW/SEND play: REPLACED, 679 ms after the request (state=3 playWhenReady=false)
 * ```
 *
 * The restore had finished before the VIEW (that is where the 1119 items come from) and did not
 * overwrite anything. The VIEW's own load was queued behind the MediaController, and the guard read
 * the player *before* the load ran: it saw the restored song, concluded "replaced", and gave up 145 ms
 * before its own song was put there. The VIEW then ended paused on its own song.
 *
 * The harness reproduces the order of `PlayerViewModel`'s controller listener: the ready flag first,
 * then the queued action. `yield()` between the two is the point where the waiter can run (with
 * `Dispatchers.Main.immediate` it runs inline there).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalViewColdStartTest {

    private class Device(private val scope: TestScope) {
        var queue: List<String> = emptyList()
        var current: String? = null
        var playing = false
        val playedSongs = mutableListOf<String?>()
        val controllerReady = MutableStateFlow(false)
        private var pendingAction: (() -> Unit)? = null
        private var pendingLoad: CompletableDeferred<Boolean>? = null

        /** The service's restore, with the rule it uses: only into an empty player. */
        fun restoreApplies(ids: List<String>, startIndex: Int): Boolean {
            if (!RestoreQueuePolicy.mayApply(queue.size)) return false
            queue = ids
            current = ids[startIndex]
            return true
        }

        /** A VIEW's load: straight into the player when the controller is connected, else queued behind it. */
        fun requestViewLoad(id: String, loaded: CompletableDeferred<Boolean>) {
            val action: () -> Unit = {
                queue = listOf(id)
                current = id
                loaded.complete(true)
            }
            if (controllerReady.value) {
                action()
            } else {
                // `queuePlaybackAction`: a request already waiting is dropped, and told.
                pendingLoad?.complete(false)
                pendingLoad = loaded
                pendingAction = action
            }
        }

        /** The user picks something else in the app. */
        fun userChooses(id: String) {
            queue = listOf(id)
            current = id
        }

        /** MediaController connects: the flag, a chance for any waiter to run, then the queued action. */
        suspend fun controllerConnects() {
            controllerReady.value = true
            yield()
            pendingAction?.invoke()
            pendingAction = null
        }

        fun play() {
            playing = true
            playedSongs += current
        }

        fun coordinator(
            id: String,
            awaitLoaded: suspend () -> Boolean,
            foregroundFromMs: Long = 0L,
        ) = ExternalPlayStart(
            awaitLoaded = awaitLoaded,
            awaitForeground = {
                if (scope.currentTime >= foregroundFromMs) {
                    false
                } else {
                    delay(foregroundFromMs - scope.currentTime)
                    true
                }
            },
            stillCurrent = { current == id },
            focusDenials = { 0 },
            play = ::play,
            pause = { delay(it) },
        )
    }

    private val restored = List(1119) { "restored-$it" }

    @Test
    fun `a VIEW queued behind the controller plays once its song is loaded, over the restored queue`() {
        runTest {
            val device = Device(this)
            assertThat(device.restoreApplies(restored, 3)).isTrue()
            val loaded = CompletableDeferred<Boolean>()
            device.requestViewLoad("view", loaded)
            val result = async { device.coordinator("view", awaitLoaded = { loaded.await() }).run() }

            delay(145)
            device.controllerConnects()

            assertThat(result.await()).isEqualTo(Outcome.PLAYING)
            assertThat(device.current).isEqualTo("view")
            assertThat(device.queue).containsExactly("view")
            assertThat(device.playedSongs).containsExactly("view")
            assertThat(device.playing).isTrue()
        }
    }

    @Test
    fun `reading the player as soon as the controller is ready called the restored queue a replacement`() {
        runTest {
            val device = Device(this)
            device.restoreApplies(restored, 3)
            val loaded = CompletableDeferred<Boolean>()
            device.requestViewLoad("view", loaded)
            // The first wiring: wait for the controller flag, then look at the player.
            val result = async {
                device.coordinator("view", awaitLoaded = { device.controllerReady.first { it }; true }).run()
            }

            delay(145)
            device.controllerConnects()

            // The bug as logged: REPLACED, the load runs afterwards, the song is there but never played.
            assertThat(result.await()).isEqualTo(Outcome.REPLACED)
            assertThat(device.current).isEqualTo("view")
            assertThat(device.playing).isFalse()
        }
    }

    @Test
    fun `the restore finishing after the VIEW has loaded does not overwrite it`() {
        runTest {
            val device = Device(this)
            // The controller is already connected: the VIEW is in the player before the restore applies.
            device.controllerConnects()
            val loaded = CompletableDeferred<Boolean>()
            device.requestViewLoad("view", loaded)

            assertThat(device.restoreApplies(restored, 3)).isFalse()

            assertThat(device.current).isEqualTo("view")
            assertThat(device.queue).containsExactly("view")
            assertThat(device.coordinator("view", awaitLoaded = { loaded.await() }).run()).isEqualTo(Outcome.PLAYING)
            assertThat(device.playedSongs).containsExactly("view")
        }
    }

    @Test
    fun `the restore only ever fills an empty player`() {
        assertThat(RestoreQueuePolicy.mayApply(0)).isTrue()
        assertThat(RestoreQueuePolicy.mayApply(1)).isFalse()
        assertThat(RestoreQueuePolicy.mayApply(1119)).isFalse()
    }

    @Test
    fun `a song the user chooses after the VIEW loaded and before it played is a real replacement`() {
        runTest {
            val device = Device(this)
            device.restoreApplies(restored, 3)
            device.controllerConnects()
            val loaded = CompletableDeferred<Boolean>()
            device.requestViewLoad("view", loaded)
            // The app is not in the foreground yet: the play is waiting for it.
            val result = async {
                device.coordinator("view", awaitLoaded = { loaded.await() }, foregroundFromMs = 600L).run()
            }
            delay(300)
            device.userChooses("chosen-in-the-app")

            assertThat(result.await()).isEqualTo(Outcome.REPLACED)
            assertThat(device.current).isEqualTo("chosen-in-the-app")
            assertThat(device.playedSongs).isEmpty()
        }
    }

    @Test
    fun `a VIEW whose load was taken over before it ran is superseded and never plays`() {
        runTest {
            val device = Device(this)
            val first = CompletableDeferred<Boolean>()
            val second = CompletableDeferred<Boolean>()
            device.requestViewLoad("first", first)
            val firstResult = async { device.coordinator("first", awaitLoaded = { first.await() }).run() }
            device.requestViewLoad("second", second)
            val secondResult = async { device.coordinator("second", awaitLoaded = { second.await() }).run() }

            delay(2_000)
            device.controllerConnects()

            assertThat(firstResult.await()).isEqualTo(Outcome.SUPERSEDED)
            assertThat(secondResult.await()).isEqualTo(Outcome.PLAYING)
            assertThat(device.playedSongs).containsExactly("second")
            assertThat(device.current).isEqualTo("second")
        }
    }

    @Test
    fun `a controller that takes as long as the slowest cold start still ends in a play`() {
        runTest {
            val device = Device(this)
            device.restoreApplies(restored, 3)
            val loaded = CompletableDeferred<Boolean>()
            device.requestViewLoad("view", loaded)
            val result = async { device.coordinator("view", awaitLoaded = { loaded.await() }).run() }

            delay(5_800)
            device.controllerConnects()

            assertThat(result.await()).isEqualTo(Outcome.PLAYING)
            // No wait of its own on top of the controller's: it plays when the song is there.
            assertThat(currentTime).isEqualTo(5_800L)
            assertThat(device.playedSongs).containsExactly("view")
        }
    }

    @Test
    fun `the foreground arriving after the load is waited for, not skipped`() {
        runTest {
            val device = Device(this)
            val loaded = CompletableDeferred<Boolean>()
            device.requestViewLoad("view", loaded)
            val result = async {
                device.coordinator("view", awaitLoaded = { loaded.await() }, foregroundFromMs = 1_200L).run()
            }
            delay(400)
            device.controllerConnects()

            assertThat(result.await()).isEqualTo(Outcome.PLAYING)
            assertThat(currentTime).isEqualTo(1_200L + ExternalPlayStart.SETTLE_AFTER_WAIT_MS)
        }
    }
}
