package com.lostf1sh.pixelplayeross.data.image

import coil.size.Dimension
import coil.size.Size
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import org.junit.jupiter.api.Test

/**
 * B13-2. A list row asks for ~100 px. Extracting the whole cover from the audio file, scaling it to 1536 px and
 * re-encoding it (what a request for a large cover still does) was most of the CPU of a fling over songs not shown
 * before: the profile on the HONOR had the artwork pool at ~55 % of all CPU while the main thread had 16 %.
 * Requests up to [AlbumArtUtils.THUMBNAIL_EDGE_PX] on both edges are now served from a thumbnail.
 */
class LocalArtworkThumbnailRequestTest {

    @Test
    fun `a row thumbnail is a thumbnail request`() {
        assertThat(LocalArtworkCoilFetcher.isThumbnailRequest(Size(100, 100))).isTrue()
        assertThat(LocalArtworkCoilFetcher.isThumbnailRequest(Size(128, 128))).isTrue()
    }

    @Test
    fun `the largest thumbnail is a thumbnail request and one pixel more is not`() {
        val edge = AlbumArtUtils.THUMBNAIL_EDGE_PX
        assertThat(LocalArtworkCoilFetcher.isThumbnailRequest(Size(edge, edge))).isTrue()
        assertThat(LocalArtworkCoilFetcher.isThumbnailRequest(Size(edge + 1, edge))).isFalse()
        assertThat(LocalArtworkCoilFetcher.isThumbnailRequest(Size(edge, edge + 1))).isFalse()
    }

    @Test
    fun `a full-player cover and the original size keep the full-size path`() {
        assertThat(LocalArtworkCoilFetcher.isThumbnailRequest(Size(1024, 1024))).isFalse()
        assertThat(LocalArtworkCoilFetcher.isThumbnailRequest(Size.ORIGINAL)).isFalse()
    }

    @Test
    fun `a request with an unknown edge is not a thumbnail request`() {
        assertThat(LocalArtworkCoilFetcher.isThumbnailRequest(Size(Dimension.Undefined, Dimension(100)))).isFalse()
        assertThat(LocalArtworkCoilFetcher.isThumbnailRequest(Size(Dimension(100), Dimension.Undefined))).isFalse()
    }
}
