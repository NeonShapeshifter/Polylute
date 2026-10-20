package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B22, fix 1: the output line ("● SNOWSKY Melody · USB Direct · 16 bits · 44,1 kHz") goes **where it was** and nowhere
 * else: the DAP lock screen's footer (with the battery), Details, and the Settings root under Volume. The design drew it
 * at the foot of the player and under the "Audio" title as well; it does not fit at 360 dp, and it is not there.
 */
class NoOutputLineOnThePlayerTest {
    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")

    private fun callsTheOutputLine(file: File): Boolean {
        val text = file.readText()
        return text.contains("dapOutputSignalLine(") || text.contains("DapOutputLine(") || text.contains("DapSignalReadout(")
    }

    @Test
    fun `the player, the lyrics, the queue and the Audio page do not draw the output line`() {
        val surfaces = listOf(
            "presentation/components/player", "presentation/components/LyricsSheet.kt", "presentation/components/QueueBottomSheet.kt",
            "presentation/settings/AudioSettings.kt", "presentation/screens/EqualizerScreen.kt",
        )
        val offenders = surfaces.flatMap { rel ->
            val f = File(main, rel)
            (if (f.isDirectory) f.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() else listOf(f))
                .filter { callsTheOutputLine(it) && !it.name.startsWith("TrackDetails") }
                .map { it.name }
        }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `it is on the lock screen's footer, Details and the Settings root`() {
        assertThat(File(main, "presentation/lockscreen/DapLockScreen.kt").readText()).contains("DapOutputLine(")
        assertThat(File(main, "presentation/screens/DapDeviceControlComponents.kt").readText()).contains("dapOutputSignalLine(state)")
        assertThat(File(main, "presentation/components/player/TrackDetailsSheet.kt").readText()).contains("Details")
    }
}
