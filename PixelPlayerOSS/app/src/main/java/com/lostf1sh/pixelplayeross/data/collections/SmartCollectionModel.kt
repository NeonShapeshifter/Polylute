package com.lostf1sh.pixelplayeross.data.collections

/**
 * The fields a rule can talk about.
 *
 * Every one of these is backed by data that actually exists on the row — nothing here is invented
 * to round out the list, and there are deliberately no fields whose value would have to be guessed.
 *
 * [storageKey] is what gets persisted. It is an explicit, stable identifier, never a Kotlin name:
 * renaming a class or an enum constant must not corrupt a user's saved collections, and a
 * collection saved by one build has to keep working in the next.
 */
enum class SmartCollectionField(
    val storageKey: String,
    val displayName: String,
    val type: FieldType,
) {
    // --- text -------------------------------------------------------------------------------------
    TITLE("title", "Title", FieldType.TEXT),
    TRACK_ARTIST("track_artist", "Track artist", FieldType.TEXT),
    ALBUM_ARTIST("album_artist", "Album artist", FieldType.TEXT),
    ALBUM("album", "Album", FieldType.TEXT),

    // --- multi-value text ---------------------------------------------------------------------------
    GENRE("genre", "Genre", FieldType.MULTI_TEXT),

    // --- numbers ------------------------------------------------------------------------------------
    PLAY_COUNT("play_count", "Play count", FieldType.NUMBER),
    COMPLETION_COUNT("completion_count", "Completion count", FieldType.NUMBER),
    SKIP_COUNT("skip_count", "Skip count", FieldType.NUMBER),
    DURATION_MS("duration_ms", "Duration", FieldType.NUMBER),
    TRACK_NUMBER("track_number", "Track number", FieldType.NUMBER),
    DISC_NUMBER("disc_number", "Disc number", FieldType.NUMBER),
    RELEASE_YEAR("release_year", "Release year", FieldType.NUMBER),
    BITRATE("bitrate", "Bitrate", FieldType.NUMBER),
    SAMPLE_RATE("sample_rate", "Sample rate", FieldType.NUMBER),

    // --- dates --------------------------------------------------------------------------------------
    FIRST_SEEN("first_seen", "First seen", FieldType.DATE),
    LAST_PLAYED("last_played", "Last played", FieldType.DATE),
    LAST_COMPLETED("last_completed", "Last completed", FieldType.DATE),
    LAST_SKIPPED("last_skipped", "Last skipped", FieldType.DATE),

    // --- booleans -----------------------------------------------------------------------------------
    FAVOURITE("favourite", "Favourite", FieldType.BOOLEAN),
    COMPILATION("compilation", "Compilation", FieldType.BOOLEAN),
    PROBABLE_DUPLICATE("probable_duplicate", "Probable duplicate", FieldType.BOOLEAN);

    companion object {
        fun fromStorageKey(key: String?): SmartCollectionField? =
            entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * What kind of value a field holds, which is what makes the operators type-safe.
 *
 * A rule can only ever be built from an operator that belongs to its field's type, so
 * "play count contains foo" is not a state the model can represent rather than a validation
 * failure to be caught later.
 */
enum class FieldType {
    TEXT,
    MULTI_TEXT,
    NUMBER,
    DATE,
    BOOLEAN,
}

/**
 * The comparisons a rule can make.
 *
 * [applicableTo] is the type-safety mechanism: the editor offers only these, and a persisted rule
 * whose operator does not belong to its field is rejected on load rather than evaluated oddly.
 */
enum class SmartCollectionOperator(
    val storageKey: String,
    val displayName: String,
    val applicableTo: Set<FieldType>,
    /** True when the comparison needs a second value, as `between` does. */
    val needsSecondValue: Boolean = false,
) {
    // text
    IS("is", "is", setOf(FieldType.TEXT, FieldType.MULTI_TEXT)),
    IS_NOT("is_not", "is not", setOf(FieldType.TEXT, FieldType.MULTI_TEXT)),
    CONTAINS("contains", "contains", setOf(FieldType.TEXT, FieldType.MULTI_TEXT)),
    DOES_NOT_CONTAIN("does_not_contain", "does not contain", setOf(FieldType.TEXT, FieldType.MULTI_TEXT)),
    STARTS_WITH("starts_with", "starts with", setOf(FieldType.TEXT)),
    ENDS_WITH("ends_with", "ends with", setOf(FieldType.TEXT)),

    // number
    EQUALS("equals", "equals", setOf(FieldType.NUMBER)),
    NOT_EQUALS("not_equals", "does not equal", setOf(FieldType.NUMBER)),
    GREATER_THAN("gt", "is greater than", setOf(FieldType.NUMBER)),
    GREATER_OR_EQUAL("gte", "is at least", setOf(FieldType.NUMBER)),
    LESS_THAN("lt", "is less than", setOf(FieldType.NUMBER)),
    LESS_OR_EQUAL("lte", "is at most", setOf(FieldType.NUMBER)),
    BETWEEN("between", "is between", setOf(FieldType.NUMBER), needsSecondValue = true),

    // date — all comparisons are against "now", so no date value is stored
    BEFORE("before", "is before", setOf(FieldType.DATE)),
    AFTER("after", "is after", setOf(FieldType.DATE)),
    IN_LAST_DAYS("in_last_days", "is in the last N days", setOf(FieldType.DATE)),
    NOT_IN_LAST_DAYS("not_in_last_days", "is not in the last N days", setOf(FieldType.DATE)),

    // boolean
    IS_TRUE("is_true", "is true", setOf(FieldType.BOOLEAN)),
    IS_FALSE("is_false", "is false", setOf(FieldType.BOOLEAN)),

    /**
     * The two operators that make unknown data expressible.
     *
     * They exist because every other operator *fails* on unknown — see [SmartCollectionEngine] for
     * why — so without these there would be no way to ask for tracks that have no genre, no year or
     * no play history at all.
     */
    IS_KNOWN("is_known", "is known", FieldType.entries.toSet()),
    IS_UNKNOWN("is_unknown", "is unknown", FieldType.entries.toSet());

    companion object {
        fun fromStorageKey(key: String?): SmartCollectionOperator? =
            entries.firstOrNull { it.storageKey == key }

        /** The operators an editor may offer for [field]. */
        fun forField(field: SmartCollectionField): List<SmartCollectionOperator> =
            entries.filter { field.type in it.applicableTo }
    }
}

/** ALL is `AND`, ANY is `OR`. Flat, by design — see the note in `SmartCollection`. */
enum class SmartCollectionMatchMode(val storageKey: String, val displayName: String) {
    ALL("all", "Match all"),
    ANY("any", "Match any");

    companion object {
        fun fromStorageKey(key: String?): SmartCollectionMatchMode =
            entries.firstOrNull { it.storageKey == key } ?: ALL
    }
}

/** What the result is ordered by. Every mode has a deterministic tie-break. */
enum class SmartCollectionSortField(val storageKey: String, val displayName: String) {
    TITLE("title", "Title"),
    TRACK_ARTIST("track_artist", "Track artist"),
    ALBUM_ARTIST("album_artist", "Album artist"),
    ALBUM("album", "Album"),
    RELEASE_YEAR("release_year", "Release year"),
    FIRST_SEEN("first_seen", "Date added"),
    LAST_PLAYED("last_played", "Last played"),
    PLAY_COUNT("play_count", "Play count"),
    COMPLETION_COUNT("completion_count", "Completion count"),
    DURATION("duration", "Duration"),
    /** Album order: album grouping, then disc, then track. */
    ALBUM_ORDER("album_order", "Album order");

    companion object {
        fun fromStorageKey(key: String?): SmartCollectionSortField =
            entries.firstOrNull { it.storageKey == key } ?: TITLE
    }
}

enum class SmartCollectionSortDirection(val storageKey: String) {
    ASCENDING("asc"),
    DESCENDING("desc");

    companion object {
        fun fromStorageKey(key: String?): SmartCollectionSortDirection =
            entries.firstOrNull { it.storageKey == key } ?: ASCENDING
    }
}

/**
 * One comparison.
 *
 * [field], [operator] and the values are stored as explicit keys and primitives — never a Kotlin
 * class name — so a refactor cannot make a saved collection unreadable. [sortPosition] preserves
 * the order the user arranged the rules in, which matters because the editor shows them in that
 * order even though ALL/ANY is order-independent.
 */
data class SmartCollectionRule(
    val id: Long = 0L,
    val target: SmartCollectionField,
    /**
     * Named `operation` rather than `operator` deliberately: `operator` is a Kotlin soft keyword,
     * and a bare `operator` in an expression position parses as a modifier rather than as this
     * property — which produces error messages that point at everything except the cause.
     */
    val operation: SmartCollectionOperator,
    /** Text operand, for text comparisons. */
    val textValue: String? = null,
    /** Numeric operand: a count, a year, a threshold, or an epoch-millis date. */
    val numberValue: Double? = null,
    /** The upper bound of `between`. */
    val secondNumberValue: Double? = null,
    val position: Int = 0,
) {
    /**
     * True when this rule can be evaluated at all.
     *
     * A rule that fails validation is **kept, not deleted**: the user's intent is still there, and
     * silently dropping it would quietly change what the collection means. The collection is shown
     * as needing attention instead.
     */
    val isValid: Boolean
        get() = when {
            !SmartCollectionOperator.forField(target).contains(operation) -> false
            operation.needsSecondValue && secondNumberValue == null -> false
            target.type == FieldType.TEXT || target.type == FieldType.MULTI_TEXT ->
                // `is known` / `is unknown` take no operand; every other text operator needs one.
                operation == SmartCollectionOperator.IS_KNOWN ||
                    operation == SmartCollectionOperator.IS_UNKNOWN ||
                    !textValue.isNullOrEmpty()
            target.type == FieldType.NUMBER ->
                operation == SmartCollectionOperator.IS_KNOWN ||
                    operation == SmartCollectionOperator.IS_UNKNOWN ||
                    numberValue != null
            target.type == FieldType.DATE ->
                when (operation) {
                    SmartCollectionOperator.IS_KNOWN,
                    SmartCollectionOperator.IS_UNKNOWN,
                    SmartCollectionOperator.BEFORE,
                    SmartCollectionOperator.AFTER -> true
                    SmartCollectionOperator.IN_LAST_DAYS,
                    SmartCollectionOperator.NOT_IN_LAST_DAYS -> numberValue != null && numberValue > 0
                    else -> false
                }
            target.type == FieldType.BOOLEAN ->
                operation == SmartCollectionOperator.IS_TRUE ||
                    operation == SmartCollectionOperator.IS_FALSE ||
                    operation == SmartCollectionOperator.IS_KNOWN ||
                    operation == SmartCollectionOperator.IS_UNKNOWN
            else -> false
        }
}

/**
 * A saved deterministic query over the library.
 *
 * ## What it stores, and what it does not
 *
 * It stores **rules**. It does not store the tracks that matched. Membership is a view over current
 * library state, so a play count changing, a favourite being added or a track going dormant is
 * reflected the next time the collection is read — with nothing to invalidate and no second copy of
 * the library to keep in step.
 *
 * ## Why the rule set is flat
 *
 * ALL/ANY over a flat list, with negation expressed by the operator itself. A nested boolean tree
 * would be more expressive and would need an editor, a validator, a serialization format and an
 * evaluator for it — and the questions people actually ask of a music library do not need one. A
 * rule system that is powerful but bounded is one whose behaviour can be explained on screen.
 *
 * ## Identity
 *
 * Rules address `library_item_id` and nothing else. A renumber cannot change membership, a reused
 * locator inherits nothing, and a dormant item is not a playable result.
 */
data class SmartCollection(
    val id: Long = 0L,
    val name: String,
    val matchMode: SmartCollectionMatchMode = SmartCollectionMatchMode.ALL,
    val rules: List<SmartCollectionRule> = emptyList(),
    val sortField: SmartCollectionSortField = SmartCollectionSortField.TITLE,
    val sortDirection: SmartCollectionSortDirection = SmartCollectionSortDirection.ASCENDING,
    /** Null means no limit. Applied after sorting. */
    val limit: Int? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** True for a built-in supplied by the app; built-ins may be edited but not deleted. */
    val isBuiltIn: Boolean = false,

    /**
     * Rules that were stored but could not be understood, described by their raw keys.
     *
     * A collection saved by a newer build, or one whose rule type has since been removed, must still
     * open — so an unrecognised rule is **retained as a description rather than discarded**. Dropping
     * it would silently change what the collection means, which is worse than refusing to run it:
     * under ALL, losing a rule quietly *widens* the result, and the user would have no way to tell
     * that their collection had started answering a different question.
     */
    val unparsedRules: List<String> = emptyList(),
) {
    /** True when every rule can be evaluated. A collection with an invalid rule reports itself. */
    val isValid: Boolean get() = unparsedRules.isEmpty() && rules.all { it.isValid }
}
