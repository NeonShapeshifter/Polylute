package com.lostf1sh.pixelplayeross.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.ui.animatedcover.AnimatedCoverDirectory
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B59 review (HONOR 11-06): every "Update library" took 163 s instead of 3-8 s, 122 s of it in `folder-artwork`. [FolderArtworkSession.discSource] kept "this disc has no file" with
 * `getOrPut`, which runs its block again for a stored null, so every song of a folder without `discN.*` listed the animated-cover folder again (a SAF query on Android 13+). One listing per
 * folder and disc, whatever the number of songs (B60: now one per folder, whatever the discs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B59DiscSourceListingTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root = Files.createTempDirectory("b59-disc-listing").toFile()

    @After fun cleanup() {
        root.deleteRecursively()
    }

    private class CountingDirectory : AnimatedCoverDirectory {
        val listed = mutableListOf<String>()
        override fun names(dir: File): List<String>? { listed += dir.name; return dir.list()?.toList() }
        override fun stamp(dir: File): Long = dir.lastModified()
        override fun sizeOf(file: File): Long = file.length()
        override fun modifiedOf(file: File): Long = file.lastModified()
    }

    @Test
    fun `a folder without disc files is listed once, not once per song or per disc`() {
        val album = File(root, "SAYONARA").apply { mkdirs() }
        val songs = (1..20).map { File(album, "1-%02d. song.flac".format(it)).apply { writeBytes(ByteArray(64)) } }
        val directory = CountingDirectory()
        val session = FolderArtworkSession(context, directory)

        songs.forEach { assertThat(session.discSource(it.path, 1)).isNull() }
        assertThat(directory.listed).containsExactly("SAYONARA")

        // B60: disc 2 of the same folder asks nothing more of the chosen folder (the reviewer's version asked once per disc)
        songs.take(3).forEach { assertThat(session.discSource(it.path, 2)).isNull() }
        assertThat(directory.listed).containsExactly("SAYONARA")
    }

    @Test
    fun `a disc picture is still found and kept for the folder`() {
        val album = File(root, "SAYONARA").apply { mkdirs() }
        File(album, "disc1.jpg").writeBytes(ByteArray(3000) { 11 })
        val songs = (1..5).map { File(album, "1-%02d. song.flac".format(it)).apply { writeBytes(ByteArray(64)) } }
        val directory = CountingDirectory()
        val session = FolderArtworkSession(context, directory)

        val sources = songs.map { session.discSource(it.path, 1) }
        assertThat(sources.map { it?.file?.name }.distinct()).containsExactly("disc1.jpg")
        assertThat(directory.listed).isEmpty()
    }
}
