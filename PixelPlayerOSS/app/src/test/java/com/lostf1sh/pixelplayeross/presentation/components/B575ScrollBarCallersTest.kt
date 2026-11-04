package com.lostf1sh.pixelplayeross.presentation.components

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B57.5 3: the bubble is one change in the shared component, so every list that passes `dragLabelProvider` gets it with no code of its own. This pins who passes one and that nobody gives the
 * bar a size of its own, so every bubble in the app is the same bubble.
 */
class B575ScrollBarCallersTest {
    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")

    /** Every `ExpressiveScrollBar(` call: the file, and the expression it passes as `dragLabelProvider` (null when it passes none). */
    private fun callers(): List<Pair<String, String?>> {
        val out = mutableListOf<Pair<String, String?>>()
        for (file in main.walkTopDown().filter { it.extension == "kt" }) {
            val text = file.readText()
            var from = 0
            while (true) {
                val at = text.indexOf("ExpressiveScrollBar(", from)
                if (at < 0) break
                from = at + 1
                if (text.substring(maxOf(0, at - 4), at) == "fun ") continue
                var depth = 0
                var end = at
                var started = false
                while (end < text.length) {
                    when (text[end]) {
                        '(' -> { depth++; started = true }
                        ')' -> depth--
                    }
                    if (started && depth == 0) break
                    end++
                }
                val block = text.substring(at, end)
                assertThat(block).doesNotContain("dragLabelSize")
                assertThat(block).doesNotContain("dragLabelGap")
                val provider = Regex("dragLabelProvider\\s*=\\s*([A-Za-z0-9_]+)").find(block)?.groupValues?.get(1)
                out += file.relativeTo(main).path to provider
            }
        }
        return out
    }

    @Test
    fun `fourteen lists use the one bar, and these six pass a label provider, each of which gets the bubble`() {
        val all = callers()
        assertThat(all).hasSize(14)
        val withProvider = all.filter { it.second != null }
        assertThat(withProvider).containsExactly(
            "presentation/components/PlaylistContainer.kt" to "labelProvider",
            "presentation/screens/LibraryMediaTabs.kt" to "albumFastScrollLabelProvider",
            "presentation/screens/LibraryMediaTabs.kt" to "albumFastScrollLabelProvider",
            "presentation/screens/LibraryMediaTabs.kt" to "artistFastScrollLabelProvider",
            "presentation/screens/LibrarySongsAndFavoritesTabs.kt" to "favoriteFastScrollLabelProvider",
            "presentation/screens/LibrarySongsTab.kt" to "songFastScrollLabelProvider",
        )
    }

    @Test
    fun `the other eight calls pass no provider, so they show no label before or after, and none was given one in this batch`() {
        val without = callers().filter { it.second == null }.map { it.first }
        assertThat(without).containsExactly(
            "presentation/components/FileExplorerBottomSheet.kt",
            "presentation/components/QueueBottomSheet.kt",
            "presentation/components/SongPickerBottomSheet.kt",
            "presentation/components/SongPickerBottomSheet.kt",
            "presentation/screens/AlbumDetailScreen.kt",
            "presentation/screens/ArtistDetailScreen.kt",
            "presentation/screens/LibraryScreen.kt",
            "presentation/screens/PlaylistDetailScreen.kt",
        )
    }

    @Test
    fun `the bubble exists in the bar and nowhere else`() {
        val users = main.walkTopDown().filter { it.extension == "kt" }.filter { it.readText().contains("ScrollBubble.") }.map { it.relativeTo(main).path }.toList()
        assertThat(users).containsExactly("presentation/components/ExpressiveScrollBar.kt")
        // the shared bar's defaults are the bubble's
        val bar = File(main, "presentation/components/ExpressiveScrollBar.kt").readText()
        assertThat(bar).contains("dragLabelSize: Dp = ScrollBubble.Size")
        assertThat(bar).contains("dragLabelGap: Dp = ScrollBubble.Gap")
        // B41/B57 touch rules are untouched: the initial pass, the jump strip, the grab slack, the shared pointer input
        assertThat(bar).contains("awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)")
        assertThat(bar).contains("ScrollbarGutter.JumpStripWidth")
        assertThat(bar).contains("ScrollbarGutter.GrabSlack")
        assertThat(bar).contains(".sharedPointerInput {")
        assertThat(bar).contains("awaitPointerEvent(PointerEventPass.Initial)")
    }
}
