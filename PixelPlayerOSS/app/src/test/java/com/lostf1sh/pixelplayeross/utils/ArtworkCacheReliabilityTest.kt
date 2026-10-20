package com.lostf1sh.pixelplayeross.utils

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.media.ArtworkCacheKeys
import java.io.File
import org.junit.jupiter.api.Test

/**
 * The two ways the artwork cache used to lose a cover it already had, and the key set that decides
 * whether an edited cover is actually replaced everywhere.
 */
class ArtworkCacheReliabilityTest {

    // ---- Invalidation reaches every size the app asks for --------------------------------------

    @Test
    fun `invalidation covers the sizes that were missing`() {
        val keys = ArtworkCacheKeys.keysFor("pixelplayer_local_art://song/7")

        // 2048 is the default OptimizedAlbumArt target and was absent from the hand-written list, so
        // the largest and most expensive entry survived every invalidation. 96 is the compact-list
        // target and was absent too.
        assertThat(keys).contains("pixelplayer_local_art://song/7_2048x2048")
        assertThat(keys).contains("pixelplayer_local_art://song/7_96x96")
    }

    @Test
    fun `invalidation covers the bare key and every size variant`() {
        val base = "pixelplayer_local_art://song/7"
        val keys = ArtworkCacheKeys.keysFor(base)

        assertThat(keys.first()).isEqualTo(base)
        assertThat(keys).hasSize(ArtworkCacheKeys.SIZE_TOKENS.size + 1)
        assertThat(keys.toSet()).hasSize(keys.size)
    }

    @Test
    fun `every size the product requests is in the list`() {
        // The list used to be maintained by hand at the invalidation site, which is how it fell
        // behind. This reads the call sites, so a new size cannot be added without appearing here.
        val requested = Regex("""targetSize\s*=\s*Size\(\s*(\d+)\s*,\s*(\d+)\s*\)""")
            .findAll(allSources())
            .map { "${it.groupValues[1]}x${it.groupValues[2]}" }
            .toSet()

        assertThat(requested).isNotEmpty()
        val missing = requested - ArtworkCacheKeys.SIZE_TOKENS.toSet()
        assertThat(missing).isEmpty()
    }

    // ---- A negative result must not be permanent -----------------------------------------------

    @Test
    fun `a fresh negative marker is believed and an old one is not`() {
        val now = 1_000_000_000_000L

        assertThat(
            AlbumArtUtils.isNegativeArtMarkerExpired(now - 60_000L, now)
        ).isFalse()

        assertThat(
            AlbumArtUtils.isNegativeArtMarkerExpired(
                now - AlbumArtUtils.NEGATIVE_ART_MARKER_TTL_MS - 1L,
                now,
            )
        ).isTrue()
    }

    @Test
    fun `a marker with no usable timestamp is treated as expired`() {
        // The cost of trying again is one failed read; the cost of believing an unreadable timestamp
        // is a track that never shows its cover again.
        assertThat(AlbumArtUtils.isNegativeArtMarkerExpired(0L, 1_000_000_000_000L)).isTrue()
        assertThat(AlbumArtUtils.isNegativeArtMarkerExpired(-1L, 1_000_000_000_000L)).isTrue()
    }

    @Test
    fun `a valid cached cover outranks a stale marker`() {
        // The read path used to check the marker first and delete the cover it found. Both orders
        // are asserted here on the source, because the branch is what the bug was.
        // Whitespace-insensitive, so the assertion is about the order of the two branches rather
        // than about how they happen to be indented.
        val source = source("utils/AlbumArtUtils.kt").replace(Regex("\\s+"), " ")
        val positiveFirst = source.indexOf("if (cachedFile.exists() && cachedFile.length() > 0) { return true")
        val markerSecond = source.indexOf("if (noArtFile.exists()) { return false")

        assertThat(positiveFirst).isGreaterThan(-1)
        assertThat(markerSecond).isGreaterThan(-1)
        assertThat(positiveFirst).isLessThan(markerSecond)
        // The branch that deleted a valid cached cover when it found a marker alongside it.
        assertThat(source).doesNotContain("if (cachedFile.exists()) { cachedFile.delete() }")
    }

    @Test
    fun `a failing request does not delete a cover that exists`() {
        assertThat(source("utils/AlbumArtUtils.kt"))
            .contains("if (!cachedFile.exists() || cachedFile.length() <= 0) {")
    }

    @Test
    fun `extraction for one song is serialised and the write lands atomically`() {
        val source = source("utils/AlbumArtUtils.kt")

        assertThat(source).contains("synchronized(artLockFor(songId))")
        // Written beside the target and renamed onto it, so a concurrent reader never decodes a
        // half-written JPEG.
        assertThat(source).contains("staging.renameTo(file)")
        assertThat(source).doesNotContain("file.outputStream().use { outputStream ->")
    }

    private fun source(relative: String) =
        root.resolve("src/main/java/com/lostf1sh/pixelplayeross/$relative").readText()

    private fun allSources(): String =
        root.resolve("src/main/java").walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }

    private companion object {
        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
    }
}
