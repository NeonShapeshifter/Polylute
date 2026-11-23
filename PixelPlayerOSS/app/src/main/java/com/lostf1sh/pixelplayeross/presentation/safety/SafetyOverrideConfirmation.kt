package com.lostf1sh.pixelplayeross.presentation.safety

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.data.volume.SafetyOverridePolicy

/**
 * B69: the Safety Max override asked for from the notification's "Raise the limit", outside the app.
 *
 * What the authority is: [com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController], the one place an override is granted.
 */
interface SafetyOverrideAuthority {
    /** There is still something to confirm: Safety Max protects the output, no override is on, the level is at the ceiling. */
    fun outsideOfferStillValid(): Boolean

    /** The ordinary override session, with its ordinary lifetime (the in-app button calls the same). */
    fun allowTemporaryOverride()

    /** Cancelled: the ceiling stays. */
    fun declineOutsideOffer()
}

/**
 * The confirmation's decisions, apart from its window. It never changes a volume: opening it asks, [confirm] grants the ordinary
 * temporary override (the level is still the listener's to raise, with the keys), [cancel] leaves the ceiling as it is.
 * The device must be unlocked: a confirmation found on a locked device is not shown, and a confirm that arrives locked does nothing.
 */
class SafetyOverrideConfirmation(
    private val authority: SafetyOverrideAuthority,
    private val deviceLocked: () -> Boolean,
) {
    /** Whether the dialog is shown at all. A stale action (the level was lowered, the override is on already) opens nothing. */
    fun shouldShow(): Boolean = !deviceLocked() && authority.outsideOfferStillValid()

    fun confirm() {
        if (deviceLocked() || !authority.outsideOfferStillValid()) return
        authority.allowTemporaryOverride()
    }

    fun cancel() = authority.declineOutsideOffer()
}

/**
 * The same confirmation the app shows (title, message, how long the override lasts, "Temporary override"), less the "Disable for this
 * headphone" button: as over the lock screen (FIX-5), from outside the app the choice is the temporary override or nothing.
 */
@Composable
fun SafetyOverrideConfirmDialog(
    profileName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    /** The app's modal input contract (volume keys to the Safety authority, edge Back); off only in a JVM test, which has no Hilt. */
    bridgeInput: Boolean = true,
) {
    AlertDialog(
        modifier = Modifier.testTag(SAFETY_OVERRIDE_CONFIRM_TAG),
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.safety_max_reached_title)) },
        text = {
            // Like every app window: the volume keys reach the same authority (and its ceiling) as in the app.
            if (bridgeInput) {
                ModalInputBridge()
            }
            Text(
                stringResource(R.string.safety_max_reached_message, profileName) + "\n\n" +
                    stringResource(R.string.safety_max_override_lasts, (SafetyOverridePolicy.IDLE_END_MS / 60_000L).toInt())
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.safety_max_temporary_override)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

const val SAFETY_OVERRIDE_CONFIRM_TAG = "safety_override_confirm"
