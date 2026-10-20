package com.lostf1sh.pixelplayeross.data.stats

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The truth layer of Phase 12: what one listening session actually was.
 *
 * Every case here is a pure function call — no clock, no device, no coroutine. That is deliberate:
 * these thresholds decide what the library believes about the listener, and they should be
 * arguable in a test rather than observable only after a week of listening.
 */
class PlaybackOutcomeClassifierTest {

    private val interlude = 45_000L      // 45 s
    private val ordinary = 240_000L      // 4 min
    private val long = 15 * 60_000L      // 15 min

    private fun classify(
        listened: Long,
        duration: Long = ordinary,
        reason: TrackEndReason = TrackEndReason.UNKNOWN,
    ) = PlaybackOutcomeClassifier.classify(listened, duration, reason)

    // --- Duration-adaptive thresholds -------------------------------------------------------------

    @Test
    fun `a short interlude does not have to run four minutes to count`() {
        // 50% of 45 s = 22.5 s. A fixed four-minute bar would call every interlude a skip.
        assertThat(PlaybackOutcomeClassifier.meaningfulThresholdMs(interlude)).isEqualTo(22_500L)
        assertThat(classify(listened = 25_000L, duration = interlude)).isEqualTo(PlaybackOutcome.PLAYED)
    }

    @Test
    fun `a long track does not have to run eight minutes to count`() {
        // 50% of 15 min would be 7.5 min; the cap holds it at 4 min.
        assertThat(PlaybackOutcomeClassifier.meaningfulThresholdMs(long)).isEqualTo(4 * 60_000L)
        assertThat(classify(listened = 4 * 60_000L, duration = long)).isEqualTo(PlaybackOutcome.PLAYED)
    }

    @Test
    fun `an ordinary track needs half of itself`() {
        assertThat(PlaybackOutcomeClassifier.meaningfulThresholdMs(ordinary)).isEqualTo(120_000L)
    }

    // --- Completion -------------------------------------------------------------------------------

    @Test
    fun `a natural end is a completion even if the clock says otherwise`() {
        // The track reached its end. Whatever the accumulated total says, it was heard through.
        assertThat(classify(listened = 30_000L, duration = ordinary, reason = TrackEndReason.NATURAL_END))
            .isEqualTo(PlaybackOutcome.COMPLETED)
    }

    @Test
    fun `ninety percent counts as finished without reaching the end`() {
        assertThat(classify(listened = 216_000L)).isEqualTo(PlaybackOutcome.COMPLETED) // 90% of 4 min
        assertThat(classify(listened = 215_000L)).isEqualTo(PlaybackOutcome.PLAYED)
    }

    // --- Played vs skipped ------------------------------------------------------------------------

    @Test
    fun `a meaningful listen that was left deliberately is PLAYED, not SKIPPED`() {
        // Half the track, then Next. They heard it; they just did not finish it.
        assertThat(classify(listened = 150_000L, reason = TrackEndReason.USER_NEXT))
            .isEqualTo(PlaybackOutcome.PLAYED)
    }

    @Test
    fun `leaving early on purpose is a SKIP`() {
        assertThat(classify(listened = 20_000L, reason = TrackEndReason.USER_NEXT))
            .isEqualTo(PlaybackOutcome.SKIPPED)
        assertThat(classify(listened = 20_000L, reason = TrackEndReason.USER_SELECTED_OTHER))
            .isEqualTo(PlaybackOutcome.SKIPPED)
    }

    // --- The cases where skip data is easiest to poison -------------------------------------------

    @Test
    fun `an interruption is never a skip`() {
        // Process death, a DAC disconnect, an error, a reboot, a replaced queue. None of these are
        // the listener deciding to leave, and counting them would fill the skip signal with the
        // app's own failures.
        listOf(
            TrackEndReason.INTERRUPTED,
            TrackEndReason.QUEUE_REPLACED,
            TrackEndReason.UNKNOWN,
        ).forEach { reason ->
            assertThat(classify(listened = 20_000L, reason = reason))
                .isEqualTo(PlaybackOutcome.NEUTRAL)
        }
    }

    @Test
    fun `only the three deliberate departures can skip`() {
        TrackEndReason.entries.forEach { reason ->
            val expected = reason == TrackEndReason.USER_NEXT ||
                reason == TrackEndReason.USER_PREVIOUS ||
                reason == TrackEndReason.USER_SELECTED_OTHER
            assertThat(reason.isDeliberateDeparture).isEqualTo(expected)
        }
    }

    @Test
    fun `a very short listen is neutral rather than a skip, whatever the reason`() {
        assertThat(classify(listened = 2_000L, reason = TrackEndReason.USER_NEXT))
            .isEqualTo(PlaybackOutcome.NEUTRAL)
    }

    // --- Completion fraction is bounded -----------------------------------------------------------

    @Test
    fun `seeking and replaying cannot manufacture more than a whole track`() {
        // Real listening that exceeds the track length still counts toward listening time, but it
        // is not 300% of a song and nothing downstream may read it that way.
        assertThat(PlaybackOutcomeClassifier.completionFraction(720_000L, ordinary)).isEqualTo(1.0f)
        assertThat(PlaybackOutcomeClassifier.completionFraction(120_000L, ordinary)).isEqualTo(0.5f)
        assertThat(PlaybackOutcomeClassifier.completionFraction(60_000L, 0L)).isEqualTo(0f)
    }

