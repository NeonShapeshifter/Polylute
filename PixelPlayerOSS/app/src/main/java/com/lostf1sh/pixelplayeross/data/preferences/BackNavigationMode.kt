package com.lostf1sh.pixelplayeross.data.preferences

/**
 * How the app performs Back.
 *
 * This is an app-navigation preference and nothing more. It chooses between the two input
 * mechanisms *this app* offers — the visible affordance and the app's own edge gesture — and it has
 * no opinion about the platform's notification shade, Home gesture, Recents, or the system back
 * gesture itself, none of which an app can or should replace.
 *
 * [BOTH] is the default deliberately. It is the only value that removes nothing on upgrade: every
 * back affordance that existed before this preference did is still drawn, and the app-level edge
 * gesture becomes available alongside it. Choosing [BUTTONS] would silently take a navigation
 * mechanism away from a user who never asked for the change.
 */
enum class BackNavigationMode(val id: String) {
    /** Visible affordances only. The app's edge gesture is off. */
    BUTTONS("buttons"),

    /** The app's edge gesture only. Affordances drawn *solely* to perform Back are hidden. */
    GESTURES("gestures"),

    /** Both, with identical semantics. */
    BOTH("both");

    /** Whether a screen should draw a Back affordance that exists only to perform Back. */
    val showsVisibleBackAffordance: Boolean
        get() = this != GESTURES

    /** Whether the app's own left-edge Back gesture is active. */
    val edgeGestureEnabled: Boolean
        get() = this != BUTTONS

    companion object {
        val DEFAULT = BOTH

        /**
         * Reads a stored value.
         *
         * An unrecognised or absent id resolves to [DEFAULT] rather than throwing: the store is
         * read on every launch, and a value written by a newer build must degrade to a usable mode
         * instead of taking navigation down with it.
         */
        fun fromId(id: String?): BackNavigationMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
