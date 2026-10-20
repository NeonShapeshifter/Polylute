package com.lostf1sh.pixelplayeross.data.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The outgoing-track contract at a crossfade boundary.
 *
 * A crossfade does not end the outgoing track — the audio is still sounding while the next one fades
 * in — but it *does* swap the player the listening session is attached to, and the session is
 * finalised at that moment. The temptation is to treat the swap as an end and label it accordingly;
 * that would be inventing a fact. These tests pin what the code does instead.
 *
 * The rule, stated once: a track ended by the app's own transition reports [TrackEndReason.UNKNOWN],
 * and the outcome is then decided by **how much of it was actually heard**. A completion that shows
 * up here was measured, not assumed.
 */
class CrossfadeOutcomeTest {

    private val trackMs = 240_000L

    @Test
    @DisplayName("a player swap is not a natural end, so it can never be labelled as one")
    fun aCrossfadeBoundaryDoesNotProduceANaturalEnd() {
        // MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED is what a swap looks like from the player's
        // side; it is deliberately not mapped to NATURAL_END, and neither is anything unrecognised.
        assertEquals(
            TrackEndReason.QUEUE_REPLACED,
            trackEndReasonFromTransition(androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED),
        )
        assertEquals(TrackEndReason.UNKNOWN, trackEndReasonFromTransition(Int.MIN_VALUE))
    }

    @Test
    @DisplayName("an interrupted crossfade is neutral, neither completed nor skipped")
    fun anEarlyInterruptedCrossfadeIsNeutral() {
        // Twenty seconds of a four-minute track, ended by something the listener did not do.
        assertEquals(
            PlaybackOutcome.NEUTRAL,
            PlaybackOutcomeClassifier.classify(
                listenedMs = 20_000L,
                durationMs = trackMs,
                endReason = TrackEndReason.UNKNOWN,
            ),
        )
    }

    @Test
    @DisplayName("a crossfaded completion is credited because it was measured, not because it was assumed")
    fun aFullyHeardCrossfadeIsCompletedOnMeasurement() {
        // Almost all of the track was heard before the fade began. This is COMPLETED — and the
        // reason is the measured listen, not the swap. Prove the distinction by holding the amount
        // heard fixed and varying only the stated reason: UNKNOWN and NATURAL_END agree here, so
        // the label followed the measurement.
        val heard = (trackMs * 95) / 100
        assertEquals(
            PlaybackOutcome.COMPLETED,
            PlaybackOutcomeClassifier.classify(heard, trackMs, TrackEndReason.UNKNOWN),
        )
        assertEquals(
            PlaybackOutcome.COMPLETED,
            PlaybackOutcomeClassifier.classify(heard, trackMs, TrackEndReason.NATURAL_END),
        )
    }

    @Test
    @DisplayName("a deliberately skipped crossfade is still a skip, because the listener chose it")
    fun adeliberateDepartureAtACrossfadeIsStillASkip() {
        assertEquals(
            PlaybackOutcome.SKIPPED,
            PlaybackOutcomeClassifier.classify(
                listenedMs = 20_000L,
                durationMs = trackMs,
                endReason = TrackEndReason.USER_NEXT,
            ),
        )
    }

    @Test
    @DisplayName("an unknown ending cannot manufacture a skip out of a short listen")
    fun anUnknownEndingNeverBecomesASkip() {
        // The asymmetry the classifier exists for: a missing completion signal is a small loss, a
        // phantom skip is a wrong fact about the listener that no later pass can detect.
        listOf(1_000L, 5_000L, 30_000L, 100_000L).forEach { heard ->
            val outcome = PlaybackOutcomeClassifier.classify(heard, trackMs, TrackEndReason.UNKNOWN)
            assertEquals(
                PlaybackOutcome.NEUTRAL,
                outcome,
                "a crossfaded track heard for ${heard}ms must stay neutral",
            )
        }
    }

    @Test
    @DisplayName("the fade tail is a known measurement loss, and it is bounded by the fade length")
    fun theFadeTailIsAMeasurementLossRatherThanAMislabelling() {
        // The session is finalised when the swap is published, which is at the *start* of the fade,
        // so up to one fade length of genuinely-heard audio is not credited. On a four-minute track
        // even a twelve-second smart fade leaves the listen above the completion threshold, so the
        // outcome survives; the risk is confined to short tracks with long fades.
        val longestFadeMs = 12_000L
        val shortestPlausibleTrackMs = 120_000L
        val heardWithWorstCaseLoss = shortestPlausibleTrackMs - longestFadeMs

        assertEquals(
            PlaybackOutcome.COMPLETED,
            PlaybackOutcomeClassifier.classify(
                listenedMs = heardWithWorstCaseLoss,
                durationMs = shortestPlausibleTrackMs,
                endReason = TrackEndReason.UNKNOWN,
            ),
            "the completion threshold must absorb the worst-case fade tail",
        )
    }
}
