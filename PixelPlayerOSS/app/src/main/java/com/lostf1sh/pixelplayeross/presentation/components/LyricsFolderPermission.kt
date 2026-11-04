package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R

@Composable
internal fun LyricsFolderPermission(visible: Boolean, onResult: (Uri?, Int) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onResult(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null, result.data?.flags ?: 0)
    }
    if (visible) {
        AlertDialog(
            onDismissRequest = { onResult(null, 0) },
            title = {
                ModalInputBridge()
                Text(stringResource(R.string.lyrics_folder_title))
            },
            text = { Text(stringResource(R.string.lyrics_folder_explanation)) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        ))
                    } catch (_: android.content.ActivityNotFoundException) { onResult(null, 0) }
                }) { Text(stringResource(R.string.lyrics_choose_folder)) }
            },
            dismissButton = { TextButton(onClick = { onResult(null, 0) }) { Text(stringResource(R.string.lyrics_keep_in_app)) } }
        )
    }
}
