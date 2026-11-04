package com.lostf1sh.pixelplayeross.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.lyrics.LrcSyncState
import com.lostf1sh.pixelplayeross.data.lyrics.SyncEditLine
import com.lostf1sh.pixelplayeross.data.lyrics.SyncLeave
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapButton
import com.lostf1sh.pixelplayeross.ui.ds.DapButtonVariant
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconButton
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent

/** The test tags of the editor's controls. */
internal object LyricsSyncTags {
    const val Stamp = "lyrics-sync-stamp"
    const val Undo = "lyrics-sync-undo"
    const val Close = "lyrics-sync-close"
    const val Save = "lyrics-sync-save"
    const val Preview = "lyrics-sync-preview"
    const val Discard = "lyrics-sync-discard"
    const val KeepEditing = "lyrics-sync-keep-editing"
    const val NudgeEarlier = "lyrics-sync-nudge-earlier"
    const val NudgeLater = "lyrics-sync-nudge-later"
    fun line(index: Int) = "lyrics-sync-line-$index"
}

/** How far the seek buttons jump. */
private const val SEEK_STEP_MS = 5_000L

/** "mm:ss.xx", as the file will write it. */
internal fun formatSyncTime(timeMs: Int?): String {
    if (timeMs == null) return "--:--.--"
    val minutes = timeMs / 60_000
    val seconds = (timeMs % 60_000) / 1000
    val hundredths = (timeMs % 1000) / 10
    return "%02d:%02d.%02d".format(minutes, seconds, hundredths)
}

/**
 * B56 phase 3.2 (#31): the lyrics sync editor, a layer of the lyrics page (no window of its own).
 *
 * The song plays through the player's own controls: [onPlayPause] and [onSeekTo] are the lyrics page's, nothing here touches an audio path. The big button stamps the
 * playing position on the line marked next; "Undo last" takes the last stamp back; a selected line can be moved by 0.1 s; "Preview" shows the result in the normal synced
 * view ([previewContent]) while the song plays. Leaving with changes asks first, inside the page. All the rules are [LrcSyncState]'s.
 */
