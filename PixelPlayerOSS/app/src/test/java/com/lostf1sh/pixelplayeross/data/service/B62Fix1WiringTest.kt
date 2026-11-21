package com.lostf1sh.pixelplayeross.data.service

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

class B62Fix1WiringTest {
    private val service = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/MusicService.kt").readText()
    private fun block(from: String, to: String) = service.substringAfter(from).substringBefore(to)

    @Test fun `ordinary startup waits without a deadline and logs the whole restore once`() {
        assertThat(service).contains("val restored = playbackRestore.awaitCompletion()")
        assertThat(service).contains("Playback restore finished: ms=%d result=%s")
    }

    @Test fun `debounced and unload writes share the guard including a captured null`() {
        val persist = block("private suspend fun persistPlaybackSnapshot(", "private suspend fun capturePlaybackSnapshot(")
        val unload = block("private fun writePlaybackSnapshotOnUnload(", "A debounced snapshot persist")
        for (body in listOf(persist, unload)) {
            assertThat(body).contains("playbackRestore.mayPersistSnapshot(")
            assertThat(body).contains("snapshot?.items?.isNotEmpty() == true")
            assertThat(body).contains("isRestoringPlaybackSnapshot")
            assertThat(body.indexOf("mayPersistSnapshot(")).isLessThan(body.indexOf("setPlaybackQueueSnapshot(snapshot)"))
        }
        assertThat(unload.indexOf("mayPersistSnapshot(")).isLessThan(unload.indexOf("appScope.launch"))
    }

    @Test fun `checkpoint sampling also checks the restore before allowing position writes`() {
        val sample = block("private fun playingPosition()", "/** Writes the position now")
        assertThat(sample).contains("playbackRestore.mayPersistSnapshot(")
        assertThat(sample).contains("isRestoringPlaybackSnapshot")
    }

    @Test fun `timed out media waiters return no playable result`() {
        val resume = block("override fun onPlaybackResumption(", "override fun onGetLibraryRoot(")
        assertThat(resume).contains("showPlaybackStartRefused")
        assertThat(resume).contains("throw java.util.concurrent.TimeoutException")
        val children = block("override fun onGetChildren(", "override fun onConnect(")
        assertThat(children).contains("if (!playbackRestore.await())")
        assertThat(children).contains("showPlaybackStartRefused")
        assertThat(children).contains("return@future LibraryResult.ofError")
    }
    @Test fun `temporary foreground cleanup cannot stop a restore whose command has timed out`() {
        val cleanup = block("private fun finishTemporaryForeground(", "private suspend fun dispatchWidgetCommand(")
        assertThat(cleanup).contains("if (!playbackRestore.isComplete)")
        assertThat(cleanup).contains("playbackRestore.awaitCompletion()")
        assertThat(cleanup.indexOf("awaitCompletion()")).isLessThan(cleanup.indexOf("stopSelfResult(startId)"))
    }

}
