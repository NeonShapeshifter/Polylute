package com.lostf1sh.pixelplayeross.ui.animatedcover

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.DiscCoverNames
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test

/**
 * B59 4 (#82): per-disc covers. Which file names are a disc's cover (the table in the result), what wins over what, and that `disc2.mp4` animates disc 2 only, the other discs falling back
 * to the album's `cover.mp4`. (The still covers installed by the sync are `B59DiscCoverSyncTest`.)
 */
class B59DiscCoverTest {
    private class FakeDirectory : AnimatedCoverDirectory {
        val folders = HashMap<String, List<String>>()
        override fun names(dir: File): List<String>? = folders[dir.path]
        override fun stamp(dir: File): Long = 1L
        override fun sizeOf(file: File): Long = 5_000L
        override fun modifiedOf(file: File): Long = 42L
    }

    private fun finder(dir: FakeDirectory) = AnimatedCoverFinder(Dispatchers.Unconfined, dir, AlbumArtUtils::shouldTrustDirectoryArtwork) { 0L }

    @Test fun `the names that are a disc's cover`() {
        val still = DiscCoverNames.STILL_EXTENSIONS
        // accepted: disc + number (leading zeros fine) with at most one separator, any case, jpg jpeg png
        for (name in listOf("disc1.jpg", "Disc1.JPG", "DISC2.png", "disc3.jpeg", "disc 1.jpg", "disc_1.jpg", "disc-1.png", "disc01.jpg", "Disc 02.JPEG")) {
            assertThat(DiscCoverNames.discOf(name, still)).isNotNull()
        }
        assertThat(DiscCoverNames.discOf("disc01.jpg", still)).isEqualTo(1)
        assertThat(DiscCoverNames.discOf("Disc 02.JPEG", still)).isEqualTo(2)
        // not accepted: cd N (often the CD label's own picture), no number, another extension, text around it, disc 0, a letter
        for (name in listOf("cd1.jpg", "cd 1.jpg", "CD2.png", "disc.jpg", "disc1.bmp", "disc1.jpg.txt", "disc0.jpg", "discA.jpg", "mydisc1.jpg", "disc  1.jpg", "disc1.mp4", "cover.jpg", "disc1")) {
            assertThat(DiscCoverNames.discOf(name, still)).isNull()
        }
        // the animated ones: the extensions of cover.*
        assertThat(DiscCoverNames.discOf("disc2.mp4", DiscCoverNames.ANIMATED_EXTENSIONS)).isEqualTo(2)
        assertThat(DiscCoverNames.discOf("disc2.webp", DiscCoverNames.ANIMATED_EXTENSIONS)).isEqualTo(2)
        assertThat(DiscCoverNames.discOf("disc2.gif", DiscCoverNames.ANIMATED_EXTENSIONS)).isEqualTo(2)
        assertThat(DiscCoverNames.discOf("disc2.jpg", DiscCoverNames.ANIMATED_EXTENSIONS)).isNull()
    }

    @Test fun `inside one disc the exact name wins over a spelled out one, then jpg over jpeg over png, and mp4 over webp over gif`() {
        assertThat(DiscCoverNames.pickStill(listOf("disc 2.jpg", "disc2.png", "disc2.jpeg"), 2)).isEqualTo("disc 2.jpg") // jpg < jpeg < png by extension first, spelling second
        assertThat(DiscCoverNames.pickStill(listOf("disc 2.jpeg", "disc2.png", "disc2.jpeg"), 2)).isEqualTo("disc2.jpeg")
        assertThat(DiscCoverNames.pickStill(listOf("disc 2.jpg", "disc2.jpg"), 2)).isEqualTo("disc2.jpg")
        assertThat(DiscCoverNames.pickStill(listOf("disc1.jpg"), 2)).isNull()
        assertThat(DiscCoverNames.pickStill(listOf("disc2.jpg"), 0)).isNull()
        assertThat(DiscCoverNames.pickAnimated(listOf("disc2.gif", "disc2.webp", "disc2.mp4"), 2)).isEqualTo("disc2.mp4")
        assertThat(DiscCoverNames.pickAnimated(listOf("disc2.gif", "disc2.webp"), 2)).isEqualTo("disc2.webp")
    }

