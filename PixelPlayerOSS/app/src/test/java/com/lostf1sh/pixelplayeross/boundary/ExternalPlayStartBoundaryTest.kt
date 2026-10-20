package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * FIX-1b: an external VIEW loads fast and still plays, because `play()` (and with it the audio-focus
 * request Android 15 refuses to a background app) moves to the moment the app is in the foreground.
 * The player, the process lifecycle and the engine are Android-bound, so the wiring is asserted at
 * source level; the rule itself is in `ExternalPlayStartTest`.
 */
class ExternalPlayStartBoundaryTest {

    private val java = "java/com/lostf1sh/pixelplayeross"
    private fun source(relative: String): String =
        File("src/main", "$java/$relative").readText().replace("\r\n", "\n")

    private val viewModel = source("presentation/viewmodel/PlayerViewModel.kt")
    private val engine = source("data/service/player/DualPlayerEngine.kt")

    @Test
    fun `the external VIEW loads without playing and plays through the coordinator`() {
        val external = viewModel.substringAfter("pendingQueueSegmentsJob = ExternalPlayback(").substringBefore("loadSiblings = {")
        assertThat(Regex("""internalPlaySongs\(\s*queueSongs, chosen, queueLabel, null,\s*source = ListenSource\(ListenSourceKind.EXTERNAL_VIEW\),\s*autoPlay = false,\s*onLoaded = """).containsMatchIn(external)).isTrue()
        // The hand-over to the player is still immediate: the load is not waiting for the foreground.
        assertThat(external.indexOf("internalPlaySongs(")).isLessThan(external.indexOf("startExternalPlayback(chosen, requestedAt, loaded, timeline)"))
        val start = viewModel.substringAfter("private fun startExternalPlayback(").substringBefore("/** Suspends until the app is in the foreground")
        assertThat(start).contains("ExternalPlayStart(")
        assertThat(start).contains("focusDenials = { dualPlayerEngine.focusDenials.value }")
        assertThat(start).contains("stillCurrent = { dualPlayerEngine.masterPlayer.currentMediaItem?.mediaId == chosen.id }")
        assertThat(start).contains("dualPlayerEngine.masterPlayer.play()")
    }

    @Test
    fun `waiting for the foreground is bounded and asks the lifecycle and the process state`() {
        val wait = viewModel.substringAfter("private suspend fun awaitAppForeground(): Boolean {").substringBefore("\n    }\n")
        // B12a-5: the lifecycle RESUMED, or the activity manager saying the process is the top app, whichever first
        assertThat(wait).contains("ForegroundWait(")
        assertThat(wait).contains("ProcessLifecycleOwner.get().lifecycle")
        assertThat(wait).contains("Lifecycle.State.RESUMED")
        assertThat(wait).contains("myProcessImportance() <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND")
        // still bounded by the same cap, and it says which signal ended the wait
        assertThat(wait).contains(".await(ExternalPlayStart.FOREGROUND_WAIT_MS)")
        assertThat(wait).contains("External VIEW foreground: via=%s")
        assertThat(wait).contains("return result.waited")
        // the stricter state only: a foreground *service* (importance 125) is not "in front"
        assertThat(wait).doesNotContain("IMPORTANCE_FOREGROUND_SERVICE")
        assertThat(wait).doesNotContain("IMPORTANCE_VISIBLE")
    }

    @Test
    fun `every other way of playing a song still starts it at once`() {
        // `autoPlay` defaults to true and is passed as false from exactly one call site.
        assertThat(viewModel).contains("autoPlay: Boolean = true,")
        assertThat(Regex("""autoPlay = false""").findAll(viewModel).count()).isEqualTo(1)
        assertThat(viewModel).contains("if (autoPlay) enginePlayer.play()")
        assertThat(Regex("""enginePlayer\.play\(\)""").findAll(viewModel).count()).isEqualTo(1)
    }

    @Test
    fun `the engine counts a refused focus request and its own handling is unchanged`() {
        val refused = engine.substringAfter("Timber.tag(\"TransitionDebug\").w(\"AudioFocus Request Failed: \$result\")")
            .substringBefore("\n            }\n        }")
        assertThat(refused).contains("_focusDenials.value = _focusDenials.value + 1")
        // The engine still answers a refusal as it always did; the retry is the caller's, not the engine's.
        assertThat(refused).contains("playerA.playWhenReady = false")
        assertThat(engine).contains("val focusDenials: StateFlow<Int> = _focusDenials.asStateFlow()")
    }

    @Test
    fun `the fix touches neither the native engine nor the USB output`() {
        // Code only: the explanation of why the refusal happens names the platform call in a comment.
        val coordinator = source("presentation/viewmodel/ExternalPlayStart.kt")
            .replace(Regex("""(?s)/\*.*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")
        assertThat(coordinator).doesNotContain("UsbAudioSink")
        assertThat(coordinator).doesNotContain("com.decent.usbaudio")
        assertThat(coordinator).doesNotContain("requestAudioFocus")
        assertThat(coordinator).doesNotContain("AudioManager")
    }
}