@Composable
internal fun LyricsSyncEditor(
    state: LrcSyncState,
    onStateChange: (LrcSyncState) -> Unit,
    positionProvider: () -> Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    previewContent: @Composable (Lyrics) -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewing by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    fun requestLeave() = when (state.leave()) {
        SyncLeave.LEAVE -> onClose()
        SyncLeave.ASK -> asking = true
    }
    BackHandler(enabled = true) { if (asking) asking = false else requestLeave() }
    // the next line stays in view as the stamps move down the list
    LaunchedEffect(state.cursor, previewing) {
        if (!previewing && state.lines.isNotEmpty()) listState.animateScrollToItem((state.cursor + 1).coerceIn(0, state.lines.size), scrollOffset = -SCROLL_LEAD_PX)
    }

    Column(modifier.fillMaxSize().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = DapSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DapTextAction(label = stringResource(R.string.lyrics_sync_close), onClick = ::requestLeave, modifier = Modifier.tagged(LyricsSyncTags.Close))
            Text(
                text = stringResource(R.string.lyrics_sync_title),
                style = DapType.Heading,
                color = Dap.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = DapSpacing.Sm),
            )
            DapTextAction(
                label = stringResource(R.string.lyrics_sync_save),
                onClick = onSave,
                enabled = state.isComplete && state.isDirty,
                color = LocalDapAccent.current,
                modifier = Modifier.tagged(LyricsSyncTags.Save),
            )
        }
        DapDivider()

        if (asking) {
            Column(Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Md), verticalArrangement = Arrangement.spacedBy(DapSpacing.Sm)) {
                Text(stringResource(R.string.lyrics_sync_discard_title), style = DapType.Row, color = Dap.primaryText)
                Text(stringResource(R.string.lyrics_sync_discard_message), style = DapType.Body, color = Dap.secondaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(DapSpacing.Sm)) {
                    DapButton(
                        label = stringResource(R.string.lyrics_sync_keep_editing),
                        onClick = { asking = false },
                        modifier = Modifier.weight(1f).tagged(LyricsSyncTags.KeepEditing),
                    )
                    DapButton(
                        label = stringResource(R.string.lyrics_sync_discard),
                        onClick = onClose,
                        variant = DapButtonVariant.Destructive,
                        modifier = Modifier.weight(1f).tagged(LyricsSyncTags.Discard),
                    )
                }
            }
            DapDivider()
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val preview = if (previewing) state.toLyrics() else null
            if (preview != null) {
                previewContent(preview)
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item(key = "hint") {
                        Text(
                            text = stringResource(R.string.lyrics_sync_hint),
                            style = DapType.Note,
                            color = Dap.secondaryText,
                            modifier = Modifier.padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Md),
                        )
                    }
                    itemsIndexed(state.lines, key = { index, _ -> "line-$index" }) { index, line ->
                        SyncLineRow(
                            index = index,
                            line = line,
                            isNext = index == state.cursor,
                            isSelected = index == state.selected,
                            onSelect = { onStateChange(state.select(if (state.selected == index) null else index)) },
                            onNudge = { delta -> onStateChange(state.nudge(delta, index)) },
                        )
                    }
                    item(key = "tail") { Spacer(Modifier.height(DapSpacing.Lg)) }
                }
            }
        }

        DapDivider()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter).padding(top = DapSpacing.Md, bottom = DapSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(DapSpacing.Sm),
        ) {
            val remaining = state.lines.size - state.stampedCount
            Text(
                text = if (state.isComplete) stringResource(R.string.lyrics_sync_all_stamped) else pluralStringResource(R.plurals.lyrics_sync_remaining, remaining, remaining),
                style = DapType.Note,
                color = Dap.secondaryText,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                DapTextAction(label = stringResource(R.string.lyrics_sync_undo), onClick = { onStateChange(state.undoLast()) }, enabled = state.stampedCount > 0 && !previewing, modifier = Modifier.tagged(LyricsSyncTags.Undo))
                DapTextAction(label = stringResource(R.string.lyrics_sync_restart), onClick = { onStateChange(state.restart()) }, enabled = state.stampedCount > 0 && !previewing)
                DapTextAction(
                    label = stringResource(if (previewing) R.string.lyrics_sync_edit else R.string.lyrics_sync_preview),
                    onClick = { previewing = !previewing },
                    enabled = state.isComplete,
                    modifier = Modifier.tagged(LyricsSyncTags.Preview),
                )
            }
            DapPrimaryButton(
                label = stringResource(R.string.lyrics_sync_stamp),
                onClick = { onStateChange(state.stamp(positionProvider())) },
                enabled = !previewing && state.cursor in state.lines.indices,
                modifier = Modifier.fillMaxWidth().height(72.dp).tagged(LyricsSyncTags.Stamp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                DapTextAction(label = stringResource(R.string.lyrics_sync_back_5), onClick = { onSeekTo((positionProvider() - SEEK_STEP_MS).coerceAtLeast(0L)) })
                DapIconButton(
                    kind = if (isPlaying) DapIconKind.Pause else DapIconKind.Play,
                    contentDescription = stringResource(if (isPlaying) R.string.lyrics_sync_pause else R.string.lyrics_sync_play),
                    onClick = onPlayPause,
                )
                DapTextAction(label = stringResource(R.string.lyrics_sync_forward_5), onClick = { onSeekTo(positionProvider() + SEEK_STEP_MS) })
            }
        }
    }
}

private const val SCROLL_LEAD_PX = 200

@Composable
private fun SyncLineRow(
    index: Int,
    line: SyncEditLine,
    isNext: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onNudge: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().tagged(LyricsSyncTags.line(index))) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClick = onSelect)
                .padding(horizontal = DapSpacing.Gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md),
        ) {
            // the marker: which line the next stamp goes to
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                if (isNext) DapIcon(DapIconKind.PlaySmall, tint = LocalDapAccent.current, size = 12.dp)
            }
            Text(
                text = formatSyncTime(line.timeMs),
                style = DapType.ValueSmall,
                color = if (line.timeMs == null) Dap.tertiaryText else Dap.secondaryText,
                modifier = Modifier.width(76.dp),
            )
            Text(
                text = line.text,
                style = DapType.Row,
                fontWeight = if (isNext) FontWeight.SemiBold else null,
                color = if (isNext || line.timeMs != null) Dap.primaryText else Dap.secondaryText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (isSelected && line.timeMs != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = DapSpacing.Gutter + 16.dp + DapSpacing.Md, end = DapSpacing.Gutter),
                horizontalArrangement = Arrangement.spacedBy(DapSpacing.Sm),
            ) {
                DapTextAction(label = stringResource(R.string.lyrics_sync_nudge_earlier), onClick = { onNudge(-NUDGE_MS) }, modifier = Modifier.tagged(LyricsSyncTags.NudgeEarlier))
                DapTextAction(label = stringResource(R.string.lyrics_sync_nudge_later), onClick = { onNudge(+NUDGE_MS) }, modifier = Modifier.tagged(LyricsSyncTags.NudgeLater))
            }
        }
    }
}

/** The fine nudge: a tenth of a second. */
internal const val NUDGE_MS = 100

private fun Modifier.tagged(tag: String): Modifier = this.testTag(tag)
