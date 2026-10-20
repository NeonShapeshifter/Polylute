package com.lostf1sh.pixelplayeross.dap.admin

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B35-fix1 R1: the checks that say "this app's Lock Task policy is gone" are the ones every API answers alike. The mask is not one of them: with
 * no allowlist it means nothing, and the policy engine (Android 14+) reads `GLOBAL_ACTIONS` where the legacy store reads what was written.
 */
class DapClearedChecksTest {

    @Test
    fun `every check that fails is named, in the order the release reads them`() {
        val names = DapContainmentPolicy.unclearedOwnerPolicies(keyguardReleased = false, lockTaskPermitted = true, lockTaskPackages = listOf("p"))
        assertThat(names).containsExactly("keyguard hold", "isLockTaskPermitted", "getLockTaskPackages").inOrder()
    }

    @Test
    fun `a clear that worked names nothing`() {
        assertThat(DapContainmentPolicy.unclearedOwnerPolicies(keyguardReleased = true, lockTaskPermitted = false, lockTaskPackages = emptyList())).isEmpty()
    }

    @Test
    fun `each check fails on its own`() {
        assertThat(DapContainmentPolicy.unclearedOwnerPolicies(false, false, emptyList())).containsExactly("keyguard hold")
        assertThat(DapContainmentPolicy.unclearedOwnerPolicies(true, true, emptyList())).containsExactly("isLockTaskPermitted")
        assertThat(DapContainmentPolicy.unclearedOwnerPolicies(true, false, listOf("p"))).containsExactly("getLockTaskPackages")
    }
}
