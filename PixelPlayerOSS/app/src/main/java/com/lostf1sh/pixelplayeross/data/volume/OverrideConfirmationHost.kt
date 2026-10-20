package com.lostf1sh.pixelplayeross.data.volume

/**
 * The window that can show the Safety Max override confirmation (FIX-5): the app's main activity,
 * registered while it is resumed. The controller owns the decision and the state; the host only owns
 * the window flags that let the dialog it already draws appear above the lock screen.
 */
interface OverrideConfirmationHost {
    /**
     * Lets the activity show over the lock screen (true), or puts that back (false). Main thread. Only
     * meant to hold while the activity is resumed: the activity puts it back itself when it pauses, and
     * the controller applies it again when it resumes if the confirmation is still open.
     */
    fun setOverLockScreen(enabled: Boolean)
}
