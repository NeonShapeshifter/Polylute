package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerOutputState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SafetyMaxOutputState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapState
import com.lostf1sh.pixelplayeross.ui.ds.DapStateKind
import com.lostf1sh.pixelplayeross.ui.ds.DapType

@Composable
internal fun EqualizerOutputStatus(state: EqualizerOutputState) {
    DapState(
        kind = when (state) {
            EqualizerOutputState.APPLIED -> DapStateKind.Selected
            EqualizerOutputState.OFF -> DapStateKind.Off
            else -> DapStateKind.Bypassed
        },
        label = equalizerOutputLabel(state),
    )
}

@Composable
internal fun equalizerOutputLabel(state: EqualizerOutputState): String = stringResource(
    when (state) {
        EqualizerOutputState.OFF -> R.string.headphone_eq_off
        EqualizerOutputState.PROFILE_NOT_SELECTED -> R.string.profile_audio_not_selected
        EqualizerOutputState.BYPASSED_PURE_DIRECT -> R.string.eq_output_pure_direct
        EqualizerOutputState.BYPASSED_USB_DIRECT -> R.string.eq_output_usb_direct
        EqualizerOutputState.BYPASSED_MAINTENANCE -> R.string.eq_output_maintenance
        EqualizerOutputState.APPLIED -> R.string.profile_audio_applied
    },
)

/** Prose can wrap on narrow screens; the longer explanation is never a trailing row value. */
@Composable
internal fun SafetyMaxOutputStatus(state: SafetyMaxOutputState, backendKind: SafetyVolumeBackendKind) {
    Text(
        text = stringResource(when (state) {
            SafetyMaxOutputState.OFF -> R.string.safety_max_disabled
            SafetyMaxOutputState.PROFILE_NOT_SELECTED -> R.string.profile_audio_not_selected
            SafetyMaxOutputState.UNAVAILABLE -> if (backendKind == SafetyVolumeBackendKind.FIXED_OUTPUT) {
                R.string.safety_max_saved_unavailable_usb
            } else R.string.safety_max_saved_unavailable
            SafetyMaxOutputState.NOT_CALIBRATED -> R.string.safety_max_not_set_output
            SafetyMaxOutputState.ACTIVE -> R.string.safety_max_enabled_summary
            SafetyMaxOutputState.ACTIVE_RESCALED -> R.string.safety_max_output_rescaled
            SafetyMaxOutputState.TEMPORARY_OVERRIDE -> R.string.safety_max_output_override
        }),
        style = DapType.Caption,
        color = Dap.secondaryText,
    )
}
