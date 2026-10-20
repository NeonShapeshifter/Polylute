package com.lostf1sh.pixelplayeross.data.collections

import com.lostf1sh.pixelplayeross.data.model.SmartPlaylistRule

/**
 * The four legacy smart playlists, expressed as ordinary collection definitions.
 *
 * ## Why this exists
 *
 * These shipped before the rule engine, each with its own hand-written selection logic keyed on the
 * MediaStore locator. That meant a renumber could change which tracks "Top Played" contained, and
 * that the one part of the app a user could not customise was also the part with the most bespoke
 * code.
 *
 * The `SmartPlaylistRule` enum survives only because existing rows reference it as a storage key
 * (`source = "SMART:top_played"`). The *logic* behind it does not: each value now resolves to a
 * `SmartCollection` and is evaluated by [SmartCollectionEngine], so a legacy playlist and a
 * user-created one cannot diverge, and both inherit identity correctness for free.
 *
 * ## Two of these are deliberately not identical to their old behaviour
 *
 * * **New Gems** was "added recently with a low play count", keyed on MediaStore's date-added. It now
 *   reads durable first-seen, so a scanner rebuild no longer makes an old track look new.
 * * **Forgotten Favorites** used a fixed 30-day threshold. It keeps that — changing it would change
 *   what an existing playlist contains — but it is now also guarded on the track actually having been
 *   played, so a starred track that was never listened to is no longer swept in.
 */
object LegacySmartPlaylistDefinitions {

    fun forRule(rule: SmartPlaylistRule, limit: Int): SmartCollection = when (rule) {
        SmartPlaylistRule.TOP_PLAYED -> SmartCollection(
            name = rule.title,
            rules = listOf(
                SmartCollectionRule(
                    target = SmartCollectionField.PLAY_COUNT,
                    operation = SmartCollectionOperator.GREATER_OR_EQUAL,
                    numberValue = 1.0,
                ),
            ),
            sortField = SmartCollectionSortField.PLAY_COUNT,
            sortDirection = SmartCollectionSortDirection.DESCENDING,
            limit = limit,
        )

        SmartPlaylistRule.RECENTLY_PLAYED -> SmartCollection(
            name = rule.title,
            // "Has been played at all", which is what keeps a never-played track out of a
            // recently-played list. A date comparison would be the wrong tool: the only timestamp
            // that is not a real date is zero, and `is known` is the operator that says exactly
            // that rather than approximating it with a bound.
            rules = listOf(
                SmartCollectionRule(
                    target = SmartCollectionField.LAST_PLAYED,
                    operation = SmartCollectionOperator.IS_KNOWN,
                ),
            ),
            sortField = SmartCollectionSortField.LAST_PLAYED,
            sortDirection = SmartCollectionSortDirection.DESCENDING,
            limit = limit,
        )

        SmartPlaylistRule.FORGOTTEN_FAVORITES -> SmartCollection(
            name = rule.title,
            rules = listOf(
                SmartCollectionRule(
                    target = SmartCollectionField.FAVOURITE,
                    operation = SmartCollectionOperator.IS_TRUE,
                ),
                SmartCollectionRule(
                    target = SmartCollectionField.LAST_PLAYED,
                    operation = SmartCollectionOperator.IS_KNOWN,
                ),
                SmartCollectionRule(
                    target = SmartCollectionField.LAST_PLAYED,
                    operation = SmartCollectionOperator.NOT_IN_LAST_DAYS,
                    numberValue = 30.0,
                ),
            ),
            sortField = SmartCollectionSortField.LAST_PLAYED,
            limit = limit,
        )

        SmartPlaylistRule.NEW_GEMS -> SmartCollection(
            name = rule.title,
            rules = listOf(
                SmartCollectionRule(
                    target = SmartCollectionField.FIRST_SEEN,
                    operation = SmartCollectionOperator.IN_LAST_DAYS,
                    numberValue = 90.0,
                ),
                SmartCollectionRule(
                    target = SmartCollectionField.PLAY_COUNT,
                    operation = SmartCollectionOperator.LESS_OR_EQUAL,
                    numberValue = 2.0,
                ),
            ),
            sortField = SmartCollectionSortField.FIRST_SEEN,
            sortDirection = SmartCollectionSortDirection.DESCENDING,
            limit = limit,
        )
    }
}
