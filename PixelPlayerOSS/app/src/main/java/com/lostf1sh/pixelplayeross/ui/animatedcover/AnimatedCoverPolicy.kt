package com.lostf1sh.pixelplayeross.ui.animatedcover

import androidx.compose.runtime.Immutable

/**
 * B56 phase 7.2 (#48): when an animated cover is held (a decoder exists and shows the cover) and when it runs. The rules are the live background's (7.1: only where the player is
 * in front, nothing under a sheet, on the DAP lock screen, with the screen off or the app in the background), plus the ones that belong to a cover: it exists only while the
 * full player shows the cover page, and it needs the setting.
 */
object AnimatedCoverPolicy {
    @Immutable
    data class Inputs(
        /** "Animated covers", as the listener set it or as the mode defaults it ([com.lostf1sh.pixelplayeross.data.preferences.AnimatedCovers.effective]). */
        val enabled: Boolean = false,
        /** The full player is fully open. */
        val playerExpanded: Boolean = false,
        /** The player's pager is on the Player page, where the cover is (not the menu, the lyrics or the details). */
        val coverPageShowing: Boolean = false,
        /** A bottom sheet or the open queue is over the player. */
        val sheetOpen: Boolean = false,
        /** The DAP lock screen is up. */
        val lockSurfaceVisible: Boolean = false,
        /** The activity is resumed (the app is in front). */
        val resumed: Boolean = true,
        /** The screen is on and interactive. */
        val screenInteractive: Boolean = true,
        /** Neither "Remove animations" nor Battery saver is on. */
        val systemAnimationsAllowed: Boolean = true,
        /** A song is playing (not paused). */
        val playing: Boolean = false,
    ) {
        /** Every input on one line, in a fixed order, for the device log (B57.5 2.2): the next test reads why a cover came or went from this. */
        fun describe(): String =
            "enabled=$enabled playerExpanded=$playerExpanded coverPageShowing=$coverPageShowing sheetOpen=$sheetOpen lockSurfaceVisible=$lockSurfaceVisible " +
                "resumed=$resumed screenInteractive=$screenInteractive systemAnimationsAllowed=$systemAnimationsAllowed playing=$playing"
    }

    /** A decoder may exist: the setting is on and the cover page is the one on screen. Leaving the page or closing the player ends it (the decoder is released). */
    fun shouldHold(i: Inputs): Boolean = i.enabled && i.playerExpanded && i.coverPageShowing

    /** The cover can be seen: the app is in front, the screen is on and the lock screen is not covering it. Not seen for long, a held decoder is let go ([AnimatedCoverController]). */
    fun visible(i: Inputs): Boolean = i.resumed && i.screenInteractive && !i.lockSurfaceVisible

    /** The cover moves: held, seen, no sheet over it, a song playing and animations allowed. Otherwise it holds its frame. */
    fun shouldRun(i: Inputs): Boolean = shouldHold(i) && visible(i) && !i.sheetOpen && i.playing && i.systemAnimationsAllowed
}
