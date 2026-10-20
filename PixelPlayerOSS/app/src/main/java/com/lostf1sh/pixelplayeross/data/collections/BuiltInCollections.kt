package com.lostf1sh.pixelplayeross.data.collections

/**
 * The collections the app ships with.
 *
 * ## These are ordinary definitions
 *
 * Nothing about them is special at execution time — each is a name and a list of rules, and the same
 * [SmartCollectionEngine] evaluates them exactly as it evaluates anything the user writes. That is
 * the point of listing them here rather than hand-writing a query for each: the moment a built-in
 * takes a different path from a user collection, the two start to be able to disagree, and only one
 * of them gets tested.
 *
 * ## Why these seven
 *
 * Each one is a question people actually ask of a music library, and between them they exercise
 * every operator family — a boolean, a count, a multi-value text, an unset field, a durable date,
 * a release date and a technical measurement. "Unknown Artist" in particular exists because it is
 * the only way to reach the untagged part of a library, and `IS_UNKNOWN` is the only operator that
 * can express it.
 *
 * ## `High Resolution` and its threshold
 *
 * 96 kHz is a real, checkable property of a file, not a taste judgement, so the collection is honest
 * — but it is only as reliable as the sample-rate column, which comes from the scan and is null for
 * a file whose rate could not be read. A track with no sample rate does not match, which is the
 * conservative outcome rather than the convenient one.
 */
object BuiltInCollections {

    data class BuiltIn(val key: String, val name: String, val collection: SmartCollection)

    private fun rule(
        target: SmartCollectionField,
        operation: SmartCollectionOperator,
        text: String? = null,
        number: Double? = null,
        secondNumber: Double? = null,
    ) = SmartCollectionRule(
        target = target,
        operation = operation,
        textValue = text,
        numberValue = number,
        secondNumberValue = secondNumber,
    )

    val all: List<BuiltIn> = listOf(
        BuiltIn(
            key = "never_played",
            name = "Never Played",
            collection = SmartCollection(
                name = "Never Played",
                rules = listOf(rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.EQUALS, number = 0.0)),
                sortField = SmartCollectionSortField.FIRST_SEEN,
                sortDirection = SmartCollectionSortDirection.DESCENDING,
                isBuiltIn = true,
            ),
        ),
        BuiltIn(
            key = "recently_added",
            name = "Recently Added",
            collection = SmartCollection(
                name = "Recently Added",
                // First-seen, not MediaStore's date-added. A scanner rebuild must not re-announce an
                // old album as new, and this is the difference between the two fields.
                rules = listOf(rule(SmartCollectionField.FIRST_SEEN, SmartCollectionOperator.IN_LAST_DAYS, number = 30.0)),
                sortField = SmartCollectionSortField.FIRST_SEEN,
                sortDirection = SmartCollectionSortDirection.DESCENDING,
                isBuiltIn = true,
            ),
        ),
        BuiltIn(
            key = "favorites",
            name = "Favorites",
            collection = SmartCollection(
                name = "Favorites",
                rules = listOf(rule(SmartCollectionField.FAVOURITE, SmartCollectionOperator.IS_TRUE)),
                sortField = SmartCollectionSortField.TITLE,
                isBuiltIn = true,
            ),
        ),
        BuiltIn(
            key = "most_played",
            name = "Most Played",
            collection = SmartCollection(
                name = "Most Played",
                rules = listOf(rule(SmartCollectionField.PLAY_COUNT, SmartCollectionOperator.GREATER_OR_EQUAL, number = 1.0)),
                sortField = SmartCollectionSortField.PLAY_COUNT,
                sortDirection = SmartCollectionSortDirection.DESCENDING,
                isBuiltIn = true,
            ),
        ),
        BuiltIn(
            key = "recently_played",
            name = "Recently Played",
            collection = SmartCollection(
                name = "Recently Played",
                rules = listOf(rule(SmartCollectionField.LAST_PLAYED, SmartCollectionOperator.IN_LAST_DAYS, number = 30.0)),
                sortField = SmartCollectionSortField.LAST_PLAYED,
                sortDirection = SmartCollectionSortDirection.DESCENDING,
                isBuiltIn = true,
            ),
        ),
        BuiltIn(
            key = "not_heard_in_90_days",
            name = "Not Heard in 90 Days",
            collection = SmartCollection(
                name = "Not Heard in 90 Days",
                // Requires that the track *has* been played: `not in the last 90 days` alone would
                // sweep in everything never played at all, which is a different question and already
                // has its own collection.
                matchMode = SmartCollectionMatchMode.ALL,
                rules = listOf(
                    rule(SmartCollectionField.LAST_PLAYED, SmartCollectionOperator.IS_KNOWN),
                    rule(SmartCollectionField.LAST_PLAYED, SmartCollectionOperator.NOT_IN_LAST_DAYS, number = 90.0),
                ),
                sortField = SmartCollectionSortField.LAST_PLAYED,
                isBuiltIn = true,
            ),
        ),
        BuiltIn(
            key = "high_resolution",
            name = "High Resolution",
            collection = SmartCollection(
                name = "High Resolution",
                rules = listOf(rule(SmartCollectionField.SAMPLE_RATE, SmartCollectionOperator.GREATER_OR_EQUAL, number = 96_000.0)),
                sortField = SmartCollectionSortField.TITLE,
                isBuiltIn = true,
            ),
        ),
        BuiltIn(
            key = "unknown_artist",
            name = "Unknown Artist",
            collection = SmartCollection(
                name = "Unknown Artist",
                // The only way to reach the untagged part of a library — and the reason the two
                // absence operators exist, since every asserting operator fails on a missing value.
                rules = listOf(rule(SmartCollectionField.TRACK_ARTIST, SmartCollectionOperator.IS_UNKNOWN)),
                sortField = SmartCollectionSortField.TITLE,
                isBuiltIn = true,
            ),
        ),
    )
}
