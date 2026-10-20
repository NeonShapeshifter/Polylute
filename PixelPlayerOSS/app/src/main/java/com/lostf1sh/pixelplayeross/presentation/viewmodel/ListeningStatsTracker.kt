package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.os.SystemClock
import androidx.media3.common.C
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.stats.LibraryIdentityLookup
import com.lostf1sh.pixelplayeross.data.stats.ListenAudioFacts
import com.lostf1sh.pixelplayeross.data.stats.ListenContext
import com.lostf1sh.pixelplayeross.data.stats.ListenOccurrence
import com.lostf1sh.pixelplayeross.data.stats.ListenSource
import com.lostf1sh.pixelplayeross.data.stats.ListenSourceKind
import com.lostf1sh.pixelplayeross.data.stats.PlaybackOutcome
import com.lostf1sh.pixelplayeross.data.stats.PlaybackOutcomeClassifier
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import com.lostf1sh.pixelplayeross.data.stats.TrackEndReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Tracks listening statistics for songs.
 * Extracted from PlayerViewModel to reduce its size and improve modularity.
 *
 * Responsibilities:
 * - Track active listening sessions
 * - Record play statistics when session ends
 * - Handle voluntary vs automatic plays
 *
 * Since B28a every event it writes is complete: it carries the track's durable identity, the occurrence it belongs
 * to ([ListenOccurrence]), how it ended and the audio facts that carried it (see [onAudioFacts]). Since B28 it also says
 * where the queue came from ([setQueueSource]) and what the listener did to leave the track ([noteUserDeparture]).
 *
 * It writes the history and nothing else. Plays, completions and skips are not counted here: they are read back from
 * the events by `ListeningLedger`, so there is one definition of them and no counter that can drift from the history.
 *
 * ## One listen, one or more events (R28-02)
 *
 * A session is one occurrence of a track, and it stays one across pauses. But an event is a stretch of time, so a session whose
 * audio played in the morning and again at night must not be written as one continuous stretch ending at night. The session
 * keeps its **runs** (an uninterrupted stretch of playing, [ListenRun]) and writes one event per run, all with the same
 * `occurrenceId`: the ledger sums them into the one listen they are, and puts each one's time where it was heard (the hours of
 * Habits, the sessions, the day it falls in).
 *
 * A run goes on across a pause that is short ([MAX_PAUSE_IN_RUN_MS] in all), so a track paused for a moment is one event, not
 * two; that pause is not in the event and its start is later by as much, which is the most it can be wrong. A longer pause ends
 * the run. Only the last event of a listen says how it ended; the earlier ones are [TrackEndReason.INTERRUPTED], so the
 * pieces of a listen are never several plays, several skips or a departure the listener did not make.
 */
