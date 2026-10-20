package com.lostf1sh.pixelplayeross.data.intelligence

import com.lostf1sh.pixelplayeross.data.stats.PlaybackOutcome
import com.lostf1sh.pixelplayeross.data.stats.TrackEndReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The Maintenance diagnostic's rendering rules.
 *
 * What is being checked is not the layout but the *honesty*: that a value the app does not have is
 * shown as missing rather than as a zero, and that a projection is never presented as a fact.
 */
class TrackIntelligenceDiagnosticTest {

    private fun diagnostic(
        libraryItemId: String = "L1",
        locator: Long? = 11L,
        stableKey: String = "",
        session: TrackIntelligenceDiagnostic.SessionView? = null,
    ) = TrackIntelligenceDiagnostic(
        libraryItemId = libraryItemId,
        currentLocator = locator,
        lifecycle = "ACTIVE",
        stableKey = stableKey,
        fileSignature = "",
        projectionExists = locator != null,
        title = "Title",
        artist = "Artist",
        album = "Album",
        playCount = 3,
        completionCount = 2,
        skipCount = 1,
        totalListenedMs = 500_000L,
        firstPlayedAt = 1_700_000_000_000L,
        lastPlayedAt = 1_700_000_500_000L,
        lastCompletedAt = 1_700_000_500_000L,
        lastSkippedAt = 0L,
        session = session,
    )

    private fun rows(d: TrackIntelligenceDiagnostic) = d.rows().associate { it.label to it.value }

    @Test
    @DisplayName("a counter that was never recorded reads as never, not as 1970")
    fun anUnrecordedTimestampIsShownAsNever() {
        // Zero is the schema's documented encoding of "never" — `MIGRATION_6_7` chose it rather than
        // guess a first-played date it could not know. Rendering it as an epoch would silently
        // misdate every track the user has owned for years.
        assertEquals("— (never)", rows(diagnostic())["last skipped"])
        assertTrue(rows(diagnostic())["last completed"]!!.startsWith("20"))
    }

    @Test
    @DisplayName("matching evidence the scan has not computed says so, rather than showing a blank")
    fun uncomputedEvidenceExplainsItself() {
        assertEquals("— (not recorded yet)", rows(diagnostic(stableKey = ""))["matching fingerprint"])
        assertEquals("— (not recorded yet)", rows(diagnostic(stableKey = ""))["file signature"])
        assertEquals("abc123", rows(diagnostic(stableKey = "abc123"))["matching fingerprint"])
    }

    @Test
    @DisplayName("an item with no projection is described, not hidden")
    fun anItemWithNoProjectionIsStillDescribed() {
        val d = diagnostic(locator = null)
        val r = rows(d)
        assertEquals("— (no projection)", r["current locator"])
        assertEquals("none", r["projection row"])
        // The counters are the point: this is exactly the state where the user's history survives
        // and the track is not playable, and the diagnostic has to show both halves of that.
        assertEquals("3", r["plays"])
        assertEquals("2", r["completions"])
    }

    @Test
    @DisplayName("a session in flight is reported as measured so far, never as a finished outcome")
    fun aLiveSessionIsNotPresentedAsAnOutcome() {
        val d = diagnostic(
            session = TrackIntelligenceDiagnostic.SessionView(
                listenedMs = 60_000L,
                totalDurationMs = 240_000L,
                isPlaying = true,
                endReason = TrackEndReason.UNKNOWN,
                projectedOutcome = PlaybackOutcome.PLAYED,
            )
        )
        val r = rows(d)
        assertEquals("1m 0s", r["listened this session"])
        assertEquals("4m 0s", r["track duration"])
        assertEquals("UNKNOWN", r["TrackEndReason"])
        assertEquals("PLAYED (measured so far, reason unknown while playing)", r["projected PlaybackOutcome"])
    }

    @Test
    @DisplayName("with no session the section says so instead of showing zeroes")
    fun noSessionIsStatedPlainly() {
        assertEquals("no session in flight", rows(diagnostic(session = null))["state"])
        assertFalse(diagnostic(session = null).rows().any { it.label == "listened this session" })
    }

    @Test
    @DisplayName("the ownership rule is stated in the diagnostic itself")
    fun theOwnershipRuleIsVisible() {
        val r = rows(diagnostic())
        assertEquals("library_item_id", r["user state owner"])
        assertEquals("how the file is found; disposable", r["locator role"])
    }

    @Test
    @DisplayName("an unassigned identity is shown as unassigned rather than as an empty string")
    fun anUnassignedIdentitySaysSo() {
        assertEquals("— (not assigned)", rows(diagnostic(libraryItemId = ""))["library_item_id"])
    }

    @Test
    @DisplayName("durations render in the unit that makes them readable")
    fun durationsRenderReadably() {
        assertEquals("0s", TrackIntelligenceDiagnostic.formatDuration(0L))
        assertEquals("45s", TrackIntelligenceDiagnostic.formatDuration(45_000L))
        assertEquals("3m 20s", TrackIntelligenceDiagnostic.formatDuration(200_000L))
        assertEquals("1h 2m 3s", TrackIntelligenceDiagnostic.formatDuration(3_723_000L))
    }
}
