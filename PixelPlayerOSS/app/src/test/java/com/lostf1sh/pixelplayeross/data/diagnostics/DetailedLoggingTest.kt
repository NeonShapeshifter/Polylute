package com.lostf1sh.pixelplayeross.data.diagnostics

import android.util.Log
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B40-F7: the detailed log's rules, with no phone: what is scrubbed, what is written, how the two files rotate, when it stops.
 */
class DetailedLoggingTest {
    private val directory: File = Files.createTempDirectory("detailed-log-test").toFile()
    private var now = 1_000_000L
    private val clock: () -> Long = { now }

    @AfterEach
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun recorder(maxFileBytes: Long = DetailedLogPolicy.MAX_FILE_BYTES, queue: Int = DetailedLogPolicy.QUEUE_CAPACITY) =
        DetailedLogRecorder(directory, clock, maxFileBytes, queue)

    private fun DetailedLogRecorder.written(): String {
        drainNow()
        return logFile.readAll()
    }

    // -- the scrubber --

    @Test
    fun `an e-mail address, a secret, a serial number and an account name never stay in a line`() {
        val scrubbed = LogScrubber.scrub(
            "mail ale@example.com token=abc123 password: \"hunter two\" serial=SN12345 android_id=9774d56d682e549c " +
                "Account {name=ale.person@gmail.com, type=com.google} Authorization: Bearer xyz",
            includeEverything = true,
        )
        assertThat(scrubbed).doesNotContain("example.com")
        assertThat(scrubbed).doesNotContain("abc123")
        assertThat(scrubbed).doesNotContain("hunter")
        assertThat(scrubbed).doesNotContain("SN12345")
        assertThat(scrubbed).doesNotContain("9774d56d682e549c")
        assertThat(scrubbed).doesNotContain("gmail.com")
        assertThat(scrubbed).doesNotContain("xyz")
        assertThat(scrubbed).contains("<email>")
        assertThat(scrubbed).contains("token=<hidden>")
    }

    @Test
    fun `addresses, paths into the user's storage and file names are replaced unless titles and paths were asked for`() {
        val line = "opened content://media/external/audio/media/4814 from /storage/emulated/0/Music/Mägo de Oz/Gaia/01 Porque Ahora.flac " +
            "and file:///sdcard/Download/x.lrc via https://lrclib.net/api/get?artist_name=Foo&track_name=Bar cover Porque Ahora.jpg"
        val plain = LogScrubber.scrub(line, includeEverything = false)
        assertThat(plain).doesNotContain("content://")
        assertThat(plain).doesNotContain("/storage")
        assertThat(plain).doesNotContain("Mägo")
        assertThat(plain).doesNotContain("Porque Ahora")
        assertThat(plain).doesNotContain("lrclib")
        assertThat(plain).doesNotContain("file://")
        assertThat(plain).contains("<address>")
        assertThat(plain).contains("<path>")

        val everything = LogScrubber.scrub(line, includeEverything = true)
        assertThat(everything).contains("/storage/emulated/0/Music/Mägo de Oz/Gaia/01 Porque Ahora.flac")
        assertThat(everything).contains("content://media/external/audio/media/4814")
    }

    @Test
    fun `what the DAC conversation and the app's own storage look like is kept`() {
        val line = "DAC_HID|TX GET_EQ ret=64 [BB 0B 00 12 1A 00 3F EE] /dev/bus/usb/001/002 /data/user/0/app.polylute/files/noise-marks/noise-20261025-101010.txt " +
            "0BDA:0316 Fosi DS2 rate=96000 Hz"
        val scrubbed = LogScrubber.scrub(line, includeEverything = false)
        assertThat(scrubbed).isEqualTo(line)
    }

    @Test
    fun `a unit's serial number at the end of a USB label comes off`() {
        assertThat(LogScrubber.withoutBracketedSerial("0BDA:0316 Fosi DS2 [S12345678]")).isEqualTo("0BDA:0316 Fosi DS2")
        assertThat(LogScrubber.withoutBracketedSerial("0BDA:0316 Fosi DS2")).isEqualTo("0BDA:0316 Fosi DS2")
    }

