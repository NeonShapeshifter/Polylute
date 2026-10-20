package com.lostf1sh.pixelplayeross.data.collections

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One library item, reduced to exactly the fields a rule can address.
 *
 * Built by one bulk query. The engine never asks the database anything per candidate — a rule set
 * over a few thousand rows is a single read and then arithmetic, which is what makes an interactive
 * editor possible and keeps an N+1 shape structurally impossible.
 *
 * Every text field here is the **resolved** value: the user's override if there is one, otherwise
 * the normalized source metadata. A rule must never see raw metadata behind a correction the user
 * made deliberately.
 */
data class CollectionCandidate(
    val libraryItemId: String,
    /** The current MediaStore locator. Used to play the result, never to identify it. */
    val locator: Long,

    val title: String?,
    val trackArtist: String?,
    val albumArtist: String?,
    val album: String?,
    /** Parsed genre values. Empty means "no genre", which is different from "genre is empty text". */
    val genres: List<String>,

    val playCount: Int,
    val completionCount: Int,
    val skipCount: Int,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val releaseYear: Int?,
    val bitrate: Int?,
    val sampleRate: Int?,

    /** Durable first-seen, owned by the library item. Never MediaStore's date-added. */
    val firstSeenAt: Long,
    val lastPlayedAt: Long,
    val lastCompletedAt: Long,
    val lastSkippedAt: Long,

    val isFavourite: Boolean,
    val isCompilation: Boolean,
    val isProbableDuplicate: Boolean,

    // Grouping inputs for album-order sorting, from Phase 12.3.
    val albumGroupingKey: String,
) {
    /** True when the track has ever been started. "Never played" is a fact, not a zero. */
    val hasBeenPlayed: Boolean get() = playCount > 0
}

/**
 * One rule's verdict on one track.
 *
 * [actual] is carried so the diagnostic can show what the track's value *was* when the rule failed,
 * which is the only way "why did this not match?" can be answered without re-deriving it by hand.
 */
data class RuleEvaluation(
    val rule: SmartCollectionRule,
    val result: Verdict,
    val actual: String,
) {
    enum class Verdict {
        PASS,
        FAIL,

        /**
         * The field had no value and the operator was an `is known` / `is unknown` question, or the
         * rule itself could not be evaluated.
         *
         * Kept distinct from FAIL so the diagnostic can say *why* something did not match: "there is
         * no release year on this track" is a different answer from "the release year is 1994".
         */
        UNKNOWN,
    }
}

/** What a collection produced, with enough detail to explain any individual track. */
data class SmartCollectionResult(
    val collectionId: Long,
    val entries: List<CollectionCandidate>,
    /** Every candidate considered, before rules and before the limit. */
    val candidateCount: Int,
    val matchedCount: Int,
    val limitApplied: Int?,
    /**
     * Rules that could not be evaluated. Non-empty means the collection needs attention; the result
     * is still returned so the user can see what the working rules produce.
     */
    val invalidRules: List<SmartCollectionRule> = emptyList(),
    /** Rules that could not even be parsed, as raw `field operator` descriptions. */
    val unparsedRuleDescriptions: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = entries.isEmpty()
    /** True when the collection is defined but its rules cannot all be evaluated. */
    val needsAttention: Boolean
        get() = invalidRules.isNotEmpty() || unparsedRuleDescriptions.isNotEmpty()
}

