package com.lostf1sh.pixelplayeross.data.collections

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The rule engine, as a corpus.
 *
 * A collection is a predicate, so almost everything worth testing about it is a statement about
 * values — which is why the engine is pure and why this suite needs no database. The exceptions are
 * the identity behaviours, which are structural: a candidate carries a `library_item_id`, and the
 * engine has no other way to name a track.
 */
class SmartCollectionEngineTest {

    private val engine = SmartCollectionEngine()

    private val now = 1_800_000_000_000L
    private val day = TimeUnit.DAYS.toMillis(1)
    private fun daysAgo(days: Long): Long = now - days * day

    private fun track(
        id: String,
        title: String? = null,
        artist: String? = "Artist",
        albumArtist: String? = null,
        album: String? = "Album",
        genres: List<String> = emptyList(),
        plays: Int = 0,
        completions: Int = 0,
        skips: Int = 0,
        durationMs: Long = 200_000L,
        trackNumber: Int? = null,
        discNumber: Int? = null,
        year: Int? = null,
        bitrate: Int? = null,
        sampleRate: Int? = null,
        firstSeenDaysAgo: Long = 100,
        lastPlayedDaysAgo: Long? = null,
        lastCompletedDaysAgo: Long? = null,
        lastSkippedDaysAgo: Long? = null,
        favourite: Boolean = false,
        compilation: Boolean = false,
        duplicate: Boolean = false,
        albumGroupingKey: String = "alb:album|artist",
        locator: Long = id.hashCode().toLong() and 0xffff,
    ) = CollectionCandidate(
        libraryItemId = id,
        locator = locator,
        title = title ?: "Title $id",
        trackArtist = artist,
        albumArtist = albumArtist,
        album = album,
        genres = genres,
        playCount = plays,
        completionCount = completions,
        skipCount = skips,
        durationMs = durationMs,
        trackNumber = trackNumber,
        discNumber = discNumber,
        releaseYear = year,
        bitrate = bitrate,
        sampleRate = sampleRate,
        firstSeenAt = daysAgo(firstSeenDaysAgo),
        lastPlayedAt = lastPlayedDaysAgo?.let { daysAgo(it) } ?: 0L,
        lastCompletedAt = lastCompletedDaysAgo?.let { daysAgo(it) } ?: 0L,
        lastSkippedAt = lastSkippedDaysAgo?.let { daysAgo(it) } ?: 0L,
        isFavourite = favourite,
        isCompilation = compilation,
        isProbableDuplicate = duplicate,
        albumGroupingKey = albumGroupingKey,
    )

    private fun rule(
        target: SmartCollectionField,
        operation: SmartCollectionOperator,
        text: String? = null,
        number: Double? = null,
        second: Double? = null,
    ) = SmartCollectionRule(target = target, operation = operation, textValue = text, numberValue = number, secondNumberValue = second)

    private fun collection(
        vararg rules: SmartCollectionRule,
        match: SmartCollectionMatchMode = SmartCollectionMatchMode.ALL,
        sort: SmartCollectionSortField = SmartCollectionSortField.TITLE,
        direction: SmartCollectionSortDirection = SmartCollectionSortDirection.ASCENDING,
        limit: Int? = null,
    ) = SmartCollection(
        id = 1L,
        name = "Test",
        matchMode = match,
        rules = rules.toList(),
        sortField = sort,
        sortDirection = direction,
        limit = limit,
    )

    private fun ids(result: SmartCollectionResult) = result.entries.map { it.libraryItemId }

    private fun run(c: SmartCollection, tracks: List<CollectionCandidate>) = engine.evaluate(c, tracks, now)

