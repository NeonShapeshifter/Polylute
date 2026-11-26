package com.lostf1sh.pixelplayeross.presentation.safety

import android.app.Application
import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfile
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneSafetyPolicy
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.volume.SafetyMaxCalibrationResult
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import com.lostf1sh.pixelplayeross.data.volume.VolumeBackendSnapshot
import com.lostf1sh.pixelplayeross.di.ModalInputEntryPoint
import com.lostf1sh.pixelplayeross.presentation.components.SafetyMaxNotificationPromptSheet
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.screens.HeadphoneProfileScreenActions
import com.lostf1sh.pixelplayeross.presentation.screens.HeadphoneProfileScreenContent
import com.lostf1sh.pixelplayeross.presentation.screens.HeadphoneProfileScreenState
import com.lostf1sh.pixelplayeross.presentation.screens.SafetyMaxScreenActions
import com.lostf1sh.pixelplayeross.presentation.screens.SafetyMaxScreenContent
import com.lostf1sh.pixelplayeross.presentation.screens.SafetyMaxScreenState
import com.lostf1sh.pixelplayeross.presentation.settings.AudioSettingsActions
import com.lostf1sh.pixelplayeross.presentation.settings.AudioSettingsScreen
import com.lostf1sh.pixelplayeross.presentation.settings.AudioSettingsState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class B72SafetyMaxNotificationPromptTest {
    @get:Rule val compose = createComposeRule()

    @Before fun provideModalAuthority() {
        val prefs = mockk<UserPreferencesRepository>()
        every { prefs.backNavigationModeFlow } returns flowOf(BackNavigationMode.DEFAULT)
        val point = object : ModalInputEntryPoint {
            override fun safetyVolumeController() = mockk<SafetyVolumeController>(relaxed = true)
            override fun userPreferencesRepository() = prefs
        }
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(
                ApplicationProvider.getApplicationContext<Context>(),
                ModalInputEntryPoint::class.java,
            )
        } returns point
    }

    @After fun restoreModalAuthority() = unmockkStatic(EntryPointAccessors::class)

    private fun sampleProfile(withCeiling: Boolean): HeadphoneProfile {
        val calibration = if (withCeiling) {
            SafetyMaxCalibration(
                outputContextId = "speaker",
                backendKind = SafetyVolumeBackendKind.ANDROID_MEDIA,
                ceilingRaw = 10,
                backendMinRaw = 0,
                backendMaxRaw = 15,
                calibratedAtEpochMs = 1L,
            )
        } else {
            null
        }
        return HeadphoneProfile(
            profileId = "p1",
            displayName = "Synthetic Phones",
            equalizer = HeadphoneEqState(),
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
            safetyMax = HeadphoneSafetyPolicy(
                enabled = true,
                calibrations = calibration?.let { mapOf(it.storageKey to it) } ?: emptyMap(),
            ),
        )
    }

    private fun sampleVolume(withCeiling: Boolean): SafetyVolumeState {
        val calibration = sampleProfile(withCeiling).safetyMax.calibrationFor("speaker", SafetyVolumeBackendKind.ANDROID_MEDIA)
        return SafetyVolumeState(
            backend = VolumeBackendSnapshot(
                kind = SafetyVolumeBackendKind.ANDROID_MEDIA,
                outputContextId = "speaker",
                outputLabel = "Phone speaker",
                rawLevel = 10,
                rawMin = 0,
                rawMax = 15,
                controllable = true,
                levelVerified = true,
            ),
            selectedProfileId = "p1",
            selectedProfileName = "Synthetic Phones",
            safetyEnabled = true,
            calibration = calibration,
        )
    }

    @Test
    fun `sheet appears once on save, Not now prevents re-asking, Allow invokes fake launcher`() {
        var canPrompt by mutableStateOf(true)
        var notificationsOff by mutableStateOf(true)
        var launcherCalls = 0
        var dismissCalls = 0
        var openSettingsCalls = 0

        compose.setContent {
            DapTheme {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    SafetyMaxScreenContent(
                        state = SafetyMaxScreenState(
                            profileId = "p1",
                            profile = sampleProfile(withCeiling = true),
                            volume = sampleVolume(withCeiling = true),
                            notificationsOff = notificationsOff,
                            canPromptForNotifications = canPrompt,
                        ),
                        actions = object : SafetyMaxScreenActions {
                            override fun onBack() {}
                            override fun onSetRawVolume(raw: Int) {}
                            override fun onSafetyMaxEnabledChange(enabled: Boolean) {}
                            override fun onCalibrate(onResult: (SafetyMaxCalibrationResult) -> Unit) {
                                onResult(SafetyMaxCalibrationResult.SAVED)
                            }
                            override fun onOpenNotificationSettings() { openSettingsCalls++ }
                            override fun onRequestNotificationPermission() {
                                launcherCalls++
                                canPrompt = false
                            }
                            override fun onDismissNotificationPrompt() {
                                dismissCalls++
                                canPrompt = false
                            }
                        },
                    )
                }
            }
        }

        // First save shows the sheet
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Use current volume as Safety Max"))
        compose.onNodeWithText("Use current volume as Safety Max").performClick()
        compose.onNodeWithText("Let Polylute notify you?").assertIsDisplayed()
        compose.onNodeWithText("Not now").performClick()
        assertThat(dismissCalls).isEqualTo(1)
        assertThat(launcherCalls).isEqualTo(0)

        // Saving again does not re-ask after Not now
        compose.onNodeWithText("Use current volume as Safety Max").performClick()
        compose.onNodeWithText("Let Polylute notify you?").assertDoesNotExist()

        // Settings link is visible and invokes callback when notifications are off
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Notifications are off: you can only raise the limit inside the app"))
        compose.onNodeWithText("Notifications are off: you can only raise the limit inside the app").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Notification settings"))
        compose.onNodeWithText("Notification settings").performClick()
        assertThat(openSettingsCalls).isEqualTo(1)

        // When allowed on a fresh state, Allow launches the permission request
        canPrompt = true
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Use current volume as Safety Max"))
        compose.onNodeWithText("Use current volume as Safety Max").performClick()
        compose.onNodeWithText("Allow").performClick()
        assertThat(launcherCalls).isEqualTo(1)
        compose.onNodeWithText("Let Polylute notify you?").assertDoesNotExist()

        // Once granted (notificationsOff = false), the quiet line disappears
        notificationsOff = false
        compose.waitForIdle()
        compose.onNodeWithText("Notifications are off: you can only raise the limit inside the app").assertDoesNotExist()
        compose.onNodeWithText("Notification settings").assertDoesNotExist()
    }

    @Test
    fun `notifications off line appears on HeadphoneProfile and AudioSettings when ceiling is shown and missing`() {
        var notificationsOff by mutableStateOf(true)
        var settingsClicks = 0
        val profile = sampleProfile(withCeiling = true)
        val profileItem = HeadphoneProfileUiItem(profile = profile, isSelected = true)
        val profiles = HeadphoneProfilesUiState(
            profiles = listOf(profileItem),
            selected = profileItem,
        )
        val volume = sampleVolume(withCeiling = true)

        compose.setContent {
            DapTheme {
                CompositionLocalProvider(LocalDapBackNavigation provides DapBackAuthority(OnBackPressedDispatcher())) {
                    HeadphoneProfileScreenContent(
                        state = HeadphoneProfileScreenState.from(
                            profileId = "p1",
                            profiles = profiles,
                            eq = EqualizerUiState(),
                            audio = AudioStatus(),
                            volume = volume,
                            notificationsOff = notificationsOff,
                        ),
                        actions = object : HeadphoneProfileScreenActions {
                            override fun onBack() {}
                            override fun onSelect(profileId: String) {}
                            override fun onRename(profileId: String, name: String) {}
                            override fun onForget(profileId: String) {}
                            override fun onOpenEqualizer(profileId: String) {}
                            override fun onOpenSafetyMax(profileId: String) {}
                            override fun onBassBoostEnabledChange(enabled: Boolean) {}
                            override fun onBassBoostStrengthChange(strength: Int) {}
                            override fun onVirtualizerEnabledChange(enabled: Boolean) {}
                            override fun onVirtualizerStrengthChange(strength: Int) {}
                            override fun onLoudnessEnhancerEnabledChange(enabled: Boolean) {}
                            override fun onLoudnessEnhancerStrengthChange(strength: Int) {}
                            override fun onOpenNotificationSettings() { settingsClicks++ }
                        },
                    )
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Notification settings"))
        compose.onNodeWithText("Notification settings").performClick()
        assertThat(settingsClicks).isEqualTo(1)

        notificationsOff = false
        compose.waitForIdle()
        compose.onNodeWithText("Notifications are off: you can only raise the limit inside the app").assertDoesNotExist()
    }

    @Test
    fun `notifications off line appears on AudioSettings safety row only when missing`() {
        var notificationsOff by mutableStateOf(true)
        var settingsClicks = 0
        val profile = sampleProfile(withCeiling = true)
        val profileItem = HeadphoneProfileUiItem(profile = profile, isSelected = true)
        val profiles = HeadphoneProfilesUiState(
            profiles = listOf(profileItem),
            selected = profileItem,
        )
        val volume = sampleVolume(withCeiling = true)

        compose.setContent {
            DapTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    AudioSettingsScreen(
                    state = AudioSettingsState.from(
                        ui = SettingsUiState(),
                        playbackSpeed = 1f,
                        audio = AudioStatus(),
                        dacs = DacProfilesUiState(),
                        headphones = profiles,
                        volume = volume,
                        notificationsOff = notificationsOff,
                    ),
                    actions = object : AudioSettingsActions {
                        override fun onPureDirectChange(value: Boolean) {}
                        override fun onConfirmPureDirectRise() {}
                        override fun onDismissPureDirectRise() {}
                        override fun onResetDigitalGain() {}
                        override fun onReplayGainEnabledChange(value: Boolean) {}
                        override fun onReplayGainUseAlbumGainChange(value: Boolean) {}
                        override fun onCrossfadeEnabledChange(value: Boolean) {}
                        override fun onSmartCrossfadeEnabledChange(value: Boolean) {}
                        override fun onPlaybackSpeedChange(value: Float) {}
                        override fun onPersistentShuffleEnabledChange(value: Boolean) {}
                        override fun onOpenDacProfile(profileId: String) {}
                        override fun onOpenKnownDacs() {}
                        override fun onOpenHeadphoneProfile(profileId: String) {}
                        override fun onOpenKnownHeadphones() {}
                        override fun onOpenEqualizer(profileId: String) {}
                        override fun onOpenSafetyMax(profileId: String) {}
                        override fun onOpenGlobalTransitionEditor() {}
                        override fun onShowFixedOutputNotice() {}
                        override fun onDacShareChange(value: Boolean) {}
                        override fun onAskUsbPermission() {}
                        override fun onOpenNotificationSettings() { settingsClicks++ }
                    },
                    mountInputBridge = false,
                    )
                }
            }
        }
        compose.onNodeWithText("Notifications are off: you can only raise the limit inside the app")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Notification settings").performScrollTo().performClick()
        assertThat(settingsClicks).isEqualTo(1)

        notificationsOff = false
        compose.waitForIdle()
        compose.onNodeWithText("Notifications are off: you can only raise the limit inside the app").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "en")
    fun `prompt and notice strings in English`() {
        compose.setContent {
            DapTheme {
                SafetyMaxNotificationPromptSheet(onAllow = {}, onNotNow = {})
            }
        }
        compose.onNodeWithText("Let Polylute notify you?").assertIsDisplayed()
        compose.onNodeWithText("When you reach your Safety Max outside Polylute", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Allow").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "es")
    fun `prompt and notice strings in Spanish`() {
        compose.setContent {
            DapTheme {
                SafetyMaxNotificationPromptSheet(onAllow = {}, onNotNow = {})
            }
        }
        compose.onNodeWithText("¿Permitir notificaciones de Polylute?").assertIsDisplayed()
        compose.onNodeWithText("Cuando llegues a tu Safety Max fuera de Polylute", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Permitir").assertIsDisplayed()
        compose.onNodeWithText("Ahora no").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "sv")
    fun `prompt and notice strings in Swedish`() {
        compose.setContent {
            DapTheme {
                SafetyMaxNotificationPromptSheet(onAllow = {}, onNotNow = {})
            }
        }
        compose.onNodeWithText("Låt Polylute skicka aviseringar?").assertIsDisplayed()
        compose.onNodeWithText("När du når ditt Safety Max utanför Polylute", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Tillåt").assertIsDisplayed()
        compose.onNodeWithText("Inte nu").assertIsDisplayed()
    }
}
