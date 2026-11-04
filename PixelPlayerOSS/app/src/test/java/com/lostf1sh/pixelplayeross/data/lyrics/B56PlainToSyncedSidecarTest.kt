package com.lostf1sh.pixelplayeross.data.lyrics

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B56 phase 3.1/3.2: a timed version of lyrics that were plain is saved as a sidecar `.lrc` beside the audio (and the database, by the caller), never into the
 * audio file; on Android 10 and on a removable card too. The platform is a fake storage with the rules of each case, the saver and the provenance journal are the real ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B56PlainToSyncedSidecarTest {
    private val root = Files.createTempDirectory("b56-sidecar").toFile()
    private val audio = File(root, "Album/Song.flac").apply { parentFile.mkdirs(); writeBytes(ByteArray(2048) { (it * 31).toByte() }) }
    private val song = Song.emptySong().copy(id = "56", libraryItemId = "identity-56", path = audio.absolutePath)
    private val timed = "[00:01.00] Hola\n[10:04.50] Adiós"

    /** A storage that writes real files under [root], with the permission rules of the case. */
    private class Storage(val removable: Boolean = false, val tree: String? = null) : LyricsStorage {
        val operations = mutableListOf<String>()
        override fun isRemovable(path: String) = removable
        override fun matchingTree(path: String): String? = tree
        override fun writePath(path: String, content: String) { operations += "path"; File(path).writeText(content, Charsets.UTF_8) }
        override fun writeTree(tree: String, path: String, content: String) { operations += "tree"; File(path).writeText(content, Charsets.UTF_8) }
        override fun dropTree(tree: String) = Unit
        override fun writeAccessRequest(path: String): android.content.IntentSender? = error("a background save must not ask for an Android screen")
    }

    @After fun cleanup() { root.deleteRecursively() }

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun files(storage: Storage) = DownloadedLyricsFiles(storage, File(root, "sidecars.json"))
    private val sidecar get() = File(root, "Album/Song.lrc")

    @Test fun `a timed version is saved as a sidecar beside the audio and the audio file is untouched`(): Unit = runBlocking {
        val before = sha(audio)
        var savedInApp = false

        val result = files(Storage()).save(song, timed) { savedInApp = true }

        assertThat(result).isEqualTo(LyricsSaveResult.FileSaved)
        assertThat(sidecar.readText()).isEqualTo(timed)
        assertThat(sha(audio)).isEqualTo(before)
        assertThat(savedInApp).isFalse()
    }

    @Test @Config(sdk = [29])
    fun `on Android 10 the same save writes the sidecar by path with no Android screen`(): Unit = runBlocking {
        val storage = Storage()

        val result = files(storage).save(song, timed) { error("the app copy is the caller's") }

        assertThat(result).isEqualTo(LyricsSaveResult.FileSaved)
        assertThat(storage.operations).containsExactly("path")
        assertThat(sidecar.readText()).isEqualTo(timed)
    }

    @Test fun `on a removable card with the folder granted the sidecar goes through the grant`(): Unit = runBlocking {
        val storage = Storage(removable = true, tree = "content://tree/card")

        val result = files(storage).save(song, timed) { error("granted: no app copy needed") }

        assertThat(result).isEqualTo(LyricsSaveResult.FileSaved)
        assertThat(storage.operations).containsExactly("tree")
    }

    @Test fun `on a removable card with no grant nothing is written to the card and the lyrics are kept in the app`(): Unit = runBlocking {
        val storage = Storage(removable = true)
        var savedInApp = false

        val result = files(storage).save(song, timed) { savedInApp = true }

        assertThat(result).isInstanceOf(LyricsSaveResult.SavedInApp::class.java)
        assertThat(savedInApp).isTrue()
        assertThat(storage.operations).isEmpty()
        assertThat(sidecar.exists()).isFalse()
    }

    @Test fun `a sidecar the listener already has is never overwritten`(): Unit = runBlocking {
        sidecar.writeText("plain words of the listener")
        var savedInApp = false

        val result = files(Storage()).save(song, timed) { savedInApp = true }

        assertThat(result).isEqualTo(LyricsSaveResult.SavedInApp(LyricsWriteReason.EXISTING_FILE))
        assertThat(sidecar.readText()).isEqualTo("plain words of the listener")
        assertThat(savedInApp).isTrue()
    }

    @Test fun `undo removes the sidecar the app wrote and nothing else`(): Unit = runBlocking {
        val storage = Storage()
        val saver = files(storage)
        saver.save(song, timed) { }
        val before = sha(audio)

        saver.delete(song)

        assertThat(sidecar.exists()).isFalse()
        assertThat(sha(audio)).isEqualTo(before)
        assertThat(audio.exists()).isTrue()
    }
}