    // --- A. ALL ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("A · favourite AND play count >= 10")
    fun a_favouriteAndPlayCount() {
        val library = listOf(
            track("both", plays = 14, favourite = true),
            track("favourite-only", plays = 3, favourite = true),
            track("played-only", plays = 40, favourite = false),
        )
        val result = run(
            collection(
                rule(SmartCollectionField.FAVOURITE, SmartCollectionOperator.IS_TRUE),
                rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.GREATER_OR_EQUAL, number = 10.0),
            ),
            library,
        )
        assertEquals(listOf("both"), ids(result))
    }

    // --- B. ANY ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("B · track artist is Queen OR album artist is Queen")
    fun b_anyAcrossTwoFields() {
        val library = listOf(
            track("performer", artist = "Queen", albumArtist = "Various Artists"),
            track("owner", artist = "Freddie Mercury", albumArtist = "Queen"),
            track("neither", artist = "ABBA", albumArtist = "ABBA"),
        )
        val result = run(
            collection(
                rule(SmartCollectionField.TRACK_ARTIST, SmartCollectionOperator.IS, text = "Queen"),
                rule(SmartCollectionField.ALBUM_ARTIST, SmartCollectionOperator.IS, text = "Queen"),
                match = SmartCollectionMatchMode.ANY,
            ),
            library,
        )
        assertEquals(setOf("performer", "owner"), ids(result).toSet())
    }

    // --- C. multi-valued genre ---------------------------------------------------------------------------

    @Test
    @DisplayName("C · genre contains Rock matches a track tagged with several genres")
    fun c_multiValuedGenre() {
        val library = listOf(
            track("rock-and-pop", genres = listOf("Rock", "Pop")),
            track("pop-only", genres = listOf("Pop")),
            track("no-genre", genres = emptyList()),
        )
        val result = run(collection(rule(SmartCollectionField.GENRE, SmartCollectionOperator.CONTAINS, text = "rock")), library)
        assertEquals(listOf("rock-and-pop"), ids(result))
    }

    // --- D. unknown semantics -----------------------------------------------------------------------------

    @Test
    @DisplayName("D · a track with no genre satisfies neither 'is Rock' nor 'is not Rock'")
    fun d_unknownGenreIsNotEvidenceEitherWay() {
        val untagged = track("untagged", genres = emptyList())
        val rock = track("rock", genres = listOf("Rock"))

        val isRock = run(collection(rule(SmartCollectionField.GENRE, SmartCollectionOperator.IS, text = "Rock")), listOf(untagged, rock))
        assertEquals(listOf("rock"), ids(isRock))

        // The interesting one. A naive `value != "Rock"` would return the untagged track here.
        val isNotRock = run(collection(rule(SmartCollectionField.GENRE, SmartCollectionOperator.IS_NOT, text = "Rock")), listOf(untagged, rock))
        assertEquals(emptyList<String>(), ids(isNotRock), "a track with no genre has not made 'not Rock' true")

        // And the question that *is* about absence has its own operator.
        val unknown = run(collection(rule(SmartCollectionField.GENRE, SmartCollectionOperator.IS_UNKNOWN)), listOf(untagged, rock))
        assertEquals(listOf("untagged"), ids(unknown))
    }

    @Test
    @DisplayName("D · a missing release year fails every numeric comparison, in both directions")
    fun d_missingYearFailsBothDirections() {
        val noYear = track("no-year", year = null)
        val nineties = track("nineties", year = 1994)

        assertEquals(
            listOf("nineties"),
            ids(run(collection(rule(SmartCollectionField.RELEASE_YEAR, SmartCollectionOperator.GREATER_THAN, number = 1900.0)), listOf(noYear, nineties))),
        )
        assertEquals(
            emptyList<String>(),
            ids(run(collection(rule(SmartCollectionField.RELEASE_YEAR, SmartCollectionOperator.LESS_THAN, number = 2000.0)), listOf(noYear))),
        )
        assertEquals(
            listOf("no-year"),
            ids(run(collection(rule(SmartCollectionField.RELEASE_YEAR, SmartCollectionOperator.IS_UNKNOWN)), listOf(noYear, nineties))),
        )
    }

    @Test
    @DisplayName("D · a never-played track has no last-played date, and play count zero is a fact")
    fun d_neverPlayedIsUnknownAsADateButZeroAsACount() {
        val never = track("never", plays = 0)

        // A counter: "never played" is a true fact about it, so count rules see a real zero.
        assertEquals(listOf("never"), ids(run(collection(rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.EQUALS, number = 0.0)), listOf(never))))
        // A date: zero means the event never happened, which is unknown, not midnight in 1970.
        assertEquals(listOf("never"), ids(run(collection(rule(SmartCollectionField.LAST_PLAYED, SmartCollectionOperator.IS_UNKNOWN)), listOf(never))))
        assertEquals(emptyList<String>(), ids(run(collection(rule(SmartCollectionField.LAST_PLAYED, SmartCollectionOperator.BEFORE, number = now.toDouble())), listOf(never))))
        assertEquals(emptyList<String>(), ids(run(collection(rule(SmartCollectionField.LAST_PLAYED, SmartCollectionOperator.NOT_IN_LAST_DAYS, number = 90.0)), listOf(never))))
    }

    // --- E. between -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("E · year between 1990 and 1999, bounds inclusive and order-independent")
    fun e_yearBetween() {
        val library = listOf(
            track("1989", year = 1989), track("1990", year = 1990), track("1995", year = 1995),
            track("1999", year = 1999), track("2000", year = 2000), track("none", year = null),
        )
        val result = run(collection(rule(SmartCollectionField.RELEASE_YEAR, SmartCollectionOperator.BETWEEN, number = 1990.0, second = 1999.0)), library)
        assertEquals(setOf("1990", "1995", "1999"), ids(result).toSet())

        // Reversed bounds mean the same thing rather than matching nothing.
        val reversed = run(collection(rule(SmartCollectionField.RELEASE_YEAR, SmartCollectionOperator.BETWEEN, number = 1999.0, second = 1990.0)), library)
        assertEquals(setOf("1990", "1995", "1999"), ids(reversed).toSet())
    }

    // --- F / G. engagement -------------------------------------------------------------------------------------

    @Test
    @DisplayName("F · never played")
    fun f_neverPlayed() {
        val library = listOf(track("never", plays = 0), track("played", plays = 1))
        assertEquals(listOf("never"), ids(run(collection(rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.EQUALS, number = 0.0)), library)))
    }

    @Test
    @DisplayName("G · not heard in the last 90 days, and never-played tracks are a different question")
    fun g_notHeardIn90Days() {
        val library = listOf(
            track("yesterday", plays = 5, lastPlayedDaysAgo = 1),
            track("a-year-ago", plays = 5, lastPlayedDaysAgo = 365),
            track("never", plays = 0),
        )
        val result = run(
            collection(
                rule(SmartCollectionField.LAST_PLAYED, SmartCollectionOperator.IS_KNOWN),
                rule(SmartCollectionField.LAST_PLAYED, SmartCollectionOperator.NOT_IN_LAST_DAYS, number = 90.0),
            ),
            library,
        )
        // The `is known` guard is what keeps "not heard lately" from silently meaning "including
        // everything never heard at all".
        assertEquals(listOf("a-year-ago"), ids(result))
    }

    // --- H. first seen ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("H · added in the last 30 days uses first saw, not a MediaStore date")
    fun h_addedInLast30Days() {
        val library = listOf(track("new", firstSeenDaysAgo = 3), track("old", firstSeenDaysAgo = 400))
        val result = run(collection(rule(SmartCollectionField.FIRST_SEEN, SmartCollectionOperator.IN_LAST_DAYS, number = 30.0)), library)
        assertEquals(listOf("new"), ids(result))
        // Structural: `CollectionCandidate` carries only `firstSeenAt`. There is no MediaStore date
        // on the candidate for a rule to reach, so a scanner rebuild cannot make an old track new.
    }

    // --- I / J / K / L. identity ---------------------------------------------------------------------------------

    @Test
    @DisplayName("I · a renumber does not change membership")
    fun i_renumberPreservesMembership() {
        val before = track("L1", plays = 12, locator = 11L)
        val after = before.copy(locator = 22L)
        val c = collection(rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.GREATER_OR_EQUAL, number = 10.0))

        assertEquals(ids(run(c, listOf(before))), ids(run(c, listOf(after))))
        assertEquals(11L, run(c, listOf(before)).entries.single().locator)
        assertEquals(22L, run(c, listOf(after)).entries.single().locator, "the locator is reported, not owned")
    }

    @Test
    @DisplayName("J · a dormant track is not in the candidate set at all")
    fun j_dormantTrackIsAbsent() {
        // The engine only ever sees the ACTIVE projection — the repository's query is where the
        // filter lives, and every other surface in the app uses the same one. This asserts the
        // engine's contract with that input: what it is not given, it cannot return.
        val live = listOf(track("live", plays = 50))
        val c = collection(rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.GREATER_OR_EQUAL, number = 1.0))
        assertEquals(listOf("live"), ids(run(c, live)))
        assertFalse(ids(run(c, live)).any { it == "dormant" })
    }

    @Test
    @DisplayName("K/L · a returned track and a reused locator carry no inherited history")
    fun k_and_l_identityIsNotTheLocator() {
        val returning = track("L1", plays = 30, locator = 11L, lastPlayedDaysAgo = 200)
        val newcomer = track("L2", plays = 0, locator = 11L, firstSeenDaysAgo = 3)

        val played = collection(rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.GREATER_OR_EQUAL, number = 10.0))
        assertEquals(listOf("L1"), ids(run(played, listOf(returning))))
        // The newcomer sits on the same locator and inherits nothing.
        assertEquals(emptyList<String>(), ids(run(played, listOf(newcomer))))
    }

    // --- M. overrides ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("M · a rule matches the resolved value, not the raw one")
    fun m_rulesSeeResolvedMetadata() {
        // The candidate carries resolved values by construction: the projection the repository reads
        // already holds the user's override (Phase 12.1's derived mirror). So a rule naming the
        // user's album finds the track, and one naming the file's album does not.
        val overridden = track("L1", album = "Discovery")
        val c = collection(rule(SmartCollectionField.ALBUM, SmartCollectionOperator.IS, text = "Discovery"))
        assertEquals(listOf("L1"), ids(run(c, listOf(overridden))))

        val oldName = collection(rule(SmartCollectionField.ALBUM, SmartCollectionOperator.IS, text = "Homework"))
        assertEquals(emptyList<String>(), ids(run(oldName, listOf(overridden))))
    }

    // --- N / O. metadata ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("N · compilation and O · disc number")
    fun n_and_o_compilationAndDisc() {
        val library = listOf(
            track("comp-disc2", compilation = true, discNumber = 2, trackNumber = 1),
            track("comp-disc1", compilation = true, discNumber = 1, trackNumber = 12),
            track("normal", compilation = false, discNumber = null),
        )
        assertEquals(
            setOf("comp-disc1", "comp-disc2"),
            ids(run(collection(rule(SmartCollectionField.COMPILATION, SmartCollectionOperator.IS_TRUE)), library)).toSet(),
        )
        assertEquals(
            listOf("comp-disc2"),
            ids(run(collection(rule(SmartCollectionField.DISC_NUMBER, SmartCollectionOperator.EQUALS, number = 2.0)), library)),
        )
        // No disc number is unknown, so it matches neither `is 1` nor `is not 1`.
        assertEquals(
            emptyList<String>(),
            ids(run(collection(rule(SmartCollectionField.DISC_NUMBER, SmartCollectionOperator.NOT_EQUALS, number = 1.0)), library.filter { it.libraryItemId == "normal" })),
        )
    }

    // --- P. ALL vs ANY ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("P · ALL and ANY genuinely differ")
    fun p_allVersusAny() {
        val library = listOf(track("a", plays = 50, genres = emptyList()), track("b", plays = 1, genres = listOf("Jazz")))
        val rules = arrayOf(
            rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.GREATER_OR_EQUAL, number = 10.0),
            rule(SmartCollectionField.GENRE, SmartCollectionOperator.IS, text = "Jazz"),
        )
        assertEquals(emptyList<String>(), ids(run(collection(*rules, match = SmartCollectionMatchMode.ALL), library)))
        assertEquals(setOf("a", "b"), ids(run(collection(*rules, match = SmartCollectionMatchMode.ANY), library)).toSet())
    }

    // --- Q / R / S. sorting and limit --------------------------------------------------------------------------------

    @Test
    @DisplayName("Q · descending play count")
    fun q_sortByPlayCountDescending() {
        val library = listOf(track("low", plays = 1), track("high", plays = 30), track("mid", plays = 9))
        val result = run(
            collection(rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.GREATER_OR_EQUAL, number = 1.0), sort = SmartCollectionSortField.PLAY_COUNT, direction = SmartCollectionSortDirection.DESCENDING),
            library,
        )
        assertEquals(listOf("high", "mid", "low"), ids(result))
    }

    @Test
    @DisplayName("R · album order is disc then track, and is never reversed")
    fun r_albumOrderSortsDiscThenTrack() {
        val library = listOf(
            track("d2t1", trackNumber = 1, discNumber = 2, albumGroupingKey = "alb:the wall|pink floyd"),
            track("d1t12", trackNumber = 12, discNumber = 1, albumGroupingKey = "alb:the wall|pink floyd"),
            track("d1t2", trackNumber = 2, discNumber = 1, albumGroupingKey = "alb:the wall|pink floyd"),
            track("earlier-album", trackNumber = 1, discNumber = null, albumGroupingKey = "alb:animals|pink floyd"),
        )
        val rules = arrayOf(rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.EQUALS, number = 0.0))

        val ascending = run(collection(*rules, sort = SmartCollectionSortField.ALBUM_ORDER), library)
        assertEquals(listOf("earlier-album", "d1t2", "d1t12", "d2t1"), ids(ascending))

        // Descending is deliberately ignored for album order: a discography played backwards is not
        // what anyone wants, and honouring it would put disc 2 before disc 1.
        val descending = run(collection(*rules, sort = SmartCollectionSortField.ALBUM_ORDER, direction = SmartCollectionSortDirection.DESCENDING), library)
        assertEquals(ids(ascending), ids(descending))
    }

    @Test
    @DisplayName("R · a track with no track number sorts last within its disc, not first")
    fun r_missingTrackNumberSortsLast() {
        val library = listOf(
            track("unnumbered", trackNumber = null, discNumber = 1),
            track("numbered", trackNumber = 5, discNumber = 1),
        )
        val result = run(collection(rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.EQUALS, number = 0.0), sort = SmartCollectionSortField.ALBUM_ORDER), library)
        assertEquals(listOf("numbered", "unnumbered"), ids(result))
    }

    @Test
    @DisplayName("S · the limit is applied after sorting")
    fun s_limitAppliesAfterSort() {
        val library = (1..10).map { track("t$it", plays = it) }
        val result = run(
            collection(rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.GREATER_OR_EQUAL, number = 1.0), sort = SmartCollectionSortField.PLAY_COUNT, direction = SmartCollectionSortDirection.DESCENDING, limit = 3),
            library,
        )
        assertEquals(listOf("t10", "t9", "t8"), ids(result))
        assertEquals(10, result.matchedCount, "the match count is reported before the limit")
        assertEquals(3, result.limitApplied)
    }

    // --- T. invalid rules -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("T · an invalid rule does not crash and does not silently change the collection")
    fun t_invalidRuleIsReported() {
        // A stored rule whose value never got written — the shape a hand-edited or half-migrated
        // collection would have.
        val broken = SmartCollectionRule(
            target = SmartCollectionField.PLAY_COUNT,
            operation = SmartCollectionOperator.GREATER_OR_EQUAL,
            numberValue = null,
        )
        assertFalse(broken.isValid)

        val result = run(
            collection(broken, rule(SmartCollectionField.FAVOURITE, SmartCollectionOperator.IS_TRUE)),
            listOf(track("a", favourite = true)),
        )
        assertTrue(result.needsAttention)
        assertEquals(listOf(broken), result.invalidRules)
        // Under ALL, ignoring the broken rule would *widen* the collection — so nothing is returned.
        assertEquals(emptyList<String>(), ids(result))
    }

    @Test
    @DisplayName("T · an operator that does not belong to its field is rejected, not evaluated")
    fun t_mismatchedOperatorIsRejected() {
        // "play count contains foo" — the brief's example of a nonsensical combination. The model
        // cannot express it sanely, so it is invalid rather than interpreted.
        val nonsense = SmartCollectionRule(
            target = SmartCollectionField.PLAY_COUNT,
            operation = SmartCollectionOperator.CONTAINS,
            textValue = "foo",
        )
        assertFalse(nonsense.isValid)
        assertTrue(run(collection(nonsense), listOf(track("a", plays = 5))).needsAttention)
    }

    @Test
    @DisplayName("T · a rule referencing a field the build does not know is surfaced, not dropped")
    fun t_unparsedRuleIsSurfaced() {
        val collection = SmartCollection(
            id = 1L,
            name = "From a newer build",
            rules = listOf(rule(SmartCollectionField.FAVOURITE, SmartCollectionOperator.IS_TRUE)),
            unparsedRules = listOf("mood is_euphoric"),
        )
        val result = run(collection, listOf(track("a", favourite = true)))

        assertTrue(result.needsAttention)
        assertEquals(listOf("mood is_euphoric"), result.unparsedRuleDescriptions)
        // Dropping the unknown rule would have quietly widened the collection, so none is returned.
        assertEquals(emptyList<String>(), ids(result))
    }

    // --- U / V. determinism ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("U · the same inputs produce the same order, every time")
    fun u_deterministicOrder() {
        // Deliberately full of ties: every track has the same play count, so the whole order is
        // decided by the tie-break.
        val library = (1..60).map { track("t$it", plays = 5) }
        val c = collection(rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.EQUALS, number = 5.0), sort = SmartCollectionSortField.PLAY_COUNT)

        val first = ids(run(c, library))
        repeat(5) { assertEquals(first, ids(run(c, library))) }
        assertEquals(60, first.size)
    }

    @Test
    @DisplayName("V · no duplicate identity, however the sort reverses")
    fun v_noDuplicateIdentities() {
        val library = (1..40).map { track("t$it", plays = it, artist = "A${it % 3}") }
        val c = collection(rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.GREATER_OR_EQUAL, number = 1.0), sort = SmartCollectionSortField.PLAY_COUNT, direction = SmartCollectionSortDirection.DESCENDING)
        val result = run(c, library)
        assertEquals(result.entries.size, ids(result).toSet().size)
    }

    // --- W. dormant --------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("W · an empty collection is a valid answer, not an error")
    fun w_emptyResultIsValid() {
        val result = run(
            collection(
                rule(SmartCollectionField.FAVOURITE, SmartCollectionOperator.IS_TRUE),
                rule(SmartCollectionField.GENRE, SmartCollectionOperator.IS, text = "Jazz"),
            ),
            listOf(track("a", favourite = true, genres = listOf("Rock"))),
        )
        assertTrue(result.isEmpty)
        assertFalse(result.needsAttention)
        assertEquals(1, result.candidateCount)
        assertEquals(0, result.matchedCount)
    }

    // --- text operators -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("text comparisons fold case and whitespace, and nothing else")
    fun textComparisonsAreConservative() {
        val library = listOf(
            track("queen", artist = "Queen"),
            track("queen-uk", artist = "  queen  "),
            track("acdc-slash", artist = "AC/DC"),
            track("acdc-plain", artist = "ACDC"),
        )
        assertEquals(
            setOf("queen", "queen-uk"),
            ids(run(collection(rule(SmartCollectionField.TRACK_ARTIST, SmartCollectionOperator.IS, text = "QUEEN")), library)).toSet(),
        )
        // No fuzzy matching: two spellings of one artist stay two artists.
        assertEquals(
            listOf("acdc-slash"),
            ids(run(collection(rule(SmartCollectionField.TRACK_ARTIST, SmartCollectionOperator.IS, text = "AC/DC")), library)),
        )
        // Both, since "Queen" starts with "queen" as well — the point being that the comparison is
        // case-folded rather than that only one of them qualifies.
        assertEquals(
            setOf("queen", "queen-uk"),
            ids(run(collection(rule(SmartCollectionField.TRACK_ARTIST, SmartCollectionOperator.STARTS_WITH, text = "queen")), library)).toSet(),
        )
        assertEquals(
            setOf("acdc-slash", "acdc-plain"),
            ids(run(collection(rule(SmartCollectionField.TRACK_ARTIST, SmartCollectionOperator.ENDS_WITH, text = "c")), library)).toSet(),
        )
        assertEquals(
            setOf("queen", "queen-uk"),
            ids(run(collection(rule(SmartCollectionField.TRACK_ARTIST, SmartCollectionOperator.ENDS_WITH, text = "n")), library)).toSet(),
        )
    }

    // --- explainability --------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the diagnostic reports each rule's verdict and the actual value")
    fun explainReportsEveryRule() {
        val candidate = track("L1", plays = 14, genres = listOf("Soul", "R&B"))
        val c = collection(
            rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.GREATER_OR_EQUAL, number = 10.0),
            rule(SmartCollectionField.GENRE, SmartCollectionOperator.CONTAINS, text = "Jazz"),
        )

        val explained = engine.explain(c, candidate, now)

        assertEquals(2, explained.size)
        assertEquals(RuleEvaluation.Verdict.PASS, explained[0].result)
        assertEquals("14", explained[0].actual)
        assertEquals(RuleEvaluation.Verdict.FAIL, explained[1].result)
        assertEquals("[Soul, R&B]", explained[1].actual)
    }

    @Test
    @DisplayName("the diagnostic distinguishes 'no value' from 'the value did not match'")
    fun explainDistinguishesUnknownFromFail() {
        val candidate = track("L1", genres = emptyList())
        val explained = engine.explain(collection(rule(SmartCollectionField.GENRE, SmartCollectionOperator.IS, text = "Rock")), candidate, now)
        assertEquals(RuleEvaluation.Verdict.UNKNOWN, explained.single().result)
        assertEquals("— (no genre)", explained.single().actual)
    }

    // --- summary ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("every field has at least one operator an editor could offer")
    fun everyFieldIsUsable() {
        SmartCollectionField.entries.forEach { field ->
            assertTrue(
                SmartCollectionOperator.forField(field).isNotEmpty(),
                "${field.storageKey} has no operators, so no rule can mention it",
            )
        }
        // And no operator is offered for a type it cannot compare.
        assertFalse(SmartCollectionOperator.CONTAINS in SmartCollectionOperator.forField(SmartCollectionField.PLAY_COUNT))
        assertFalse(SmartCollectionOperator.GREATER_THAN in SmartCollectionOperator.forField(SmartCollectionField.TITLE))
        assertNotEquals(0, SmartCollectionOperator.forField(SmartCollectionField.GENRE).size)
    }
}
