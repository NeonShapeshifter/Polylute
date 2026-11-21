package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.SortDirection
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.presentation.model.methodLabelRes
import com.lostf1sh.pixelplayeross.ui.ds.*
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySortBottomSheet(
    title: String,
    options: ImmutableList<SortOption>,
    selectedOption: SortOption?,
    onDismiss: () -> Unit,
    onOptionSelected: (SortOption) -> Unit,
    onDirectionToggle: (SortOption) -> Unit = onOptionSelected,
    showViewToggle: Boolean = false,
    viewSectionTitle: String = "View",
    viewToggleLabel: String = "Playlist View",
    viewToggleChecked: Boolean = false,
    onViewToggleChange: (Boolean) -> Unit = {},
    viewToggleContent: (@Composable () -> Unit)? = null,
    sourceToggleContent: (@Composable () -> Unit)? = null,
    extraContent: (@Composable () -> Unit)? = null,
    /** True in the app (the sheet is a window of its own); the JVM render tests have no Hilt application and turn it off (B32-Q3). */
    mountInputBridge: Boolean = true,
) {
    val sheetState = rememberModalSheetState(skipPartiallyExpanded = true)
    GlassModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (mountInputBridge) ModalInputBridge()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = DapSpacing.Gutter)) {
            Text(title, style = DapType.Heading, color = Dap.primaryText)
            Spacer(Modifier.height(DapSpacing.Sm))
            LibrarySortChoices(options, selectedOption, onOptionSelected, onDirectionToggle)
            if (showViewToggle || viewToggleContent != null) {
                Spacer(Modifier.height(DapSpacing.Md))
                Text(viewSectionTitle, style = DapType.Heading, color = Dap.primaryText)
                if (viewToggleContent != null) viewToggleContent()
                else DapSwitchRow(label = viewToggleLabel, checked = viewToggleChecked, onCheckedChange = onViewToggleChange)
            }
            if (sourceToggleContent != null) {
                Text(stringResource(R.string.presentation_batch_g_sort_source), style = DapType.Heading, color = Dap.primaryText)
                sourceToggleContent()
            }
            extraContent?.invoke()
            Spacer(Modifier.height(DapSpacing.Lg))
        }
    }
}

/** Same choice-list rows as ThemeSelectorItem's sheet in Audio / Appearance. */
@Composable
internal fun LibrarySortChoices(options: List<SortOption>, selectedOption: SortOption?, onOptionSelected: (SortOption) -> Unit, onDirectionToggle: (SortOption) -> Unit) {
    @Suppress("UNCHECKED_CAST")
    val safe = (options as List<SortOption?>).filterNotNull()
    val selection = safe.firstOrNull { it.storageKey == selectedOption?.storageKey } ?: selectedOption ?: safe.firstOrNull()
    val methods = safe.map { it.methodOption() }.distinctBy { it.methodKey }
    // B63 (reviewer on the HONOR 11-16): the methods first, as plain choices (no chevron: nothing opens), then the direction as
    // a two-way picker with fixed words. The old switch named the *current* direction while "on" meant descending, so "Title ↑"
    // showed as "Ascending: off".
    Column(Modifier.selectableGroup()) {
        methods.forEach { option ->
            val chosen = selection?.methodKey == option.methodKey
            DapNavRow(
                label = stringResource(option.methodLabelRes()),
                onClick = { onOptionSelected(option.resolveForDirection(selection?.direction)) },
                modifier = Modifier.semantics { selected = chosen; role = Role.RadioButton },
                trailing = if (chosen) { { DapState(kind = DapStateKind.Selected, label = stringResource(R.string.presentation_batch_f_cd_selected)) } } else { {} },
            )
        }
    }
    if (selection != null && methods.any { it.canFlipDirection }) {
        Spacer(Modifier.height(DapSpacing.Md))
        val orderLabel = stringResource(R.string.presentation_batch_g_sort_order)
        val direction = selection.direction
        if (direction == null) {
            DapNavRow(label = orderLabel, value = stringResource(R.string.presentation_batch_g_sort_original_order), valueStyle = DapType.CaptionMedium, trailing = {}, divider = false)
        } else {
            val ascending = stringResource(R.string.presentation_batch_g_sort_ascending)
            val descending = stringResource(R.string.presentation_batch_g_sort_descending)
            DapSegmentedRow(
                label = orderLabel,
                options = listOf(SortDirection.Ascending, SortDirection.Descending),
                selected = direction,
                onSelect = { if (it != direction) onDirectionToggle(selection.flipDirection()) },
                optionLabel = { if (it == SortDirection.Ascending) ascending else descending },
                enabled = selection.canFlipDirection,
                divider = false,
            )
        }
    }
}

/** Kept for the existing custom view-toggle callers; its appearance belongs to the DS. */
@Composable
internal fun LibrarySheetToggleCard(label: String, checked: Boolean, boxBackgroundColor: Color, boxCornerRadius: Dp, onCheckedChange: (Boolean) -> Unit) {
    DapSwitchRow(label = label, checked = checked, onCheckedChange = onCheckedChange)
}
