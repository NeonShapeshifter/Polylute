package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.worker.SyncProgress
import com.lostf1sh.pixelplayeross.presentation.utils.LocalAppHapticsConfig
import com.lostf1sh.pixelplayeross.presentation.utils.performAppCompatHapticFeedback
import com.lostf1sh.pixelplayeross.ui.ds.DapActionRow
import com.lostf1sh.pixelplayeross.ui.ds.DapChoiceRow
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapIndeterminateBar
import com.lostf1sh.pixelplayeross.ui.ds.DapNavRow
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapProgressBar
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSheetSurface
import com.lostf1sh.pixelplayeross.ui.ds.DapSliderRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapState
import com.lostf1sh.pixelplayeross.ui.ds.DapStateKind
import com.lostf1sh.pixelplayeross.ui.ds.DapSwitchRow
import com.lostf1sh.pixelplayeross.ui.ds.DapType

/**
 * A settings group: a hairline, a mono section label, then its rows.
 *
 * The header carries no icon. An icon beside every section turned the page into a grid of badges
 * and took the width the settings' own values needed to line up.
 */
@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DapSpacing.SectionGap)
    ) {
        DapGroup(title = title) { content() }
    }
}

/**
 * A settings row: label, optional description, trailing affordance.
 *
 * A row, not a card. The page is the surface; the hairline under the row is the structure. The
 * previous shape — a rounded container, an icon and a 22 sp title per setting — cost about 90 dp
 * per setting and made a five-toggle page scroll.
 */
@Composable
fun SettingsItem(
        title: String,
        subtitle: String,
        leadingIcon: @Composable () -> Unit,
        trailingIcon: (@Composable () -> Unit)? = null,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
) {
    DapNavRow(
        label = title,
        description = subtitle.takeIf { it.isNotBlank() },
        onClick = onClick,
        modifier = modifier,
        leading = leadingIcon,
        trailing = trailingIcon,
    )
}

@Composable
fun SwitchSettingItem(
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        leadingIcon: (@Composable (() -> Unit))? = null,
        enabled: Boolean = true,
        modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val appHapticsConfig = LocalAppHapticsConfig.current

    DapSwitchRow(
        label = title,
        description = subtitle.takeIf { it.isNotBlank() },
        checked = checked,
        enabled = enabled,
        leading = leadingIcon,
        onCheckedChange = { newValue ->
            performAppCompatHapticFeedback(
                view,
                appHapticsConfig,
                HapticFeedbackConstantsCompat.GESTURE_START
            )
            onCheckedChange(newValue)
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSelectorItem(
        label: String,
        description: String,
        options: Map<String, String>,
        selectedKey: String,
        onSelectionChanged: (String) -> Unit,
        leadingIcon: @Composable () -> Unit,
        modifier: Modifier = Modifier
) {
    var showSheet by remember { mutableStateOf(false) }
    val selectedOption = options[selectedKey] ?: selectedKey

    DapChoiceRow(
        label = label,
        description = description.takeIf { it.isNotBlank() },
        selectedLabel = selectedOption,
        onClick = { showSheet = true },
        modifier = modifier,
    )

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = Dap.surface,
            contentColor = Dap.primaryText,
            dragHandle = null,
        ) {
            // This window is its own surface, so the activity's volume-key path does not
            // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
            ModalInputBridge()
            DapSheetSurface(title = label, subtitle = description.takeIf { it.isNotBlank() }) {
                LazyColumn(
                    modifier = Modifier
                        .padding(horizontal = DapSpacing.Gutter)
                        .padding(top = DapSpacing.Sm)
                        .heightIn(max = 400.dp),
                ) {
                    items(options.entries.toList()) { (key, optionLabel) ->
                        val isSelected = key == selectedKey
                        DapNavRow(
                            label = optionLabel,
                            onClick = {
                                onSelectionChanged(key)
                                showSheet = false
                            },
                            trailing = if (isSelected) {
                                {
                                    DapState(
                                        kind = DapStateKind.Selected,
                                        label = stringResource(R.string.presentation_batch_f_cd_selected),
                                    )
                                }
                            } else null,
                        )
                    }
                }
                Spacer(Modifier.height(DapSpacing.Gutter))
            }
        }
    }
}

@Composable
fun ExpressiveSettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        content()
    }
}

/**
 * A slider row.
 *
 * [valueRange] and [steps] are the Material `Slider` contract the call sites already speak — a
 * closed range and the number of stops *between* the two ends. [DapSlider] travels on a 0..1
 * fraction, so this maps between the two rather than changing every caller's units.
 */
@Composable
fun SliderSettingsItem(
        label: String,
        value: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        steps: Int,
        onValueChange: (Float) -> Unit,
        onValueChangeFinished: (() -> Unit)? = null,
        valueText: (Float) -> String,
        modifier: Modifier = Modifier
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it != 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    DapSliderRow(
        label = label,
        value = fraction,
        valueText = valueText(value),
        enabled = true,
        onValueChange = { raw ->
            val scaled = valueRange.start + raw.coerceIn(0f, 1f) * span
            // A stepped slider reports the nearest stop, matching the discrete control it replaces.
            val snapped = if (steps > 0) {
                val stops = steps + 1
                val step = span / stops
                (valueRange.start + (Math.round((scaled - valueRange.start) / step) * step))
                    .coerceIn(valueRange.start, valueRange.endInclusive)
            } else {
                scaled
            }
            onValueChange(snapped)
        },
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
    )
}

