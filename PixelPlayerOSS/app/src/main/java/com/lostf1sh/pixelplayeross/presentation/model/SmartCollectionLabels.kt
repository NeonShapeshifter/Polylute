package com.lostf1sh.pixelplayeross.presentation.model

import androidx.annotation.StringRes
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionField
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionMatchMode
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionOperator
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionSortField

/**
 * The rules editor's labels, resolved in the presentation layer.
 *
 * The four enums in `data/collections/` pair every value with a `storageKey` — the thing that is
 * persisted — and a `displayName`, which is only ever drawn. The display names stay English there
 * so that what is stored never depends on the UI language; the call sites that *draw* them resolve
 * them here. Where two enums share a label ("Title", "Play count"…) they share the string too, so
 * the two lists cannot drift apart in translation.
 */

/** A rule's left-hand side: what the rule is about. */
@StringRes
fun SmartCollectionField.labelRes(): Int = when (this) {
    SmartCollectionField.TITLE -> R.string.smart_collections_field_title
    SmartCollectionField.TRACK_ARTIST -> R.string.smart_collections_field_track_artist
    SmartCollectionField.ALBUM_ARTIST -> R.string.smart_collections_field_album_artist
    SmartCollectionField.ALBUM -> R.string.smart_collections_field_album
    SmartCollectionField.GENRE -> R.string.smart_collections_field_genre
    SmartCollectionField.PLAY_COUNT -> R.string.smart_collections_field_play_count
    SmartCollectionField.COMPLETION_COUNT -> R.string.smart_collections_field_completion_count
    SmartCollectionField.SKIP_COUNT -> R.string.smart_collections_field_skip_count
    SmartCollectionField.DURATION_MS -> R.string.smart_collections_field_duration
    SmartCollectionField.TRACK_NUMBER -> R.string.smart_collections_field_track_number
    SmartCollectionField.DISC_NUMBER -> R.string.smart_collections_field_disc_number
    SmartCollectionField.RELEASE_YEAR -> R.string.smart_collections_field_release_year
    SmartCollectionField.BITRATE -> R.string.smart_collections_field_bitrate
    SmartCollectionField.SAMPLE_RATE -> R.string.smart_collections_field_sample_rate
    SmartCollectionField.FIRST_SEEN -> R.string.smart_collections_field_first_seen
    SmartCollectionField.LAST_PLAYED -> R.string.smart_collections_field_last_played
    SmartCollectionField.LAST_COMPLETED -> R.string.smart_collections_field_last_completed
    SmartCollectionField.LAST_SKIPPED -> R.string.smart_collections_field_last_skipped
    SmartCollectionField.FAVOURITE -> R.string.smart_collections_field_favourite
    SmartCollectionField.COMPILATION -> R.string.smart_collections_field_compilation
    SmartCollectionField.PROBABLE_DUPLICATE -> R.string.smart_collections_field_probable_duplicate
}

/** How a collection's result is ordered. */
@StringRes
fun SmartCollectionSortField.labelRes(): Int = when (this) {
    SmartCollectionSortField.TITLE -> R.string.smart_collections_field_title
    SmartCollectionSortField.TRACK_ARTIST -> R.string.smart_collections_field_track_artist
    SmartCollectionSortField.ALBUM_ARTIST -> R.string.smart_collections_field_album_artist
    SmartCollectionSortField.ALBUM -> R.string.smart_collections_field_album
    SmartCollectionSortField.RELEASE_YEAR -> R.string.smart_collections_field_release_year
    SmartCollectionSortField.FIRST_SEEN -> R.string.smart_collections_field_date_added
    SmartCollectionSortField.LAST_PLAYED -> R.string.smart_collections_field_last_played
    SmartCollectionSortField.PLAY_COUNT -> R.string.smart_collections_field_play_count
    SmartCollectionSortField.COMPLETION_COUNT -> R.string.smart_collections_field_completion_count
    SmartCollectionSortField.DURATION -> R.string.smart_collections_field_duration
    SmartCollectionSortField.ALBUM_ORDER -> R.string.smart_collections_field_album_order
}

/** Whether every rule has to hold or just one of them. */
@StringRes
fun SmartCollectionMatchMode.labelRes(): Int = when (this) {
    SmartCollectionMatchMode.ALL -> R.string.smart_collections_match_all
    SmartCollectionMatchMode.ANY -> R.string.smart_collections_match_any
}

/** A rule's comparison: "contains", "is at least"… Read between the field and its value. */
@StringRes
fun SmartCollectionOperator.labelRes(): Int = when (this) {
    SmartCollectionOperator.IS -> R.string.smart_collections_op_is
    SmartCollectionOperator.IS_NOT -> R.string.smart_collections_op_is_not
    SmartCollectionOperator.CONTAINS -> R.string.smart_collections_op_contains
    SmartCollectionOperator.DOES_NOT_CONTAIN -> R.string.smart_collections_op_does_not_contain
    SmartCollectionOperator.STARTS_WITH -> R.string.smart_collections_op_starts_with
    SmartCollectionOperator.ENDS_WITH -> R.string.smart_collections_op_ends_with
    SmartCollectionOperator.EQUALS -> R.string.smart_collections_op_equals
    SmartCollectionOperator.NOT_EQUALS -> R.string.smart_collections_op_not_equals
    SmartCollectionOperator.GREATER_THAN -> R.string.smart_collections_op_greater_than
    SmartCollectionOperator.GREATER_OR_EQUAL -> R.string.smart_collections_op_at_least
    SmartCollectionOperator.LESS_THAN -> R.string.smart_collections_op_less_than
    SmartCollectionOperator.LESS_OR_EQUAL -> R.string.smart_collections_op_at_most
    SmartCollectionOperator.BETWEEN -> R.string.smart_collections_op_between
    SmartCollectionOperator.BEFORE -> R.string.smart_collections_op_before
    SmartCollectionOperator.AFTER -> R.string.smart_collections_op_after
    SmartCollectionOperator.IN_LAST_DAYS -> R.string.smart_collections_op_in_last_days
    SmartCollectionOperator.NOT_IN_LAST_DAYS -> R.string.smart_collections_op_not_in_last_days
    SmartCollectionOperator.IS_TRUE -> R.string.smart_collections_op_is_true
    SmartCollectionOperator.IS_FALSE -> R.string.smart_collections_op_is_false
    SmartCollectionOperator.IS_KNOWN -> R.string.smart_collections_op_is_known
    SmartCollectionOperator.IS_UNKNOWN -> R.string.smart_collections_op_is_unknown
}
