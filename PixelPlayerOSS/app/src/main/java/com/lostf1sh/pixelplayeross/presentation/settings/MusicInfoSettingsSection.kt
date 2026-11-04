package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.*
import com.lostf1sh.pixelplayeross.ui.ds.*

/** Shared by the Library page and host renders; automatic preferences never gate the manual entry. */
@Composable
internal fun MusicInfoSettingsSection(state: LibrarySettingsState, actions: LibrarySettingsActions, onOpen: () -> Unit, mountInputBridge: Boolean = true) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deletedBytes by remember { mutableStateOf<Long?>(null) }
    var failed by remember { mutableStateOf(false) }
    fun delete() {
        confirm = false
        deleting = true
        failed = false
        actions.onDeleteMusicInfoResult { result ->
            deleting = false
            result.onSuccess { deletedBytes = it }.onFailure { failed = true }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setcat_music_info).uppercase(), Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp), style = DapType.SectionHeader, color = LocalDapAccent.current)
        InfoSettingsRow(stringResource(R.string.offline_download_all), stringResource(R.string.info_entry_settings_sub), "download", onOpen, trailing = { InfoGlyph("right", modifier = Modifier.size(20.dp), tint = Dap.secondaryText) })
        Text(stringResource(R.string.setcat_music_info_note), Modifier.padding(start = 52.dp, end = 16.dp, top = 6.dp, bottom = 14.dp), style = DapType.Note.copy(lineHeight = 17.sp), color = Dap.secondaryText)
        Text(stringResource(R.string.setcat_music_info_auto).uppercase(), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), style = DapType.SectionHeader, color = Dap.secondaryText)
        InfoSettingsSwitch(stringResource(R.string.setcat_music_info_covers_title), stringResource(R.string.setcat_music_info_covers_subtitle), "COVER", state.musicInfoCoversEnabled, actions::onMusicInfoCoversChange)
        InfoSettingsSwitch(stringResource(R.string.setcat_music_info_photos_title), stringResource(R.string.setcat_music_info_photos_subtitle), "PHOTO", state.musicInfoArtistPhotosEnabled, actions::onMusicInfoArtistPhotosChange)
        InfoSettingsSwitch(stringResource(R.string.setcat_music_info_descriptions_title), stringResource(R.string.setcat_music_info_descriptions_subtitle), "BIO", state.musicInfoDescriptionsEnabled, actions::onMusicInfoDescriptionsChange)
        Text(stringResource(R.string.setcat_lyrics_pointer), Modifier.padding(start = 52.dp, end = 16.dp, top = 2.dp, bottom = 10.dp), style = DapType.Note.copy(lineHeight = 17.sp), color = Dap.secondaryText)
        InfoSettingsRow(stringResource(R.string.setcat_music_info_delete_title), if(state.musicInfoUsedBytes == 0L) stringResource(R.string.setcat_music_info_nothing_saved) else stringResource(R.string.setcat_music_info_delete_subtitle, infoSize(state.musicInfoUsedBytes)), "delete", { confirm = true }, enabled = state.musicInfoUsedBytes > 0 && !deleting)
        if (deleting) Text(stringResource(R.string.info_deleting_all), Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = DapType.Body, color = Dap.secondaryText)
        if (failed) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.music_info_delete_failed), Modifier.weight(1f), style = DapType.Body, color = Dap.error)
            InfoTextAction(stringResource(R.string.info_retry), ::delete)
        }
        deletedBytes?.let { bytes ->
            LaunchedEffect(bytes) { kotlinx.coroutines.delay(3500); deletedBytes = null }
            Popup(alignment = Alignment.BottomCenter, properties = PopupProperties(focusable = false)) {
                InfoSnack(stringResource(R.string.setcat_music_info_deleted, infoSize(bytes)), Modifier.navigationBarsPadding())
            }
        }
    }
    if (confirm) MusicInfoDeleteConfirmation(state.musicInfoUsedBytes, { confirm = false }, ::delete, mountInputBridge)
}

@Composable
private fun InfoSettingsSwitch(title: String, note: String, icon: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    InfoSettingsRow(title, note, icon, { onChecked(!checked) }, trailing = {
        Switch(checked, onChecked, Modifier.padding(end = 4.dp).semantics { contentDescription = title }, colors = SwitchDefaults.colors(checkedTrackColor = LocalDapAccent.current, checkedThumbColor = Dap.onFill, uncheckedTrackColor = Dap.surfaceRaised, uncheckedThumbColor = Dap.secondaryText, uncheckedBorderColor = Dap.secondaryText))
    })
}

@Composable
private fun InfoSettingsRow(title: String, note: String, icon: String, click: () -> Unit, enabled: Boolean = true, trailing: @Composable (() -> Unit)? = null) {
    HorizontalDivider(color = Dap.divider)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, onClick = click).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        InfoGlyph(icon, modifier = Modifier.size(22.dp), tint = Dap.secondaryText)
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = DapType.SettingLabel, color = if(enabled) Dap.primaryText else Dap.secondaryText)
            Text(note, style = DapType.Note.copy(lineHeight = 17.sp), color = Dap.secondaryText)
        }
        trailing?.invoke()
    }
}

@Composable
internal fun MusicInfoDeleteConfirmation(bytes: Long, dismiss: () -> Unit, delete: () -> Unit, mountInputBridge: Boolean = true) {
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { dismiss() } }, contentAlignment = Alignment.TopCenter) {
        Surface(Modifier.padding(top = 190.dp.coerceAtMost(maxHeight / 4)).padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth().pointerInput(Unit) { detectTapGestures {} }, color = Dap.surfaceRaised, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 20.dp, top = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (mountInputBridge) {
                    ModalInputBridge()
                }
                Text(stringResource(R.string.setcat_delete_confirm_title), Modifier.semantics { heading() }, style = DapType.Heading.copy(fontSize = 20.sp), color = Dap.primaryText)
                listOf("delete" to stringResource(R.string.setcat_delete_confirm_what, infoSize(bytes)), "folder" to stringResource(R.string.setcat_delete_confirm_local), "blocked" to stringResource(R.string.setcat_delete_confirm_lasting)).forEach { (icon,text) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InfoGlyph(icon, modifier = Modifier.padding(top = 1.dp).size(18.dp), tint = Dap.secondaryText)
                        Text(text, style = DapType.Body, color = Dap.secondaryText)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    InfoTextAction(stringResource(R.string.common_cancel), dismiss)
                    TextButton(delete, Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = Dap.error)) { Text(stringResource(R.string.common_delete), style = DapType.Button) }
                }
            }
        }
    }
    }
}
