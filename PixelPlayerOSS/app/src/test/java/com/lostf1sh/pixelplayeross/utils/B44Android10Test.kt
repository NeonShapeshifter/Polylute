package com.lostf1sh.pixelplayeross.utils

import android.app.Application
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.app.RemoteAction
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.library.LibraryFileWalk
import com.lostf1sh.pixelplayeross.data.library.LibraryFolders
import com.lostf1sh.pixelplayeross.data.media.SongMetadataEditor
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import io.mockk.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class B44Android10Test {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val dir = Files.createTempDirectory("b44-api29").toFile()
    private val file = File(dir, "song.wav")
    private var consent = false
    private val sender get() = PendingIntent.getActivity(app, 44, Intent("b44-consent"), PendingIntent.FLAG_IMMUTABLE).intentSender
    private val provider = object : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "audio/wav"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(MediaStore.MediaColumns.DATA)
            return MatrixCursor(columns).apply {
                addRow(columns.map { column -> when (column) {
                    MediaStore.MediaColumns.DATA -> file.path
                    MediaStore.MediaColumns._ID -> 44L
                    MediaStore.MediaColumns.VOLUME_NAME -> MediaStore.VOLUME_EXTERNAL_PRIMARY
                    else -> ""
                } }.toTypedArray())
            }
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (consent) {
                val action = RemoteAction(Icon.createWithResource(app, android.R.drawable.ic_dialog_alert), "Consent", "Music file", PendingIntent.getActivity(app, 44, Intent("b44-consent"), PendingIntent.FLAG_IMMUTABLE))
                throw RecoverableSecurityException(SecurityException("grant required"), "Grant access", action)
            }
            return 1
        }
    }
    private fun register() { ShadowContentResolver.registerProviderInternal("media", provider) }
    @After fun clean() { dir.deleteRecursively() }

    @Test fun `audio content URIs use the API twenty nine collection overload`() {
        register()
        val uris = MediaStorePermissionHelper.getAudioMediaStoreUris(app, 44)
        assertThat(uris).isNotEmpty()
        assertThat(uris.all { it.lastPathSegment == "44" && it.authority == "media" }).isTrue()
    }

    @Test fun `legacy deletion removes a writable file even when MediaStore removes no row`() = runBlocking {
        register()
        file.writeText("local music")
        assertThat(FileDeletionUtils.deleteFile(app, file.path)).isTrue()
        assertThat(file.exists()).isFalse()
    }

    @Test fun `removable volume root comes from app external directories without an API thirty call`() {
        val volume = mockk<StorageVolume>(relaxed = true)
        every { volume.state } returns Environment.MEDIA_MOUNTED
        every { volume.isPrimary } returns false
        every { volume.isRemovable } returns true
        every { volume.uuid } returns "1234-ABCD"
        every { volume.getDescription(any()) } returns "SD Card"
        val manager = mockk<StorageManager>(relaxed = true)
        every { manager.storageVolumes } returns listOf(volume)
        every { manager.getStorageVolume(any<File>()) } returns volume
        val external = File(dir, "Android/data/${app.packageName}/files").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? = if (name == Context.STORAGE_SERVICE) manager else super.getSystemService(name)
            override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(external)
        }
        assertThat(StorageUtils.getAvailableStorages(context).single().path.canonicalFile).isEqualTo(dir.canonicalFile)
    }

    @Test fun `folder walk and local lrc reads work on Android ten`() {
        file.writeText("local audio")
        val lrc = File(dir, "song.lrc").apply { writeText("[00:01.00] Una canción local") }
        val rules = LibraryFolders.Rules(setOf(dir.path), setOf(LibraryFolders.NOTHING_BY_DEFAULT))
        assertThat(LibraryFileWalk.walk(rules, emptyMap()).candidates.map { it.path }).containsExactly(file.path)
        val validated = com.lostf1sh.pixelplayeross.utils.LyricsImportSecurity.validateLocalLyricsFile(lrc)
        assertThat(validated).isInstanceOf(com.lostf1sh.pixelplayeross.utils.LyricsImportValidationResult.Valid::class.java)
    }

    private fun wav() {
        val bytes = ByteBuffer.allocate(244).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(236).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(200).put(ByteArray(200))
        file.writeBytes(bytes.array())
    }
    private suspend fun edit(): com.lostf1sh.pixelplayeross.data.media.SongMetadataEditResult {
        register()
        wav()
        val dao = mockk<MusicDao>(relaxed = true)
        coEvery { dao.getAllArtistsListRaw() } returns emptyList()
        coEvery { dao.getMaxArtistId() } returns null
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.artistDelimitersFlow } returns flowOf(listOf(","))
        every { prefs.artistWordDelimitersFlow } returns flowOf(emptyList())
        every { prefs.extractArtistsFromTitleFlow } returns flowOf(false)
        return SongMetadataEditor(app, dao, prefs).editSongMetadata(44, "Edited on Android 10", "Artist", "Album",
            newGenre = "Rock", newLyrics = "Local words", newTrackNumber = 1, newDiscNumber = 1)
    }

    @Test fun `tag editing writes the real file on Android ten`() = runBlocking {
        val result = edit()
        assertThat(result.success).isTrue()
        assertThat(AudioFileIO.read(file).tag.getFirst(FieldKey.TITLE)).isEqualTo("Edited on Android 10")
    }

    @Test fun `a recoverable MediaStore update returns the system consent action`() = runBlocking {
        consent = true
        val result = edit()
        val getter = result.javaClass.methods.firstOrNull { it.name == "getPermissionIntentSender" }
        assertThat(getter?.invoke(result)).isEqualTo(sender)
    }
}
