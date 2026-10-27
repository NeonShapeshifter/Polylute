package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqOffer

/**
 * B40-F2: asks for the listener's permission to change the DAC's EQ, and says what will happen. Saying yes writes nothing: the first change
 * is made when they edit the curve, behind the same gate as before (a copy of the DAC first, only the app's own slot, never louder than the
 * original, a read-back after every write).
 */
@Composable
fun DacEqConsentDialog(offer: DacEqOffer, onAllow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dac_eq_consent_title)) },
        text = {
            // this window is its own surface: the activity's volume-key path does not reach it
            ModalInputBridge()
            Text(stringResource(R.string.dac_eq_consent_body, offer.dacName, offer.slotName))
        },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.dac_eq_consent_allow)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dac_eq_consent_not_now)) } },
    )
}