/**
 * The one rule engine.
 *
 * ## Two layers, and why
 *
 * This class is **pure**: candidates in, matches out, plus a clock. Everything that decides whether
 * a track belongs to a collection is a function of values that were passed in, so the whole rule
 * system is testable without a database, an Android runtime or a device — and the parts most worth
 * pinning are exactly the awkward ones, like what happens when a field has no value.
 *
 * Reading the library is somebody else's job. `SmartCollectionRepository` does that, in one query.
 *
 * ## Unknown data, and the deliberate choice made here
 *
 * Most rules *assert* something about a value. When the value is not there, there is nothing to
 * assert about, and **every asserting operator fails** — including the negated ones.
 *
 * ```
 *   Genre is "Rock"          track has no genre  →  FAIL
 *   Genre is not "Rock"      track has no genre  →  FAIL      ← the interesting one
 * ```
 *
 * The second line is the choice worth stating. It differs from what a naive Kotlin implementation
 * does (`value != "Rock"` is `true` for null) and it is stricter than SQL's three-valued logic,
 * which yields NULL and filters the row out — the same outcome here, reached by different reasoning
 * and made explicit rather than inherited.
 *
 * The reasoning: "this track's genre is not Rock" is a claim about the track's genre. A track with
 * no genre has not made that claim true, it has made it unanswerable. Since the brief's stance is
 * that unknown is not evidence for a positive *or* negative metadata assertion, both directions
 * fail, and the two operators that do mean something about absence — [SmartCollectionOperator.IS_KNOWN]
 * and [SmartCollectionOperator.IS_UNKNOWN] — exist so the other question is still askable.
 *
 * Numeric counters are the exception, and deliberately. A track with no engagement row has
 * `playCount = 0`, because "never played" is a true fact about it rather than a missing value. So
 * `Play count is 0` matches it, and `Play count is at least 1` does not. The date fields are *not*
 * like this: `lastPlayedAt = 0` means "never", which is unknown as a date, so every date operator
 * fails on it and only `IS_UNKNOWN` matches.
 */
@Singleton
class SmartCollectionEngine @Inject constructor() {

    /**
     * Evaluates [collection] over [candidates].
     *
     * [now] is passed in rather than read from the clock, so a collection's behaviour on a given day
     * is a pure function of its inputs.
     */
    fun evaluate(
        collection: SmartCollection,
        candidates: List<CollectionCandidate>,
        now: Long,
    ): SmartCollectionResult {
        val invalid = collection.rules.filterNot { it.isValid }

        // An unreadable rule is an invalid rule. It cannot be ignored: under ALL, dropping it would
        // quietly widen the collection, and the user would have no way to tell that their collection
        // had started answering a different question.
        //
        // So a collection that cannot be fully understood reports itself as needing attention and
        // returns nothing, rather than returning something that is not what it says it is.
        //
        // The guard is on `collection.isValid`, not on `invalid.isNotEmpty()`: a rule whose storage
        // keys this build does not recognise never becomes a `SmartCollectionRule` at all — it is
        // carried separately as a description — so checking only the parsed rules would let a
        // collection from a newer build run with part of its definition missing.
        if (invalid.isNotEmpty() || collection.unparsedRules.isNotEmpty()) {
            return SmartCollectionResult(
                collectionId = collection.id,
                entries = emptyList(),
                candidateCount = candidates.size,
                matchedCount = 0,
                limitApplied = collection.limit,
                invalidRules = invalid,
                unparsedRuleDescriptions = collection.unparsedRules,
            )
        }

        val matched = candidates.filter { candidate ->
            when (collection.matchMode) {
                SmartCollectionMatchMode.ALL -> collection.rules.all { matches(it, candidate, now) }
                SmartCollectionMatchMode.ANY -> collection.rules.any { matches(it, candidate, now) }
            }
        }

        val sorted = sort(matched, collection)
        val limited = collection.limit?.let { sorted.take(it.coerceAtLeast(0)) } ?: sorted

        return SmartCollectionResult(
            collectionId = collection.id,
            entries = limited,
            candidateCount = candidates.size,
            matchedCount = matched.size,
            limitApplied = collection.limit,
        )
    }

    /**
     * Every rule's verdict for one track, for the diagnostic.
     *
     * Runs through [evaluate]'s own comparison logic — not a reimplementation — so what the
     * diagnostic reports and what the collection does cannot drift apart.
     */
    fun explain(
        collection: SmartCollection,
        candidate: CollectionCandidate,
        now: Long,
    ): List<RuleEvaluation> = collection.rules.map { rule ->
        val actual = describeValue(rule.target, candidate)
        val verdict = when {
            !rule.isValid -> RuleEvaluation.Verdict.UNKNOWN
            matches(rule, candidate, now) -> RuleEvaluation.Verdict.PASS
            isUnknown(rule.target, candidate) -> RuleEvaluation.Verdict.UNKNOWN
            else -> RuleEvaluation.Verdict.FAIL
        }
        RuleEvaluation(rule = rule, result = verdict, actual = actual)
    }

