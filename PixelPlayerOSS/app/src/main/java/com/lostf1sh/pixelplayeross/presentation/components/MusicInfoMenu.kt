package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.DapMenu
import com.lostf1sh.pixelplayeross.ui.ds.DapMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.ui.ds.DapOverflowButton

@Composable
fun MusicInfoMenuItems(onPrepare: () -> Unit, onRefresh: () -> Unit, onDelete: () -> Unit) {
    DapMenuItem(text = stringResource(R.string.music_info_prepare), leadingIcon = { InfoGlyph("download", modifier = Modifier.size(20.dp), tint = Dap.secondaryText) }, onClick = onPrepare)
    DapMenuItem(text = stringResource(R.string.music_info_refresh), leadingIcon = { InfoGlyph("retry", modifier = Modifier.size(20.dp), tint = Dap.secondaryText) }, onClick = onRefresh)
    DapMenuItem(text = stringResource(R.string.music_info_delete), leadingIcon = { InfoGlyph("delete", modifier = Modifier.size(20.dp), tint = Dap.secondaryText) }, onClick = onDelete)
}

@Composable
fun MusicInfoPageMenu(onPrepare: () -> Unit, onRefresh: () -> Unit, onDelete: () -> Unit, onEditInfo: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DapOverflowButton(onClick = { expanded = true })
        DapMenu(expanded, onDismissRequest = { expanded = false }) {
            // B57 1.3: the album page's own entry, first: edit the whole album's tags (the three below are about downloaded info).
            if (onEditInfo != null) {
                DapMenuItem(text = stringResource(R.string.edit_album_menu_item), onClick = { expanded = false; onEditInfo() })
            }
            MusicInfoMenuItems(onPrepare = { expanded = false; onPrepare() },
                onRefresh = { expanded = false; onRefresh() }, onDelete = { expanded = false; onDelete() })
        }
    }
}

@Composable
internal fun MusicInfoSelectionEntry(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        InfoGlyph("download", modifier = Modifier.size(20.dp), tint = Dap.secondaryText)
        Text(stringResource(R.string.offline_download_title), style = DapType.RowSmall, color = Dap.primaryText)
    }
}
