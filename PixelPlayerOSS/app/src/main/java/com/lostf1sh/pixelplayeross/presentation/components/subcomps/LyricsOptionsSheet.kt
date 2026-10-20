package com.lostf1sh.pixelplayeross.presentation.components.subcomps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapDestructiveAction
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSectionHeader
import com.lostf1sh.pixelplayeross.ui.ds.DapSegmentedRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapSwitchRow
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import java.util.Locale

/**
 * The lyrics options (B22, `LyricsMenu.dc.html`): the one place that changes how the lyrics are read, in three groups.
 *
 *  - **This song**: the sync offset, in 0.1 s steps (stored per song);
 *  - **Reading**: alignment, translation, romanisation, keep the screen on;
 *  - **File**: search lyrics, import a `.lrc`, save to a file.
 *
 * Every control calls what it always called. What the design leaves out is not here: the synced/static switch, the
 * temporary immersive-off, shuffle and repeat (the immersive mode itself is set under Settings, Lyrics). One thing the
 * design does not draw is kept: taking this song's imported lyrics away.
 *
 * [mountInputBridge] is true in the app (a sheet is a window of its own and the volume keys must still reach the Safety
 * authority); a JVM render has no Hilt component to take it from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsOptionsSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    /** The sync row is only for synced lyrics; null hides it. */
    syncOffsetMs: Int?,
    onSyncOffsetChange: (Int) -> Unit,
    lyricsAlignment: String,
    onLyricsAlignmentChange: (String) -> Unit,
    hasTranslatedLyrics: Boolean,
    showTranslation: Boolean,
    onShowTranslationChange: (Boolean) -> Unit,
    hasRomanizedLyrics: Boolean,
    showRomanization: Boolean,
    onShowRomanizationChange: (Boolean) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    /** Online search is offered only when online lyrics are on: the repository would refuse it otherwise. */
    canSearch: Boolean,
    onSearchLyrics: () -> Unit,
    onImportLyrics: () -> Unit,
    /** Saving needs lyrics to save. */
    canSave: Boolean,
    onSaveLyrics: () -> Unit,
    /**
     * Takes the imported lyrics of **this song** away (after a confirmation). The design's File group has three actions;
     * this one is kept because without it a bad import could not be undone.
     */
    canReset: Boolean,
    onResetLyrics: () -> Unit,
    mountInputBridge: Boolean = true,
) {
    var showResetDialog by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Dap.surface,
        contentColor = Dap.primaryText,
        shape = RoundedCornerShape(topStart = DapRadius.SheetTop, topEnd = DapRadius.SheetTop),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = DapSpacing.Sm, bottom = DapSpacing.Md)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Dap.border)
            )
        },
    ) {
        if (mountInputBridge) {
            ModalInputBridge()
        }
        val navigationBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DapSpacing.Gutter)
                .padding(bottom = navigationBarsPadding + DapSpacing.SectionGap),
        ) {
            Text(
                text = stringResource(R.string.lyrics_menu_title),
                style = DapType.Heading,
                color = Dap.primaryText,
            )

            if (syncOffsetMs != null) {
                SheetSection(stringResource(R.string.lyrics_menu_section_song))
                LyricsSyncRow(offsetMs = syncOffsetMs, onOffsetChange = onSyncOffsetChange)
            }

            SheetSection(stringResource(R.string.lyrics_menu_section_reading))
            val left = stringResource(R.string.lyrics_menu_align_left)
            val center = stringResource(R.string.lyrics_menu_align_center)
            DapSegmentedRow(
                label = stringResource(R.string.lyrics_menu_alignment),
                options = listOf("left", "center"),
                selected = lyricsAlignment,
                onSelect = onLyricsAlignmentChange,
                optionLabel = { if (it == "left") left else center },
                // The sheet is the surface; the track sits one step below it, as in the design.
                containerColor = Dap.background,
            )
            DapSwitchRow(
                label = stringResource(R.string.lyrics_menu_translation),
                checked = showTranslation && hasTranslatedLyrics,
                onCheckedChange = onShowTranslationChange,
                enabled = hasTranslatedLyrics,
            )
            DapSwitchRow(
                label = stringResource(R.string.lyrics_menu_romanization),
                checked = showRomanization && hasRomanizedLyrics,
                onCheckedChange = onShowRomanizationChange,
                enabled = hasRomanizedLyrics,
            )
            DapSwitchRow(
                label = stringResource(R.string.lyrics_menu_keep_screen_on),
                checked = keepScreenOn,
                onCheckedChange = onKeepScreenOnChange,
            )

            SheetSection(stringResource(R.string.lyrics_menu_section_file))
            if (canSearch) {
                SheetAction(Icons.Outlined.Search, stringResource(R.string.lyrics_menu_search), onSearchLyrics)
            }
            SheetAction(Icons.Outlined.UploadFile, stringResource(R.string.lyrics_menu_import), onImportLyrics)
            if (canSave) {
                SheetAction(Icons.Outlined.Save, stringResource(R.string.lyrics_menu_save), onSaveLyrics)
            }
            if (canReset) {
                SheetAction(Icons.Outlined.RestartAlt, stringResource(R.string.reset_imported_lyrics)) { showResetDialog = true }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = Dap.surface,
            shape = RoundedCornerShape(DapRadius.Surface),
            title = {
                // An alert is a window of its own: the volume keys must still reach the Safety authority.
                if (mountInputBridge) {
                    ModalInputBridge()
                }
                Text(stringResource(R.string.lyrics_more_dialog_reset_title), style = DapType.Heading, color = Dap.primaryText)
            },
            text = {
                Text(stringResource(R.string.lyrics_more_dialog_reset_message), style = DapType.Body, color = Dap.secondaryText)
            },
            confirmButton = {
                DapDestructiveAction(
                    label = stringResource(R.string.action_reset),
                    onClick = {
                        showResetDialog = false
                        onDismissRequest()
                        onResetLyrics()
                    },
                )
            },
            dismissButton = {
                DapTextAction(label = stringResource(R.string.cancel), onClick = { showResetDialog = false })
            },
        )
    }
}

