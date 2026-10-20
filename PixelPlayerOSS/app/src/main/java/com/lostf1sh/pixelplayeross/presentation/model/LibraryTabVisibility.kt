package com.lostf1sh.pixelplayeross.presentation.library

/**
 * Which Library tabs are shown, given the complete ordered set and the ids the user has hidden.
 *
 * Pure, so the rule can be tested without a ViewModel or a device. Two invariants matter:
 *
 * - **Hiding is a filter, never a removal.** The order always keeps every tab, so a hidden tab is
 *   still there to be turned back on and its position is not lost.
 * - **The Library is never empty.** A stored hidden set that would hide everything (a hand-edited
 *   preference, or an install that once knew a different tab set) falls back to showing the first
 *   tab rather than leaving a blank screen with no way back.
 *
 * Ids in [hidden] that are not in [all] are ignored, so a stale preference cannot affect anything.
 */
internal fun resolveVisibleTabs(all: List<String>, hidden: Collection<String>): List<String> {
    val visible = all.filterNot { it in hidden }
    return visible.ifEmpty { all.take(1) }
}
