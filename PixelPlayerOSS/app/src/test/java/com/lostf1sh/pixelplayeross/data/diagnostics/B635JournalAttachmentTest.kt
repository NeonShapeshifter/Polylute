package com.lostf1sh.pixelplayeross.data.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B635JournalAttachmentTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val journal = "USB JOURNAL v1\n-- previous.jsonl --\n" +
        "{\"seq\":41,\"kind\":\"dac-locked\",\"what\":\"no-readings\"}\n" +
        "-- current.jsonl --\n{\"seq\":42,\"kind\":\"dac-recovery\",\"what\":\"step 3 ok\"}\n"
    private var logging: DetailedLogging? = null

    @Before fun prepare() {
        context.getSharedPreferences(DetailedLogging.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, DetailedLogging.DIRECTORY).deleteRecursively()
        File(context.filesDir, NoiseMark.DIRECTORY).deleteRecursively()
    }
    @After fun cleanup() {
        logging?.disable()
        File(context.filesDir, DetailedLogging.DIRECTORY).deleteRecursively()
        File(context.filesDir, NoiseMark.DIRECTORY).deleteRecursively()
    }

    @Test fun `noise mark freezes the complete readable journal in its existing attachment`() {
        // The compatibility fallback lets this same behavioural test run against the before tree.
        val method = NoiseMark::class.java.declaredMethods.firstOrNull {
            it.name.startsWith("markWithJournal") && it.parameterCount == 3
        }
        val file = if (method != null) {
            method.isAccessible = true
            method.invoke(NoiseMark, context, 128, { journal }) as File?
        } else NoiseMark.mark(context)
        assertThat(file).isNotNull()
        val text = file!!.readText()
        assertThat(text).startsWith("NOISE MARK\n")
        assertThat(text).contains(journal)
        assertThat(file.parentFile).isEqualTo(File(context.filesDir, NoiseMark.DIRECTORY))
    }

    @Test fun `the existing diagnostics share file also carries both journal generations`() {
        val l = DetailedLogging(context).also { logging = it }
        l.uriFor = { android.net.Uri.parse("content://test.provider/${it.name}") }
        runCatching {
            DetailedLogging::class.java.getDeclaredField("journalReader").apply { isAccessible = true }.set(l, { journal })
        }
        l.enable()
        assertThat(l.shareIntent()).isNotNull()
        val file = File(File(context.filesDir, DetailedLogging.DIRECTORY), "polylute-detailed-log.txt")
        val text = file.readText()
        assertThat(text).startsWith("POLYLUTE DETAILED LOG\n")
        assertThat(text).contains("includes song titles and file paths=false")
        assertThat(text).contains(journal)
    }
}
