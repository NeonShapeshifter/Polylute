package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.ui.ds.DapOverflowButton

@Composable
fun MusicInfoMenuItems(onPrepare: () -> Unit, onRefresh: () -> Unit, onDelete: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(R.string.music_info_prepare)) }, onClick = onPrepare)
    DropdownMenuItem(text = { Text(stringResource(R.string.music_info_refresh)) }, onClick = onRefresh)
    DropdownMenuItem(text = { Text(stringResource(R.string.music_info_delete)) }, onClick = onDelete)
}

@Composable
fun MusicInfoPageMenu(onPrepare: () -> Unit, onRefresh: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DapOverflowButton(onClick = { expanded = true })
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            MusicInfoMenuItems(onPrepare = { expanded = false; onPrepare() },
                onRefresh = { expanded = false; onRefresh() }, onDelete = { expanded = false; onDelete() })
        }
    }
}
