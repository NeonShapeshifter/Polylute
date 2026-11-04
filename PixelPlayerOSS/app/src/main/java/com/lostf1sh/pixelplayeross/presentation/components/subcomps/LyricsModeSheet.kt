package com.lostf1sh.pixelplayeross.presentation.components.subcomps

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.preferences.LyricsMode
import com.lostf1sh.pixelplayeross.presentation.components.GlassModalBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent

/** The name of a lyrics mode (Appearance and the sheet say the same words). */
@StringRes
internal fun lyricsModeLabel(mode: LyricsMode): Int = when (mode) {
    LyricsMode.OVER_COVER -> R.string.lyrics_mode_over_cover
    LyricsMode.FULL_PAGE -> R.string.lyrics_mode_full_page
    LyricsMode.OFF -> R.string.lyrics_mode_off
}

@StringRes
private fun lyricsModeHint(mode: LyricsMode): Int = when (mode) {
    LyricsMode.OVER_COVER -> R.string.lyrics_mode_over_cover_hint
    LyricsMode.FULL_PAGE -> R.string.lyrics_mode_full_page_hint
    LyricsMode.OFF -> R.string.lyrics_mode_off_hint
}

/**
 * B56 phase 3.4 (#24): the small sheet behind a long press on the player's lyrics button. The three ways the lyrics can show, one choice, taken at once
 * ([onSelect] closes the sheet). Built from `ui/ds` rows: a name, what it does, a check on the one in use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsModeSheet(
    mode: LyricsMode,
    onSelect: (LyricsMode) -> Unit,
    onDismissRequest: () -> Unit,
    mountInputBridge: Boolean = true,
) {
    GlassModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
        LyricsModeChoices(mode, onSelect)
    }
}

/** The sheet's body, without the window: what a render draws. */
@Composable
internal fun LyricsModeChoices(mode: LyricsMode, onSelect: (LyricsMode) -> Unit) {
    val navigationBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DapSpacing.Gutter)
            .padding(bottom = navigationBarsPadding + DapSpacing.SectionGap),
    ) {
        Text(text = stringResource(R.string.lyrics_mode_title), style = DapType.Heading, color = Dap.primaryText)
        Spacer(Modifier.height(DapSpacing.Md))
        Column(Modifier.selectableGroup()) {
            for (choice in LyricsMode.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .selectable(selected = choice == mode, role = Role.RadioButton, onClick = { onSelect(choice) })
                        .padding(vertical = DapSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DapSpacing.Lg),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(text = stringResource(lyricsModeLabel(choice)), style = DapType.SettingLabel, color = Dap.primaryText)
                        Text(text = stringResource(lyricsModeHint(choice)), style = DapType.Note, color = Dap.secondaryText)
                    }
                    if (choice == mode) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = LocalDapAccent.current, modifier = Modifier.size(24.dp))
                    } else {
                        Spacer(Modifier.width(24.dp))
                    }
                }
            }
        }
    }
}
