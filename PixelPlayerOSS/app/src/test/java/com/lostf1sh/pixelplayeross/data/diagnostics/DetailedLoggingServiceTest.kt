package com.lostf1sh.pixelplayeross.data.diagnostics

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * B40-F7: the switch, on the platform: the file it makes, what survives a restart of the app, what an expired run leaves, the share
 * sheet. Timber is driven through the tree the application plants.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DetailedLoggingServiceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val made = ArrayList<DetailedLogging>()

    // Robolectric does not carry the app's file provider; the address it would give is not what these tests are about
    private fun logging(): DetailedLogging = DetailedLogging(context).also {
        it.uriFor = { file -> android.net.Uri.parse("content://test.provider/${file.name}") }
        made += it
    }

    @Before
    fun clean() {
        context.getSharedPreferences(DetailedLogging.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, DetailedLogging.DIRECTORY).deleteRecursively()
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
        made.forEach { it.disable() }
        File(context.filesDir, DetailedLogging.DIRECTORY).deleteRecursively()
    }

    private fun fileText(): String {
        val dir = File(context.filesDir, DetailedLogging.DIRECTORY)
        return listOf(RotatingLogFile.OLDER, RotatingLogFile.CURRENT).map { File(dir, it) }.filter { it.isFile }.joinToString("") { it.readText() }
    }

    private fun drain(l: DetailedLogging) {
        // the share path drains what is queued; the tests use it as the way to see the file
        l.shareIntent()
    }

    @Test
    fun `off, nothing is recorded, not even a technical line, and there is nothing to share`() {
        val l = logging()
        Timber.plant(l.tree)
        Timber.tag("DAC_HID").i("RX GET_EQ ok")
        assertThat(l.state.value.on).isFalse()
        assertThat(l.shareIntent()).isNull()
        assertThat(fileText()).isEmpty()
    }

    @Test
    fun `on, the technical lines of the app reach the file, the others do not, and it says when it started`() {
        val l = logging()
        Timber.plant(l.tree)
        l.enable()
        assertThat(l.state.value.on).isTrue()
        assertThat(l.state.value.includeEverything).isFalse()
        Timber.tag("DAC_HID").i("RX GET_EQ ok for ale@example.com")
        Timber.tag("QueueLoad").i("Shuffle \"Mis favoritas\": 120 songs")
        Timber.tag("LyricsRepository").w("no lyrics for Porque Ahora")
        drain(l)
        val text = fileText()
        assertThat(text).contains("DETAILED LOG START")
        assertThat(text).contains("I/DAC_HID: RX GET_EQ ok for <email>")
        assertThat(text).doesNotContain("Mis favoritas")
        assertThat(text).doesNotContain("Porque Ahora")
        assertThat(text).contains("W/LyricsRepository: (text withheld: not a technical tag)")
    }

    @Test
    fun `switched off, it writes its footer and stops taking lines`() {
        val l = logging()
        Timber.plant(l.tree)
        l.enable()
        l.disable()
        Timber.tag("DAC_HID").i("after the end")
        drain(l)
        assertThat(l.state.value.on).isFalse()
        val text = fileText()
        assertThat(text).contains("DETAILED LOG END (switched off)")
        assertThat(text).doesNotContain("after the end")
    }

    @Test
    fun `the second switch is an option of a run and starts off every time`() {
        val l = logging()
        Timber.plant(l.tree)
        l.setIncludeEverything(true)
        assertThat(l.state.value.includeEverything).isFalse()
        l.enable()
        l.setIncludeEverything(true)
        assertThat(l.state.value.includeEverything).isTrue()
        Timber.tag("QueueLoad").i("Shuffle \"Mis favoritas\"")
        drain(l)
        assertThat(fileText()).contains("Shuffle \"Mis favoritas\"")
        l.disable()
        l.enable()
        assertThat(l.state.value.includeEverything).isFalse()
    }

    @Test
    fun `a restart of the app inside the half hour carries on, and the second switch with it`() {
        val first = logging()
        first.enable()
        first.setIncludeEverything(true)
        val until = first.state.value.untilMs
        val second = logging()
        assertThat(second.state.value.on).isTrue()
        assertThat(second.state.value.untilMs).isEqualTo(until)
        assertThat(second.state.value.includeEverything).isTrue()
        drain(second)
        assertThat(fileText()).contains("DETAILED LOG RESUMED")
    }

    @Test
    fun `a restart after the time is up finds it off and leaves the log for sharing`() {
        context.getSharedPreferences(DetailedLogging.PREFS, Context.MODE_PRIVATE).edit()
            .putLong("until_ms", System.currentTimeMillis() - 1_000L).putBoolean("include_everything", true).commit()
        val l = logging()
        assertThat(l.state.value.on).isFalse()
        assertThat(l.state.value.includeEverything).isFalse()
        assertThat(context.getSharedPreferences(DetailedLogging.PREFS, Context.MODE_PRIVATE).getLong("until_ms", -1L)).isEqualTo(0L)
    }

    @Test
    fun `it is on for half an hour from the press`() {
        val l = logging()
        val before = System.currentTimeMillis()
        l.enable()
        val until = l.state.value.untilMs
        assertThat(until - before).isAtLeast(DetailedLogPolicy.DURATION_MS)
        assertThat(until - System.currentTimeMillis()).isAtMost(DetailedLogPolicy.DURATION_MS)
    }

    @Test
    fun `the share sheet carries one text file that says whether titles are in it`() {
        val l = logging()
        Timber.plant(l.tree)
        l.enable()
        Timber.tag("SafetyVolume").i("ceiling step 12")
        val chooser = l.shareIntent()!!
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)).isNotNull()
        val shared = File(context.filesDir, "${DetailedLogging.DIRECTORY}/polylute-detailed-log.txt").readText()
        assertThat(shared).startsWith("POLYLUTE DETAILED LOG")
        assertThat(shared).contains("includes song titles and file paths=false")
        assertThat(shared).contains("ceiling step 12")
    }

    @Test
    fun `delete removes the log and what was made to share, and the state says so`() {
        val l = logging()
        Timber.plant(l.tree)
        l.enable()
        Timber.tag("SafetyVolume").i("ceiling step 12")
        l.shareIntent()
        l.disable()
        assertThat(l.state.value.sizeBytes).isGreaterThan(0L)
        l.delete()
        assertThat(l.state.value.sizeBytes).isEqualTo(0L)
        assertThat(fileText()).isEmpty()
        assertThat(File(context.filesDir, "${DetailedLogging.DIRECTORY}/polylute-detailed-log.txt").exists()).isFalse()
        assertThat(l.shareIntent()).isNull()
    }

    @Test
    fun `a new run starts a new file`() {
        val l = logging()
        Timber.plant(l.tree)
        l.enable()
        Timber.tag("SafetyVolume").i("from the first run")
        drain(l)
        l.disable()
        l.enable()
        drain(l)
        assertThat(fileText()).doesNotContain("from the first run")
    }
}
