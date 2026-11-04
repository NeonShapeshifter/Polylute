package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/** B56: wiring that the behaviour tests of each phase cannot reach (who asks for what). */
class B56WiringBoundaryTest {
    private fun source(path: String): String = File("src/main/java/com/lostf1sh/pixelplayeross/$path").readText()

    private fun bodyOf(text: String, signature: String): String {
        val start = text.indexOf(signature)
        check(start >= 0) { "$signature is gone" }
        return text.substring(start, text.indexOf("\n    }\n", start))
    }

    @Test
    fun `phase 1_2 the library menu refresh and the pull to refresh carry the asked-for mark and the automatic paths do not`() {
        val manager = source("data/worker/SyncManager.kt")

        assertThat(bodyOf(manager, "fun incrementalSync(")).contains("requestedByListener = true")
        assertThat(bodyOf(manager, "fun forceRefresh(")).contains("requestedByListener = true")
        assertThat(bodyOf(manager, "fun sync(")).doesNotContain("requestedByListener")
        assertThat(bodyOf(manager, "private suspend fun runLocalAutoSyncAfterDebounce(")).doesNotContain("requestedByListener")
        assertThat(manager.substringAfter("private val foregroundCatchUp").substringBefore("/**")).doesNotContain("requestedByListener")
    }

    @Test
    fun `phase 3_4 the lines under the cover and the Lyrics page both come from the one lyrics mode`() {
        val content = source("presentation/components/player/FullPlayerContent.kt")
        assertThat(content).contains("underCover = fullPlayerSlice.lyricsMode.showsLinesOnPlayer")
        assertThat(content).contains("mode = fullPlayerSlice.lyricsMode")
        val screen = source("presentation/components/player/FullPlayerScreen.kt")
        assertThat(screen).contains("key(lyricsMode.hasLyricsPage) { rememberPlayerPagesState(lyricsPage = lyricsMode.hasLyricsPage) }")
        assertThat(source("presentation/components/player/PlayerLyricsLines.kt")).contains("if (!lyrics.underCover) return")
        // the old on/off switch is gone from the code (its key stays in the preferences, read once)
        assertThat(source("presentation/viewmodel/PlayerViewModel.kt")).doesNotContain("lyricsUnderCover")
        assertThat(source("data/preferences/UserPreferencesRepository.kt")).doesNotContain("lyricsUnderCoverFlow")
    }

    @Test
    fun `phase 2 there is one overflow menu and no Material DropdownMenu is used outside ui_ds`() {
        val root = File("src/main/java/com/lostf1sh/pixelplayeross")
        val call = Regex("""\bDropdownMenu\w*\s*\(""")
        val import = Regex("""import\s+androidx\.compose\.material3\.(DropdownMenu|ExposedDropdownMenu)\w*""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.invariantSeparatorsPath.contains("/ui/ds/") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val code = line.trim()
                    if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
                    else if (call.containsMatchIn(code) || import.containsMatchIn(code)) "${file.name}:${index + 1}: $code" else null
                }
            }
            .toList()

        assertThat(offenders).isEmpty()
    }
}
