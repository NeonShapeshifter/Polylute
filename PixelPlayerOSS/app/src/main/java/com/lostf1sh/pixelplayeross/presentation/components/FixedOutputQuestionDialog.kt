package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.volume.DeviceVolumeMode
import com.lostf1sh.pixelplayeross.data.volume.FixedOutputNotice
import com.lostf1sh.pixelplayeross.data.volume.VolumeQuestionStage
import kotlin.math.ceil

/**
 * B40-P3: the question about a device whose volume Polylute cannot set. Once per device: does it have its own control; if so Resonance
 * (recommended, the default button) or Purity; if not, Resonance only. A rise Purity would bring is confirmed before it applies.
 */
@Composable
fun FixedOutputQuestionDialog(
    notice: FixedOutputNotice,
    onDismiss: () -> Unit,
    onOwnControl: (Boolean) -> Unit,
    onChoose: (DeviceVolumeMode) -> Unit,
    onConfirmRise: () -> Unit,
) {
    val notNow: @Composable () -> Unit = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.fixed_output_notice_not_now)) }
    }
    // one window for the three stages: what differs is its words and its buttons
    val title: String
    val body: String
    val confirm: @Composable () -> Unit
    val dismiss: @Composable () -> Unit
    when (notice.stage) {
        VolumeQuestionStage.ASK_CONTROL -> {
            title = stringResource(R.string.fixed_output_notice_title)
            body = stringResource(R.string.fixed_output_question_body, notice.dac.name)
            confirm = {
                TextButton(onClick = { onOwnControl(true) }) { Text(stringResource(R.string.fixed_output_question_yes)) }
            }
            dismiss = {
                Row {
                    TextButton(onClick = { onOwnControl(false) }) { Text(stringResource(R.string.fixed_output_question_no)) }
                    notNow()
                }
            }
        }
        VolumeQuestionStage.CHOOSE_MODE -> {
            title = stringResource(R.string.fixed_output_mode_title, notice.dac.name)
            body = stringResource(R.string.fixed_output_mode_body)
            // Resonance is the recommended, default button
            confirm = {
                TextButton(onClick = { onChoose(DeviceVolumeMode.RESONANCE) }) { Text(stringResource(R.string.fixed_output_mode_resonance)) }
            }
            dismiss = {
                Row {
                    TextButton(onClick = { onChoose(DeviceVolumeMode.PURITY) }) { Text(stringResource(R.string.fixed_output_mode_purity)) }
                    notNow()
                }
            }
        }
        VolumeQuestionStage.CONFIRM_RISE -> {
            title = stringResource(R.string.fixed_output_rise_title)
            body = stringResource(R.string.fixed_output_rise_body, notice.dac.name, ceil(notice.riseDb ?: 0f).toInt())
            confirm = {
                TextButton(onClick = onConfirmRise) { Text(stringResource(R.string.fixed_output_rise_confirm)) }
            }
            dismiss = notNow
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // this window is its own surface: the activity's volume-key path does not reach it
            ModalInputBridge()
            Text(body)
        },
        confirmButton = confirm,
        dismissButton = dismiss,
    )
}
