package com.lostf1sh.pixelplayeross.dap.mode

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppLaunchPolicyTest {
    @Test
    fun `fresh install renders music UI without media permission or onboarding`() {
        val decision = AppLaunchPolicy.decide(
            legacySetupComplete = false,
            mediaPermissionGranted = false,
            benchmarkMode = false,
        )

        assertTrue(decision.showMusicUi)
        assertTrue(decision.markLegacySetupComplete)
        assertFalse(decision.startLibrarySync)
    }

    @Test
    fun `unknown legacy preference never blocks music UI`() {
        val decision = AppLaunchPolicy.decide(null, false, false)

        assertTrue(decision.showMusicUi)
        assertFalse(decision.markLegacySetupComplete)
        assertFalse(decision.startLibrarySync)
    }

    @Test
    fun `library sync starts only after music access exists`() {
        assertFalse(AppLaunchPolicy.decide(true, false, false).startLibrarySync)
        assertTrue(AppLaunchPolicy.decide(true, true, false).startLibrarySync)
    }

    @Test
    fun `notification permission is not a first launch input`() {
        val decision = AppLaunchPolicy.decide(false, false, false)

        assertTrue(decision.showMusicUi)
        assertTrue(decision.markLegacySetupComplete)
    }
}
