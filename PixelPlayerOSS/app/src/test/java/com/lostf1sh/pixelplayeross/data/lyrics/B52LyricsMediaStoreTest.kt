package com.lostf1sh.pixelplayeross.data.lyrics

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.utils.MediaStorePermissionHelper
import io.mockk.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B52LyricsMediaStoreTest {
    private val context = mockk<Context>()
    private val path = "/storage/emulated/0/Music/Song.lrc"

    @After fun cleanup() = unmockkAll()

    @Test fun `write request uses the sidecar MediaStore URI and never the audio URI`() {
        mockkObject(MediaStorePermissionHelper)
        val uri = Uri.parse("content://media/external_primary/file/52")
        val sender = mockk<IntentSender>()
        every { MediaStorePermissionHelper.getMediaStoreUri(context, path) } returns uri
        every { MediaStorePermissionHelper.createWriteRequestIntentSender(context, listOf(uri)) } returns sender
        org.junit.Assert.assertSame(sender, AndroidLyricsStorage(context).writeAccessRequest(path))
        verify(exactly = 1) { MediaStorePermissionHelper.getMediaStoreUri(context, path) }
        verify(exactly = 1) { MediaStorePermissionHelper.createWriteRequestIntentSender(context, listOf(uri)) }
    }

    @Test fun `unindexed sidecar denied sender and failed query all have no request`() {
        mockkObject(MediaStorePermissionHelper)
        every { MediaStorePermissionHelper.getMediaStoreUri(context, path) } returns null
        assertThat(AndroidLyricsStorage(context).writeAccessRequest(path)).isNull()
        verify(exactly = 0) { MediaStorePermissionHelper.createWriteRequestIntentSender(any(), any()) }
        val uri = Uri.parse("content://media/external_primary/file/52")
        every { MediaStorePermissionHelper.getMediaStoreUri(context, path) } returns uri
        every { MediaStorePermissionHelper.createWriteRequestIntentSender(context, listOf(uri)) } returns null
        assertThat(AndroidLyricsStorage(context).writeAccessRequest(path)).isNull()
        every { MediaStorePermissionHelper.getMediaStoreUri(context, path) } throws SecurityException()
        assertThat(AndroidLyricsStorage(context).writeAccessRequest(path)).isNull()
    }
}