    // --- comparison ---------------------------------------------------------------------------------

    private fun matches(rule: SmartCollectionRule, candidate: CollectionCandidate, now: Long): Boolean {
        if (!rule.isValid) return false
        val op = rule.operation

        // The two absence operators are answered before anything else, because they are the only
        // ones that are *about* absence.
        if (op == SmartCollectionOperator.IS_KNOWN) return !isUnknown(rule.target, candidate)
        if (op == SmartCollectionOperator.IS_UNKNOWN) return isUnknown(rule.target, candidate)

        return when (rule.target.type) {
            FieldType.TEXT -> matchText(textOf(rule.target, candidate), rule)
            FieldType.MULTI_TEXT -> matchMultiText(rule.target, candidate, rule)
            FieldType.NUMBER -> matchNumber(numberOf(rule.target, candidate), rule)
            FieldType.DATE -> matchDate(dateOf(rule.target, candidate), rule, now)
            FieldType.BOOLEAN -> matchBoolean(booleanOf(rule.target, candidate), rule)
        }
    }

    /** Case-insensitive, whitespace-normalized. `Queen` and `queen` are the same artist. */
    private fun matchText(value: String?, rule: SmartCollectionRule): Boolean {
        if (value == null) return false
        val haystack = normalize(value)
        val needle = normalize(rule.textValue.orEmpty())
        return when (rule.operation) {
            SmartCollectionOperator.IS -> haystack == needle
            SmartCollectionOperator.IS_NOT -> haystack != needle
            SmartCollectionOperator.CONTAINS -> haystack.contains(needle)
            SmartCollectionOperator.DOES_NOT_CONTAIN -> !haystack.contains(needle)
            SmartCollectionOperator.STARTS_WITH -> haystack.startsWith(needle)
            SmartCollectionOperator.ENDS_WITH -> haystack.endsWith(needle)
            else -> false
        }
    }

    /**
     * Genre is a list, and the rule is about *any* of its values.
     *
     * `Genre contains Rock` matches a track tagged `["Rock", "Pop"]`. `is` means "the track is
     * tagged with exactly this genre, among others" rather than "has only this genre" — asking for
     * tracks tagged Rock should not exclude a track that is also tagged Pop, which is what "has only
     * this genre" would do.
     */
    private fun matchMultiText(
        field: SmartCollectionField,
        candidate: CollectionCandidate,
        rule: SmartCollectionRule,
    ): Boolean {
        val values = genresOf(candidate)
        // No genre at all: every assertion about genre fails, in both directions.
        if (values.isEmpty()) return false
        val needle = normalize(rule.textValue.orEmpty())
        return when (rule.operation) {
            SmartCollectionOperator.IS -> values.any { normalize(it) == needle }
            // "is not Rock" means the track carries genres and none of them is Rock.
            SmartCollectionOperator.IS_NOT -> values.none { normalize(it) == needle }
            SmartCollectionOperator.CONTAINS -> values.any { normalize(it).contains(needle) }
            SmartCollectionOperator.DOES_NOT_CONTAIN -> values.none { normalize(it).contains(needle) }
            else -> false
        }
    }

    private fun matchNumber(value: Double?, rule: SmartCollectionRule): Boolean {
        if (value == null) return false
        val operand = rule.numberValue ?: return false
        return when (rule.operation) {
            SmartCollectionOperator.EQUALS -> value == operand
            SmartCollectionOperator.NOT_EQUALS -> value != operand
            SmartCollectionOperator.GREATER_THAN -> value > operand
            SmartCollectionOperator.GREATER_OR_EQUAL -> value >= operand
            SmartCollectionOperator.LESS_THAN -> value < operand
            SmartCollectionOperator.LESS_OR_EQUAL -> value <= operand
            SmartCollectionOperator.BETWEEN -> {
                val upper = rule.secondNumberValue ?: return false
                value in minOf(operand, upper)..maxOf(operand, upper)
            }
            else -> false
        }
    }

