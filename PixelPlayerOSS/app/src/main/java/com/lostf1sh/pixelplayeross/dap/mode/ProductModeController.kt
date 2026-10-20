package com.lostf1sh.pixelplayeross.dap.mode

import android.content.Context
import com.lostf1sh.pixelplayeross.dap.admin.DapDeviceOwnerController
import com.lostf1sh.pixelplayeross.dap.admin.DapPolicyRestoreResult
import com.lostf1sh.pixelplayeross.dap.lifecycle.DapRecoveryTrigger

/**
 * Authoritative product-mode boundary.
 *
 * UI and lifecycle code ask this controller for an effective mode instead of inferring product
 * behavior from Device Owner state in scattered call sites.
 */
class ProductModeController(context: Context) {
    private val appContext = context.applicationContext
    private val store = ProductModeStore(appContext)
    private val deviceOwnerController = DapDeviceOwnerController(appContext)

    fun currentState(): ProductModeState {
        val ownerState = deviceOwnerController.currentState()
        val evidence = DedicatedProvisioningEvidence(
            isDeviceOwner = ownerState.isDeviceOwner,
            isPixelPlayerHome = ownerState.isPixelPlayerHome,
            isDedicatedModeEnabled = ownerState.isDedicatedModeEnabled,
            isLockTaskPermitted = ownerState.isLockTaskPermitted,
            lockTaskModeState = ownerState.lockTaskModeState,
            homeExitChosen = ownerState.isHomeExitChosen,
        )
        // B34: a Pinned or Dedicated request does not outlive the home role. Read here, where every consumer reads the mode, so the page
        // and the activity agree on the moment it ends (no one has to remember to call a cleanup).
        ProductModeResolver.requestAfterLosingHome(store.requestedMode(), evidence)?.let(store::setRequestedMode)
        val resolved = ProductModeResolver.resolve(requestedMode = store.requestedMode(), evidence = evidence)

        // One-time migration for installations that predate the explicit mode model.
        if (resolved.isDedicatedActive && store.requestedMode() != ProductModeRequest.DEDICATED) {
            store.setRequestedMode(ProductModeRequest.DEDICATED)
        }
        return resolved
    }

    /**
     * Records the level the listener picked (B34). A request, not a claim: [currentState] still says what Android confirms, and the
     * page shows both. Asked for a level only makes sense while Polylute is the home app; anywhere else the preference is reset on the
     * next read ([ProductModeResolver.requestAfterLosingHome]).
     */
    fun requestLevel(level: ProductLevel): ProductModeState {
        store.setRequestedMode(
            when (level) {
                ProductLevel.LAUNCHER -> ProductModeRequest.APP
                ProductLevel.PINNED -> ProductModeRequest.PINNED
                ProductLevel.DEDICATED -> ProductModeRequest.DEDICATED
            },
        )
        return currentState()
    }

    fun requestDedicatedSetup(): ProductModeState = requestLevel(ProductLevel.DEDICATED)

    /** Whether Polylute holds an app pin it should not (it is no longer the home app); the activity releases it. */
    fun shouldReleasePin(): Boolean {
        val ownerState = deviceOwnerController.currentState()
        return ProductModeResolver.shouldReleasePin(
            DedicatedProvisioningEvidence(
                isDeviceOwner = ownerState.isDeviceOwner,
                isPixelPlayerHome = ownerState.isPixelPlayerHome,
                isDedicatedModeEnabled = ownerState.isDedicatedModeEnabled,
                isLockTaskPermitted = ownerState.isLockTaskPermitted,
                lockTaskModeState = ownerState.lockTaskModeState,
                homeExitChosen = ownerState.isHomeExitChosen,
            ),
        )
    }

    fun isDedicatedActive(): Boolean = currentState().isDedicatedActive

    fun restoreDedicatedPoliciesIfActive(
        trigger: DapRecoveryTrigger = DapRecoveryTrigger.ACTIVITY_START,
    ): DapPolicyRestoreResult {
        if (!isDedicatedActive()) return DapPolicyRestoreResult(
            isDeviceOwner = false,
            persistentHomeApplied = false,
            containmentConfigured = false,
        )
        return deviceOwnerController.restoreOwnerPolicies(trigger)
    }
}
