package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * FIX-1c: a VIEW into a freshly started process. The Android-bound wiring (the MediaController
 * listener, the player, the service's restore) is asserted at source level; the rules are in
 * `ExternalViewColdStartTest` and `ExternalPlayStartTest`.
 */
class ExternalViewColdStartBoundaryTest {

    private val java = "java/com/lostf1sh/pixelplayeross"
    private fun source(relative: String): String =
        File("src/main", "$java/$relative").readText().replace("\r\n", "\n")

    private val viewModel = source("presentation/viewmodel/PlayerViewModel.kt")
    private val service = source("data/service/MusicService.kt")
    private val external = viewModel.substringAfter("private suspend fun playExternalUriNow(").substringBefore("fun showPlayer()")

    @Test
    fun `every request that waits for the controller goes through one helper that tells the VIEW it was dropped`() {
        // The only non-null assignment of the waiting action is inside the helper.
        assertThat(Regex("""pendingPlaybackAction = (?!null)""").findAll(viewModel).count()).isEqualTo(1)
        assertThat(Regex("""queuePlaybackAction\s*[({]""").findAll(viewModel).count()).isEqualTo(4) // definition + 3 sites
        val helper = viewModel.substringAfter("private fun queuePlaybackAction(action: () -> Unit) {").substringBefore("\n    }\n")
        assertThat(helper).contains("pendingExternalLoad?.complete(false)")
        assertThat(helper.indexOf("complete(false)")).isLessThan(helper.indexOf("pendingPlaybackAction = action"))
        // A controller that fails to connect tells it too: its play must not wait for a load that never runs.
        val listenerCatch = viewModel.substringAfter("mediaController = mediaControllerFuture.get()").substringAfter("} catch (e: Exception) {")
            .substringBefore("Error setting up MediaController")
        assertThat(listenerCatch).contains("pendingExternalLoad?.complete(false)")
    }

    @Test
    fun `the VIEW's play and its tail wait for its own load, never for the player to look replaced`() {
        assertThat(external).contains("val loaded = CompletableDeferred<Boolean>()")
        assertThat(external).contains("loaded.complete(true)")
        assertThat(external).contains("if (!loaded.isCompleted) pendingExternalLoad = loaded")
        // The tail waits for the load (the player still holds the restored queue until then).
        val attach = external.substringAfter("attach = { tail ->").substringBefore("withContext(Dispatchers.Main.immediate) {")
        assertThat(attach).contains("loaded.await()")
        assertThat(attach).doesNotContain("_isMediaControllerReady")
        // Nothing in the VIEW path reads the controller flag and then looks at the player.
        assertThat(external).doesNotContain("_isMediaControllerReady")
        val start = viewModel.substringAfter("private fun startExternalPlayback(").substringBefore("/** Suspends until the app is in the foreground")
        assertThat(start).contains("awaitLoaded = {")
        assertThat(start).contains("loaded.await()")
        assertThat(start).doesNotContain("_isMediaControllerReady")
    }

    @Test
    fun `the load reports itself from inside the action, after the song is prepared`() {
        val action = viewModel.substringAfter("val playSongsAction = {").substringBefore("if (songsToPlay.size > 1) {")
        val prepare = action.indexOf("enginePlayer.prepare()")
        val play = action.indexOf("if (autoPlay) enginePlayer.play()")
        val loaded = action.indexOf("onLoaded?.invoke()")
        assertThat(prepare).isAtLeast(0)
        assertThat(play).isGreaterThan(prepare)
        assertThat(loaded).isGreaterThan(play)
        // Every other caller passes nothing: the default is no callback.
        assertThat(viewModel).contains("onLoaded: (() -> Unit)? = null,")
        assertThat(Regex("""onLoaded = \{""").findAll(viewModel).count()).isEqualTo(1)
    }

    @Test
    fun `the restore yields to anything that loaded first, at the start and again right before it applies`() {
        val restore = service.substringAfter("private suspend fun restorePlaybackQueueSnapshotIfNeeded() {")
            .substringBefore("private fun buildMediaItemFromSnapshot(")
        assertThat(Regex("""RestoreQueuePolicy\.mayApply\(""").findAll(restore).count()).isEqualTo(2)
        val finalApply = restore.substringAfter("val applied = withContext(Dispatchers.Main.immediate) {")
        // The re-check is on the main thread, before the queue is set, after all the off-main probing.
        assertThat(finalApply.indexOf("RestoreQueuePolicy.mayApply(")).isLessThan(finalApply.indexOf("player.setMediaItems("))
        assertThat(restore.indexOf("QueueFileProbe.presence(")).isLessThan(restore.indexOf("val applied = withContext"))
        // When it yields it says so, and does not claim to have restored.
        val skipped = restore.substringAfter("if (!applied) {").substringBefore("Restored playback snapshot")
        assertThat(skipped).contains("not restored: the player already has a queue")
        assertThat(skipped).contains("return")
    }

    @Test
    fun `the cold start writes where its time went`() {
        val marks = listOf("BUILT", "HANDED", "CONTROLLER", "LOADED", "FOREGROUND", "PLAY", "PLAYING")
        for (mark in marks) assertThat(viewModel).contains("ExternalViewTimeline.$mark")
        assertThat(viewModel).contains("\"External VIEW timeline: %s\", timeline.describe()")
        assertThat(viewModel).contains("android.os.Process.getStartElapsedRealtime()")
        // The controller step is marked before the flag flips, so it is not later than the load it enables.
        assertThat(viewModel.indexOf("ExternalViewTimeline.CONTROLLER"))
            .isLessThan(viewModel.indexOf("_isMediaControllerReady.value = true"))
    }

    @Test
    fun `the wait for the controller is long enough for the slowest cold start measured`() {
        val wait = Regex("""EXTERNAL_CONTROLLER_WAIT_MS = (\d+)_(\d+)L""").find(viewModel)!!
        val millis = (wait.groupValues[1] + wait.groupValues[2]).toLong()
        assertThat(millis).isAtLeast(10 * 5_800L / 2) // well past the 5.8 s the device measured
        assertThat(viewModel).contains("withTimeoutOrNull(EXTERNAL_CONTROLLER_WAIT_MS) { loaded.await() }")
    }
}
