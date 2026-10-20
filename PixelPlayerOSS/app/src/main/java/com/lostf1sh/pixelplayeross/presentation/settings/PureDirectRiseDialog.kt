package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.formatDecibels

/**
 * "El nivel subiría" (`DialogPureRise.dc.html`): turning Pure Direct on would raise the level, because the digital
 * level it leaves at 100 % was holding it down and the DAC cannot take that attenuation over.
 *
 * The trigger is what it has always been: `DeviceControlViewModel.setPristineMode(true)` asking for a confirmation
 * (`pristineRiseToConfirm`), and the two answers are `confirmPristineRise` / `dismissPristineRise`. Only the look is
 * new. It states the rise and says why, because the reason differs (outside USB Direct, a DAC with no volume the app
 * can set, a DAC already at its lowest level) and the listener decides on it.
 */
@Composable
fun PureDirectRiseDialog(
    riseDb: Float?,
    reason: PristineModePolicy.RiseReason?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * Whether the dialog's window routes the volume keys to the Safety authority (always, in the app). A JVM render has
     * no Hilt component to take that authority from, so it opts out.
     */
    mountInputBridge: Boolean = true,
) {
    if (riseDb == null) return
    val why = stringResource(
        when (reason) {
            PristineModePolicy.RiseReason.OUTSIDE_DIRECT -> R.string.pristine_rise_reason_outside_direct
            PristineModePolicy.RiseReason.DAC_RANGE -> R.string.pristine_rise_reason_range
            else -> R.string.pristine_rise_reason_no_hardware
        }
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Dap.surface,
        shape = RoundedCornerShape(DapRadius.Surface),
        title = {
            // An alert is a window of its own: the bridge routes the volume keys to the same Safety authority the
            // activity uses.
            if (mountInputBridge) {
                ModalInputBridge()
            }
            Text(
                text = stringResource(R.string.pristine_rise_title),
                style = DapType.Heading,
                color = Dap.primaryText,
            )
        },
        text = {
            Text(
                text = stringResource(
                    R.string.pristine_rise_body,
                    stringResource(R.string.pristine_lock_name),
                    formatDecibels(riseDb),
                    why,
                ),
                style = DapType.Body,
                color = Dap.secondaryText,
            )
        },
        confirmButton = {
            DapTextAction(
                label = stringResource(R.string.pristine_rise_confirm),
                onClick = onConfirm,
                color = LocalDapAccent.current,
            )
        },
        dismissButton = {
            DapTextAction(
                label = stringResource(R.string.pristine_rise_cancel),
                onClick = onDismiss,
                color = Dap.secondaryText,
            )
        },
    )
}
