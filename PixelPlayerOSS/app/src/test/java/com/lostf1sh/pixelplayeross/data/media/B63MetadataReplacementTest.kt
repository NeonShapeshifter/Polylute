package com.lostf1sh.pixelplayeross.data.media

import android.app.Application
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B63MetadataReplacementTest {
    @Test fun `no sibling access refuses the save and leaves every original byte`() {
        val dir = Files.createTempDirectory("b63-safe").toFile()
        try {
            val original = File(dir, "song.flac").apply { writeBytes(ByteArray(1024) { it.toByte() }) }
            val before = original.readBytes()
            val source = File(dir, "edit.flac").apply { writeBytes(ByteArray(4096) { 37 }) }
            val inaccessible = object : File(original.path) {
                override fun getParentFile() = File(dir, "missing/subfolder")
            }
            val editor = SongMetadataEditor(ApplicationProvider.getApplicationContext(), mockk(relaxed = true), mockk(relaxed = true))
            val replace = SongMetadataEditor::class.java.getDeclaredMethod("replaceFileContentSafely", File::class.java, File::class.java).apply { isAccessible = true }
            var refused = false
            try { replace.invoke(editor, source, inaccessible) }
            catch (e: InvocationTargetException) { refused = e.cause is IOException }
            assertArrayEquals("no fallback may truncate the target", before, original.readBytes())
            assertTrue("save must report the failure", refused)
        } finally { dir.deleteRecursively() }
    }
}
