package com.lostf1sh.pixelplayeross.utils

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/** B57 4.2: artwork is requested at the real size of its cell in pixels, capped at 512, from one helper; no more fixed 256 / 128 on the cells that were soft. */
class B57ArtworkRequestSizeTest {
    @Test
    fun `a cell asks for dp times density, within the cap and the floor`() {
        assertThat(ArtworkRequestSize.sidePx(157f, 3.1f)).isEqualTo(486)   // a tester's Albums grid: ~490 px cells, asked 256 before
        assertThat(ArtworkRequestSize.sidePx(100f, 2f)).isEqualTo(200)
        assertThat(ArtworkRequestSize.sidePx(300f, 3f)).isEqualTo(ArtworkRequestSize.CAP_PX) // a big cell never asks for more than 512
        assertThat(ArtworkRequestSize.sidePx(8f, 2f)).isEqualTo(ArtworkRequestSize.MIN_PX)
        assertThat(ArtworkRequestSize.sidePx(Float.NaN, 2f)).isEqualTo(ArtworkRequestSize.MIN_PX)
    }

    @Test
    fun `a grid cell is what is left of the width after the padding and the gaps, shared`() {
        assertThat(ArtworkRequestSize.columnCellDp(360f, columns = 2, horizontalPadding = 32f, gap = 14f)).isEqualTo(157f)
        assertThat(ArtworkRequestSize.columnCellDp(360f, columns = 3, horizontalPadding = 40f, gap = 12f)).isWithin(0.01f).of(98.67f)
    }

    @Test
    fun `the cells that were soft no longer ask for a fixed size`() {
        val root = "src/main/java/com/lostf1sh/pixelplayeross/"
        fun text(path: String) = File(root + path).readText()
        val albumArtwork = text("presentation/screens/LibraryScreen.kt").substringAfter("private fun AlbumArtwork(").substringBefore("\n}\n")
        assertThat(albumArtwork).doesNotContain("Size(256, 256)")
        assertThat(albumArtwork).contains("targetSize = request")
        for (path in listOf("presentation/screens/HomeScreen.kt", "presentation/components/HomeDiscoverySections.kt", "presentation/screens/GenreOverview.kt", "presentation/screens/SuggestionsScreen.kt")) {
            val source = text(path)
            assertThat(source).doesNotContain("Size(256,256)")
            assertThat(source).doesNotContain("Size(256, 256)")
            assertThat(source).doesNotContain("coil.size.Size(128,128)")
            assertThat(source).contains("ArtworkRequestSize.")
        }
        // the prefetch asks for what the cell will ask for
        assertThat(text("presentation/screens/LibraryMediaTabs.kt")).doesNotContain(".size(Size(256, 256))")
    }
}
