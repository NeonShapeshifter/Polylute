package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.view.View
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * B58 1 (#69): an MP4 has no size cap (the hardware decoder reads it a few seconds at a time), a WebP or GIF keeps 64 MiB (they are decoded into memory), and a cover skipped for its
 * size says so once. The size is faked on the [AnimatedCover] (a 100 MB file is not written); the file itself is a real, tiny one so that `canRead()` is true.
 */
class B58AnimatedCoverSizeTest {
    @TempDir lateinit var dir: File

    private class FakeEngine : AnimatedCoverEngine {
        override val view: View? = null
        override val firstFrame = MutableStateFlow(false)
        override fun setRunning(running: Boolean) {}
        override fun release() {}
    }

    private class Rig {
        val lines = mutableListOf<String>()
        val video = mutableListOf<AnimatedCover>()
        val image = mutableListOf<AnimatedCover>()
        val factory = PlatformAnimatedCoverEngines(
            makeVideo = { cover, _ -> video += cover; FakeEngine() },
            makeImage = { cover, _, _ -> image += cover; FakeEngine() },
            log = { lines += it },
        )
    }

    private fun cover(name: String, mb: Long, modified: Long = 7L): AnimatedCover {
        val file = File(dir, name).apply { writeBytes(byteArrayOf(1)) }
        val kind = AnimatedCoverKind.entries.first { it.fileName == name }
        return AnimatedCover(file, kind, mb * 1024 * 1024, modified)
    }

    @Test
    fun `a 100 MB mp4 is made`() {
        val rig = Rig()
        val engine = rig.factory.create(cover("cover.mp4", 100), 720) {}
        assertThat(engine).isNotNull()
        assertThat(rig.video).hasSize(1)
        assertThat(rig.lines).isEmpty()
    }

    @Test
    fun `an mp4 of any size is made, a gigabyte included`() {
        val rig = Rig()
        assertThat(rig.factory.create(cover("cover.mp4", 1024), 720) {}).isNotNull()
        assertThat(AnimatedCoverLimits.maxBytes(AnimatedCoverKind.VIDEO)).isNull()
        assertThat(AnimatedCoverLimits.tooBig(cover("cover.mp4", 4096))).isNull()
    }

    @Test
    fun `a 100 MB gif is not made and the log says why`() {
        val rig = Rig()
        val big = cover("cover.gif", 100)
        assertThat(rig.factory.create(big, 720) {}).isNull()
        assertThat(rig.image).isEmpty()
        assertThat(rig.lines).hasSize(1)
        assertThat(rig.lines.single()).isEqualTo("skip kind=GIF file=${big.file.path} too big (100 MB, the cap is 64 MB)")
    }

    @Test
    fun `a 100 MB webp is not made either`() {
        val rig = Rig()
        assertThat(rig.factory.create(cover("cover.webp", 100), 720) {}).isNull()
        assertThat(rig.lines.single()).contains("kind=WEBP")
        assertThat(rig.lines.single()).contains("too big (100 MB, the cap is 64 MB)")
    }

    @Test
    fun `a skipped cover says so once however many times it is asked`() {
        val rig = Rig()
        val big = cover("cover.gif", 100)
        repeat(5) { rig.factory.create(big, 720) {} }
        assertThat(rig.lines).hasSize(1)
    }

    @Test
    fun `a file that changes is a new file and is reported again`() {
        val rig = Rig()
        rig.factory.create(cover("cover.gif", 100, modified = 1L), 720) {}
        rig.factory.create(cover("cover.gif", 100, modified = 2L), 720) {}
        assertThat(rig.lines).hasSize(2)
    }

    @Test
    fun `the cap is a limit on the size and a gif or webp at the cap plays`() {
        val rig = Rig()
        assertThat(rig.factory.create(cover("cover.gif", 64), 720) {}).isNotNull()
        assertThat(rig.factory.create(cover("cover.webp", 63), 720) {}).isNotNull()
        assertThat(rig.image).hasSize(2)
        assertThat(rig.lines).isEmpty()
    }

    @Test
    fun `sizes are written in megabytes with one decimal only when there is one`() {
        assertThat(AnimatedCoverLimits.megabytes(100L * 1024 * 1024)).isEqualTo("100")
        assertThat(AnimatedCoverLimits.megabytes((64.4 * 1024 * 1024).toLong())).isEqualTo("64.4")
        assertThat(AnimatedCoverLimits.megabytes(CoverImageEngine.MAX_BYTES)).isEqualTo("64")
    }

    @Test
    fun `a cover that cannot be read is still not made, whatever its size`() {
        val rig = Rig()
        val missing = AnimatedCover(File(dir, "gone/cover.mp4"), AnimatedCoverKind.VIDEO, 5L * 1024 * 1024, 1L)
        assertThat(rig.factory.create(missing, 720) {}).isNull()
        assertThat(rig.lines).isEmpty()
    }
}
