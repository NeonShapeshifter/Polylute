package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.UriPermission
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.AnimatedCoverFolderStore
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * B57-fix1 4 (#52): the animated covers' folder. A fake shared-storage provider stands for the folder the listener picked (document ids are `volume:path`, as on the phone); everything else is the
 * real code: the grant (shared with the lyrics saver), the directory that reads through the tree, the finder, the frame maker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B57Fix1AnimatedCoverFolderTest {
    private val authority = "com.android.externalstorage.documents"
    private val tree = Uri.parse("content://$authority/tree/primary%3AMusic")
    private val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val root: String get() = File(Environment.getExternalStorageDirectory(), "Music").path

    /** The folder the listener picked, as documents: `primary:Music/<album>/<file>`. Files carry a size and a time; folders are listed by [children]. */
    private class Fs {
        class Doc(val id: String, val name: String, val dir: Boolean, val size: Long = 0, val modified: Long = 0)
        val docs = LinkedHashMap<String, Doc>()
        val children = HashMap<String, MutableList<String>>()
        fun folder(id: String, modified: Long = 1) { docs[id] = Doc(id, id.substringAfterLast('/').substringAfterLast(':'), true, modified = modified) }
        fun file(parent: String, name: String, size: Long, modified: Long) {
            val id = "$parent/$name"
            docs[id] = Doc(id, name, false, size, modified)
            children.getOrPut(parent) { mutableListOf() } += id
        }
    }

    private val fs = Fs()
    private var queries = 0

    private val provider = object : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queries++
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val cursor = MatrixCursor(columns)
            val ids = if (uri.path.orEmpty().endsWith("/children")) {
                fs.children[DocumentsContract.getDocumentId(uri)].orEmpty()
            } else {
                listOfNotNull(DocumentsContract.getDocumentId(uri).takeIf { it in fs.docs })
            }
            for (id in ids) {
                val doc = fs.docs[id] ?: continue
                cursor.addRow(columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> doc.name
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> doc.id
                        DocumentsContract.Document.COLUMN_SIZE -> doc.size
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> doc.modified
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> if (doc.dir) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                        else -> null
                    }
                })
            }
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private val permissions = mutableListOf<UriPermission>()
    private lateinit var resolver: ContentResolver
    private lateinit var context: Context

    @Before fun setUp() {
        ShadowContentResolver.registerProviderInternal(authority, provider)
        resolver = spyk(app.contentResolver)
        every { resolver.persistedUriPermissions } answers { permissions.toList() }
        every { resolver.takePersistableUriPermission(any(), any()) } answers {
            val chosen = firstArg<Uri>()
            permissions += mockk<UriPermission> { every { uri } returns chosen; every { isReadPermission } returns true; every { isWritePermission } returns false }
        }
        every { resolver.releasePersistableUriPermission(any(), any()) } answers { permissions.removeAll { it.uri == firstArg<Uri>() } }
        context = object : ContextWrapper(app) { override fun getContentResolver(): ContentResolver = resolver }
        app.getSharedPreferences("animated_cover_folder", Context.MODE_PRIVATE).edit().clear().commit()

        // /Music/Gaia (mp4 + gif), /Music/Mora (webp), /Music2/Gaia is not inside the folder
        fs.folder("primary:Music")
        fs.folder("primary:Music/Gaia", modified = 5)
        fs.folder("primary:Music/Mora", modified = 6)
        fs.file("primary:Music/Gaia", "cover.gif", 900, 30)
        fs.file("primary:Music/Gaia", "cover.mp4", 5_000, 31)
        fs.file("primary:Music/Gaia", "01.flac", 80_000, 32)
        fs.file("primary:Music/Mora", "Cover.WEBP", 700, 33)
    }

    @After fun tearDown() {
        ShadowContentResolver.reset()
    }

    private fun store(sdk: Int = 35) = AnimatedCoverFolderStore(context, sdk)
    private fun finder(store: AnimatedCoverFolderStore, sdk: Int = 35) =
        AnimatedCoverFinder(Dispatchers.Unconfined, AnimatedCoverFolder(context, store, sdk), AlbumArtUtils::shouldTrustDirectoryArtwork) { 0L }

    private fun song(vararg path: String) = File(File(Environment.getExternalStorageDirectory(), path.first()), path.drop(1).joinToString("/")).path

    // ── the grant: the lyrics saver's code, read-only ─────────────────────────────────────────────────

    @Test fun a_picked_folder_is_kept_read_only_and_becomes_the_folder() {
        val s = store()
        assertThat(s.current()).isNull()
        assertThat(s.choose(tree, read)).isTrue()
        verify(exactly = 1) { resolver.takePersistableUriPermission(tree, read) }
        assertThat(s.folder.value).isEqualTo(tree)
        assertThat(s.nameOf(tree)).isEqualTo("Music")
        // survives the process: a second store reads the same folder, from the stored name and the OS's grant
        assertThat(store().current()).isEqualTo(tree)
    }

    @Test fun a_folder_that_cannot_be_used_is_refused_and_changes_nothing() {
        val s = store()
        assertThat(s.choose(tree, read)).isTrue()
        val other = Uri.parse("content://com.example.docs/tree/primary%3AMusic")
        assertThat(s.choose(other, read)).isFalse()                       // not the shared-storage provider
        assertThat(s.choose(Uri.parse("content://$authority/tree/primary%3A"), read)).isFalse() // a volume's root (Android 11+)
        assertThat(s.choose(Uri.parse("content://$authority/tree/primary%3AOther"), 0)).isFalse() // the system granted no read access
        assertThat(s.folder.value).isEqualTo(tree)
    }

    @Test fun changing_the_folder_lets_go_of_the_old_one_and_removing_it_releases_the_permission() {
        val s = store()
        s.choose(tree, read)
        val second = Uri.parse("content://$authority/tree/primary%3ADAP")
        assertThat(s.choose(second, read)).isTrue()
        verify { resolver.releasePersistableUriPermission(tree, any()) }
        assertThat(s.folder.value).isEqualTo(second)
        s.remove()
        verify { resolver.releasePersistableUriPermission(second, any()) }
        assertThat(s.folder.value).isNull()
        assertThat(s.current()).isNull()
        assertThat(permissions).isEmpty()
    }

    @Test fun a_folder_whose_permission_was_revoked_behind_the_apps_back_is_no_folder() {
        val s = store()
        s.choose(tree, read)
        permissions.clear()
        assertThat(s.current()).isNull()
        assertThat(finder(s).findNow(song("Music", "Gaia", "01.flac"))).isNull()
    }

    @Test fun lyrics_and_animated_covers_share_one_grant_implementation() {
        val main = File("src/main/java/com/lostf1sh/pixelplayeross")
        // the grant is taken in one place; the lyrics saver and the animated covers' folder both ask it, and neither takes a permission itself
        assertThat(File(main, "utils/TreeGrants.kt").readText()).contains("takePersistableUriPermission")
        for (file in listOf("data/lyrics/AndroidLyricsStorage.kt", "data/preferences/AnimatedCoverFolderStore.kt")) {
            assertThat(File(main, file).readText()).doesNotContain("takePersistableUriPermission")
        }
        assertThat(File(main, "data/lyrics/AndroidLyricsStorage.kt").readText()).contains("treeGrants.keep(")
        assertThat(File(main, "data/preferences/AnimatedCoverFolderStore.kt").readText()).contains("grants.keep(")
    }

    // ── reading the covers through the folder ──────────────────────────────────────────────────────

    @Test fun an_album_inside_the_folder_plays_its_cover_by_document_address_and_mp4_wins() {
        val s = store()
        s.choose(tree, read)
        val cover = finder(s).findNow(song("Music", "Gaia", "01.flac"))
        assertThat(cover).isNotNull()
        assertThat(cover!!.kind).isEqualTo(AnimatedCoverKind.VIDEO)
        assertThat(cover.file.path).isEqualTo("$root/Gaia/cover.mp4")
        assertThat(cover.uri.scheme).isEqualTo("content")                    // read through the tree, not by path
        assertThat(cover.documentUri).isNotNull()
        assertThat(cover.uri.toString()).contains("primary%3AMusic%2FGaia%2Fcover.mp4")
        assertThat(cover.sizeBytes).isEqualTo(5_000)
        assertThat(cover.modifiedMs).isEqualTo(31)
        assertThat(cover.canRead()).isTrue()
        // file names are compared without regard to case, as on a card
        assertThat(finder(s).findNow(song("Music", "Mora", "01.flac"))!!.kind).isEqualTo(AnimatedCoverKind.WEBP)
    }

    @Test fun an_album_outside_the_folder_shows_the_still_cover() {
        val s = store()
        s.choose(tree, read)
        fs.folder("primary:Music2/Gaia")
        fs.file("primary:Music2/Gaia", "cover.mp4", 5_000, 31)
        val f = finder(s)
        assertThat(f.findNow(song("Music2", "Gaia", "01.flac"))).isNull()                // a sibling with the same prefix
        assertThat(f.findNow(song("Download", "Gaia", "01.flac"))).isNull()
        assertThat(f.findNow(song("Music", "Unknown", "01.flac"))).isNull()              // inside, but there is nothing there
    }

    @Test fun with_no_folder_on_android_13_plus_nothing_is_read_and_before_it_the_disk_is_read_as_before() {
        val none = store()
        assertThat(finder(none).findNow(song("Music", "Gaia", "01.flac"))).isNull()
        // Android 10: no picker, no folder; the covers are read from the disk, a plain file address
        val dir = java.nio.file.Files.createTempDirectory("b57fix1-album").toFile().resolve("Gaia").also { it.mkdirs() }
        File(dir, "cover.gif").writeBytes(ByteArray(10) { 1 })
        val old = store(sdk = 29)
        assertThat(old.current()).isNull()
        val found = finder(old, sdk = 29).findNow(File(dir, "01.flac").path)
        assertThat(found).isNotNull()
        assertThat(found!!.documentUri).isNull()
        assertThat(found.uri.scheme).isEqualTo("file")
        assertThat(found.kind).isEqualTo(AnimatedCoverKind.GIF)
        dir.parentFile.deleteRecursively()
    }

    @Test fun a_folder_is_listed_once_and_a_new_folder_forgets_what_was_read_through_the_old_one() {
        val s = store()
        s.choose(tree, read)
        val f = finder(s)
        repeat(5) { assertThat(f.findNow(song("Music", "Gaia", "01.flac"))).isNotNull() }
        assertThat(f.listings).isEqualTo(1)
        val before = queries
        repeat(5) { f.findNow(song("Music", "Gaia", "01.flac")) }
        assertThat(queries - before).isAtMost(5)
        // the finder's cache is dropped when the folder changes (MainActivity does it): the answer is read again
        f.invalidate()
        f.findNow(song("Music", "Gaia", "01.flac"))
        assertThat(f.listings).isEqualTo(2)
    }

    // ── the still frame the library scan takes of a cover read through the folder ─────────────────

    @Test fun the_scan_takes_the_first_frame_of_a_cover_in_the_folder_by_its_address() {
        val s = store()
        s.choose(tree, read)
        val directory = AnimatedCoverFolder(context, s, 35)
        val asked = mutableListOf<Uri>()
        val old = AnimatedCoverFrames.uriDecoder
        AnimatedCoverFrames.uriDecoder = AnimatedCoverFrames.UriFirstFrameDecoder { _, uri, kind ->
            asked += uri
            assertThat(kind).isEqualTo(AnimatedCoverKind.VIDEO)
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        }
        try {
            val frame = AnimatedCoverFrames.frameFor(context, song("Music", "Gaia", "01.flac"), directory)
            assertThat(frame).isNotNull()
            assertThat(frame!!.isFile).isTrue()
            assertThat(asked.single().scheme).isEqualTo("content")
            // an album outside the folder has no frame
            assertThat(AnimatedCoverFrames.frameFor(context, song("Music2", "Gaia", "01.flac"), directory)).isNull()
        } finally {
            AnimatedCoverFrames.uriDecoder = old
            File(context.cacheDir, "animated-cover-frames").deleteRecursively()
        }
    }

    @Test fun the_cover_engines_read_the_address_of_the_cover_and_not_only_its_path() {
        val doc = AnimatedCover(File("/storage/emulated/0/Music/Gaia/cover.mp4"), AnimatedCoverKind.VIDEO, 10, 1, documentUri = Uri.parse("content://$authority/tree/primary%3AMusic/document/primary%3AMusic%2FGaia%2Fcover.mp4"))
        assertThat(doc.canRead()).isTrue()                                    // a document is opened by the engine; a missing path says nothing about it
        val plain = AnimatedCover(File("/nowhere/cover.mp4"), AnimatedCoverKind.VIDEO, 10, 1)
        assertThat(plain.uri.scheme).isEqualTo("file")
        assertThat(plain.canRead()).isFalse()
        val video = File("src/main/java/com/lostf1sh/pixelplayeross/ui/animatedcover/CoverVideoEngine.kt").readText()
        assertThat(video).contains("MediaItem.fromUri(cover.uri)")
        assertThat(video).doesNotContain("Uri.fromFile(cover.file)")
        val image = File("src/main/java/com/lostf1sh/pixelplayeross/ui/animatedcover/CoverImageEngine.kt").readText()
        assertThat(image).contains("ImageDecoder.createSource(context.contentResolver, cover.documentUri)")
    }
}
