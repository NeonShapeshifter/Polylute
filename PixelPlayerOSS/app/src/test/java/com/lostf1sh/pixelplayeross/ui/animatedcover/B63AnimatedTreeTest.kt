package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract as D
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B63AnimatedTreeTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
    private val root = "/storage/emulated/0/Music"
    private val song = "$root/Release/01.flac"
    private var files = emptyList<String>()
    private var denied = false
    private var listings = 0
    private val logs = mutableListOf<String>()
    private val provider = object : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            if (denied) throw SecurityException("grant revoked")
            val cols = projection!!
            val c = MatrixCursor(cols)
            if (uri.path!!.endsWith("/children")) {
                listings++
                files.forEach { name -> c.addRow(cols.map { col -> when(col) {
                    D.Document.COLUMN_DOCUMENT_ID -> "primary:Music/Release/$name"
                    D.Document.COLUMN_DISPLAY_NAME -> name
                    D.Document.COLUMN_SIZE -> 4900000L
                    D.Document.COLUMN_LAST_MODIFIED -> 1L
                    D.Document.COLUMN_MIME_TYPE -> "video/mp4"
                    else -> null
                } }) }
            } else c.addRow(cols.map { if(it == D.Document.COLUMN_LAST_MODIFIED) 0L else null })
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
    @Before fun setup() { ShadowContentResolver.registerProviderInternal("com.android.externalstorage.documents", provider) }
    @After fun close() { ShadowContentResolver.reset() }
    private fun finder() = AnimatedCoverFinder(Dispatchers.Unconfined,
        TreeAnimatedCoverDirectory(app.contentResolver, tree, root), { true }, { 0L },
        AlbumFolders { listOf(AlbumFolder("$root/Release", 12)) }, logs::add)

    @Test fun `provider adds a cover without advancing folder last modified`() {
        val f = finder()
        assertNull(f.findNow(song, 1))
        files = listOf("cover.mp4")
        val header = f.findNow(song, 1, 0)
        val player = f.findNow(song, 1, 1)
        assertNotNull("a stale negative must not hide an existing SAF file", header)
        assertNotNull(player)
        assertEquals(header!!.uri, player!!.uri)
        assertEquals("content", header.uri.scheme)
        assertTrue(logs.last().contains("source="))
        assertTrue(logs.last().contains("grant="))
    }

    @Test fun `a revoked grant does not reuse a previously cached positive`() {
        files = listOf("cover.mp4")
        val f = finder()
        assertNotNull(f.findNow(song, 1))
        denied = true
        assertNull("revocation must show the still cover", f.findNow(song, 1))
        assertTrue(logs.last().contains("unreachable") || logs.last().contains("unreadable"))
        denied = false
        assertNotNull(f.findNow(song, 1))
    }

    @Test fun `every lookup logs a cache or listing decision`() {
        files = listOf("cover.mp4")
        val f = finder()
        f.findNow(song, 1); f.findNow(song, 1)
        assertEquals(2, logs.size)
        assertTrue(logs.all { it.contains("source=") && it.contains("grant=") })
    }
}
