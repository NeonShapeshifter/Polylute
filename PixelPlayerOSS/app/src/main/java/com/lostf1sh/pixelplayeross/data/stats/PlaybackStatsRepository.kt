package com.lostf1sh.pixelplayeross.data.stats

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The listening history: `playback_history.json`, one [PlaybackEvent] per finished listen.
 *
 * Since B28 this class is the **store and nothing else**. What the history means (which track an event is, what a play is,
 * every ranking) is decided from the events by `ListeningLedger` and `StatsEngine`; this class keeps them safe and cheap
 * to write.
 *
 * ## The file
 *
 * A JSON array of events, exactly the format it has always had: an old event stays readable and is never rewritten to
 * "fix" it, and the backup and the R8 keep rules (B28a) are about the same shape.
 *
 * ## Writing an event costs one event
 *
 * It used to read, parse, serialise and `fsync` the whole file at every track end, which with the 400-500 byte events of
 * B28a is a second of CPU per track at two years of history (27 MB). An event is now **appended in place**: the closing
 * `]` is overwritten by `,{event}]` and the file is synced. The file is a whole array before and after, and a process that
 * dies in the middle leaves one whose tail is torn; [PlaybackEventJson] reads up to the tear and the next write rewrites
 * the file whole (through [AtomicFile]) instead of appending to it. A rewrite is also what imports, a restore and the
 * pruning of events older than [MAX_HISTORY_AGE_MS] do; none of them happens per event.
 */
