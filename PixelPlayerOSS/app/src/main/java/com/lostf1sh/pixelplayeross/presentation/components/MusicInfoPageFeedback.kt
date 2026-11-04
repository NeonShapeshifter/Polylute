package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.viewmodel.*
import com.lostf1sh.pixelplayeross.ui.ds.*
import kotlinx.coroutines.delay

@Composable
fun MusicInfoPageFeedback(viewModel: MusicInfoActionsViewModel, onChanged: () -> Unit) {
    val result by viewModel.result.collectAsStateWithLifecycle()
    result?.let { value ->
        LaunchedEffect(value) {
            if(value.status == MusicInfoActionStatus.SUCCESS) {
                delay(value.undo?.let { (it.untilAt - System.currentTimeMillis()).coerceAtLeast(0) } ?: 3500)
                viewModel.clearResult()
            }
        }
        Popup(alignment = Alignment.BottomCenter, properties = PopupProperties(focusable = false)) {
            MusicInfoPageNotice(value, viewModel::retry,
                { value.undo?.let { viewModel.undo(it.token, value.name, onChanged) } },
                Modifier.navigationBarsPadding().padding(bottom = LocalDapPlayerShelfHeight.current))
        }
    }
}

@Composable
internal fun MusicInfoPageNotice(result: MusicInfoActionResult, retry: () -> Unit, undo: () -> Unit, modifier: Modifier = Modifier) {
    val text = when(result.status) {
        MusicInfoActionStatus.BUSY -> stringResource(if(result.action == MusicInfoPageAction.REFRESH) R.string.music_info_refreshing else R.string.info_deleting_all)
        MusicInfoActionStatus.FAILURE -> stringResource(if(result.action == MusicInfoPageAction.REFRESH) R.string.music_info_refresh_failed else if(result.action == MusicInfoPageAction.UNDO) R.string.info_error_undo else R.string.music_info_delete_failed)
        MusicInfoActionStatus.SUCCESS -> when(result.action) {
            MusicInfoPageAction.REFRESH -> stringResource(R.string.music_info_refreshed)
            MusicInfoPageAction.DELETE -> stringResource(R.string.music_info_deleted_page, result.name)
            MusicInfoPageAction.UNDO -> stringResource(R.string.info_undone)
        }
    }
    InfoSnack(text, modifier, if(result.status == MusicInfoActionStatus.FAILURE) "error" else if(result.status == MusicInfoActionStatus.BUSY) "loading" else null) {
        if(result.status == MusicInfoActionStatus.FAILURE) InfoTextAction(stringResource(R.string.info_retry), retry)
        else if(result.undo != null) InfoTextAction(stringResource(R.string.info_undo), undo)
    }
}

@Composable
internal fun InfoSnack(text: String, modifier: Modifier = Modifier, icon: String? = null, action: @Composable () -> Unit = {}) {
    Surface(modifier.padding(start = 12.dp, end = 12.dp, bottom = 20.dp).fillMaxWidth(), color = Dap.surfaceRaised, shape = RoundedCornerShape(10.dp), shadowElevation = 8.dp) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).heightIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if(icon != null) InfoGlyph(icon, modifier = Modifier.size(20.dp), tint = if(icon == "error") Dap.error else LocalDapAccent.current)
            Text(text, Modifier.weight(1f), style = DapType.Body, color = Dap.primaryText)
            action()
        }
    }
}
