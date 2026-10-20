package com.lostf1sh.pixelplayeross.data.stats

import android.os.SystemClock
import android.util.AtomicFile
import com.decent.usbaudio.media3.BitPerfectTruth
import com.decent.usbaudio.media3.DirectSignalMode
import com.decent.usbaudio.media3.UsbDirectSinkState
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.service.player.UsbDirectAudioDiagnostics
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ListeningStatsTracker
import io.mockk.coEvery
import io.mockk.EqMatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking

/**
 * The real tracker and the real history file, with the clock under the test's hand (B28a).
 *
 * The tracker is driven with the call shapes `MusicService` uses: `onTrackChanged` for an item transition, `ensureSession` for every
 * other sync, `onSeekWithinItem` for a seek, `onAudioFacts` for the audio. Events are read back from the file the repository wrote,
 * so what a test asserts is what a restart would find.
 *
 * Occurrence ids are `o1`, `o2`... in the order the tracker asks for them, so a test can say which occurrence an event is.
 */
internal class ListenHarness(
    private val identityOfLocator: (String) -> String? = { null },
) : AutoCloseable {
    val dir: File = createTempDirectory("listen-event-${Instant.now().toEpochMilli()}-").toFile()
    val repository: PlaybackStatsRepository
    val tracker: ListeningStatsTracker

    /** What `SystemClock.elapsedRealtime()` answers. */
    var now = 1_000L

    /** Locators the identity lookup was asked about. */
    val lookedUp = mutableListOf<String>()

    init {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { now }
        repository = repositoryOver(dir)
        val dao = mockk<MusicDao>()
        coEvery { dao.libraryItemIdForLocator(any()) } answers {
            val locator = firstArg<Long>().toString()
            lookedUp += locator
            identityOfLocator(locator)
        }
        tracker = ListeningStatsTracker(repository, LibraryIdentityLookup(dao))
        var next = 0
        tracker.newOccurrenceId = { "o${++next}" }
    }

    fun advance(ms: Long) {
        now += ms
    }

    /** An item transition, the way the service reports it. */
    fun transition(
        songId: String,
        reason: TrackEndReason = TrackEndReason.UNKNOWN,
        durationMs: Long = 4 * 60_000L,
        playing: Boolean = true,
        context: ListenContext? = ListenContext(shuffle = false),
        positionMs: Long = 0L,
    ) = tracker.onTrackChanged(
        songId = songId,
        positionMs = positionMs,
        durationMs = durationMs,
        fallbackDurationMs = 0L,
        isPlaying = playing,
        previousEndReason = reason,
        context = context,
    )

    /** Any other sync of the tracker with the player (a play state change, a rebuild, a state callback). */
    fun sync(
        songId: String,
        playing: Boolean,
        positionMs: Long = 0L,
        durationMs: Long = 4 * 60_000L,
        context: ListenContext? = ListenContext(shuffle = false),
    ) = tracker.ensureSession(
        songId = songId,
        positionMs = positionMs,
        durationMs = durationMs,
        fallbackDurationMs = 0L,
        isPlaying = playing,
        context = context,
    )

    /** Closes the session and waits for its event to be on disk; a listen paused for long is written as [events] of them (R28-02). */
    fun finish(reason: TrackEndReason = TrackEndReason.UNKNOWN, events: Int = 1) {
        val before = events().size
        tracker.finalizeCurrentSession(endReason = reason)
        awaitEvents(before + events)
    }

    /**
     * Puts the wall clock the events are stamped with under the test's hand, starting at [startMs]. Until this is called the tracker
     * reads the real clock, which does not move while the test advances [now], so a pause is as long as the test takes to run.
     */
    fun wallClockAt(startMs: Long): WallClock = WallClock(startMs).also { tracker.epochClock = it::read }

    /** The wall clock of [wallClockAt]: [pass] moves it and the monotonic clock together, as time does. */
    inner class WallClock(var epochMs: Long) {
        fun read(): Long = epochMs

        fun pass(ms: Long) {
            advance(ms)
            epochMs += ms
        }
    }

    fun events(): List<PlaybackStatsRepository.PlaybackEvent> = runBlocking { repository.exportEventsForBackup() }

    /** Waits until the identity lookup of the session just opened has run, which it does off the calling thread. */
    fun awaitLookups(count: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (lookedUp.size < count && System.nanoTime() < deadline) Thread.sleep(5)
        check(lookedUp.size >= count) { "expected $count lookups, saw ${lookedUp.size}" }
    }

    /** Waits (in real time: the write is on another thread) until the file holds [count] events. */
    fun awaitEvents(count: Int): List<PlaybackStatsRepository.PlaybackEvent> {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val events = events()
            if (events.size >= count) return events
            Thread.sleep(10)
        }
        throw AssertionError("expected $count events, the file holds ${events().size}")
    }

    override fun close() {
        unmockkStatic(SystemClock::class)
        releaseHistoryFiles()
        dir.deleteRecursively()
    }
}

