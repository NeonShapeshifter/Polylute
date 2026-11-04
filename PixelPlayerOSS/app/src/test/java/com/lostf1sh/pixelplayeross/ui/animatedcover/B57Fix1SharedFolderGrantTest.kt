package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.lyrics.AndroidLyricsStorage
import com.lostf1sh.pixelplayeross.data.preferences.AnimatedCoverFolderStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B57-fix1 review. The lyrics saver and the animated covers can be given **the same folder**, and Android keeps one grant per folder: letting it go for one of them let it go
 * for both. Before the fix, removing (or changing) the covers' folder released read + write, so the lyrics could no longer be saved there, and dropping the lyrics' folder
 * took the covers' read with it. The resolver here keeps the modes per folder as Android does (take adds modes, release removes only the modes it names).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B57Fix1SharedFolderGrantTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val authority = "com.android.externalstorage.documents"
    private val music = Uri.parse("content://$authority/tree/primary%3AMusic")
    private val other = Uri.parse("content://$authority/tree/primary%3ADAP")
    private val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private val pickerFlags = read or write or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

    private val modes = linkedMapOf<Uri, Int>()
    private lateinit var context: Context

    @Before fun setUp() {
        val resolver = spyk(app.contentResolver)
        every { resolver.persistedUriPermissions } answers {
            modes.filterValues { it != 0 }.map { (u, m) ->
                mockk<UriPermission> { every { uri } returns u; every { isReadPermission } returns (m and read != 0); every { isWritePermission } returns (m and write != 0) }
            }
        }
        every { resolver.takePersistableUriPermission(any(), any()) } answers { modes[firstArg()] = (modes[firstArg()] ?: 0) or secondArg<Int>() }
        every { resolver.releasePersistableUriPermission(any(), any()) } answers { modes[firstArg()] = (modes[firstArg()] ?: 0) and secondArg<Int>().inv() }
        context = object : ContextWrapper(app) { override fun getContentResolver(): ContentResolver = resolver }
        app.getSharedPreferences("animated_cover_folder", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun covers() = AnimatedCoverFolderStore(context, 35)
    private fun lyrics() = AndroidLyricsStorage(context)
    private fun songIn(folder: String) = "${android.os.Environment.getExternalStorageDirectory().absolutePath}/$folder/Album/01.flac"

    @Test fun the_covers_keep_only_read_even_when_the_picker_hands_out_write() {
        assertThat(covers().choose(music, pickerFlags)).isTrue()
        assertThat(modes[music]).isEqualTo(read)
    }

    @Test fun removing_the_covers_folder_keeps_the_lyrics_write_on_the_same_folder() {
        lyrics().keepTree(music, pickerFlags, songIn("Music"))
        val c = covers()
        assertThat(c.choose(music, pickerFlags)).isTrue()
        c.remove()
        assertThat(modes[music]).isEqualTo(read or write)
        assertThat(lyrics().grants().map { it.first }).containsExactly(music.toString())
        assertThat(c.current()).isNull()
    }

    @Test fun changing_the_covers_folder_away_from_the_lyrics_folder_keeps_the_lyrics_grant() {
        lyrics().keepTree(music, pickerFlags, songIn("Music"))
        val c = covers()
        c.choose(music, pickerFlags)
        assertThat(c.choose(other, pickerFlags)).isTrue()
        assertThat(modes[music]).isEqualTo(read or write)
        assertThat(modes[other]).isEqualTo(read)
    }

    @Test fun dropping_the_lyrics_folder_keeps_the_covers_read_on_the_same_folder() {
        val c = covers()
        c.choose(music, pickerFlags)
        lyrics().keepTree(music, pickerFlags, songIn("Music"))
        lyrics().dropTree(music.toString())
        assertThat(modes[music]).isEqualTo(read)
        assertThat(lyrics().grants()).isEmpty()
        assertThat(c.current()).isEqualTo(music)
    }

    @Test fun a_folder_only_one_feature_holds_is_released_in_full() {
        val c = covers()
        c.choose(other, pickerFlags)
        c.remove()
        assertThat(modes[other]).isEqualTo(0)

        lyrics().keepTree(music, pickerFlags, songIn("Music"))
        lyrics().dropTree(music.toString())
        assertThat(modes[music]).isEqualTo(0)
    }
}