@Composable
private fun SheetSection(title: String) {
    Spacer(Modifier.height(DapSpacing.Lg + DapSpacing.Xs))
    DapSectionHeader(title)
}

/** A row that is an action: a glyph and its sentence, 52 dp tall. */
@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DapSpacing.Lg),
    ) {
        Icon(icon, contentDescription = null, tint = Dap.primaryText, modifier = Modifier.size(24.dp))
        Text(
            text = label,
            style = DapType.SettingLabel,
            color = Dap.primaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The step of the sync offset: 0.1 s, as the design draws it. */
internal const val SYNC_STEP_MS = 100

/** "+0,0 s", "-0,3 s": the offset, in the user's decimal. */
internal fun formatSyncOffset(offsetMs: Int): String = String.format(Locale.getDefault(), "%+.1f s", offsetMs / 1000f)

/**
 * "Sincronía": the label and what it does, then `[−] +0,0 s [+]` at the end. Tapping the value puts it back to 0.
 *
 * The three controls are square 40 dp targets on the sheet's own surface; the value is mono, because it is a measurement
 * the listener is setting.
 */
@Composable
private fun LyricsSyncRow(offsetMs: Int, onOffsetChange: (Int) -> Unit) {
    val earlier = stringResource(R.string.lyrics_menu_sync_earlier)
    val later = stringResource(R.string.lyrics_menu_sync_later)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(vertical = DapSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.lyrics_menu_sync),
                    style = DapType.SettingLabel,
                    color = Dap.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.lyrics_menu_sync_sub),
                    style = DapType.Caption,
                    color = Dap.secondaryText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(DapSpacing.Sm))
            SquareButton(Icons.Rounded.Remove, earlier) { onOffsetChange(offsetMs - SYNC_STEP_MS) }
            Text(
                text = formatSyncOffset(offsetMs),
                style = DapType.Value,
                color = Dap.primaryText,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .width(60.dp)
                    .clickable(enabled = offsetMs != 0) { onOffsetChange(0) },
            )
            SquareButton(Icons.Rounded.Add, later) { onOffsetChange(offsetMs + SYNC_STEP_MS) }
        }
        DapDivider()
    }
}

@Composable
private fun SquareButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(DapRadius.Surface))
            .background(Dap.background)
            .border(1.dp, Dap.border, RoundedCornerShape(DapRadius.Surface))
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Dap.primaryText, modifier = Modifier.size(20.dp))
    }
}
