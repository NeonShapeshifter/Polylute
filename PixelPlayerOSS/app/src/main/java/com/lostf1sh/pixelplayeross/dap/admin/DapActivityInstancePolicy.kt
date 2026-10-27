package com.lostf1sh.pixelplayeross.dap.admin

/**
 * B40-F9b: one MainActivity for the role Polylute has, not two.
 *
 * MainActivity is the home app's activity and the launcher icon's. Android keeps them in different kinds of task: a task of the *home*
 * type, started by the HOME intent, which no other launch ever reuses, and a standard one. So while another launcher (HONOR's) is home and the
 * listener taps Polylute's icon, Android starts a second MainActivity beside the stale one the home task still holds. On the B34-fix3 device
 * run that second instance was task t108 beside t106, and it is what made "pick Polylute again" depend on which instance happened to resume.
 *
 * The rule is the one the listener's choice of home already makes:
 *  - **another launcher is home**: the instance started from the icon is the one in use; the instance started as HOME is a leftover of when
 *    Polylute was home, and goes once a standard one exists;
 *  - **Polylute is home**: the instance started as HOME is the appliance; an instance started from the icon (a notification, a shortcut) goes
 *    once the HOME one exists.
 *
 * It never touches two instances of the same kind (an audio file opened from another app starts a standard one beside the standard one in
 * use, and that is not this fix's to change), and never the instance in front of the listener: that one is judged again when it is left.
 *
 * Pure: the activity describes its instances, the answer is which of them to finish.
 */
internal object DapActivityInstancePolicy {

    data class Instance(
        /** Identity of the activity object. */
        val key: Int,
        /** Started by the HOME intent (the home task's root), as against the launcher icon or anything else. */
        val startedAsHome: Boolean,
        /** Between `onResume` and `onPause`. */
        val resumed: Boolean,
    )

    /** The instances to finish, given every live instance of the activity and whether Polylute is the home app now. */
    fun toFinish(instances: List<Instance>, polyluteIsHome: Boolean): List<Instance> {
        // the kind that belongs to the situation, and the kind that does not
        val belongs = instances.filter { it.startedAsHome == polyluteIsHome }
        val leftover = instances.filter { it.startedAsHome != polyluteIsHome }
        if (belongs.isEmpty()) return emptyList()
        return leftover.filter { !it.resumed }
    }
}
