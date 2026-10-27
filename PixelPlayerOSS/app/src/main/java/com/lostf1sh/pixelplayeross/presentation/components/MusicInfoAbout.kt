package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.musicinfo.ArtistPhoto
import com.lostf1sh.pixelplayeross.data.musicinfo.Description
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType

@Immutable
data class MusicInfoAboutState(val description: Description? = null, val photo: ArtistPhoto? = null) {
    val isEmpty: Boolean get() = description == null && photo == null
}

/** Compact, expandable offline text. Credits open inside the app; this surface never launches a browser. */
@Composable
fun MusicInfoAbout(state: MusicInfoAboutState, modifier: Modifier = Modifier, mountInputBridge: Boolean = true) {
    if (state.isEmpty) return
    var expanded by rememberSaveable(state.description?.pageUrl, state.description?.text) { mutableStateOf(false) }
    var credits by rememberSaveable { mutableStateOf(false) }
    val toggleLabel = stringResource(if (expanded) R.string.music_info_collapse else R.string.music_info_expand)
    Column(modifier.fillMaxWidth().padding(vertical = DapSpacing.Md)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.music_info_about_title), style = DapType.SectionHeader, color = Dap.tertiaryText,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { credits = true }) {
                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_show_metadata_info), tint = Dap.secondaryText)
            }
        }
        state.description?.let { description ->
            Text(
                text = description.text,
                style = DapType.Body,
                color = Dap.primaryText,
                maxLines = if (expanded) Int.MAX_VALUE else ABOUT_COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { stateDescription = toggleLabel }
                    .clickable(role = Role.Button, onClickLabel = toggleLabel) { expanded = !expanded }
                    .padding(top = DapSpacing.Xs),
            )
        }
    }
    if (credits) {
    AlertDialog(
        onDismissRequest = { credits = false },
        title = { Text(stringResource(R.string.credits_title)) },
        text = {
            if (mountInputBridge) {
                ModalInputBridge()
            }
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                state.description?.let { description ->
                    Text(if (description.pageUrl.contains("musicbrainz.org")) "MusicBrainz" else stringResource(R.string.music_info_from_wikipedia))
                    Text(description.pageUrl, style = DapType.Caption)
                    if (!description.pageUrl.contains("musicbrainz.org")) Text(stringResource(R.string.music_info_text_license), style = DapType.Caption)
                }
                state.photo?.let { photo ->
                    val credit = when {
                        !photo.author.isNullOrBlank() && !photo.license.isNullOrBlank() -> stringResource(R.string.music_info_photo_credit, photo.author, photo.license)
                        !photo.license.isNullOrBlank() -> stringResource(R.string.music_info_photo_credit_no_author, photo.license)
                        else -> if (photo.provider == "Wikimedia Commons") stringResource(R.string.music_info_photo_credit_plain) else photo.provider
                    }
                    Text(credit, style = DapType.Caption, modifier = Modifier.padding(top = DapSpacing.Sm))
                    photo.pageUrl?.let { Text(it, style = DapType.Caption) }
                    photo.licenseUrl?.let { Text(it, style = DapType.Caption) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { credits = false }) { Text(stringResource(R.string.offline_download_close)) } },
    )
    }
}

private const val ABOUT_COLLAPSED_LINES = 4
