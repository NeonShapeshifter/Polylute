package com.lostf1sh.pixelplayeross.dap.admin

import com.lostf1sh.pixelplayeross.dap.lifecycle.DapProductionLifecycle
import com.lostf1sh.pixelplayeross.dap.lifecycle.DapRecoverySnapshot
import com.lostf1sh.pixelplayeross.dap.lifecycle.DapRecoveryTrigger

/** What settling a "leave through the home picker" found (B34-fix2 R1). */
internal enum class HomeExitSettlement {
    /** No exit was waiting to be settled. */
    NONE,

    /** Another launcher is the home app (or there is no Device Owner to contain): the exit stands and nothing was put back. */
    LEFT,

    /** Polylute is still the home app (the picker was cancelled, or Polylute was picked again): the DAP policy is back, as the dialog promised. */
    RECONTAINED,

    /** As [RECONTAINED], but Android did not confirm the policy back; the usual reconciliation keeps trying and "Volver al DAP" is the way. */
    NOT_RECONTAINED,
}

/**
 * Who may put the Dedicated policy back, and when (B34-fix2 R1). One place for the three things that write it: the restore every trigger
 * runs (activity start, focus and resume, boot, package replacement), the HOME exit the listener asks for, and the settling of that exit.
 *
 * **The listener's choice is a recorded fact.** "Choose home app" on a Dedicated Device Owner clears the policy (flag, allowlist, keyguard
 * hold, persistent HOME) and then shows Android's picker, and until the listener has picked something Polylute is *still* the home app.
 * The restore used to treat Device Owner + home as proof that a policy had been lost and heal it: a focus change between the clear and the
 * pick put the flag and the allowlist back, and the next activity start locked a device whose listener had just chosen another launcher.
 * Now [leave] writes a mark first ([markHomeExit]), the restore writes nothing while it stands, and [settle] (the next activity start or
 * resume, when the picker is over) reads what Android says: another launcher is home, the exit stands; Polylute is still home, the picker
 * was cancelled or Polylute picked again, and the policy is put back. A *lost* policy (a reboot, an OEM reset of preferred activities) on a
 * Device Owner that did not choose to leave carries no mark and is healed as before.
 *
 * Pure: every platform read and write is a lambda, so the order and the answers are what is tested.
 */
