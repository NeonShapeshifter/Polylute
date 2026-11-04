package com.lostf1sh.pixelplayeross.data.lyrics

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class B51AndroidLyricsStorageTest {
    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/ABCD%3AMusic")
    private val path = "/storage/ABCD/Music/Album/song.flac"
    private val access = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private val permissions = mutableListOf<UriPermission>()
    private fun storage(): AndroidLyricsStorage {
        every { context.contentResolver } returns resolver
        every { resolver.persistedUriPermissions } answers { permissions.toList() }
        every { resolver.takePersistableUriPermission(any(), any()) } answers {
            val chosen = firstArg<Uri>()
            permissions += mockk<UriPermission> {
                every { uri } returns chosen
                every { isReadPermission } returns true
                every { isWritePermission } returns true
            }
        }
        return AndroidLyricsStorage(context)
    }
    @After fun cleanup() { unmockkAll() }
    @Test fun `Android 10 persists a volume root or music folder and reuses it after adapter recreation`() {
        val first = storage()
        first.keepTree(tree, access, path)
        assertThat(AndroidLyricsStorage(context).matchingTree("/storage/ABCD/Music/Other/song.lrc")).isEqualTo(tree.toString())
        assertThat(first.matchingTree("/storage/ABCD/Music-other/song.lrc")).isNull()
        val volume = Uri.parse("content://com.android.externalstorage.documents/tree/ABCD%3A")
        first.keepTree(volume, access, path)
        assertThat(first.treePath(volume)).isEqualTo("/storage/ABCD")
        verify(exactly = 1) { resolver.takePersistableUriPermission(tree, access) }
    }
    @Test @Config(sdk = [35]) fun `Android 11 plus folder grant maps to the same target and rejects unrelated providers and folders`() {
        val adapter = storage()
        adapter.keepTree(tree, access, path)
        assertThat(adapter.matchingTree("/storage/ABCD/Music/Album/song.lrc")).isEqualTo(tree.toString())
        assertThat(adapter.treePath(Uri.parse("content://other/tree/ABCD%3AMusic"))).isNull()
        var reason: LyricsWriteReason? = null
        try { adapter.keepTree(tree, access, "/storage/ABCD/Elsewhere/song.flac") }
        catch (error: LyricsWriteException) { reason = error.reason }
        assertThat(reason).isEqualTo(LyricsWriteReason.WRONG_FOLDER)
    }
    @Test fun `DocumentFile creates an lrc beside the song then overwrites through a truncating stream`() {
        val adapter = storage()
        val root = mockk<DocumentFile>()
        val album = mockk<DocumentFile>()
        val document = mockk<DocumentFile>()
        val uri = Uri.parse("content://provider/document/song.lrc")
        var existing = false
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(context, tree) } returns root
        every { root.exists() } returns true
        every { root.findFile("Album") } returns album
        every { album.isDirectory } returns true
        every { album.canWrite() } returns true
        every { album.findFile("song.lrc") } answers { if (existing) document else null }
        every { album.createFile("application/octet-stream", "song.lrc") } answers { existing = true; document }
        every { document.uri } returns uri
        every { document.canWrite() } returns true
        val output = mutableListOf<ByteArrayOutputStream>()
        every { resolver.openOutputStream(uri, "wt") } answers { ByteArrayOutputStream().also { output += it } }
        adapter.writeTree(tree.toString(), path.replace(".flac", ".lrc"), "[00:01]sång")
        adapter.writeTree(tree.toString(), path.replace(".flac", ".lrc"), "short")
        assertThat(output.map { it.toString("UTF-8") }).containsExactly("[00:01]sång", "short").inOrder()
        verify(exactly = 1) { album.createFile(any(), any()) }
        verify(exactly = 2) { resolver.openOutputStream(uri, "wt") }
    }
}
