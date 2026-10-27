package com.lostf1sh.pixelplayeross.utils

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExternalLinksTest {
    @Test fun `DAP with no browser cannot crash a lyrics search`() {
        val context = mockk<Context>()
        every { context.startActivity(any()) } throws ActivityNotFoundException()
        assertThat(context.tryOpenExternalLink("https://lrclib.net/search/fixture")).isFalse()
    }
    @Test fun `device owner blocking external activities cannot crash a lyrics search`() {
        val context = mockk<Context>()
        every { context.startActivity(any()) } throws SecurityException()
        assertThat(context.tryOpenExternalLink("https://lrclib.net/search/fixture")).isFalse()
    }
    @Test fun `a browser can still open and nonactivity contexts receive a task flag`() {
        val context = mockk<Context>()
        val intent = slot<Intent>()
        every { context.startActivity(capture(intent)) } returns Unit
        assertThat(context.tryOpenExternalLink("https://lrclib.net/search/fixture")).isTrue()
        assertThat(intent.captured.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.captured.data.toString()).isEqualTo("https://lrclib.net/search/fixture")
        assertThat(intent.captured.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }
}
