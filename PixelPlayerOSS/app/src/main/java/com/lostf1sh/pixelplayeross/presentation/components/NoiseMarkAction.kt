package com.lostf1sh.pixelplayeross.presentation.components

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.diagnostics.NoiseMark
import java.io.File

/**
 * B39-S2: "Mark noise now", shared by the two places it is offered (the Now Playing More sheet and Maintenance).
 *
 * One press writes the last seconds of the USB output's summaries and the stream's state to a file ([NoiseMark.mark]) and opens a dialog
 * that names the file, says what is in it and offers to share it. Nothing is written before the press and nothing is sent by it.
 */
@Stable
class NoiseMarkState {
    /** The file just written, while its dialog is up. */
    var marked: File? by mutableStateOf(null)
        private set

    /** Marks the noise now; the result is [marked], or a toast if it could not be written. */
    fun mark(context: android.content.Context) {
        val file = NoiseMark.mark(context)
        if (file != null) {
            marked = file
        } else {
            Toast.makeText(context, context.getString(R.string.noise_mark_failed), Toast.LENGTH_LONG).show()
        }
    }

    fun close() {
        marked = null
    }
}

@Composable
fun rememberNoiseMarkState(): NoiseMarkState = remember { NoiseMarkState() }

/** The dialog of a noise mark that was just written; draws nothing when there is none. */
@Composable
fun NoiseMarkDialog(state: NoiseMarkState) {
    val file = state.marked ?: return
    val context = LocalContext.current
    val failedText = stringResource(R.string.noise_mark_failed)
    AlertDialog(
        onDismissRequest = state::close,
        title = { Text(stringResource(R.string.noise_mark_done_title)) },
        text = {
            // this window is its own surface: the activity's volume-key path does not reach it
            ModalInputBridge()
            Text(stringResource(R.string.noise_mark_done_text, file.name))
        },
        confirmButton = {
            TextButton(
                onClick = {
                    NoiseMark.shareIntent(context, file)?.let { intent ->
                        runCatching { context.startActivity(intent) }
                            .onFailure { Toast.makeText(context, failedText, Toast.LENGTH_LONG).show() }
                    }
                    state.close()
                },
            ) { Text(stringResource(R.string.noise_mark_share)) }
        },
        dismissButton = {
            TextButton(onClick = state::close) { Text(stringResource(R.string.noise_mark_close)) }
        },
    )
}
