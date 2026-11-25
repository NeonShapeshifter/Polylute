package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.dap.mode.ProductLevel
import org.junit.jupiter.api.Test

class B72NotificationPermissionPolicyTest {

    @Test
    fun `never asks below API 33`() {
        for (api in listOf(26, 29, 31, 32)) {
            assertThat(
                SafetyMaxNotificationPromptPolicy.shouldAskAfterCeilingSaved(
                    androidApi = api,
                    permissionGranted = false,
                    ceilingJustSaved = true,
                    alreadyAsked = false,
                    dapLevel = null,
                )
            ).isFalse()
            assertThat(
                SafetyMaxNotificationPromptPolicy.shouldShowNotificationsOffNotice(
                    androidApi = api,
                    permissionGranted = false,
                )
            ).isFalse()
        }
    }

    @Test
    fun `asks on API 33 plus only when missing, ceiling just saved, not already asked, and not pinned`() {
        assertThat(
            SafetyMaxNotificationPromptPolicy.shouldAskAfterCeilingSaved(
                androidApi = 33,
                permissionGranted = false,
                ceilingJustSaved = true,
                alreadyAsked = false,
                dapLevel = null,
            )
        ).isTrue()

        assertThat(
            SafetyMaxNotificationPromptPolicy.shouldAskAfterCeilingSaved(
                androidApi = 35,
                permissionGranted = true,
                ceilingJustSaved = true,
                alreadyAsked = false,
                dapLevel = null,
            )
        ).isFalse()

        assertThat(
            SafetyMaxNotificationPromptPolicy.shouldAskAfterCeilingSaved(
                androidApi = 35,
                permissionGranted = false,
                ceilingJustSaved = false,
                alreadyAsked = false,
                dapLevel = null,
            )
        ).isFalse()

        assertThat(
            SafetyMaxNotificationPromptPolicy.shouldAskAfterCeilingSaved(
                androidApi = 35,
                permissionGranted = false,
                ceilingJustSaved = true,
                alreadyAsked = true,
                dapLevel = null,
            )
        ).isFalse()
    }

    @Test
    fun `DAP levels allow Launcher and Dedicated with lift, skip Pinned`() {
        assertThat(
            SafetyMaxNotificationPromptPolicy.shouldAskAfterCeilingSaved(
                androidApi = 35,
                permissionGranted = false,
                ceilingJustSaved = true,
                alreadyAsked = false,
                dapLevel = ProductLevel.LAUNCHER,
            )
        ).isTrue()

        assertThat(
            SafetyMaxNotificationPromptPolicy.shouldAskAfterCeilingSaved(
                androidApi = 35,
                permissionGranted = false,
                ceilingJustSaved = true,
                alreadyAsked = false,
                dapLevel = ProductLevel.DEDICATED,
            )
        ).isTrue()

        assertThat(
            SafetyMaxNotificationPromptPolicy.shouldAskAfterCeilingSaved(
                androidApi = 35,
                permissionGranted = false,
                ceilingJustSaved = true,
                alreadyAsked = false,
                dapLevel = ProductLevel.PINNED,
            )
        ).isFalse()
    }

    @Test
    fun `notifications off notice shows only on API 33 plus when permission is missing`() {
        assertThat(
            SafetyMaxNotificationPromptPolicy.shouldShowNotificationsOffNotice(
                androidApi = 33,
                permissionGranted = false,
            )
        ).isTrue()
        assertThat(
            SafetyMaxNotificationPromptPolicy.shouldShowNotificationsOffNotice(
                androidApi = 35,
                permissionGranted = true,
            )
        ).isFalse()
        assertThat(
            SafetyMaxNotificationPromptPolicy.shouldShowNotificationsOffNotice(
                androidApi = 32,
                permissionGranted = false,
            )
        ).isFalse()
    }
}
