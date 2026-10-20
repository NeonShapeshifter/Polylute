package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/** AUD-121 wiring: checkpoint while playing, and on task removal, destroy and memory trim. */
class PositionCheckpointWiringBoundaryTest {
    private val service = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/MusicService.kt")
        .readText().replace("\r\n", "\n")

    private fun fn(signature: String): String {
        val start = service.indexOf(signature)
        assertThat(start).isAtLeast(0)
        return service.substring(start, service.indexOf("\n    }\n", start))
    }

    @Test
    fun `playing starts the checkpoint loop and process-death hooks write the position`() {
        assertThat(service).contains("updatePositionCheckpointing(isPlaying)")
        assertThat(fn("override fun onTaskRemoved(")).contains("checkpointPositionNow()")
        assertThat(fn("override fun onDestroy(")).contains("checkpointPositionNow()")
        assertThat(fn("override fun onTrimMemory(")).contains("checkpointPositionNow()")
        // Captured before the players are released at the end of onDestroy.
        val destroy = fn("override fun onDestroy(")
        assertThat(destroy.indexOf("checkpointPositionNow()")).isLessThan(destroy.indexOf("engine.release()"))
    }
}
