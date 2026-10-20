package com.lostf1sh.pixelplayeross.dap.mode

data class AppLaunchDecision(
    val showMusicUi: Boolean,
    val markLegacySetupComplete: Boolean,
    val startLibrarySync: Boolean,
)

/** First launch is an application launch, never a mandatory onboarding gate. */
object AppLaunchPolicy {
    fun decide(
        legacySetupComplete: Boolean?,
        mediaPermissionGranted: Boolean,
        benchmarkMode: Boolean,
    ) = AppLaunchDecision(
        showMusicUi = true,
        markLegacySetupComplete = !benchmarkMode && legacySetupComplete == false,
        startLibrarySync = mediaPermissionGranted &&
            (legacySetupComplete == true || benchmarkMode),
    )
}
