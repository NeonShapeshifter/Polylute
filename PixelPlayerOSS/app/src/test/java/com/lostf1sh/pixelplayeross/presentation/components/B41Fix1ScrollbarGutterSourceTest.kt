package com.lostf1sh.pixelplayeross.presentation.components

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * B41-fix1 (B9): no list gives its content a wider end margin because it can scroll. Reads the source of every file that had the pattern
 * (`end = if (state.canScrollForward || state.canScrollBackward) 22..26.dp else 12..16.dp`, a dozen times); it compiles against the tree as it
 * was before the batch, where it fails on those files.
 */
class B41Fix1ScrollbarGutterSourceTest {
    companion object {
        val files = listOf(
            "presentation/screens/LibrarySongsAndFavoritesTabs.kt",
            "presentation/screens/LibraryMediaTabs.kt",
            "presentation/screens/LibrarySongsTab.kt",
            "presentation/screens/LibraryScreen.kt",
            "presentation/screens/AlbumDetailScreen.kt",
            "presentation/screens/ArtistDetailScreen.kt",
            "presentation/screens/PlaylistDetailScreen.kt",
            "presentation/components/QueueBottomSheet.kt",
            "presentation/components/SongPickerBottomSheet.kt",
            "presentation/components/FileExplorerBottomSheet.kt",
            "presentation/components/PlaylistContainer.kt",
        )

        fun source(path: String) = File("src/main/java/com/lostf1sh/pixelplayeross/$path").readText().replace("\r\n", "\n")
    }

    @Test
    fun `no list gives its content a wider end margin when it can scroll`() {
        val offenders = mutableListOf<String>()
        for (path in files) {
            val code = source(path)
            // the old pattern: an end margin chosen by whether the list can scroll
            val old = Regex("""end\s*=\s*if\s*\(\s*(?:\w+\.)?(?:canScrollForward|canScrollBackward|showScrollBar)[^)]*\)\s*[^,\n]*\belse\b""")
            val padded = Regex("""canScrollForward\s*\|\|\s*\w+\.canScrollBackward\)\s*\d+\.dp""")
            if (old.containsMatchIn(code) || padded.containsMatchIn(code)) offenders += path
        }
        assertFalse(offenders.isNotEmpty(), "these still choose their end margin by whether the list scrolls: $offenders")
    }
}
