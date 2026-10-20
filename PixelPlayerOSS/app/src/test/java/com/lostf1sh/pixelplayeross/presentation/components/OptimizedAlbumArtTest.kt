package com.lostf1sh.pixelplayeross.presentation.components

import coil.size.Dimension
import coil.size.Size
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OptimizedAlbumArtTest {

    @Test
    fun safeAlbumArtTargetSize_clampsOriginalRequests() {
        val targetSize = safeAlbumArtTargetSize(Size.ORIGINAL)

        assertThat((targetSize.width as Dimension.Pixels).px)
            .isEqualTo(MaxSafeAlbumArtDimensionPx)
        assertThat((targetSize.height as Dimension.Pixels).px)
            .isEqualTo(MaxSafeAlbumArtDimensionPx)
    }

    @Test
    fun safeAlbumArtTargetSize_keepsBoundedRequests() {
        val targetSize = Size(800, 600)

        assertThat(safeAlbumArtTargetSize(targetSize)).isEqualTo(targetSize)
    }

    // ---- AUD-113: covers are decoded at the size of the screen, never at 2048² ---------------------

    private fun side(size: Size) = (size.width as Dimension.Pixels).px

    @Test
    fun screenBoundedAlbumArtSize_neverDecodesBeyondTheScreen() {
        // The phone is 720 px wide: an "original" request is a 720² bitmap (2 MB), not 2048² (16 MB).
        assertThat(screenBoundedAlbumArtSize(Size.ORIGINAL, screenPx = 720)).isEqualTo(Size(720, 720))
        assertThat(screenBoundedAlbumArtSize(Size(2048, 2048), screenPx = 720)).isEqualTo(Size(720, 720))
        assertThat(screenBoundedAlbumArtSize(Size(800, 800), screenPx = 720)).isEqualTo(Size(720, 720))
        // Smaller requests are what the surface asked for.
        assertThat(screenBoundedAlbumArtSize(Size(256, 256), screenPx = 720)).isEqualTo(Size(256, 256))
        // A big screen may go up to the safe cap and no further.
        assertThat(side(screenBoundedAlbumArtSize(Size.ORIGINAL, screenPx = 3000))).isEqualTo(MaxSafeAlbumArtDimensionPx)
        // A nonsense screen never asks for a zero-pixel image.
        assertThat(side(screenBoundedAlbumArtSize(Size.ORIGINAL, screenPx = 0))).isAtLeast(1)
    }

    @Test
    fun theDefaultAndTheNeighbourPrefetchAreScreenBounded() {
        val root = java.io.File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components")
        val art = java.io.File(root, "OptimizedAlbumArt.kt").readText().replace("\r\n", "\n")
        // The parameter no longer defaults to 2048², and the composable resolves it against the screen.
        assertThat(art).doesNotContain("targetSize: Size = SafeOriginalAlbumArtSize")
        assertThat(art).contains("targetSize: Size? = null,")
        assertThat(art).contains("screenBoundedAlbumArtSize(targetSize ?: Size.ORIGINAL, screenPx)")
        val prefetch = java.io.File(root, "scoped/PrefetchAlbumNeighbors.kt").readText().replace("\r\n", "\n")
        assertThat(prefetch).doesNotContain("safeAlbumArtTargetSize(Size.ORIGINAL)")
        assertThat(prefetch).contains("screenBoundedAlbumArtSize(Size.ORIGINAL,")
    }

    @Test
    fun anEditedCoverIsInvalidatedAtTheScreenSizeToo() {
        val keys = com.lostf1sh.pixelplayeross.data.media.ArtworkCacheKeys
            .keysFor("pixelplayer_local_art://song/7", screenSidesPx = listOf(720, 1612))

        // A device-dependent size cannot be in the fixed list, so the invalidation site adds it.
        assertThat(keys).contains("pixelplayer_local_art://song/7_720x720")
        assertThat(keys).contains("pixelplayer_local_art://song/7_1612x1612")
        assertThat(keys.toSet()).hasSize(keys.size)
        // No sides given: the fixed list alone, as before.
        assertThat(com.lostf1sh.pixelplayeross.data.media.ArtworkCacheKeys.keysFor("x"))
            .hasSize(com.lostf1sh.pixelplayeross.data.media.ArtworkCacheKeys.SIZE_TOKENS.size + 1)
    }
}
