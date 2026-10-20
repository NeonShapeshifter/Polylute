package com.lostf1sh.pixelplayeross.presentation.viewmodel

/**
 * Which player the song shown on screen has to be brought in line with (B29-L2).
 *
 * The state the mini player, the full player and the lock screen draw is written by the controller's listener when the
 * player tells it an item changed. That is an edge: a transition that is not reported (or is reported to a controller
 * that is behind the engine) leaves the old song shown until something else happens to write it, and nothing did: on
 * 2026-10-17 at 20:40:31 the engine went on to STORM II by itself and the mini player and the session went on saying
 * STORM I. This is the level: the engine's current item is the authority (it is in this process, no IPC), and whenever
 * the screen comes back or the player changes its mind about playing, what is shown is compared with it.
 *
 * Pure, so it can be pinned.
 */
internal object DisplayedItemReconcile {

    enum class Source {
        /** What is shown is what the engine plays. */
        NONE,

        /** The controller agrees with the engine: the ordinary sync, which also reads the queue position from it. */
        CONTROLLER,

        /** The controller is behind the engine: the engine's item is what is shown, whatever the controller says. */
        ENGINE,
    }

    /**
     * @param displayedId the id of the song the screens show now (null: none).
     * @param controllerId the id of the item the app's controller reports (null: none).
     * @param engineId the id of the item the engine's master player is on (null: none, or not built yet).
     */
    fun followFrom(displayedId: String?, controllerId: String?, engineId: String?): Source = when {
        // Nothing playing is cleared by the player-state events, never by a comparison that may run before the engine exists.
        engineId == null -> Source.NONE
        engineId == displayedId -> Source.NONE
        engineId == controllerId -> Source.CONTROLLER
        else -> Source.ENGINE
    }
}