@Singleton
class ListeningStatsTracker @Inject constructor(
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val libraryIdentityLookup: LibraryIdentityLookup,
) {
    private var currentSession: ActiveSession? = null

    /** Where the queue that is playing was loaded from, as the screen that loaded it said; null when it did not say. */
    @Volatile private var queueSource: ListenSource? = null

    /** What the listener last did to leave a track (Next, Previous, a pick), until the track that action closes is closed. */
    private var pendingDeparture: TrackEndReason? = null
    private var pendingDepartureAtMs = 0L

    /** The audio facts as last pushed by the service; the time a session plays is booked against them. */
    private var latestAudioFacts: ListenAudioFacts? = null

    /** The wall clock the events are stamped with; a seam for the tests. */
    internal var epochClock: () -> Long = System::currentTimeMillis

    /** Where occurrence ids come from; a seam for the tests. */
    internal var newOccurrenceId: () -> String = ListenOccurrence::newId

    /** Listens that have ended and whose events are not in the history file yet (see [identitiesInFlight]). */
    private val pendingListens = ArrayList<FinishedListen>()
    private var scope: CoroutineScope? = null
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _playbackHistory = MutableStateFlow<List<PlaybackStatsRepository.PlaybackHistoryEntry>>(emptyList())
    val playbackHistory: StateFlow<List<PlaybackStatsRepository.PlaybackHistoryEntry>> = _playbackHistory.asStateFlow()

    /**
     * Must be called to set the coroutine scope for async operations.
     */
    fun initialize(coroutineScope: CoroutineScope) {
        val activeScope = scope
        if (activeScope == null || activeScope.coroutineContext[Job]?.isActive != true) {
            scope = coroutineScope
        }
        coroutineScope.launch(Dispatchers.IO) {
            _playbackHistory.value = playbackStatsRepository.loadPlaybackHistory(
                limit = MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS
            )
        }
    }

    /**
     * The screen that loads a queue says where it comes from (an album, a playlist, a collection...), so every listen of that
     * queue records it. `null` forgets it: a queue loaded by a caller that does not say is recorded with no source, never with
     * the previous queue's.
     */
    fun setQueueSource(source: ListenSource?) {
        queueSource = source
    }

    /**
     * The listener did something to leave the track that is playing: pressed Next or Previous, started it over, picked
     * another one. The player reports a skip as a transition with whatever reason it had, and that reason is often not the
     * listener's (a Previous arrives as a seek, a pick in a new queue as a replaced playlist), so the transport says what it was
     * asked, and the session it closes takes it when it closes ([resolveEndReason]).
     */
    @Synchronized
    fun noteUserDeparture(reason: TrackEndReason) {
        pendingDeparture = reason
        pendingDepartureAtMs = SystemClock.elapsedRealtime()
    }

    fun onSongChanged(
        song: Song?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        context: ListenContext? = null,
    ) {
        onTrackChanged(
            songId = song?.id,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = song?.duration ?: 0L,
            isPlaying = isPlaying,
            libraryItemId = song?.libraryItemId,
            context = context,
        )
    }

    @Synchronized
    fun onTrackChanged(
        songId: String?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        onTrackChanged(
            songId = songId,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = 0L,
            isPlaying = isPlaying
        )
    }

    @Synchronized
    fun onTrackChanged(
        songId: String?,
        positionMs: Long,
        durationMs: Long,
        fallbackDurationMs: Long,
        isPlaying: Boolean,
        previousEndReason: TrackEndReason = TrackEndReason.UNKNOWN,
        libraryItemId: String? = null,
        context: ListenContext? = null,
    ) {
        // The reason describes the session being closed here, not the one starting.
        finalizeCurrentSession(endReason = previousEndReason)
        val safeSongId = songId?.takeIf { it.isNotBlank() }
        if (safeSongId == null) {
            return
        }

        val nowRealtime = SystemClock.elapsedRealtime()
        val nowEpoch = epochClock()
        val normalizedDuration = normalizeDuration(durationMs, fallbackDurationMs)

        // A new item, a repeat-one loop, a replay: each call here is a new occurrence of the track.
        val session = ActiveSession(
            songId = safeSongId,
            libraryItemId = libraryItemId?.takeIf { it.isNotBlank() } ?: "",
            totalDurationMs = normalizedDuration,
            startedAtEpochMs = nowEpoch,
            lastKnownPositionMs = positionMs.coerceAtLeast(0L),
            accumulatedListeningMs = 0L,
            lastRealtimeMs = nowRealtime,
            isPlaying = isPlaying,
            occurrenceId = newOccurrenceId(),
            context = withQueueSource(context),
        )
        if (isPlaying) session.run = ListenRun(nowEpoch)
        currentSession = session
        if (session.libraryItemId.isEmpty()) resolveIdentity(session)
    }

    /**
     * Asks, off the main thread, which library item the locator of [session] is, the moment it opens, so a sync that
     * renumbers the library later in the listen cannot move the event onto another track. The result is read when the
     * event is written, which waits for it ([ActiveSession.identityJob]); a failed lookup records no identity.
     */
    private fun resolveIdentity(session: ActiveSession) {
        session.identityJob = persistenceScope.launch {
            val identity = runCatching { libraryIdentityLookup.identityOf(session.songId) }
                .onFailure { Timber.w(it, "Library identity lookup failed for song=%s", session.songId) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            if (identity != null && session.libraryItemId.isEmpty()) session.libraryItemId = identity
        }
    }

    @Synchronized
    fun onPlayStateChanged(isPlaying: Boolean, positionMs: Long) {
        val session = currentSession ?: return
        val nowRealtime = SystemClock.elapsedRealtime()
        accumulateRealtimeListening(session, nowRealtime)
        setPlaying(session, isPlaying)
        session.lastRealtimeMs = nowRealtime
        session.lastKnownPositionMs = positionMs.coerceAtLeast(0L)
    }

    @Synchronized
    fun onProgress(positionMs: Long, isPlaying: Boolean) {
        val session = currentSession ?: return
        val nowRealtime = SystemClock.elapsedRealtime()
        accumulateRealtimeListening(session, nowRealtime)
        setPlaying(session, isPlaying)
        session.lastRealtimeMs = nowRealtime
        session.lastKnownPositionMs = positionMs.coerceAtLeast(0L)
    }

    fun ensureSession(
        song: Song?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        context: ListenContext? = null,
    ) {
        ensureSession(
            songId = song?.id,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = song?.duration ?: 0L,
            isPlaying = isPlaying,
            libraryItemId = song?.libraryItemId,
            context = context,
        )
    }

    @Synchronized
    fun ensureSession(
        songId: String?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        ensureSession(
            songId = songId,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = 0L,
            isPlaying = isPlaying
        )
    }

    @Synchronized
    fun ensureSession(
        songId: String?,
        positionMs: Long,
        durationMs: Long,
        fallbackDurationMs: Long,
        isPlaying: Boolean,
        libraryItemId: String? = null,
        context: ListenContext? = null,
    ) {
        val safeSongId = songId?.takeIf { it.isNotBlank() }
        if (safeSongId == null) {
            finalizeCurrentSession()
            return
        }
        val existing = currentSession
        if (existing?.songId == safeSongId) {
            updateDuration(normalizeDuration(durationMs, fallbackDurationMs))
            val nowRealtime = SystemClock.elapsedRealtime()
            accumulateRealtimeListening(existing, nowRealtime)
            setPlaying(existing, isPlaying)
            existing.lastRealtimeMs = nowRealtime
            existing.lastKnownPositionMs = positionMs.coerceAtLeast(0L)
            return
        }
        onTrackChanged(
            songId = safeSongId,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = fallbackDurationMs,
            isPlaying = isPlaying,
            libraryItemId = libraryItemId,
            context = context,
        )
    }

    /**
     * The audio the app is playing through changed (the route, the signal mode, the bit-perfect verdict, the source
     * or the transport), or is being restated: the service pushes it whenever it syncs the tracker with the player
     * and whenever the engine publishes a new route or diagnostics. Equal facts cost nothing.
     *
     * The listening time of the session in progress is booked against the facts that were current while it ran, so an
     * event records the facts that carried most of it ([ListenAudioFacts.share] says how much) and a stale reading
     * at the very start of a track, before the sink has published the new one, weighs only its few milliseconds.
     */
    @Synchronized
    fun onAudioFacts(facts: ListenAudioFacts) {
        if (facts == latestAudioFacts) return
        currentSession?.let { session ->
            val nowRealtime = SystemClock.elapsedRealtime()
            accumulateRealtimeListening(session, nowRealtime)
            session.lastRealtimeMs = nowRealtime
        }
        latestAudioFacts = facts
    }

    /**
     * A seek inside the item that is playing, from [fromPositionMs] to [toPositionMs].
     *
     * A seek is the same occurrence, except one that goes back to the start after the track had been heard
     * ([ListenOccurrence.isReplayFromStart]): that is the listener playing it again, so the first pass is closed as it
     * stands and the replay opens a new occurrence. The first pass ends as [TrackEndReason.USER_REPLAY]: going back to the start
     * is the listener's own action, but it is not leaving the track.
     */
    @Synchronized
    fun onSeekWithinItem(fromPositionMs: Long, toPositionMs: Long) {
        val session = currentSession ?: return
        val nowRealtime = SystemClock.elapsedRealtime()
        accumulateRealtimeListening(session, nowRealtime)
        session.lastRealtimeMs = nowRealtime
        session.lastKnownPositionMs = toPositionMs.coerceAtLeast(0L)
        if (!ListenOccurrence.isReplayFromStart(session.accumulatedListeningMs, fromPositionMs, toPositionMs)) return
        onTrackChanged(
            songId = session.songId,
            positionMs = toPositionMs,
            durationMs = session.totalDurationMs,
            fallbackDurationMs = 0L,
            isPlaying = session.isPlaying,
            previousEndReason = TrackEndReason.USER_REPLAY,
            libraryItemId = session.libraryItemId,
            context = session.context,
        )
    }

    @Synchronized
    fun updateDuration(durationMs: Long) {
        val session = currentSession ?: return
        if (durationMs > 0 && durationMs != C.TIME_UNSET) {
            session.totalDurationMs = durationMs
        }
    }

    @Synchronized
    fun finalizeCurrentSession(
        endReason: TrackEndReason = TrackEndReason.UNKNOWN,
        forceSynchronousPersistence: Boolean = false
    ) {
        // The single exactly-once guard: the session is cleared at the end of this method, so a
        // second call — a state emission, a stop after an end, an overlapping lifecycle callback —
        // finds nothing and returns. One session, one event.
        val session = currentSession ?: return
        val nowRealtime = SystemClock.elapsedRealtime()
        val nowEpoch = epochClock()
        accumulateRealtimeListening(session, nowRealtime)
        val listened = session.accumulatedListeningMs.coerceAtLeast(0L)
        val reason = resolveEndReason(endReason, nowRealtime)
        if (listened >= MIN_SESSION_LISTEN_MS) {
            val fragments = fragmentsOf(session, reason, nowEpoch)
            val timestamp = fragments.last().timestamp
            val songId = session.songId
            val historyEntry = PlaybackStatsRepository.PlaybackHistoryEntry(
                songId = songId,
                timestamp = timestamp,
                // Captured when the session opened, so a sync that renumbers mid-listen cannot
                // redirect this event onto a different track.
                libraryItemId = session.libraryItemId.takeIf { it.isNotBlank() },
                identityResolvedFromLocator = false,
            )
            _playbackHistory.update { current ->
                (listOf(historyEntry) + current).take(MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS)
            }
            persistPlayback(
                finished = FinishedListen(session = session, listenedMs = listened, fragments = fragments),
                forceSynchronous = forceSynchronousPersistence
            )
        }
        currentSession = null
    }

    /**
     * A read-only view of the session in progress, for the Maintenance diagnostic.
     *
     * It reports what has actually been measured and what the classifier *would* conclude right
     * now — nothing here is persisted, and nothing here changes playback accounting. The projected
     * outcome is the same pure function the real finalization uses, so the diagnostic cannot drift
     * away from the behaviour it describes.
     */
    @Synchronized
    fun sessionSnapshot(): SessionSnapshot? {
        val session = currentSession ?: return null
        val listened = session.accumulatedListeningMs.coerceAtLeast(0L)
        return SessionSnapshot(
            songId = session.songId,
            libraryItemId = session.libraryItemId,
            listenedMs = listened,
            totalDurationMs = session.totalDurationMs,
            isPlaying = session.isPlaying,
            startedAtEpochMs = session.startedAtEpochMs,
            endReason = TrackEndReason.UNKNOWN,
            projectedOutcome = PlaybackOutcomeClassifier.classify(
                listenedMs = listened,
                durationMs = session.totalDurationMs,
                endReason = TrackEndReason.UNKNOWN,
            ),
        )
    }

    /** What the diagnostic shows for the session in flight. Never persisted, never counted. */
    data class SessionSnapshot(
        val songId: String,
        val libraryItemId: String,
        val listenedMs: Long,
        val totalDurationMs: Long,
        val isPlaying: Boolean,
        val startedAtEpochMs: Long,
        /**
         * The reason the session would report if it ended now.
         *
         * The honest value while a track is still playing is [TrackEndReason.UNKNOWN]: the reason a
         * session ends is only knowable when it ends. The projected outcome is therefore the
         * *measured* one, which is the same thing the classifier would conclude for an interruption
         * — a diagnostic that guessed "COMPLETED" here would be showing a prediction as a fact.
         */
        val endReason: TrackEndReason,
        val projectedOutcome: PlaybackOutcome,
    )

    @Synchronized
    fun onPlaybackStopped() {
        finalizeCurrentSession()
    }

    @Synchronized
    fun onCleared() {
        finalizeCurrentSession(forceSynchronousPersistence = true)
        scope = null
    }

    /** A session that has just ended: the session itself and the events it is written as. */
    private class FinishedListen(
        val session: ActiveSession,
        val listenedMs: Long,
        val fragments: List<FinishedFragment>,
    )

    /** One event of a listen: one run of its playing, with the facts that belong to that run alone. */
    private class FinishedFragment(
        val timestamp: Long,
        val listenedMs: Long,
        val outcome: PlaybackOutcome,
        val endReason: TrackEndReason,
        val audio: ListenAudioFacts?,
    )

    /**
     * The events a session that ended with [reason] is written as: one per run that heard anything, in time order.
     *
     * Each is stamped with the moment its run last played, so a pause that outlived the run is not inside it. The last one carries the
     * reason the listen ended with and the outcome of the whole listen; the others end as [TrackEndReason.INTERRUPTED] with the
     * outcome of what had been heard so far, because a pause is not the listener leaving the track, and the ledger reads how a
     * listen ended from its last event.
     */
    private fun fragmentsOf(session: ActiveSession, reason: TrackEndReason, nowEpoch: Long): List<FinishedFragment> {
        val runs = (session.closedRuns + listOfNotNull(session.run)).filter { it.listenedMs > 0L }
        var heard = 0L
        return runs.mapIndexed { index, run ->
            val last = index == runs.lastIndex
            heard += run.listenedMs
            val endReason = if (last) reason else TrackEndReason.INTERRUPTED
            FinishedFragment(
                timestamp = run.endEpochMs.coerceAtLeast(session.startedAtEpochMs.coerceAtLeast(0L)).coerceAtMost(nowEpoch),
                listenedMs = run.listenedMs,
                outcome = PlaybackOutcomeClassifier.classify(
                    listenedMs = heard,
                    durationMs = session.totalDurationMs,
                    endReason = endReason,
                ),
                endReason = endReason,
                audio = dominantAudio(run),
            )
        }
    }

    /**
     * The identities of the listens that the history file does not hold yet: the one in progress, and those that ended and whose
     * events are still being written. A clean-up that removes library items must not remove these, or the event would be written
     * for an item that is gone (R28-01); the events already in the file are the history's to say.
     *
     * A listen opens with the locator and learns its item a moment later ([resolveIdentity]), and that moment is exactly when a clean-up
     * can run (R28-01-F1): the lookup has read the library, the file goes, and the answer has not reached the session. So this
     * **waits for the lookups that are still running**, up to [waitMs] in all, and then says what the sessions know. A lookup that failed or was cancelled has
     * ended and leaves nothing (that listen records no identity, by B28a's design). If a lookup is still running when the time is up,
     * [InFlightIdentities.settled] is false and the caller must not treat the set as complete.
     *
     * Suspends outside the tracker's monitor (only the two snapshots take it, briefly), so it never holds up playback, and it is meant for
     * a background caller.
     */
    suspend fun identitiesInFlight(waitMs: Long = IDENTITY_LOOKUP_WAIT_MS): InFlightIdentities {
        var settled = false
        withTimeoutOrNull(waitMs) {
            while (true) {
                val running = synchronized(this@ListeningStatsTracker) { runningLookups() }
                if (running.isEmpty()) {
                    settled = true
                    break
                }
                running.forEach { it.join() }
            }
        }
        return synchronized(this) { InFlightIdentities(namedByListensInFlight(), settled) }
    }

    /** The identity lookups of the listens in flight that have not finished. Called with the monitor held. */
    private fun runningLookups(): List<Job> =
        (listOfNotNull(currentSession) + pendingListens.map { it.session })
            .mapNotNull { it.identityJob }
            .filter { it.isActive }

    /** What the listens in flight have been told they are about. Called with the monitor held. */
    private fun namedByListensInFlight(): Set<String> {
        val identities = HashSet<String>()
        currentSession?.libraryItemId?.takeIf { it.isNotBlank() }?.let(identities::add)
        for (pending in pendingListens) pending.session.libraryItemId.takeIf { it.isNotBlank() }?.let(identities::add)
        return identities
    }

    @Suppress("UNUSED_PARAMETER")
    private fun persistPlayback(
        finished: FinishedListen,
        forceSynchronous: Boolean
    ) {
        pendingListens += finished
        // Off the playback path, and never awaited by it: a failed history write must not be able
        // to stall, stop or destabilise audio. The runCatching is the whole safety story, and it is
        // deliberate that nothing upstream can observe whether this succeeded.
        persistenceScope.launch {
            try {
                runCatching {
                    persistPlaybackInternal(finished)
                }.onFailure { throwable ->
                    Timber.e(throwable, "Failed to persist listening session for song=%s", finished.session.songId)
                }
            } finally {
                synchronized(this@ListeningStatsTracker) { pendingListens.remove(finished) }
            }
        }
    }

    private suspend fun persistPlaybackInternal(finished: FinishedListen) {
        val session = finished.session
        // The lookup started when the session opened; by the time a listen long enough to be written has ended it is
        // long done, and this only waits for it in the one case where it is not.
        session.identityJob?.join()
        val songId = session.songId
        val libraryItemId = session.libraryItemId
        // In time order, one after the other: the events of one listen reach the file in the order they happened.
        for (fragment in finished.fragments) {
            playbackStatsRepository.recordPlayback(
                songId = songId,
                durationMs = fragment.listenedMs,
                timestamp = fragment.timestamp,
                // A blank identity is the *absence* of an identity, not an identity that happens to be
                // empty. Passing it through normalised keeps "recorded under no identity" and "recorded
                // under an empty string" from becoming two different things in the history file.
                libraryItemId = libraryItemId.takeIf { it.isNotBlank() },
                occurrenceId = session.occurrenceId,
                outcome = fragment.outcome,
                endReason = fragment.endReason,
                itemDurationMs = session.totalDurationMs.takeIf { it > 0L },
                audio = fragment.audio,
                context = session.context,
            )
        }
    }

    /**
     * The reason a session closes with. What the transport last said the listener did wins over a reason that only says how
     * the player moved (a seek, a replaced playlist, nothing), when it said it a moment ago; a track that ran to its end, an
     * interruption and a replay keep the reason they were reported with, because the listener did not leave in those. The note
     * is used once.
     */
    private fun resolveEndReason(reported: TrackEndReason, nowRealtime: Long): TrackEndReason {
        val pending = pendingDeparture
        pendingDeparture = null
        if (pending == null || nowRealtime - pendingDepartureAtMs > USER_DEPARTURE_WINDOW_MS) return reported
        return when (reported) {
            TrackEndReason.NATURAL_END, TrackEndReason.INTERRUPTED, TrackEndReason.USER_REPLAY -> reported
            else -> pending
        }
    }

    /** The context a session opens with: what the player knows (an external item, shuffle) and where the queue came from. */
    private fun withQueueSource(context: ListenContext?): ListenContext? {
        val source = queueSource
        if (context?.kind == ListenSourceKind.EXTERNAL_VIEW || source == null) return context
        return (context ?: ListenContext()).copy(kind = source.kind, ref = source.ref)
    }

    /** Books the time since the last call to the session, and to its run, if it was playing. Playing now is when the run last played. */
    private fun accumulateRealtimeListening(session: ActiveSession, nowRealtime: Long) {
        if (!session.isPlaying) return
        val run = session.run ?: ListenRun(epochClock()).also { session.run = it }
        val delta = (nowRealtime - session.lastRealtimeMs).coerceAtLeast(0L)
        if (delta > 0L) {
            session.accumulatedListeningMs += delta
            run.listenedMs += delta
            run.bookAudioTime(latestAudioFacts, delta)
        }
        run.endEpochMs = epochClock()
    }

    /**
     * The session is playing or is not. Starting again after a pause goes on with the run if the pauses in it add up to no more than
     * [MAX_PAUSE_IN_RUN_MS]; a longer one closes it and the playing starts a new run, which is what keeps the hours of a listen that
     * was paused for a day where they were heard. Called after the time up to now has been booked.
     */
    private fun setPlaying(session: ActiveSession, playing: Boolean) {
        if (playing && !session.isPlaying) {
            val nowEpoch = epochClock()
            val run = session.run
            if (run != null) {
                val pause = (nowEpoch - run.endEpochMs).coerceAtLeast(0L)
                if (run.pausedMs + pause <= MAX_PAUSE_IN_RUN_MS) {
                    run.pausedMs += pause
                } else {
                    session.closedRuns += run
                    session.run = ListenRun(nowEpoch)
                }
            } else {
                session.run = ListenRun(nowEpoch)
            }
        }
        session.isPlaying = playing
    }

    /**
     * The audio facts that carried the most of a run, with the share of it they carried when it was not all.
     * Null when nothing was known while it played.
     */
    private fun dominantAudio(run: ListenRun): ListenAudioFacts? {
        val (facts, ms) = run.audioMs.entries.maxByOrNull { it.value } ?: return null
        facts ?: return null
        val listened = run.listenedMs
        val share = if (listened > 0L) ((ms * 100 + listened / 2) / listened).toInt() else 100
        return if (share in 1..99) facts.copy(share = share) else facts
    }

    private fun normalizeDuration(durationMs: Long, fallbackDurationMs: Long): Long {
        return when {
            durationMs > 0 && durationMs != C.TIME_UNSET -> durationMs
            fallbackDurationMs > 0 && fallbackDurationMs != C.TIME_UNSET -> fallbackDurationMs
            else -> 0L
        }
    }

    companion object {
        private val MIN_SESSION_LISTEN_MS = TimeUnit.SECONDS.toMillis(5)

        /** How long what the listener asked of the transport is believed when the track it closes is closed. */
        private const val USER_DEPARTURE_WINDOW_MS = 2_500L

        /** The pauses a run goes on across, all together: more than this ends the run, and the playing after it is another event. */
        internal const val MAX_PAUSE_IN_RUN_MS = 60_000L

        /** How long [identitiesInFlight] waits for identity lookups that are still running: a lookup is one indexed query. */
        internal const val IDENTITY_LOOKUP_WAIT_MS = 3_000L
        private const val MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS = 500
    }
}

/**
 * Represents an active listening session for a song.
 */
data class ActiveSession(
    val songId: String,
    /**
     * The durable identity, captured once when the session opens.
     *
     * The locator in [songId] can be reassigned by a sync that runs mid-listen; this cannot. That
     * is what stops a session from finalizing onto a different track that later acquired the same
     * locator.
     *
     * Empty until it is known: a caller that has the song passes it, otherwise [identityJob] fills it in within
     * moments of the session opening, and never changes it afterwards.
     */
    @Volatile var libraryItemId: String,
    var totalDurationMs: Long,
    val startedAtEpochMs: Long,
    var lastKnownPositionMs: Long,
    var accumulatedListeningMs: Long,
    var lastRealtimeMs: Long,
    var isPlaying: Boolean,
    /** One id per occurrence of the track (see [ListenOccurrence]); every event this occurrence writes carries it. */
    val occurrenceId: String = "",
    /** Where the play came from and whether shuffle was on, as it was when the session opened. */
    val context: ListenContext? = null,
) {
    /** The lookup that fills in [libraryItemId], when the caller did not know it. */
    @Volatile var identityJob: Job? = null

    /** The runs of playing that ended with a pause too long to belong to them (see [ListenRun]), oldest first. */
    internal val closedRuns: MutableList<ListenRun> = ArrayList(1)

    /** The run in progress, or the last one while the session is paused; null until it first plays. */
    internal var run: ListenRun? = null
}

/**
 * The library items the listens in flight are about (see [ListeningStatsTracker.identitiesInFlight]). [settled] is false when an identity
 * lookup was still running when the wait ended: [identities] is then missing whatever that lookup was about to name.
 */
class InFlightIdentities(val identities: Set<String>, val settled: Boolean)

/**
 * An uninterrupted stretch of a session in which the audio played (R28-02): what is written as one event.
 *
 * [endEpochMs] is the last moment it is known to have been playing, [listenedMs] the time it played, so its start is
 * `endEpochMs - listenedMs` (later than the real one by [pausedMs], the short pauses it went on across). The audio that carried it is
 * its own, because the route can change across a long pause.
 */
internal class ListenRun(var endEpochMs: Long) {
    var listenedMs = 0L
    var pausedMs = 0L

    /** The listened time per audio facts, in the order they first carried it; null keys the time nothing was known. */
    val audioMs: MutableMap<ListenAudioFacts?, Long> = LinkedHashMap()

    fun bookAudioTime(facts: ListenAudioFacts?, deltaMs: Long) {
        val key = facts?.withoutShare()
        if (key !in audioMs && audioMs.size >= MAX_AUDIO_SEGMENTS) {
            // Facts that flap would otherwise grow this without bound: the smallest segment is let go, which only
            // lowers the share the winner reports.
            audioMs.entries.minByOrNull { it.value }?.let { audioMs.remove(it.key) }
        }
        audioMs[key] = (audioMs[key] ?: 0L) + deltaMs
    }

    private companion object {
        const val MAX_AUDIO_SEGMENTS = 8
    }
}
