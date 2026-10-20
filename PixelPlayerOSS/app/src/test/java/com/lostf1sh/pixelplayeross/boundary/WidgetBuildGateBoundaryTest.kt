package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-113: the service built the whole widget state — a Coil decode of the cover, a JPEG re-encode at
 * quality 92 and a sanitising copy, hundreds of KB to several MB — before asking whether any widget
 * exists to receive it. The log showed `No widgets found to update` 52 times in a session: 52 covers
 * decoded, encoded and thrown away, each a few tens of milliseconds after a track change.
 */
class WidgetBuildGateBoundaryTest {
    private val service = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/MusicService.kt")
        .readText().replace("\r\n", "\n")

    @Test
    fun `no widget, no build - the check comes before the state is built`() {
        val process = service.substringAfter("private suspend fun processWidgetUpdateInternal()")
            .substringBefore("private suspend fun buildPlayerInfo()")
        val gate = process.indexOf("hasInstalledWidgets()")
        val build = process.indexOf("buildPlayerInfo()")
        assertThat(gate).isAtLeast(0)
        assertThat(build).isAtLeast(0)
        assertThat(gate).isLessThan(build)
    }

    @Test
    fun `the check covers every widget the service updates, and fails open`() {
        val check = service.substringAfter("private suspend fun hasInstalledWidgets()")
            .substringBefore("private suspend fun buildPlayerInfo()")
        listOf("PixelPlayerGlanceWidget", "BarWidget4x1", "ControlWidget4x2", "GridWidget2x2").forEach {
            assertThat(check).contains("$it::class.java")
        }
        // A failure to ask must not silence a widget that exists.
        assertThat(check).contains("catch")
        assertThat(check).contains("true")
    }

    @Test
    fun `a widget added later gets its first state on the next update`() {
        // With no widget the last built state is dropped, so the update after one appears is not
        // mistaken for "nothing changed".
        val process = service.substringAfter("private suspend fun processWidgetUpdateInternal()")
            .substringBefore("private suspend fun buildPlayerInfo()")
        assertThat(process).contains("lastWidgetPlayerInfo = null")
    }
}