    @Test fun `a song with no disc tag is on the first disc`() {
        assertThat(DiscCoverNames.effectiveDisc(null)).isEqualTo(1)
        assertThat(DiscCoverNames.effectiveDisc(0)).isEqualTo(1)
        assertThat(DiscCoverNames.effectiveDisc(2)).isEqualTo(2)
    }

    @Test fun `disc2 dot mp4 animates disc 2 only, and disc 1 falls back to the album's cover dot mp4`() {
        val dir = FakeDirectory().apply { folders["/music/Alvaro/[2024] SAYONARA"] = listOf("1-01.flac", "2-01.flac", "cover.mp4", "disc2.mp4", "disc1.jpg", "disc2.jpg") }
        val f = finder(dir)
        val one = f.findNow("/music/Alvaro/[2024] SAYONARA/1-01.flac", 0L, discNumber = 1)
        val two = f.findNow("/music/Alvaro/[2024] SAYONARA/2-01.flac", 0L, discNumber = 2)
        assertThat(one!!.file.name).isEqualTo("cover.mp4")
        assertThat(two!!.file.name).isEqualTo("disc2.mp4")
        // a song that asks for no disc (0) is the album's
        assertThat(f.findNow("/music/Alvaro/[2024] SAYONARA/1-01.flac", 0L)!!.file.name).isEqualTo("cover.mp4")
    }

    @Test fun `a disc's own file wins over the album's cover even when the album's is the better kind, and the same rules of kind apply`() {
        val dir = FakeDirectory().apply { folders["/music/A/[2020] B"] = listOf("cover.mp4", "disc2.gif", "disc2.webp", "disc3.mp4") }
        val f = finder(dir)
        assertThat(f.findNow("/music/A/[2020] B/x.flac", 0L, discNumber = 2)!!.file.name).isEqualTo("disc2.webp")
        assertThat(f.findNow("/music/A/[2020] B/x.flac", 0L, discNumber = 2)!!.kind).isEqualTo(AnimatedCoverKind.WEBP)
        assertThat(f.findNow("/music/A/[2020] B/x.flac", 0L, discNumber = 3)!!.file.name).isEqualTo("disc3.mp4")
        assertThat(f.findNow("/music/A/[2020] B/x.flac", 0L, discNumber = 1)!!.file.name).isEqualTo("cover.mp4")
    }

    @Test fun `an album with no disc files behaves as before and a folder that mixes albums is never used`() {
        val dir = FakeDirectory().apply {
            folders["/music/A/[2020] B"] = listOf("cover.webp")
            folders["/music/Downloads"] = listOf("disc1.mp4", "cover.mp4")
        }
        val f = finder(dir)
        assertThat(f.findNow("/music/A/[2020] B/x.flac", 0L, discNumber = 2)!!.file.name).isEqualTo("cover.webp")
        assertThat(f.findNow("/music/Downloads/x.flac", 0L, discNumber = 1)).isNull()
    }

    @Test fun `the answer is kept per folder and per disc, so skipping between discs lists each folder once per disc`() {
        val dir = FakeDirectory().apply { folders["/music/A/[2020] B"] = listOf("cover.mp4", "disc2.mp4") }
        var listings = 0
        val counting = object : AnimatedCoverDirectory by dir {
            override fun names(dir2: File): List<String>? { listings++; return dir.names(dir2) }
        }
        val f = AnimatedCoverFinder(Dispatchers.Unconfined, counting, AlbumArtUtils::shouldTrustDirectoryArtwork) { 0L }
        repeat(5) { f.findNow("/music/A/[2020] B/x.flac", 0L, discNumber = 1) }
        repeat(5) { f.findNow("/music/A/[2020] B/y.flac", 0L, discNumber = 2) }
        assertThat(listings).isEqualTo(2)
    }
}
