package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-113 (device: 25-44 MB collected about every second while playing, worst with the lyrics open).
 * Each visible synced line took the position as a plain parameter, and keyed its `derivedStateOf`
 * on it: every progress tick rebuilt every line's derived states (and recomposed them). The line now
 * reads the position through a provider inside `derivedStateOf`, so its state survives the tick and
 * only the line whose highlight actually changes is invalidated.
 */
class LyricsPerTickAllocationBoundaryTest {
    private val sheet = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/LyricsSheet.kt")
        .readText().replace("\r\n", "\n")

    @Test
    fun `a lyric line derives its highlight from a position provider, not a per-tick key`() {
        val row = sheet.substringAfter("fun LyricLineRow(").substringBefore("\n}\n")
        assertThat(row).contains("positionProvider: () -> Long,")
        assertThat(row).doesNotContain("remember(position,")
        assertThat(row).contains("derivedStateOf { positionProvider() in line.time.toLong()..<lineEndTime }")
        assertThat(sheet).contains("positionProvider = positionProvider,")
    }
}