/**
 * A repository over its own temporary directory.
 *
 * `android.util.AtomicFile` is a stub on the JVM (it opens nothing and writes nothing), so the repository's file is given the
 * plain-file behaviour the platform class has, for this directory only: open to read, start a write, finish it. Call
 * [releaseHistoryFiles] when the test is done.
 */
internal fun newHistoryRepository(): Pair<PlaybackStatsRepository, File> {
    val dir = createTempDirectory("history-${Instant.now().toEpochMilli()}-").toFile()
    return repositoryOver(dir) to dir
}

internal fun repositoryOver(dir: File): PlaybackStatsRepository {
    val file = File(dir, "playback_history.json")
    mockkConstructor(AtomicFile::class)
    every { constructedWith<AtomicFile>(EqMatcher(file)).openRead() } answers { FileInputStream(file) }
    every { constructedWith<AtomicFile>(EqMatcher(file)).startWrite() } answers { FileOutputStream(file) }
    every { constructedWith<AtomicFile>(EqMatcher(file)).finishWrite(any()) } answers { firstArg<FileOutputStream?>()?.close() }
    every { constructedWith<AtomicFile>(EqMatcher(file)).failWrite(any()) } answers { firstArg<FileOutputStream?>()?.close() }
    val context = mockk<android.content.Context>(relaxed = true)
    every { context.filesDir } returns dir
    return PlaybackStatsRepository(context)
}

internal fun releaseHistoryFiles() {
    unmockkConstructor(AtomicFile::class)
}

/** The engine's route state with the DAC carrying the stream in [mode], as the sink publishes it. */
internal fun directRoute(
    mode: DirectSignalMode = DirectSignalMode.PRISTINE,
    bitPerfect: BitPerfectTruth = BitPerfectTruth.PRISTINE_UNVERIFIED,
    rateHz: Int? = 96_000,
    deviceName: String? = "SNOWSKY Melody",
) = AudioRouteState(
    desiredBackend = AudioBackend.USB_DIRECT,
    actualBackend = AudioBackend.USB_DIRECT,
    transition = AudioRouteTransition.DIRECT_ACTIVE,
    reason = if (mode == DirectSignalMode.PROCESSED) AudioRouteReason.DIRECT_PROCESSED else AudioRouteReason.DIRECT_PRISTINE,
    deviceName = deviceName,
    usbDacPresent = true,
    permissionGranted = true,
    confirmedOutputRateHz = rateHz,
    signalMode = mode,
    bitPerfect = bitPerfect,
)

/** The Direct sink's diagnostics for a 24-bit FLAC at 96 kHz, as `publishUsbDiagnostics` fills them. */
internal fun directDiagnostics(
    mode: DirectSignalMode = DirectSignalMode.PRISTINE,
    bitPerfect: BitPerfectTruth = BitPerfectTruth.PRISTINE_UNVERIFIED,
    rateHz: Int? = 96_000,
    bits: Int? = 24,
    codec: String? = "audio/flac",
) = UsbDirectAudioDiagnostics(
    state = UsbDirectSinkState.USB_DIRECT_ACTIVE,
    deviceName = "SNOWSKY Melody",
    permissionGranted = true,
    sourceSampleRate = rateHz,
    sourceBitDepth = bits,
    sourceCodec = codec,
    signalMode = mode,
    bitPerfect = bitPerfect,
    transportBitDepth = bits,
)

/** The route state of an Android stream: the app is not driving the DAC. */
internal fun androidRoute(bitPerfect: BitPerfectTruth = BitPerfectTruth.NOT_DIRECT) = AudioRouteState(
    desiredBackend = AudioBackend.ANDROID_AUDIO,
    actualBackend = AudioBackend.ANDROID_AUDIO,
    transition = AudioRouteTransition.ANDROID_ACTIVE,
    reason = AudioRouteReason.NO_USB_DAC,
    usbDacPresent = false,
    bitPerfect = bitPerfect,
)