/**
 * Library scanning. This is the product's authority for scan behaviour — Storage reports capacity
 * and does not offer a second set of scan buttons.
 *
 * Both actions are rows rather than filled blocks: an incremental scan and a full rescan are two
 * sizes of the same operation, and stacking two 48 dp filled buttons made the smaller one look
 * like the bigger one's equal. Rebuilding the database is not here; it is a recovery action and
 * lives with the other developer/recovery tools.
 */
@Composable
fun RefreshLibraryItem(
        isSyncing: Boolean,
        syncProgress: SyncProgress,
        activeOperationLabel: String? = null,
        onUpdateLibrary: () -> Unit,
        onFullRescan: () -> Unit
) {
    val phaseLabel = if (syncProgress.phase == SyncProgress.SyncPhase.IDLE)
        activeOperationLabel ?: syncPhaseLabel(syncProgress.phase) else syncPhaseLabel(syncProgress.phase)

    Column(modifier = Modifier.fillMaxWidth()) {
        DapNavRow(
            label = stringResource(R.string.setcat_sync_update_library),
            description = stringResource(R.string.setcat_sync_update_library_desc),
            onClick = onUpdateLibrary,
            enabled = !isSyncing,
            trailing = {},
        )
        DapNavRow(
            label = stringResource(R.string.presentation_batch_f_full_rescan),
            description = stringResource(R.string.setcat_sync_full_rescan_desc),
            onClick = onFullRescan,
            enabled = !isSyncing,
            divider = false,
            trailing = {},
        )

        if (isSyncing) {
            Spacer(Modifier.height(DapSpacing.Md))
            if (syncProgress.hasProgress) {
                DapProgressBar(
                    progress = syncProgress.progress,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(DapSpacing.Sm))
                // The phase is a sentence and the counts are a reading, so they are two texts in two
                // faces. This used to be one string in ValueSmall, which put "Running full rescan"
                // in JetBrains Mono beside its own numbers.
                Text(
                    text = phaseLabel,
                    style = DapType.Caption,
                    color = Dap.secondaryText,
                )
                Text(
                    text = stringResource(
                        R.string.presentation_batch_f_sync_progress_counts,
                        (syncProgress.progress * 100).toInt(),
                        syncProgress.currentCount,
                        syncProgress.totalCount
                    ),
                    style = DapType.ValueSmall,
                    color = Dap.tertiaryText,
                )
            } else {
                DapIndeterminateBar(Modifier.fillMaxWidth())
                Spacer(Modifier.height(DapSpacing.Sm))
                Text(
                    text = phaseLabel,
                    style = DapType.Caption,
                    color = Dap.secondaryText,
                )
            }
        }
        if (!isSyncing && syncProgress.isCompleted) {
            Text(
                text = pluralStringResource(R.plurals.sync_catalog_total, syncProgress.totalCount, syncProgress.totalCount),
                style = DapType.Caption,
                color = Dap.secondaryText,
                modifier = Modifier.padding(vertical = DapSpacing.Sm),
            )
        }

    }
}

@Composable
private fun syncPhaseLabel(phase: SyncProgress.SyncPhase): String =
        stringResource(
                when (phase) {
                    SyncProgress.SyncPhase.IDLE -> R.string.presentation_batch_f_sync_phase_preparing
                    SyncProgress.SyncPhase.FETCHING_MEDIASTORE ->
                            R.string.presentation_batch_f_sync_phase_reading_mediastore
                    SyncProgress.SyncPhase.PROCESSING_FILES ->
                            R.string.presentation_batch_f_sync_phase_processing_tracks
                    SyncProgress.SyncPhase.SAVING_TO_DATABASE ->
                            R.string.presentation_batch_f_sync_phase_saving_db
                    SyncProgress.SyncPhase.SCANNING_LRC -> R.string.presentation_batch_f_sync_phase_scanning_lrc
                    SyncProgress.SyncPhase.CLEANING_CACHE ->
                            R.string.presentation_batch_f_sync_phase_cleaning_cache
                    SyncProgress.SyncPhase.COMPLETING -> R.string.presentation_batch_f_sync_phase_completing
                }
        )

/**
 * A row carrying one or two actions.
 *
 * The actions share a line rather than stacking as full-width blocks. Two stacked filled buttons of
 * equal width made the secondary action look like a peer of the primary one, which it never is.
 */
@Composable
fun ActionSettingsItem(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    DapActionRow(
        label = title,
        description = subtitle.takeIf { it.isNotBlank() },
        modifier = modifier,
    ) {
        DapPrimaryButton(
            label = primaryActionLabel,
            onClick = onPrimaryAction,
            enabled = enabled,
        )
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            DapSecondaryButton(
                label = secondaryActionLabel,
                onClick = onSecondaryAction,
                enabled = enabled,
            )
        }
    }
}