internal class DapContainmentReconciler(
    private val readState: () -> DapDeviceOwnerState,
    private val persistentHomeRequired: () -> Boolean,
    private val containmentRequired: () -> Boolean,
    private val requiredLockTaskFeatures: () -> Int,
    private val markHomeExit: (Boolean) -> Unit,
    private val setDedicatedFlag: (Boolean) -> Unit,
    private val applyKeyguardDisabled: () -> Boolean,
    private val applyPersistentHome: () -> Boolean,
    private val configureContainmentPolicy: () -> Boolean,
    private val clearPolicies: () -> Boolean,
    private val clearPersistentHome: () -> Boolean,
    private val enableDedicatedMode: () -> Boolean,
) {

    /**
     * Brings the appliance policy back in line with Android's state, for every trigger. Writes nothing without Device Owner, and nothing
     * while a HOME exit the listener chose is waiting to be settled. When it re-contains a Device Owner whose listener made Polylute the home
     * app again it completes Dedicated: flag, allowlist and the persistent HOME (B34-fix3 R2).
     */
    fun restore(trigger: DapRecoveryTrigger): DapPolicyRestoreResult {
        val state = readState()
        if (!state.isDeviceOwner) return DapPolicyRestoreResult(false, false, false)
        if (state.isHomeExitChosen) return DapPolicyRestoreResult(isDeviceOwner = true, persistentHomeApplied = false, containmentConfigured = false)

        reconcileContainmentFlag(state)
        // Re-applied on every restore trigger, not only while an Activity is composing: the
        // wake-surface policy has to survive boot and package replacement too, and Lock Task
        // additionally does not suppress the Keyguard until it is next held.
        applyKeyguardDisabled()
        // B34-fix3 R2: the persistent HOME is part of Dedicated, and Android cannot be asked whether the persistent entry is in force, so the
        // DAP's own record says whether it put it there (`applyPersistentHome` writes it on, the exit's clear writes it off). Polylute being
        // the home app is not that: a listener who picks Polylute in Android's picker makes it home without the entry. A Device Owner whose
        // home app is Polylute (the evidence of Dedicated) and whose record says "off" is half-provisioned, so the entry is applied. That is
        // B34's table row 6 (the exit settled as LEFT, then Polylute picked again) and the state fix2 left behind when it re-armed the flag
        // and the allowlist without it. The record off with another launcher home is a device that left: nothing is required there.
        val persistentHomeRecorded = persistentHomeRequired()
        val persistentHomeRequired = persistentHomeRecorded || state.isPixelPlayerHome
        val containmentRequired = containmentRequired()
        val containmentReady = state.isLockTaskPermitted && state.lockTaskFeatures == requiredLockTaskFeatures()
        val plan = DapProductionLifecycle.plan(
            trigger = trigger,
            snapshot = DapRecoverySnapshot(
                isDedicatedActive = true,
                isDeviceOwner = state.isDeviceOwner,
                persistentHomeRequired = persistentHomeRequired,
                persistentHomeReady = persistentHomeRecorded && state.isPixelPlayerHome,
                containmentRequired = containmentRequired,
                containmentPolicyReady = containmentReady,
            ),
        )
        val homeApplied = when {
            !persistentHomeRequired -> false
            plan.applyPersistentHome -> applyPersistentHome()
            else -> state.isPixelPlayerHome
        }
        val containmentConfigured = when {
            !containmentRequired -> false
            plan.configureContainment -> configureContainmentPolicy()
            else -> containmentReady
        }
        return DapPolicyRestoreResult(
            isDeviceOwner = true,
            persistentHomeApplied = homeApplied,
            containmentConfigured = containmentConfigured,
            persistentHomeWritten = plan.applyPersistentHome,
            containmentPolicyWritten = plan.configureContainment,
            recoveryPlan = plan,
        )
    }

    /**
     * Makes the containment flag agree with the authoritative Android state.
     *
     * `ProductModeResolver` treats Device Owner + PixelPlayer-as-HOME as proof that the appliance
     * was provisioned, and reports `DEDICATED_ACTIVE`. The flag this method writes is what
     * `DapContainmentPolicy.shouldEnterLockTask` actually gates on — through
     * `DapDeviceOwnerState.isDedicatedModeEnabled` — and nothing was reconciling the two.
     *
     * They diverge whenever HOME ownership survives while the flag does not: a package replacement,
     * an uninstall/reinstall, or a HOME set through Android's own settings rather than this app's
     * setup. The visible result is an appliance that reports itself as active, shows Home and
     * Lock Task as applied, and never enters Lock Task at all — because `maybeEnterDedicatedMode`
     * early-returns on the flag before it ever asks.
     *
     * Re-asserting is safe only when the listener has not chosen to leave. B34 said "a deliberate exit clears HOME ownership first, and once
     * PixelPlayer is no longer HOME this writes nothing", which left a window: between the clear and the pick Polylute *is* still HOME. The
     * recorded exit ([DapDeviceOwnerState.isHomeExitChosen]) closes it; [restore] returns before it gets here, and this checks again.
     */
    private fun reconcileContainmentFlag(state: DapDeviceOwnerState) {
        if (!state.isDeviceOwner || !state.isPixelPlayerHome) return
        if (state.isHomeExitChosen) return
        if (state.isDedicatedModeEnabled) return
        setDedicatedFlag(true)
    }

    /**
     * "Choose home app" on a Dedicated Device Owner: records the exit, then clears the flag, the keyguard hold, the Lock Task mask and
     * allowlist, and the persistent HOME. True when all of it is cleared. The mark goes first so that nothing running between the clear
     * and the pick (a window focus change, a resume) can read a half-cleared device as a lost policy; a clear that fails takes the mark
     * back, so the caller's usual recovery re-applies the policy. An exception propagates after the mark is taken back.
     */
    fun leave(): Boolean {
        markHomeExit(true)
        var cleared = false
        try {
            cleared = clearPolicies() && clearPersistentHome()
        } finally {
            if (!cleared) markHomeExit(false)
        }
        return cleared
    }

    /**
     * Settles a HOME exit that is waiting for Android's picker to finish. Run at an activity start or resume: by then the picker is over
     * (the activity cannot be resumed while it is in front) or it never opened. The mark is taken first, so what follows is decided from
     * Android's state and not from a mark that has done its job.
     */
    fun settle(): HomeExitSettlement {
        val state = readState()
        if (!state.isHomeExitChosen) return HomeExitSettlement.NONE
        markHomeExit(false)
        if (!state.isDeviceOwner || !state.isPixelPlayerHome) return HomeExitSettlement.LEFT
        // Polylute is still the home app: the listener cancelled the picker, or picked Polylute again. "If you cancel, Polylute locks again"
        if (!enableDedicatedMode()) return HomeExitSettlement.NOT_RECONTAINED
        // the wake-surface policy (keyguard hold) comes back with the same restore every start runs
        restore(DapRecoveryTrigger.ACTIVITY_START)
        return HomeExitSettlement.RECONTAINED
    }
}
