package com.lostf1sh.pixelplayeross.dap.admin

/**
 * Where Android's home picker is, for the HOME exit the listener asked for (B34-fix2 R1). It says when the exit can be settled
 * ([DapContainmentReconciler.settle]): not while the picker has been launched and the activity has not yet gone away to it, and not
 * while it is in front; once the activity has been away and is back, or when no picker of this process is in flight at all (the process
 * died while it was open, or it never opened).
 *
 * Deliberately process-local, like the Maintenance window: a process that starts has no picker in flight, and what it finds recorded
 * ([DapContainmentStore.isHomeExitChosen]) is settled by its first start. Main thread only.
 */
internal class HomePickerFlow {
    private enum class Phase { IDLE, LAUNCHED, AWAY }

    private var phase = Phase.IDLE

    /** The picker was started (its `startActivity` returned): the activity is about to be paused by it. */
    fun launched() {
        phase = Phase.LAUNCHED
    }

    /** The activity was paused. Only a pause after the picker was launched means the picker is in front. */
    fun activityPaused() {
        if (phase == Phase.LAUNCHED) phase = Phase.AWAY
    }

    /**
     * Whether an activity start or resume happening now is the picker coming back (or no picker at all). False between the launch and the
     * pause, when a resume cannot be the picker's return.
     */
    fun shouldSettleNow(): Boolean = phase != Phase.LAUNCHED

    fun settled() {
        phase = Phase.IDLE
    }
}

/** The one flow of this process ([HomePickerFlow]). */
internal object DapHomePickerSession {
    private val flow = HomePickerFlow()

    fun launched() = flow.launched()
    fun activityPaused() = flow.activityPaused()
    fun shouldSettleNow(): Boolean = flow.shouldSettleNow()
    fun settled() = flow.settled()
}
