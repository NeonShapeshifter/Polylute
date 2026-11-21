package com.lostf1sh.pixelplayeross.utils

import android.app.Application
import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B63FileProviderTest {
    @Before fun resetProviderPathCache() {
        val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (field.get(null) as MutableMap<*, *>).clear()
    }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    @Test fun `private settings and backup work files cannot be shared by provider`() {
        val files = listOf(File(context.filesDir,"datastore/settings.preferences_pb"), File(context.cacheDir,"backup-work.pxpl"))
        files.forEach { file ->
            val result = runCatching { uri(file) }
            assertTrue("provider must reject ${file.name}", result.exceptionOrNull() is IllegalArgumentException)
        }
    }
    @Test fun `existing artwork and log folders can still be shared`() {
        for ((root, folder) in listOf(context.filesDir to "album_art", context.filesDir to "album_art_thumbs", context.filesDir to "detailed-logs", context.filesDir to "noise-marks", context.cacheDir to "shared_zips", context.cacheDir to "animated-cover-frames")) {
            assertEquals("content", uri(File(root,"$folder/synthetic.jpg")).scheme)
        }
    }
    @Test fun `legacy custom artwork stays shareable without exposing its parent`() {
        val source = File(context.filesDir, "artist_art_63.jpg").apply { writeBytes(ByteArray(128) { it.toByte() }) }
        try {
            val uri = SharedArtworkFiles.uri(context,source)
            assertEquals("content",uri.scheme)
            assertTrue(uri.path.orEmpty().contains("shared_artwork"))
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(source.readBytes(),it.readBytes()) }
        } finally { source.delete() }
    }
}