    private fun matchDate(value: Long?, rule: SmartCollectionRule, now: Long): Boolean {
        if (value == null) return false
        val operand = rule.numberValue?.toLong() ?: return false
        return when (rule.operation) {
            SmartCollectionOperator.BEFORE -> value < operand
            SmartCollectionOperator.AFTER -> value > operand
            SmartCollectionOperator.IN_LAST_DAYS ->
                value >= now - TimeUnit.DAYS.toMillis(operand)
            SmartCollectionOperator.NOT_IN_LAST_DAYS ->
                value < now - TimeUnit.DAYS.toMillis(operand)
            else -> false
        }
    }

    private fun matchBoolean(value: Boolean?, rule: SmartCollectionRule): Boolean {
        if (value == null) return false
        return when (rule.operation) {
            SmartCollectionOperator.IS_TRUE -> value
            SmartCollectionOperator.IS_FALSE -> !value
            else -> false
        }
    }

    // --- value access -------------------------------------------------------------------------------

    private fun textOf(field: SmartCollectionField, c: CollectionCandidate): String? = when (field) {
        SmartCollectionField.TITLE -> c.title
        SmartCollectionField.TRACK_ARTIST -> c.trackArtist
        SmartCollectionField.ALBUM_ARTIST -> c.albumArtist
        SmartCollectionField.ALBUM -> c.album
        else -> null
    }

    private fun genresOf(c: CollectionCandidate): List<String> = c.genres

    /**
     * Numeric fields.
     *
     * Counters are never null: a track with no engagement row genuinely has zero plays, and
     * returning null would make `Play count is 0` fail, which is wrong. The optional numbers —
     * year, disc, bitrate, sample rate — are null when the source did not provide them, and every
     * comparison against them then fails.
     */
    private fun numberOf(field: SmartCollectionField, c: CollectionCandidate): Double? = when (field) {
        SmartCollectionField.PLAY_COUNT -> c.playCount.toDouble()
        SmartCollectionField.COMPLETION_COUNT -> c.completionCount.toDouble()
        SmartCollectionField.SKIP_COUNT -> c.skipCount.toDouble()
        SmartCollectionField.DURATION_MS -> c.durationMs.toDouble()
        SmartCollectionField.TRACK_NUMBER -> c.trackNumber?.toDouble()
        SmartCollectionField.DISC_NUMBER -> c.discNumber?.toDouble()
        SmartCollectionField.RELEASE_YEAR -> c.releaseYear?.toDouble()
        SmartCollectionField.BITRATE -> c.bitrate?.toDouble()
        SmartCollectionField.SAMPLE_RATE -> c.sampleRate?.toDouble()
        else -> null
    }

    /**
     * Dates, as epoch millis.
     *
     * Zero means "never happened", which is *unknown as a date* rather than midnight in 1970 — so
     * it is returned as null and every date comparison fails on it. Only `is unknown` matches.
     */
    private fun dateOf(field: SmartCollectionField, c: CollectionCandidate): Long? = when (field) {
        SmartCollectionField.FIRST_SEEN -> c.firstSeenAt.takeIf { it > 0L }
        SmartCollectionField.LAST_PLAYED -> c.lastPlayedAt.takeIf { it > 0L }
        SmartCollectionField.LAST_COMPLETED -> c.lastCompletedAt.takeIf { it > 0L }
        SmartCollectionField.LAST_SKIPPED -> c.lastSkippedAt.takeIf { it > 0L }
        else -> null
    }

