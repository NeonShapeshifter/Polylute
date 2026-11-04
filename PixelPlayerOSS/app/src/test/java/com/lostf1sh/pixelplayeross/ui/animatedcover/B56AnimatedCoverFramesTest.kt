package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B56 phase 7.2 (#48): an album with only an animated cover still gets a still one: the first frame, once, cached. Synthetic fixtures made with ffmpeg and Pillow (a few KB of
 * generated pattern, no copyrighted media).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class B56AnimatedCoverFramesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var root: File

    private val decoded = mutableListOf<String>()
    private val original = AnimatedCoverFrames.decoder

    /**
     * The JVM has no decoder for GIF, WebP or MP4 (Robolectric's `ImageDecoder` answers "Only supported on Android"), so the decoder is a stand-in that returns a 96 x 96 bitmap and
     * records what it was asked for. What it checks is everything around the decoder: the name, the cache, the JPEG, the markers, the order of files. That the platform decodes the
     * fixtures' first frame is on the device checklist.
     */
    @Before fun setUp() {
        root = kotlin.io.path.createTempDirectory("b56-frames").toFile()
        AnimatedCoverFrames.decoder = AnimatedCoverFrames.FirstFrameDecoder { file, kind ->
            decoded += "${file.name}:$kind"
            if (file.length() < 1_000L && file.readBytes().all { it == 0.toByte() }) null
            else if (file.readBytes().let { bytes -> bytes.size > 100 && bytes.take(6).map { it.toInt().toChar() }.joinToString("").let { it == "GIF89a" || it.startsWith("RIFF") || file.extension == "mp4" } }) {
                android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3A5F4A.toInt()) }
            } else null
        }
    }

    @After fun tearDown() {
        AnimatedCoverFrames.decoder = original
        root.deleteRecursively(); File(context.cacheDir, "animated-cover-frames").deleteRecursively()
    }

    private fun album(name: String = "Gaia", vararg files: Pair<String, String>): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "01.flac").writeBytes(ByteArray(10))
        for ((target, fixture) in files) File("src/test/resources/animatedcover/$fixture").copyTo(File(dir, target), overwrite = true)
        return dir
    }

    private fun isJpeg(file: File) = file.readBytes().let { it.size > 4 && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }

    @Test
    fun `an album with only a gif gets its first frame as a JPEG, no larger than a cover`() {
        val dir = album(files = arrayOf("cover.gif" to "cover.gif"))
        val frame = AnimatedCoverFrames.frameFor(context, File(dir, "01.flac").path)!!
        assertThat(frame.name).isEqualTo("cover.jpg")
        assertThat(isJpeg(frame)).isTrue()
        assertThat(frame.path).startsWith(File(context.cacheDir, "animated-cover-frames").path)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(frame.path, bounds)
        assertThat(bounds.outWidth).isEqualTo(96)
        assertThat(bounds.outHeight).isEqualTo(96)
    }

    @Test
    fun `an animated webp gives its first frame too`() {
        val dir = album(files = arrayOf("cover.webp" to "cover.webp"))
        val frame = AnimatedCoverFrames.frameFor(context, File(dir, "01.flac").path)
        assertThat(frame).isNotNull()
        assertThat(isJpeg(frame!!)).isTrue()
        assertThat(decoded).containsExactly("cover.webp:WEBP")
    }

    @Test
    fun `the frame is made once and kept, and a replaced file makes a new one`() {
        val dir = album(files = arrayOf("cover.gif" to "cover.gif"))
        val song = File(dir, "01.flac").path
        val first = AnimatedCoverFrames.frameFor(context, song)!!
        first.setLastModified(1_000_000L)
        val again = AnimatedCoverFrames.frameFor(context, song)!!
        assertThat(again.path).isEqualTo(first.path)
        assertThat(again.lastModified()).isEqualTo(1_000_000L)           // not written again
        assertThat(decoded).containsExactly("cover.gif:GIF")             // and not decoded again

        // the owner replaces the file with another one: another size, another frame
        File(dir, "cover.gif").appendBytes(ByteArray(3))
        val replaced = AnimatedCoverFrames.frameFor(context, song)
        assertThat(replaced?.path).isNotEqualTo(first.path)
        assertThat(decoded).hasSize(2)
    }

    @Test
    fun `a file that cannot be read leaves a marker and is not tried on every scan, and gives no cover`() {
        val dir = album(files = emptyArray())
        File(dir, "cover.gif").writeBytes(ByteArray(2_000) { (it * 31 + 7).toByte() })
        val song = File(dir, "01.flac").path
        assertThat(AnimatedCoverFrames.frameFor(context, song)).isNull()
        val folder = File(context.cacheDir, "animated-cover-frames").listFiles()!!.single()
        assertThat(File(folder, ".none").exists()).isTrue()
        assertThat(AnimatedCoverFrames.frameFor(context, song)).isNull()
    }

    @Test
    fun `a mixed folder like Music is never given a cover from one album's file, and a folder with none gives nothing`() {
        val music = album(name = "Music", files = arrayOf("cover.gif" to "cover.gif"))
        assertThat(AnimatedCoverFrames.frameFor(context, File(music, "01.flac").path)).isNull()
        val plain = album(name = "Plain")
        assertThat(AnimatedCoverFrames.frameFor(context, File(plain, "01.flac").path)).isNull()
    }

    // B57-fix1 4 changes this on purpose: the scan reads the animated cover as the platform lets the app read it. On Android 13+ that is only through the folder the listener chose (no folder, no
    // frame: B57Fix1AnimatedCoverFolderTest), so this test, which reads the album folders from the disk, runs as Android 10 does.
    @Test
    @org.robolectric.annotation.Config(sdk = [29])
    fun `the folder art path takes a cover jpg over the frame, and the frame when there is nothing else`() {
        val both = album(name = "Both", files = arrayOf("cover.gif" to "cover.gif"))
        File(both, "cover.jpg").writeBytes(ByteArray(4_000) { 7 })
        val session = com.lostf1sh.pixelplayeross.utils.FolderArtworkSession(context)
        assertThat(session.source(File(both, "01.flac").path)?.file?.name).isEqualTo("cover.jpg")

        val only = album(name = "Only", files = arrayOf("cover.gif" to "cover.gif"))
        val source = com.lostf1sh.pixelplayeross.utils.FolderArtworkSession(context).source(File(only, "01.flac").path)
        assertThat(source).isNotNull()
        assertThat(source!!.file.path).startsWith(File(context.cacheDir, "animated-cover-frames").path)
        assertThat(source.bytes.size).isGreaterThan(100)
    }

    @Test
    fun `the cache key follows the file's path, time and size`() {
        val a = AnimatedCoverFrames.key("/m/Gaia/cover.mp4", 5L, 100L)
        assertThat(a).hasLength(20)
        assertThat(AnimatedCoverFrames.key("/m/Gaia/cover.mp4", 5L, 100L)).isEqualTo(a)
        assertThat(AnimatedCoverFrames.key("/m/Gaia/cover.mp4", 6L, 100L)).isNotEqualTo(a)
        assertThat(AnimatedCoverFrames.key("/m/Gaia/cover.mp4", 5L, 101L)).isNotEqualTo(a)
        assertThat(AnimatedCoverFrames.key("/m/Other/cover.mp4", 5L, 100L)).isNotEqualTo(a)
    }

    @Test
    fun `the decode size keeps the aspect, never grows, and the longer side is the cap`() {
        assertThat(CoverImageEngine.decodeSize(1920, 1080, 600)).isEqualTo(600 to 338)
        assertThat(CoverImageEngine.decodeSize(480, 480, 600)).isEqualTo(480 to 480)
        assertThat(CoverImageEngine.decodeSize(1000, 1000, 500)).isEqualTo(500 to 500)
        assertThat(CoverImageEngine.decodeSize(100, 4000, 400)).isEqualTo(10 to 400)
    }
}
