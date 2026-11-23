package com.lostf1sh.pixelplayeross.presentation.safety

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** B69: the confirmation opened from "Raise the limit" is the app's own, less "Disable for this headphone". One test per language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B69SafetyOverrideDialogTest {
    @get:Rule val compose = createComposeRule()

    private var confirmed = 0
    private var cancelled = 0

    private fun show() = compose.setContent {
        SafetyOverrideConfirmDialog(profileName = "Headphones", onConfirm = { confirmed++ }, onCancel = { cancelled++ }, bridgeInput = false)
    }

    @Test fun english() {
        show()
        compose.onNodeWithText("Safety Max reached").assertIsDisplayed()
        compose.onNodeWithText("Headphones", substring = true).assertIsDisplayed()
        compose.onNodeWithText("The override lasts until", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Disable for this headphone").assertDoesNotExist()
        compose.onNodeWithText("Temporary override").performClick()
        assertThat(confirmed).isEqualTo(1)
        compose.onNodeWithText("Cancel").performClick()
        assertThat(cancelled).isEqualTo(1)
    }

    @Test @Config(qualifiers = "es") fun spanish() {
        show()
        compose.onNodeWithText("Máximo configurado alcanzado").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        assertThat(cancelled).isEqualTo(1)
        assertThat(confirmed).isEqualTo(0)
    }

    @Test @Config(qualifiers = "sv") fun swedish() {
        show()
        compose.onNodeWithText("Safety Max nått").assertIsDisplayed()
        compose.onNodeWithText("Avbryt").performClick()
        assertThat(cancelled).isEqualTo(1)
    }
}
