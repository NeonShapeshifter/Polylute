package com.lostf1sh.pixelplayeross.presentation.components

internal class AxisObservationTracker {
    private var trackedTotalItemsCount = -1
    private var trackedSpacingPx = Int.MIN_VALUE
    private var representativeStridePx = 1f
    private var representativeItemSizePx = 1f
    private var representativeStrideSampleCount = 0
    private var representativeItemSizeSampleCount = 0
    private val observedStridesPx = mutableMapOf<Int, Float>()
    private val observedItemSizesPx = mutableMapOf<Int, Float>()

    fun resetIfNeeded(totalItemsCount: Int, spacingPx: Int) {
        if (trackedTotalItemsCount == totalItemsCount && trackedSpacingPx == spacingPx) {
            return
        }

        trackedTotalItemsCount = totalItemsCount
        trackedSpacingPx = spacingPx
        representativeStridePx = 1f
        representativeItemSizePx = 1f
        representativeStrideSampleCount = 0
        representativeItemSizeSampleCount = 0
        observedStridesPx.clear()
        observedItemSizesPx.clear()
    }

    fun observeRepresentativeSample(
        strideSamplePx: Float?,
        itemSizeSamplePx: Float?
    ) {
        val normalizedStrideSamplePx = strideSamplePx?.coerceAtLeast(1f)
        val normalizedItemSizeSamplePx = itemSizeSamplePx?.coerceAtLeast(1f)

        if (normalizedStrideSamplePx == null && normalizedItemSizeSamplePx == null) {
            return
        }

        normalizedStrideSamplePx?.let { sample ->
            val currentCount = representativeStrideSampleCount
            representativeStrideSampleCount = currentCount + 1
            representativeStridePx =
                if (currentCount == 0) {
                    sample
                } else {
                    ((representativeStridePx * currentCount) + sample) / representativeStrideSampleCount
                }
        }

        normalizedItemSizeSamplePx?.let { sample ->
            val currentCount = representativeItemSizeSampleCount
            representativeItemSizeSampleCount = currentCount + 1
            representativeItemSizePx =
                if (currentCount == 0) {
                    sample
                } else {
                    ((representativeItemSizePx * currentCount) + sample) / representativeItemSizeSampleCount
                }
        }
    }

    fun observeItemSize(index: Int, sizePx: Float) {
        observedItemSizesPx[index] = sizePx.coerceAtLeast(1f)
    }

    fun observeStride(index: Int, stridePx: Float) {
        observedStridesPx[index] = stridePx.coerceAtLeast(1f)
    }

    fun representativeStridePx(fallbackStridePx: Float): Float {
        return if (representativeStrideSampleCount > 0) {
            representativeStridePx
        } else {
            fallbackStridePx.coerceAtLeast(1f)
        }
    }

    fun representativeItemSizePx(fallbackItemSizePx: Float): Float {
        return if (representativeItemSizeSampleCount > 0) {
            representativeItemSizePx
        } else {
            fallbackItemSizePx.coerceAtLeast(1f)
        }
    }

    fun distanceBeforeIndex(index: Int, representativeStridePx: Float): Float {
        if (index <= 0) return 0f

        var correctionPx = 0f
        observedStridesPx.forEach { (observedIndex, observedStridePx) ->
            if (observedIndex < index) {
                correctionPx += observedStridePx - representativeStridePx
            }
        }

        return ((index * representativeStridePx) + correctionPx).coerceAtLeast(0f)
    }

    fun itemSizePx(index: Int, representativeItemSizePx: Float): Float {
        return observedItemSizesPx[index] ?: representativeItemSizePx.coerceAtLeast(1f)
    }
}

internal fun resolveDragTargetIndex(
    progress: Float,
    maxScrollIndex: Int,
    totalItemsCount: Int
): Int {
    if (totalItemsCount <= 1) return 0

    val clampedProgress = progress.coerceIn(0f, 1f)
    val lastIndex = totalItemsCount - 1
    if (clampedProgress >= 1f) return lastIndex

    return (clampedProgress * maxScrollIndex.coerceAtLeast(1))
        .toInt()
        .coerceIn(0, lastIndex)
}

/**
 * The letter of the section a title falls in while the scrollbar is dragged (B29-L8).
 *
 * The lists are ordered by SQLite's `COLLATE NOCASE`: symbols first (`!`, `"`, `#`, `$`, `.`), then digits, then A to Z, with
 * anything that is not plain ASCII after Z. The label has to be where the list is, so:
 *
 *  - a letter is its capital, and a letter with a mark on it is its base letter (`Á` goes under `A`, `ñ` under `N`);
 *  - a digit **or any symbol** (`!!!!!!!`, `$`, `#`, a quote, an emoji) is `#`: the old rule skipped the symbol and labelled the
 *    row with the first letter after it, so `"The Take Over"`, which is the second row of the list, said `T`, and `!!!!!!!`, which
 *    has no letter at all, said nothing;
 *  - a letter that is not Latin (Cyrillic, kana) is itself;
 *  - an empty or blank title has no section.
 */
internal fun extractFastScrollGlyph(value: String?): String? {
    val text = value.orEmpty().trim()
    if (text.isEmpty()) return null
    val codePoint = text.codePointAt(0)
    if (!Character.isLetter(codePoint)) return "#"
    val base = java.text.Normalizer.normalize(String(Character.toChars(codePoint)), java.text.Normalizer.Form.NFD)
        .codePointAt(0)
    return String(Character.toChars(base)).uppercase(java.util.Locale.ROOT)
}

/** A release year as its own label, for the sorts that order by it; none when the year is unknown. */
internal fun fastScrollYearLabel(year: Int): String? = year.takeIf { it in 1000..9999 }?.toString()

/** The year of an epoch timestamp (seconds or milliseconds, as the library stores them), for the sorts by the date added or liked. */
internal fun fastScrollYearOfEpoch(epoch: Long): String? {
    if (epoch <= 0L) return null
    val millis = if (epoch < 100_000_000_000L) epoch * 1_000L else epoch
    return java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).year.toString()
}

internal fun medianOrNull(values: Iterable<Float>): Float? {
    val sorted = values
        .filter { it.isFinite() && it > 0f }
        .sorted()

    if (sorted.isEmpty()) return null

    val middleIndex = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middleIndex - 1] + sorted[middleIndex]) / 2f
    } else {
        sorted[middleIndex]
    }
}