    @Test
    fun `a long unbroken key is not written`() {
        val key = "A".repeat(20) + "b".repeat(20) + "9"
        assertThat(LogScrubber.scrub("key $key end", includeEverything = true)).isEqualTo("key <token> end")
    }

    // -- what is written, and when --

    @Test
    fun `off, nothing is accepted and nothing is written`() {
        val r = recorder()
        assertThat(r.isOn).isFalse()
        assertThat(r.accepts("DAC_HID", Log.INFO)).isFalse()
        r.offer("DAC_HID", Log.INFO, "a line")
        assertThat(r.written()).isEmpty()
    }

    @Test
    fun `on, a technical tag is written scrubbed, with its level and a time`() {
        val r = recorder()
        r.start(until = now + 60_000, includeEverything = false)
        assertThat(r.accepts("DAC_HID", Log.INFO)).isTrue()
        r.offer("DAC_HID", Log.INFO, "RX GET_EQ ok data=[01 02] for ale@example.com")
        val text = r.written()
        assertThat(text).contains("I/DAC_HID: RX GET_EQ ok data=[01 02] for <email>")
        assertThat(text).doesNotContain("example.com")
        assertThat(text).matches(Regex("""(?s)\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} I/DAC_HID: .*\n""").toPattern())
    }

    @Test
    fun `a line from another tag is not accepted, and a warning from it is noted with its text withheld`() {
        val r = recorder()
        r.start(until = now + 60_000, includeEverything = false)
        assertThat(r.accepts("QueueLoad", Log.INFO)).isFalse()
        assertThat(r.accepts("MusicInfo", Log.DEBUG)).isFalse()
        assertThat(r.accepts("LyricsRepository", Log.WARN)).isTrue()
        r.offer("LyricsRepository", Log.WARN, "no lyrics for \"Porque Ahora\" by Mägo de Oz")
        val text = r.written()
        assertThat(text).contains("W/LyricsRepository: (text withheld: not a technical tag)")
        assertThat(text).doesNotContain("Porque")
        assertThat(text).doesNotContain("Mägo")
    }

    @Test
    fun `with titles and paths included every tag is written, and paths stay`() {
        val r = recorder()
        r.start(until = now + 60_000, includeEverything = true)
        assertThat(r.accepts("QueueLoad", Log.INFO)).isTrue()
        r.offer("QueueLoad", Log.INFO, "Shuffle \"Mis favoritas\": /storage/emulated/0/Music/a.flac ale@example.com")
        val text = r.written()
        assertThat(text).contains("Shuffle \"Mis favoritas\": /storage/emulated/0/Music/a.flac <email>")
    }

    @Test
    fun `it stops by itself when the time is up`() {
        val r = recorder()
        r.start(until = now + 60_000, includeEverything = false)
        assertThat(r.isOn).isTrue()
        now += 59_999
        assertThat(r.isOn).isTrue()
        now += 2
        assertThat(r.isOn).isFalse()
        assertThat(r.accepts("DAC_HID", Log.INFO)).isFalse()
        r.offer("DAC_HID", Log.INFO, "too late")
        assertThat(r.written()).doesNotContain("too late")
    }

    @Test
    fun `its own footer is written after it has stopped`() {
        val r = recorder()
        r.start(until = now + 60_000, includeEverything = false)
        r.offer("DAC_HID", Log.INFO, "before")
        r.note("DetailedLog", "DETAILED LOG END (switched off)")
        r.stop()
        val text = r.written()
        assertThat(text).contains("before")
        assertThat(text).contains("DETAILED LOG END (switched off)")
    }

    @Test
    fun `a failure is written with its class, its message and the top of its stack, scrubbed`() {
        val r = recorder()
        r.start(until = now + 60_000, includeEverything = false)
        r.offer("UsbAudioSink", Log.ERROR, "write failed", IllegalStateException("device /storage/emulated/0/Music/x.flac gone"))
        val text = r.written()
        assertThat(text).contains("E/UsbAudioSink: write failed")
        assertThat(text).contains("java.lang.IllegalStateException: device <path> gone")
        assertThat(text).contains("    at ")
        assertThat(text).doesNotContain("x.flac")
    }

