package com.lostf1sh.pixelplayeross.utils

import java.util.Locale

/**
 * The metadata values that mean "there is nothing here", rather than naming something.
 *
 * A missing tag does not arrive as an empty string. MediaStore's own convention is the literal
 * `<unknown>`, and this app's sync has historically written `Unknown Artist` / `Unknown Title` /
 * `Unknown Album` into the same columns. Both are absences wearing the shape of a value, and both
 * read as broken product UI when they are drawn.
 *
 * The set is deliberately closed rather than a pattern: a real title may contain angle brackets or
 * the word "unknown", so only the exact sentinels that are actually produced are rejected.
 */
private val PLACEHOLDER_METADATA = setOf(
    "unknown",
    "unknown artist",
    "unknown title",
    "unknown album",
    "unknown genre",
    "unknown song",
    "null",
)

/**
 * Whether this value is an absence rather than a name.
 *
 * Null and blank are absences. So is a recognised sentinel once its decoration is removed, which is
 * what the angle brackets, parentheses and surrounding whitespace are: `<unknown>` names nothing
 * that `unknown` does not, and neither names anything at all.
 */
fun String?.isPlaceholderMetadata(): Boolean {
    val stripped = this?.trim()?.trim('<', '>', '(', ')', '[', ']')?.trim() ?: return true
    if (stripped.isEmpty()) return true
    return stripped.lowercase(Locale.US) in PLACEHOLDER_METADATA
}

/**
 * This value as displayable metadata, or null when there is nothing to display.
 *
 * The normalisation pass still runs first so that a genuine value keeps its mojibake repair and NFC
 * form; the placeholder check is layered on top rather than replacing it.
 */
fun String?.withoutPlaceholderMetadata(): String? =
    normalizeMetadataText()?.takeUnless { it.isPlaceholderMetadata() }

/** As [withoutPlaceholderMetadata], for callers whose field is a non-null `String`. */
fun String?.withoutPlaceholderMetadataOrEmpty(): String =
    withoutPlaceholderMetadata() ?: ""

/**
 * Placeholder-free names, dropping any that were sentinels.
 *
 * Used where a list of names is joined for display: one `<unknown>` in a multi-artist credit must
 * not survive into the joined line, and if every name was a sentinel the result is empty rather
 * than a string of them.
 */
fun List<String>.withoutPlaceholderNames(): List<String> =
    mapNotNull { it.withoutPlaceholderMetadata() }
