package com.lostf1sh.pixelplayeross.data.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * B40-fix1 (item 6): the log that is shared tells the truth about what is in it. Switching "Include song titles and file paths" off does
 * not take the lines written while it was on out of the file, so the header (and Maintenance) go by the files: each remembers, on disk,
 * whether any line in it was written with titles and paths allowed. The mark goes with the file: Delete log, a new run, and the file
 * rotating out clear it, and a restart of the app keeps it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DetailedLogTitlesMarkerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val made = ArrayList<DetailedLogging>()

    private fun logging(): DetailedLogging = DetailedLogging(context).also {
        it.uriFor = { file -> android.net.Uri.parse("content://test.provider/${file.name}") }
        made += it
    }

    private val directory get() = File(context.filesDir, DetailedLogging.DIRECTORY)

    @Before
    fun clean() {
        context.getSharedPreferences(DetailedLogging.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        directory.deleteRecursively()
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
        made.forEach { it.disable() }
        directory.deleteRecursively()
    }

    private fun shared(l: DetailedLogging): String {
        assertThat(l.shareIntent()).isNotNull()
        return File(directory, "polylute-detailed-log.txt").readText()
    }

    private fun privateLine() = Timber.tag("QueueLoad").i("Opened /storage/emulated/0/Music/Private Artist/private-fixture.flac")

    @Test
    fun `on, a line with a path, off, share - the header says the file includes titles and paths, and the line is there`() {
        val l = logging()
        Timber.plant(l.tree)
        l.enable()
        l.setIncludeEverything(true)
        privateLine()
        l.setIncludeEverything(false)
        val text = shared(l)
        assertThat(text).contains("private-fixture.flac")
        assertThat(text).contains("includes song titles and file paths=true")
        assertThat(text).contains("titles and paths switch now=off")
        assertThat(l.state.value.holdsTitlesAndPaths).isTrue()
    }

    @Test
    fun `a log that never had the switch on says it does not include them`() {
        val l = logging()
        Timber.plant(l.tree)
        l.enable()
        Timber.tag("DAC_HID").i("RX GET_EQ ok")
        privateLine()
        val text = shared(l)
        assertThat(text).contains("includes song titles and file paths=false")
        assertThat(text).doesNotContain("private-fixture.flac")
        assertThat(l.state.value.holdsTitlesAndPaths).isFalse()
    }

    @Test
    fun `the switch being on but nothing written with it is not a claim about the file`() {
        val l = logging()
        Timber.plant(l.tree)
        l.enable()
        Timber.tag("DAC_HID").i("RX GET_EQ ok")
        l.shareIntent()                       // everything so far was written before the switch
        l.setIncludeEverything(true)
        // the note that the switch changed is itself written with it on, which is the honest direction to err in
        assertThat(shared(l)).contains("titles and paths switch now=on")
    }

    @Test
    fun `Delete log clears it`() {
        val l = logging()
        Timber.plant(l.tree)
        l.enable()
        l.setIncludeEverything(true)
        privateLine()
        l.setIncludeEverything(false)
        shared(l)
        assertThat(l.state.value.holdsTitlesAndPaths).isTrue()
        l.delete()
        assertThat(l.state.value.holdsTitlesAndPaths).isFalse()
        assertThat(l.shareIntent()).isNull()
        Timber.tag("DAC_HID").i("RX GET_EQ ok")
        assertThat(shared(l)).contains("includes song titles and file paths=false")
    }

    @Test
    fun `a new run starts a new file, and the mark of the old one is gone`() {
        val l = logging()
        Timber.plant(l.tree)
        l.enable()
        l.setIncludeEverything(true)
        privateLine()
        shared(l)
        l.disable()
        assertThat(l.state.value.holdsTitlesAndPaths).isTrue()   // switching logging off does not take the lines out
        l.enable()
        assertThat(l.state.value.holdsTitlesAndPaths).isFalse()
        Timber.tag("DAC_HID").i("RX GET_EQ ok")
        assertThat(shared(l)).contains("includes song titles and file paths=false")
    }

    @Test
    fun `a restart of the app keeps what the files hold`() {
        val first = logging()
        Timber.plant(first.tree)
        first.enable()
        first.setIncludeEverything(true)
        privateLine()
        first.shareIntent()
        first.disable()
        Timber.uprootAll()
        // a new process: a new object over the same files
        val second = logging()
        assertThat(second.state.value.holdsTitlesAndPaths).isTrue()
        assertThat(shared(second)).contains("includes song titles and file paths=true")
    }

    // ── the file itself ──────────────────────────────────────────────────────────────────────────

    private val folder: File = Files.createTempDirectory("rotating-marks").toFile()

    @After
    fun removeFolder() { folder.deleteRecursively() }

    private fun fill(file: RotatingLogFile, count: Int, sensitive: Boolean) =
        file.append(List(count) { "x".repeat(40) }, sensitive = sensitive)

    @Test
    fun `the mark moves with the file when it rotates and goes when the file is dropped`() {
        val file = RotatingLogFile(folder, maxBytes = 600)
        fill(file, 10, sensitive = true)
        assertThat(file.holdsTitlesAndPaths()).isTrue()
        // the next lines fill the file and rotate it: the sensitive lines are now in the older file, still on the phone
        fill(file, 8, sensitive = false)
        assertThat(File(folder, RotatingLogFile.OLDER).isFile).isTrue()
        assertThat(file.holdsTitlesAndPaths()).isTrue()
        // another rotation drops the older file, and the mark with it
        fill(file, 40, sensitive = false)
        assertThat(file.holdsTitlesAndPaths()).isFalse()
        assertThat(folder.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".titles") }).isEmpty()
    }

    @Test
    fun `lines written without the allowance never mark a file, and clear removes the marks`() {
        val file = RotatingLogFile(folder, maxBytes = 10_000)
        fill(file, 5, sensitive = false)
        assertThat(file.holdsTitlesAndPaths()).isFalse()
        fill(file, 1, sensitive = true)
        assertThat(file.holdsTitlesAndPaths()).isTrue()
        file.clear()
        assertThat(file.holdsTitlesAndPaths()).isFalse()
        assertThat(folder.listFiles().orEmpty().toList()).isEmpty()
    }
}