@Singleton
class PlaybackStatsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val gson = Gson()

    private val historyFile = File(context.filesDir, "playback_history.json")
    private val atomicHistoryFile = AtomicFile(historyFile)

    /** Guards the file and everything below it. */
    private val fileLock = Any()

    /**
     * Everything that adds to or replaces the history goes through this one at a time, and so does [withHistoryIdentities]: a caller
     * that read what the history names and is about to act on it (R28-01) holds it, so no listen is written in between.
     */
    private val writeGate = Mutex()
    private var cache: ArrayList<PlaybackEvent>? = null

    /** Bumped by every change that is not an append (a rewrite, an import, a restore), so a reader can tell it must start over. */
    private var epoch = 1L

    /** The file's tail was torn when it was read: the next write rewrites it whole. */
    private var needsRewrite = false

    private val _refreshVersion = MutableStateFlow(0L)

    /** A counter that moves whenever the history changed, for the observers that rebuild from it. */
    val refreshFlow: StateFlow<Long> = _refreshVersion.asStateFlow()

    /**
     * One finished listen.
     *
     * [libraryItemId] is the **authoritative** identity of the track this event is about; [songId]
     * is the MediaStore locator that happened to be current when it was recorded, kept as
     * contextual/debug metadata because it is what the older consumers and the reading tools
     * understand.
     *
     * It is nullable, and that is the honest encoding rather than a convenience. Events written
     * before durable identities existed do not contain it, and a history file is a wire format — so
     * a reader has to tolerate a field that was not there when the data was written. Null means "not
     * recorded", never "unknown track".
     *
     * Since B28a every event that ends also says which occurrence of the track it belongs to, how it ended and
     * what audio carried it. All of that is additive and nullable like [libraryItemId]: an event written before
     * it has none of it, and it is never filled in afterwards.
     *
     * One occurrence (a play of a track, from its start to the listener leaving it) can reach the history as more
     * than one event, so [occurrenceId] is what ties the pieces together; two events with the same id are one play.
     *
     * @property occurrenceId one id per occurrence. A new item, a repeat-one loop and a replay from the start each
     *   get a new one; a pause, a focus or output loss, a seek inside the item and a player rebuild keep it.
     * @property outcome what the classifier made of the listen (the same value `song_engagements` moved by).
     * @property endReason why it ended, as the classifier was told.
     * @property itemDurationMs the track's length when the listen ended, 0 or less never recorded.
     * @property audio the route, signal and source facts that carried it ([ListenAudioFacts]).
     * @property context where the play came from and whether shuffle was on ([ListenContext]).
     */
    data class PlaybackEvent(
        val songId: String,
        val timestamp: Long,
        val durationMs: Long,
        val startTimestamp: Long? = null,
        val endTimestamp: Long? = null,
        val libraryItemId: String? = null,
        val occurrenceId: String? = null,
        val outcome: PlaybackOutcome? = null,
        val endReason: TrackEndReason? = null,
        val itemDurationMs: Long? = null,
        val audio: ListenAudioFacts? = null,
        val context: ListenContext? = null,
    )

    /**
     * A history entry as the UI reads it.
     *
     * [identityResolvedFromLocator] is true when the event carried no identity and it was derived
     * from the locator the event recorded. That derivation is only sound while the locator still
     * points at the same track, which is exactly what a renumber breaks — so callers that care about
     * identity can tell a recorded fact from a resolved guess instead of the two being
     * indistinguishable.
     */
    data class PlaybackHistoryEntry(
        val songId: String,
        val timestamp: Long,
        val libraryItemId: String? = null,
        val identityResolvedFromLocator: Boolean = false
    )

    /**
     * What a reader that already holds the first [count] events of [epoch] needs to catch up.
     *
     * [replaced] is true when the history changed in some way other than events being added at the end: [events] is then the
     * whole history and the reader starts over. Otherwise [events] are the ones after the reader's last.
     */
    class HistoryDelta(val epoch: Long, val replaced: Boolean, val events: List<PlaybackEvent>)

    suspend fun recordPlayback(
        songId: String,
        durationMs: Long,
        timestamp: Long = System.currentTimeMillis(),
        libraryItemId: String? = null,
        occurrenceId: String? = null,
        outcome: PlaybackOutcome? = null,
        endReason: TrackEndReason? = null,
        itemDurationMs: Long? = null,
        audio: ListenAudioFacts? = null,
        context: ListenContext? = null,
    ) = withContext(Dispatchers.IO) {
        if (songId.isBlank()) return@withContext
        val coercedTimestamp = timestamp.coerceAtLeast(0L)
        val coercedDuration = durationMs.coerceAtLeast(0L)
        val start = (coercedTimestamp - coercedDuration).coerceAtLeast(0L)
        val sanitizedEvent = sanitizeEvent(
            PlaybackEvent(
                songId = songId,
                timestamp = coercedTimestamp,
                durationMs = coercedDuration,
                startTimestamp = start,
                endTimestamp = coercedTimestamp,
                libraryItemId = libraryItemId?.takeIf { it.isNotBlank() },
                occurrenceId = occurrenceId,
                outcome = outcome,
                endReason = endReason,
                itemDurationMs = itemDurationMs,
                audio = audio,
                context = context,
            )
        )
        val written = writeGate.withLock { synchronized(fileLock) { appendLocked(sanitizedEvent) } }
        if (written) {
            notifyStatsChanged()
        }
    }

    /**
     * Runs [block] with the identities of the library items the history names, while no listen can be written to it.
     *
     * For what removes library items: an item that an event names is the only way that event reaches its track again when the file
     * comes back, so it must outlive the events (R28-01). The set is the file's as of now and stays true for the whole [block],
     * because the writers wait for it. It does not hold a listen that has not reached the repository yet (the one in progress); the
     * listening tracker answers for those.
     */
    suspend fun <T> withHistoryIdentities(block: suspend (Set<String>) -> T): T = writeGate.withLock {
        val identities = withContext(Dispatchers.IO) {
            synchronized(fileLock) { loadLocked().mapNotNullTo(HashSet()) { it.libraryItemId } }
        }
        block(identities)
    }

    /**
     * The events after the first [count] of [knownEpoch], or all of them when the history was replaced since.
     * Reads the file once per process; after that it is served from memory and an appended event is one list entry.
     */
    suspend fun readSince(knownEpoch: Long, count: Int): HistoryDelta = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            val events = loadLocked()
            if (knownEpoch == epoch && count in 0..events.size) {
                HistoryDelta(epoch, replaced = false, events = ArrayList(events.subList(count, events.size)))
            } else {
                HistoryDelta(epoch, replaced = true, events = ArrayList(events))
            }
        }
    }

    suspend fun exportEventsForBackup(): List<PlaybackEvent> = withContext(Dispatchers.IO) {
        synchronized(fileLock) { ArrayList(loadLocked()) }.map { event -> sanitizeEvent(event) }
    }

    /**
     * The most recent listens, newest first.
     *
     * [resolveIdentity] is how a legacy entry — one written before identities were recorded — gets
     * an owner. It is a function rather than a database dependency so that the repository stays
     * testable, and so that *who* resolves the locator is an explicit decision at the call site.
     *
     * It is deliberately **not** applied to events that already carry an identity: a fact that was
     * recorded must never be overwritten by one that was inferred.
     */
    suspend fun loadPlaybackHistory(
        limit: Int = DEFAULT_PLAYBACK_HISTORY_LIMIT,
        resolveIdentity: (suspend (locator: String) -> String?)? = null,
    ): List<PlaybackHistoryEntry> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val safeLimit = limit.coerceAtMost(MAX_PLAYBACK_HISTORY_LIMIT)

        // Resolved once per distinct locator rather than per event: a listener's history is mostly
        // repeats of the same handful of tracks, and this is a query per distinct locator.
        val resolvedCache = mutableMapOf<String, String?>()

        val newestFirst = synchronized(fileLock) {
            val events = loadLocked()
            // The file is in the order it was written, which is the order the listens ended, so the newest are at its end.
            events.sortedByDescending { event -> event.timestamp }.take(safeLimit)
        }
        newestFirst.map { event ->
            val recorded = event.libraryItemId?.takeIf { it.isNotBlank() }
            val inferredOwner = if (recorded == null && resolveIdentity != null) {
                resolvedCache.getOrPut(event.songId) { resolveIdentity(event.songId) }
            } else {
                null
            }
            PlaybackHistoryEntry(
                songId = event.songId,
                timestamp = event.timestamp.coerceAtLeast(0L),
                libraryItemId = recorded ?: inferredOwner,
                identityResolvedFromLocator = recorded == null && inferredOwner != null,
            )
        }
    }

    /**
     * Replaces (or merges into) the history with [events], from a backup or from a restore's own rollback.
     *
     * B35 (RT-03): a write the file system refused is **raised** as [PlaybackHistoryWriteException]. It used to be logged and
     * dropped, so a restore whose history was never written reported success while the old history stayed. The previous file
     * is intact in that case ([AtomicFile] keeps it), and so is the cache. Ordinary listening writes keep their own policy.
     */
    suspend fun importEventsFromBackup(
        events: List<PlaybackEvent>,
        clearExisting: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val written = writeGate.withLock {
            synchronized(fileLock) {
                val base = if (clearExisting) emptyList() else loadLocked()
                val merged = (base + events)
                    .map { event -> sanitizeEvent(event) }
                    .distinctBy { event ->
                        "${event.songId}:${event.startMillis()}:${event.endMillis()}:${event.durationMs}"
                    }
                    .sortedBy { event -> event.timestamp }
                rewriteLocked(merged)
            }
        }
        if (!written) throw PlaybackHistoryWriteException()
        notifyStatsChanged()
    }

    fun requestRefresh() {
        notifyStatsChanged()
    }

    // --- the file, under [fileLock] ------------------------------------------------------------------

    private fun loadLocked(): ArrayList<PlaybackEvent> {
        cache?.let { return it }
        val result = runCatching { atomicHistoryFile.openRead().use(PlaybackEventJson::read) }
            .onFailure { throwable ->
                if (throwable !is FileNotFoundException) {
                    Timber.e(throwable, "Failed reading playback history")
                }
            }
            .getOrNull()
        val events = result?.events?.mapTo(ArrayList(result.events.size)) { sanitizeEvent(it) } ?: ArrayList()
        if (result != null && !result.intact) {
            Timber.w("Playback history ended before its closing bracket; %d events recovered, it is rewritten on the next write", events.size)
            needsRewrite = true
        }
        cache = events
        return events
    }

    /** Adds [event] to the file and to the cache: one event's worth of bytes, unless the file has to be rewritten whole. */
    private fun appendLocked(event: PlaybackEvent): Boolean {
        val events = loadLocked()
        val cutoff = event.endMillis() - MAX_HISTORY_AGE_MS
        val oldestIsExpired = cutoff > 0 && events.isNotEmpty() && events.first().endMillis() < cutoff
        if (!needsRewrite && !oldestIsExpired && appendBytesLocked(event)) {
            events += event
            return true
        }
        val kept = if (cutoff > 0) events.filter { it.endMillis() >= cutoff } else events
        return rewriteLocked(kept + event)
    }

    /**
     * Overwrites the closing `]` with `,{event}]` (or `{event}]` for an empty array) and syncs. False when the file does not end
     * in the bracket of a whole array, which is the caller's cue to rewrite it.
     */
    private fun appendBytesLocked(event: PlaybackEvent): Boolean {
        val json = gson.toJson(event).toByteArray(Charsets.UTF_8)
        return try {
            historyFile.parentFile?.takeIf { !it.exists() }?.mkdirs()
            RandomAccessFile(historyFile, "rw").use { file ->
                val length = file.length()
                if (length == 0L) {
                    file.write(byteArrayOf('['.code.toByte()) + json + byteArrayOf(']'.code.toByte()))
                } else {
                    val tailSize = min(length, TAIL_BYTES.toLong()).toInt()
                    val tail = ByteArray(tailSize)
                    file.seek(length - tailSize)
                    file.readFully(tail)
                    var close = tailSize - 1
                    while (close >= 0 && tail[close].isWhitespace()) close--
                    if (close < 0 || tail[close] != ']'.code.toByte()) return false
                    var before = close - 1
                    while (before >= 0 && tail[before].isWhitespace()) before--
                    // `[` means an empty array. An array too short to show what is before its bracket is not one this wrote.
                    if (before < 0) return false
                    val comma = tail[before] != '['.code.toByte()
                    file.seek(length - tailSize + close)
                    val prefix = if (comma) byteArrayOf(','.code.toByte()) else ByteArray(0)
                    file.write(prefix + json + byteArrayOf(']'.code.toByte()))
                }
                file.fd.sync()
            }
            true
        } catch (e: IOException) {
            Timber.e(e, "Failed to append to the playback history")
            false
        }
    }

    private fun Byte.isWhitespace() = this == ' '.code.toByte() || this == '\n'.code.toByte() ||
        this == '\r'.code.toByte() || this == '\t'.code.toByte()

    /** Writes [events] as the whole file, atomically, and makes them the cache. Starts every reader over. */
    private fun rewriteLocked(events: List<PlaybackEvent>): Boolean {
        val payload = gson.toJson(events.map { sanitizeEvent(it) }).toByteArray(Charsets.UTF_8)
        var outputStream: FileOutputStream? = null
        val written = runCatching {
            val parent = historyFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Timber.w("Unable to ensure playback history directory: ${parent.absolutePath}")
            }
            outputStream = atomicHistoryFile.startWrite()
            outputStream?.write(payload)
            outputStream?.fd?.sync()
            outputStream?.let { atomicHistoryFile.finishWrite(it) }
            outputStream = null
            true
        }.onFailure { throwable ->
            outputStream?.let { stream -> atomicHistoryFile.failWrite(stream) }
            Timber.e(throwable, "Failed to persist playback history")
        }.getOrDefault(false)
        if (written) {
            cache = ArrayList(events.map { sanitizeEvent(it) })
            needsRewrite = false
            epoch++
        }
        return written
    }

    private fun sanitizeEvent(event: PlaybackEvent): PlaybackEvent {
        val safeDuration = event.durationMs.coerceAtLeast(0L)
        val safeEnd = (event.endTimestamp ?: event.timestamp).coerceAtLeast(0L)
        val safeStart = when {
            event.startTimestamp != null -> event.startTimestamp.coerceIn(0L, safeEnd)
            safeDuration > 0L -> (safeEnd - safeDuration).coerceAtLeast(0L)
            else -> safeEnd
        }
        val normalizedDuration = (safeEnd - safeStart).coerceAtLeast(0L)
        val finalDuration = when {
            event.startTimestamp == null && safeDuration in 1 until normalizedDuration -> safeDuration
            else -> normalizedDuration
        }
        val finalStart = (safeEnd - finalDuration).coerceAtLeast(0L)
        return event.copy(
            songId = event.songId,
            timestamp = safeEnd,
            durationMs = finalDuration,
            startTimestamp = finalStart,
            endTimestamp = safeEnd,
            // B28a: what an event says about its occurrence and its audio is kept as recorded, only bounded, so a
            // hand-edited or damaged file cannot put a negative length or a megabyte string into the history.
            libraryItemId = event.libraryItemId?.takeIf { it.isNotBlank() },
            occurrenceId = event.occurrenceId?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_WIRE_ID_LENGTH),
            itemDurationMs = event.itemDurationMs?.takeIf { it > 0L },
            audio = event.audio?.let(::sanitizeAudio),
            context = event.context?.let(::sanitizeContext),
        )
    }

    private fun sanitizeAudio(audio: ListenAudioFacts): ListenAudioFacts = audio.copy(
        sourceRateHz = audio.sourceRateHz?.takeIf { it > 0 },
        sourceBits = audio.sourceBits?.takeIf { it > 0 },
        sourceCodec = audio.sourceCodec?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_WIRE_TEXT_LENGTH),
        transportRateHz = audio.transportRateHz?.takeIf { it > 0 },
        transportBits = audio.transportBits?.takeIf { it > 0 },
        transportCodec = audio.transportCodec?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_WIRE_TEXT_LENGTH),
        deviceName = audio.deviceName?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_WIRE_TEXT_LENGTH),
        share = audio.share?.takeIf { it in 1..99 },
    )

    private fun sanitizeContext(context: ListenContext): ListenContext = context.copy(
        ref = context.ref?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_WIRE_ID_LENGTH),
    )

    private fun notifyStatsChanged() {
        _refreshVersion.update { current ->
            if (current == Long.MAX_VALUE) {
                1L
            } else {
                current + 1L
            }
        }
    }

    companion object {
        private const val DEFAULT_PLAYBACK_HISTORY_LIMIT = 500
        private const val MAX_PLAYBACK_HISTORY_LIMIT = 5_000
        private const val MAX_WIRE_ID_LENGTH = 64
        private const val MAX_WIRE_TEXT_LENGTH = 128

        /** How much of the end of the file an append looks at to find the closing bracket. */
        private const val TAIL_BYTES = 64

        /** Events older than this are dropped the next time the file is rewritten. */
        val MAX_HISTORY_AGE_MS: Long = TimeUnit.DAYS.toMillis(730)
    }
}

/**
 * B35 (RT-03): the history file could not be replaced. The previous history is still the one on disk; whoever asked for the
 * import (a restore, or the rollback of one) must not report it as done.
 */
class PlaybackHistoryWriteException : IOException("The listening history could not be written; the previous history was kept")

/** When the listen began, never after it ended. */
internal fun PlaybackStatsRepository.PlaybackEvent.startMillis(): Long {
    val end = (endTimestamp ?: timestamp).coerceAtLeast(0L)
    val inferredStart = (startTimestamp ?: (end - durationMs)).coerceAtLeast(0L)
    return min(inferredStart, end)
}

/** When the listen ended, never before it began. */
internal fun PlaybackStatsRepository.PlaybackEvent.endMillis(): Long {
    val end = (endTimestamp ?: timestamp).coerceAtLeast(0L)
    val start = startMillis()
    return max(end, start)
}
