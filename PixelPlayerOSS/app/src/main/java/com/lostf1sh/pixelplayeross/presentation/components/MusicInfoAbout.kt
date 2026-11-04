package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.musicinfo.ArtistPhoto
import com.lostf1sh.pixelplayeross.data.musicinfo.Description
import com.lostf1sh.pixelplayeross.ui.ds.*

@Immutable
data class MusicInfoAboutState(val description: Description? = null, val photo: ArtistPhoto? = null) {
    val isEmpty: Boolean get() = description == null && photo == null
}

/** Offline text and selectable attribution. No browser or network is needed to read credits. */
@Composable
fun MusicInfoAbout(state: MusicInfoAboutState, modifier: Modifier = Modifier, mountInputBridge: Boolean = true) {
    if (state.isEmpty) return
    var expanded by rememberSaveable(state.description?.pageUrl, state.description?.text) { mutableStateOf(false) }
    var credits by rememberSaveable { mutableStateOf(false) }
    val toggle = stringResource(if (expanded) R.string.music_info_read_less else R.string.music_info_read_more)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.music_info_about_title).uppercase(), Modifier.weight(1f), style = DapType.SectionHeader, color = Dap.secondaryText)
            IconButton({ credits = true }) {
                Icon(Icons.Outlined.Info, stringResource(R.string.cd_show_metadata_info), Modifier.size(20.dp), tint = Dap.secondaryText)
            }
        }
        state.description?.let { description ->
            Text(description.text, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = toggle }
                .clickable(role = Role.Button, onClickLabel = toggle) { expanded = !expanded },
                style = DapType.Body.copy(fontSize = 15.sp, lineHeight = 23.sp), color = Dap.primaryText,
                maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val source = if (description.pageUrl.contains("musicbrainz.org")) stringResource(R.string.music_info_source_musicbrainz)
                    else stringResource(R.string.music_info_source_line, "CC BY-SA 4.0")
                Text(source, Modifier.weight(1f), style = DapType.Note.copy(lineHeight = 17.sp), color = Dap.secondaryText)
                InfoTextAction(toggle, { expanded = !expanded })
            }
        }
    }
    if (credits) MusicInfoCredits(state, { credits = false }, mountInputBridge)
}

@Composable
internal fun MusicInfoCredits(state: MusicInfoAboutState, onClose: () -> Unit, mountInputBridge: Boolean = true) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } }, contentAlignment = Alignment.TopCenter) {
        Surface(Modifier.padding(top = 150.dp.coerceAtMost(maxHeight / 4)).padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth().pointerInput(Unit) { detectTapGestures {} }, shape = RoundedCornerShape(24.dp), color = Dap.surfaceRaised) {
            Column(Modifier.padding(start = 24.dp, end = 20.dp, top = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (mountInputBridge) {
                    ModalInputBridge()
                }
                Text(stringResource(R.string.credits_title), Modifier.semantics { heading() }, style = DapType.Heading.copy(lineHeight = 28.sp), color = Dap.primaryText)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    state.description?.let { description ->
                        CreditGroup(stringResource(R.string.credits_group_text)) {
                            val brainz = description.pageUrl.contains("musicbrainz.org")
                            Text(if(brainz) "MusicBrainz" else stringResource(R.string.music_info_from_wikipedia), style = DapType.Button, color = Dap.primaryText)
                            CreditUrl(description.pageUrl)
                            Text(if(brainz) "CC0" else stringResource(R.string.music_info_text_license), style = DapType.Body, color = Dap.secondaryText)
                        }
                    }
                    state.photo?.let { photo ->
                        CreditGroup(stringResource(R.string.credits_group_photo)) {
                            val credit = when {
                                !photo.author.isNullOrBlank() && !photo.license.isNullOrBlank() -> stringResource(R.string.music_info_photo_credit, photo.author, photo.license)
                                !photo.license.isNullOrBlank() -> stringResource(R.string.music_info_photo_credit_no_author, photo.license)
                                else -> stringResource(R.string.music_info_photo_credit_plain)
                            }
                            Text(credit, style = DapType.Button, color = Dap.primaryText)
                            Text(stringResource(R.string.music_info_from_source, photo.provider), style = DapType.Body, color = Dap.secondaryText)
                            photo.pageUrl?.let { CreditUrl(it) }
                            photo.licenseUrl?.let { CreditUrl(it) }
                        }
                    }
                    CreditGroup(stringResource(R.string.credits_group_id)) {
                        Text(stringResource(R.string.credits_musicbrainz_id), style = DapType.Body, color = Dap.secondaryText)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { InfoTextAction(stringResource(R.string.offline_download_close), onClose) }
            }
        }
        }
    }
}

@Composable private fun CreditGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), Modifier.semantics { heading() }, style = DapType.SectionHeader, color = Dap.secondaryText)
        content()
    }
}
@Composable private fun CreditUrl(url: String) {
    SelectionContainer { Text(url, style = DapType.ValueSmall.copy(lineHeight = 17.sp), color = Dap.secondaryText) }
}
