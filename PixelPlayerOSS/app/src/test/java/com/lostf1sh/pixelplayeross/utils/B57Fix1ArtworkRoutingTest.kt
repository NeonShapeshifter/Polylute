package com.lostf1sh.pixelplayeross.utils

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B57-fix1 3: the artwork the owner named (the playlist collage, the collection covers, the stats rows, the genre tiles and rows, the Daily Mix rows, the artist's album rows, the queue rows) asks the
 * loader for the size of the cell it is drawn in, from [ArtworkRequestSize]; none of them keeps a number of its own. What stays fixed is listed in the batch result, with the reason for each.
 */
class B57Fix1ArtworkRoutingTest {
    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")

    private fun text(path: String) = File(main, path).readText()

    @Test fun the_named_sites_no_longer_carry_a_fixed_size() {
        for (path in listOf(
            "presentation/components/PlaylistArtCollage.kt",
            "presentation/collections/AutoCollectionRow.kt",
            "presentation/stats/StatsComponents.kt",
            "presentation/screens/search/components/GenreCategoriesGrid.kt",
            "presentation/components/DailyMixSection.kt",
        )) {
            val source = text(path)
            assertWithMessage("$path has no fixed Size(n, n)").that(Regex("""Size\(\s*\d+\s*,\s*\d+\s*\)""").containsMatchIn(source)).isFalse()
        }
    }

    @Test fun every_named_site_asks_the_one_helper() {
        assertThat(text("presentation/components/PlaylistArtCollage.kt")).contains("ArtworkRequestSize.forCell(maxWidth)")
        assertThat(text("presentation/collections/AutoCollectionRow.kt")).contains("ArtworkRequestSize.forCell(size)")
        assertThat(text("presentation/collections/AutoCollectionRow.kt")).contains("ArtworkRequestSize.forCell(size / 2)")
        assertThat(text("presentation/stats/StatsComponents.kt")).contains("ArtworkRequestSize.forCell(DapSizes.ThumbRow)")
        assertThat(text("presentation/screens/search/components/GenreCategoriesGrid.kt")).contains("ArtworkRequestSize.forCell(DapSizes.ThumbRow)")
        assertThat(text("presentation/screens/search/components/GenreCategoriesGrid.kt")).contains("ArtworkRequestSize.forColumns(2, DapSpacing.Gutter * 2 + 32.dp, 12.dp)")
        assertThat(text("presentation/components/DailyMixSection.kt")).contains("ArtworkRequestSize.forCell(DapSizes.ThumbRow)")
    }

    @Test fun the_genre_tile_cover_is_what_the_tile_measures() {
        // 360 dp wide window, 20 dp gutters, 12 dp between the two tiles, 8 dp of padding around each cover: (360 - 40 - 12 - 32) / 2 = 138 dp; at 3x that is 414 px, under the cap
        val cell = ArtworkRequestSize.columnCellDp(360f, 2, 20f * 2 + 32f, 12f)
        assertThat(cell).isEqualTo(138f)
        assertThat(ArtworkRequestSize.sidePx(cell, 3f)).isEqualTo(414)
        // the 3-cover collage: each cover takes 2 / (2 + sqrt 3) of the collage's width
        val third = 2f / (2f + kotlin.math.sqrt(3f))
        assertThat(ArtworkRequestSize.sidePx(100f * third, 3f)).isEqualTo((100f * third * 3f).toInt())
    }
}