    private fun booleanOf(field: SmartCollectionField, c: CollectionCandidate): Boolean? = when (field) {
        SmartCollectionField.FAVOURITE -> c.isFavourite
        SmartCollectionField.COMPILATION -> c.isCompilation
        SmartCollectionField.PROBABLE_DUPLICATE -> c.isProbableDuplicate
        else -> null
    }

    /** Whether the field has no value on this track. Drives `is known` / `is unknown`. */
    private fun isUnknown(field: SmartCollectionField, c: CollectionCandidate): Boolean = when (field.type) {
        FieldType.TEXT -> textOf(field, c) == null
        FieldType.MULTI_TEXT -> genresOf(c).isEmpty()
        FieldType.NUMBER -> numberOf(field, c) == null
        FieldType.DATE -> dateOf(field, c) == null
        // A boolean field is never unknown: "not a favourite" is a fact, not a missing value.
        FieldType.BOOLEAN -> false
    }

    private fun describeValue(field: SmartCollectionField, c: CollectionCandidate): String = when (field.type) {
        FieldType.TEXT -> textOf(field, c) ?: "— (no value)"
        FieldType.MULTI_TEXT -> genresOf(c).takeIf { it.isNotEmpty() }?.toString() ?: "— (no genre)"
        FieldType.NUMBER -> numberOf(field, c)?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
            ?: "— (no value)"
        FieldType.DATE -> dateOf(field, c)?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "— (never)"
        FieldType.BOOLEAN -> booleanOf(field, c)?.toString() ?: "false"
    }

    private fun normalize(value: String): String = value.trim().lowercase()

    // --- sorting ---------------------------------------------------------------------------------------

    /**
     * Deterministic order, always.
     *
     * Every comparator ends with `libraryItemId`, so two tracks that compare equal cannot swap
     * places between two reads of the same library. An unspecified database order is not an order,
     * and a collection that reshuffles itself when nothing changed would be unusable.
     */
    private fun sort(
        candidates: List<CollectionCandidate>,
        collection: SmartCollection,
    ): List<CollectionCandidate> {
        val base: Comparator<CollectionCandidate> = when (collection.sortField) {
            SmartCollectionSortField.TITLE -> compareBy { it.title?.lowercase() ?: "￿" }
            SmartCollectionSortField.TRACK_ARTIST -> compareBy { it.trackArtist?.lowercase() ?: "￿" }
            SmartCollectionSortField.ALBUM_ARTIST -> compareBy { it.albumArtist?.lowercase() ?: "￿" }
            SmartCollectionSortField.ALBUM -> compareBy { it.album?.lowercase() ?: "￿" }
            SmartCollectionSortField.RELEASE_YEAR -> compareBy { it.releaseYear ?: Int.MAX_VALUE }
            SmartCollectionSortField.FIRST_SEEN -> compareBy { it.firstSeenAt }
            SmartCollectionSortField.LAST_PLAYED -> compareBy { it.lastPlayedAt }
            SmartCollectionSortField.PLAY_COUNT -> compareBy { it.playCount }
            SmartCollectionSortField.COMPLETION_COUNT -> compareBy { it.completionCount }
            SmartCollectionSortField.DURATION -> compareBy { it.durationMs }
            // Album order is never reversed: a discography played backwards is not a feature. The
            // direction applies to nothing here, which is why it is handled separately.
            SmartCollectionSortField.ALBUM_ORDER -> compareBy<CollectionCandidate> { it.albumGroupingKey }
                .thenBy { it.discNumber ?: Int.MAX_VALUE }
                .thenBy { it.trackNumber?.takeIf { n -> n > 0 } ?: Int.MAX_VALUE }
        }

        val directed = if (collection.sortField == SmartCollectionSortField.ALBUM_ORDER) {
            base
        } else {
            when (collection.sortDirection) {
                SmartCollectionSortDirection.ASCENDING -> base
                SmartCollectionSortDirection.DESCENDING -> base.reversed()
            }
        }

        // The tie-break runs last and always ascending, so reversing the sort does not also reverse
        // which of two equal tracks comes first.
        return candidates.sortedWith(directed.thenBy { it.libraryItemId })
    }
}
