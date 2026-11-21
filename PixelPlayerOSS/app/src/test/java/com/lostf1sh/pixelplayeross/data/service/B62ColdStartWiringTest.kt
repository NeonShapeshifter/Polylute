package com.lostf1sh.pixelplayeross.data.service

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/** The Android service is checked at its boundary; the barrier has its own virtual-clock tests. */
class B62ColdStartWiringTest {
    private val base = File("src/main/java/com/lostf1sh/pixelplayeross")
    private val service = base.resolve("data/service/MusicService.kt").readText()

    @Test fun `widget dispatch waits before selecting and commanding the player`() {
        val dispatch = service.substringAfter("private suspend fun dispatchWidgetCommand(")
        assertThat(dispatch).contains("playbackRestore.await()")
        assertThat(dispatch.indexOf("playbackRestore.await()")).isLessThan(dispatch.indexOf("val player ="))
        assertThat(service).contains("widgetCommandMutex.withLock")
    }

    @Test fun `system resumption uses the same restore and a library session for recent media`() {
        assertThat(service).contains("override fun onPlaybackResumption(")
        assertThat(service).contains("MediaLibraryService()")
        assertThat(service).contains("override fun onGetLibraryRoot(")
        val callback = service.substringAfter("override fun onPlaybackResumption(").substringBefore("override fun onGetLibraryRoot(")
        assertThat(callback).contains("playbackRestore.await()")
        assertThat(callback).contains("resumptionFromPlayer(")
        val receiver = base.resolve("data/service/PixelPlayerMediaButtonReceiver.kt").readText()
        assertThat(receiver).contains("androidx.media3.session.MediaButtonReceiver")
    }

    @Test fun `each widget can render the saved snapshot without starting the service`() {
        listOf("PixelPlayerGlanceWidget", "BarWidget4x1", "ControlWidget4x2", "GridWidget2x2").forEach {
            val source = base.resolve("ui/glancewidget/$it.kt").readText()
            assertThat(source).contains("loadWidgetSnapshot(context)")
            assertThat(source).contains("widgetInfoForDisplay(")
        }
    }
}