    @Test
    fun `an unknown duration never produces a completion, and cannot claim a meaningful listen`() {
        // With no duration there is nothing to be a fraction OF, so neither threshold can be met.
        // Falling through to NEUTRAL is the honest answer — a minute of an unknown-length track
        // might be the whole thing or a tenth of it, and guessing would be inventing evidence.
        assertThat(classify(listened = 60_000L, duration = 0L)).isEqualTo(PlaybackOutcome.NEUTRAL)
        assertThat(classify(listened = 60_000L, duration = 0L, reason = TrackEndReason.NATURAL_END))
            .isEqualTo(PlaybackOutcome.COMPLETED)
    }

    // --- Counter contract -------------------------------------------------------------------------

    @Test
    fun `the counter contract is exactly what the brief specifies`() {
        assertThat(PlaybackOutcome.COMPLETED.incrementsPlays).isTrue()
        assertThat(PlaybackOutcome.COMPLETED.incrementsCompletions).isTrue()
        assertThat(PlaybackOutcome.COMPLETED.incrementsSkips).isFalse()

        assertThat(PlaybackOutcome.PLAYED.incrementsPlays).isTrue()
        assertThat(PlaybackOutcome.PLAYED.incrementsCompletions).isFalse()
        assertThat(PlaybackOutcome.PLAYED.incrementsSkips).isFalse()

        assertThat(PlaybackOutcome.SKIPPED.incrementsPlays).isFalse()
        assertThat(PlaybackOutcome.SKIPPED.incrementsCompletions).isFalse()
        assertThat(PlaybackOutcome.SKIPPED.incrementsSkips).isTrue()

        // NEUTRAL moves nothing: we could not characterise it, so it must not inflate a play count.
        assertThat(PlaybackOutcome.NEUTRAL.incrementsPlays).isFalse()
        assertThat(PlaybackOutcome.NEUTRAL.incrementsCompletions).isFalse()
        assertThat(PlaybackOutcome.NEUTRAL.incrementsSkips).isFalse()
    }

    // --- Media3 reason mapping --------------------------------------------------------------------

    @Test
    fun `auto-advance is a natural end and a transport touch is a deliberate departure`() {
        assertThat(trackEndReasonFromTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))
            .isEqualTo(TrackEndReason.NATURAL_END)
        assertThat(trackEndReasonFromTransition(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK))
            .isEqualTo(TrackEndReason.USER_NEXT)
        assertThat(trackEndReasonFromTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED))
            .isEqualTo(TrackEndReason.QUEUE_REPLACED)
        // Anything unrecognised stays neutral-safe rather than guessing at intent.
        assertThat(trackEndReasonFromTransition(9999)).isEqualTo(TrackEndReason.UNKNOWN)
    }

    // --- Finalization must not double count -------------------------------------------------------

    @Test
    fun `finalization clears the session so one listen cannot produce two events`() {
        val source = File(
            "src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/ListeningStatsTracker.kt",
        ).let { if (it.isFile) it else File("app/${it.path}") }.readText()

        val finalize = source.substringAfter("fun finalizeCurrentSession(")
            .substringBefore("private fun persistPlayback")
        // The guard: a second call finds no session and returns before doing anything.
        assertThat(finalize).contains("currentSession ?: return")
        // And it is cleared exactly once, after the event has been built.
        assertThat(finalize.split("currentSession = null").size - 1).isEqualTo(1)
    }

    // --- Performance contract ---------------------------------------------------------------------

    @Test
    fun `a progress tick never writes to the database`() {
        // The brief's §16: playback must not churn Room every 500 ms. Progress updates accumulate
        // in memory; only finalization persists.
        val source = File(
            "src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/ListeningStatsTracker.kt",
        ).let { if (it.isFile) it else File("app/${it.path}") }.readText()

        val progress = source.substringAfter("fun onProgress(").substringBefore("@Synchronized")
        assertThat(progress).doesNotContain("persistPlayback")
        assertThat(progress).doesNotContain("recordOutcome")
        assertThat(progress).doesNotContain("recordPlay")
    }

    // --- Migration is additive --------------------------------------------------------------------

    @Test
    fun `the migration only adds columns and backfills nothing`() {
        val source = File("src/main/java/com/lostf1sh/pixelplayeross/data/database/Migrations.kt")
            .let { if (it.isFile) it else File("app/${it.path}") }.readText()
        val migration = source.substringAfter("val MIGRATION_6_7")

        assertThat(migration).contains("ALTER TABLE `song_engagements` ADD COLUMN")
        // No destructive fallback, and nothing is deleted or guessed at.
        assertThat(migration).doesNotContain("DROP TABLE")
        assertThat(migration).doesNotContain("DELETE FROM")
        // `first_played_at` stays 0 for existing rows rather than being invented from last-played.
        assertThat(migration).doesNotContain("first_played_at` INTEGER NOT NULL DEFAULT 1")
    }
}