    @Test
    fun `a line is cut at the limit`() {
        val r = recorder()
        r.start(until = now + 60_000, includeEverything = false)
        r.offer("DAC_HID", Log.INFO, "x".repeat(5_000))
        val line = r.written().lines().first { it.isNotBlank() }
        // the time and the newline are not part of the cap
        assertThat(line.length).isAtMost(DetailedLogPolicy.MAX_LINE_CHARS + 20)
    }

    @Test
    fun `when the app logs faster than the file takes the lines are dropped and the count is written`() {
        val r = recorder(queue = 3)
        r.start(until = now + 60_000, includeEverything = false)
        repeat(10) { r.offer("DAC_HID", Log.INFO, "line $it") }
        val text = r.written()
        assertThat(text).contains("line 0")
        assertThat(text).contains("line 2")
        assertThat(text).doesNotContain("line 3")
        assertThat(text).contains("7 line(s) dropped")
    }

    // -- the two files --

    @Test
    fun `the two files rotate, the older is dropped, the order is kept and the size is bounded`() {
        val file = RotatingLogFile(directory, maxBytes = 100)
        val lines = (0 until 30).map { "line number %02d xxxxxxxxxx".format(it) }
        lines.forEach { file.append(listOf(it)) }
        val all = file.readAll().lines().filter { it.isNotBlank() }
        // the newest are kept, in order, and the oldest are gone
        assertThat(all.last()).isEqualTo(lines.last())
        assertThat(all).isInStrictOrder()
        assertThat(all.first()).isNotEqualTo(lines.first())
        assertThat(file.sizeBytes()).isAtMost(200L)
        assertThat(directory.listFiles()!!.map { it.name }).containsExactly(RotatingLogFile.CURRENT, RotatingLogFile.OLDER)
    }

    @Test
    fun `a batch bigger than one file rotates inside the batch`() {
        val file = RotatingLogFile(directory, maxBytes = 100)
        file.append((0 until 40).map { "batch line %02d yyyyyyyyyy".format(it) })
        assertThat(file.sizeBytes()).isAtMost(200L)
        assertThat(file.readAll()).contains("batch line 39")
    }

    @Test
    fun `clear removes both files`() {
        val file = RotatingLogFile(directory, maxBytes = 50)
        repeat(10) { file.append(listOf("a line that is long enough $it")) }
        assertThat(file.hasContent()).isTrue()
        file.clear()
        assertThat(file.hasContent()).isFalse()
        assertThat(file.readAll()).isEmpty()
    }

    // -- the policy --

    @Test
    fun `it is on for half an hour and the time left is never negative`() {
        assertThat(DetailedLogPolicy.DURATION_MS).isEqualTo(30L * 60_000L)
        assertThat(DetailedLogPolicy.remainingMs(untilMs = 5_000, nowMs = 2_000)).isEqualTo(3_000L)
        assertThat(DetailedLogPolicy.remainingMs(untilMs = 5_000, nowMs = 9_000)).isEqualTo(0L)
        assertThat(DetailedLogPolicy.isOn(untilMs = 5_000, nowMs = 4_999)).isTrue()
        assertThat(DetailedLogPolicy.isOn(untilMs = 5_000, nowMs = 5_000)).isFalse()
    }

    @Test
    fun `the technical list holds the machinery and none of the tags that carry media`() {
        val tags = DetailedLogPolicy.TECHNICAL_TAGS
        assertThat(tags).containsAtLeast("DAC_HID", "SafetyVolume", "DapContainment", "UsbAudioSink", "Hardware", "RouteTrace", "Sampler")
        // lines about queues, playlists, lyrics, covers, metadata, searches and the network name songs, artists and folders
        assertThat(tags).containsNoneOf(
            "QueueLoad", "PlayerViewModel", "PlaylistViewModel", "PlaylistVM", "LyricsRepository", "MusicInfo", "ArtistImageRepository",
            "MediaStoreSongRepository", "SongMetadataEditor", "AudioMetadataReader", "AlbumArtCacheManager", "SearchScreen", "TransitionDebug",
            "MusicService", "MusicService_PixelPlayer", "PlaybackStateHolder", "ShuffleAll", "ShuffleDebug", "SongChange", "ArtistDebug", "AlbumDebug",
        )
    }
}
