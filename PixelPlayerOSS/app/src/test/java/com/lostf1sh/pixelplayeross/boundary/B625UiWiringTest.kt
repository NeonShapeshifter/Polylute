package com.lostf1sh.pixelplayeross.boundary
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test
class B625UiWiringTest {
    private val root = File("src/main/java/com/lostf1sh/pixelplayeross")
    @Test fun `sort reuses Settings choice and switch rows inside Cover glass`() {
        val s = File(root, "presentation/components/LibrarySortBottomSheet.kt").readText()
        assertThat(s).contains("DapNavRow(")
        assertThat(s).contains("DapSwitchRow(")
        assertThat(s).contains("GlassModalBottomSheet(")
        assertThat(s).doesNotContain("RadioButton(")
    }
    @Test fun `album and artist route their artwork taps to the same full screen viewer`() {
        for (page in listOf("Album", "Artist")) {
            val s = File(root, "presentation/screens/${page}DetailScreen.kt").readText()
            assertThat(s).contains("ArtworkViewer(")
            assertThat(s).contains("onArtworkClick = { showArtworkViewer = true }")
        }
        assertThat(File(root, "presentation/screens/AlbumDetailScreen.kt").readText()).contains("albumSurface = true")
    }
}
