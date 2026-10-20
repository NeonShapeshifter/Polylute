package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqResponse
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBand
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCapability
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqCurve
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteAvailability
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioFilterType
import com.lostf1sh.pixelplayeross.data.headphones.DacEqFit
import com.lostf1sh.pixelplayeross.data.headphones.DacEqIncompatibility
import com.lostf1sh.pixelplayeross.data.headphones.DacEqPreset
import com.lostf1sh.pixelplayeross.data.headphones.DacEqReuse
import com.lostf1sh.pixelplayeross.presentation.components.AndroidRouteHintLine
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.presentation.components.FeatureStatusLine
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.presentation.components.RenamePresetDialog
import com.lostf1sh.pixelplayeross.presentation.components.SavePresetDialog
import com.lostf1sh.pixelplayeross.presentation.components.rememberModalSheetState
import com.lostf1sh.pixelplayeross.presentation.model.SettingsCategory
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqError
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqOperation
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacPresetRow
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqScreenController
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacEqUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.FeatureStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.FeatureStatusKind
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SelectedPresetState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.textRes
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapChevron
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapEqBandSliders
import com.lostf1sh.pixelplayeross.ui.ds.DapIndeterminateBar
import com.lostf1sh.pixelplayeross.ui.ds.DapNavRow
import com.lostf1sh.pixelplayeross.ui.ds.DapRuledSection
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSegmentedPicker
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapTonalRoundButton
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.FrozenAlpha
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Resonance screen when it edits the DAC's own EQ (B30-P3, N-06): a FiiO/Snowsky DAC whose EQ was read and that this build may
 * write. It draws the DAC's real filters (its band count, filter types and limits; ten for the Melody) and its pre-gain, never the
 * software 10/15-band modes. The design system is the B22 look: ruled sections, the tonal round buttons, one status line.
 *
 * What it says it does is what the DAC read back: "applied" only when the DAC reports the EQ on; the Pure Direct lock makes it
 * read-only (nothing shapes the sound under Purity); a conflict that keeps playback on the Android route is said, with the way out,
 * and the same sentence says the DAC's EQ still applies because it lives in the DAC.
 */
interface DacEqScreenActions {
    fun onSetEnabled(on: Boolean)
    fun onEditBand(index: Int, change: (DacEqBand) -> DacEqBand)
    fun onResetFlat()
    fun onSelectPreset(presetId: String)
    fun onReusePreset(presetId: String)
    fun onSavePreset(name: String, updatePresetId: String? = null)
    fun onRenamePreset(presetId: String, name: String)
    fun onDeletePreset(presetId: String)
    fun onRefresh()
    fun onDismissError()

    /** B31 P1.3: takes the app's slot over (never offered as a popup, only on the card). */
    fun onTakeOver()

    /** B31 P1.3: gives the slot its factory curve back. */
    fun onRestoreFactory()

    /** B31 P1.4: reads the DAC's own presets into the backup. */
    fun onReadDacPresets()

    /** B31 P1.4: selects one of the DAC's own presets (a pointer move; asks first while music plays). */
    fun onSelectDacPreset(presetId: Int, confirmed: Boolean)
    fun onDismissConfirmSelect()

    /** Leads to the Audio page: where the Pure Direct switch, crossfade and speed live. */
    fun onOpenAudioSettings()
}

internal class DacEqScreenActionsImpl(
    private val controller: DacEqScreenController,
    private val navigate: (String) -> Unit,
) : DacEqScreenActions {
    override fun onSetEnabled(on: Boolean) = controller.setEnabled(on)
    override fun onEditBand(index: Int, change: (DacEqBand) -> DacEqBand) = controller.editBand(index, change)
    override fun onResetFlat() = controller.resetFlat()
    override fun onSelectPreset(presetId: String) = controller.selectPreset(presetId)
    override fun onReusePreset(presetId: String) = controller.reusePreset(presetId)
    override fun onSavePreset(name: String, updatePresetId: String?) = controller.savePreset(name, updatePresetId)
    override fun onRenamePreset(presetId: String, name: String) = controller.renamePreset(presetId, name)
    override fun onDeletePreset(presetId: String) = controller.deletePreset(presetId)
    override fun onRefresh() = controller.refresh()
    override fun onDismissError() = controller.dismissError()
    override fun onTakeOver() = controller.takeOver()
    override fun onRestoreFactory() = controller.restoreFactory()
    override fun onReadDacPresets() = controller.readDacPresets()
    override fun onSelectDacPreset(presetId: Int, confirmed: Boolean) = controller.selectDacPreset(presetId, confirmed)
    override fun onDismissConfirmSelect() = controller.dismissConfirmSelect()
    override fun onOpenAudioSettings() = navigate(Screen.SettingsCategory.createRoute(SettingsCategory.AUDIO.id))
}

/** The route's own composable: joins the controller's state with the navigation. */
@Composable
fun DacEqualizerScreen(navController: NavController, controller: DacEqScreenController) {
    val state by controller.uiState.collectAsStateWithLifecycle()
    val actions = remember(controller, navController) {
        DacEqScreenActionsImpl(controller) { route -> navController.navigateSafely(route) }
    }
    DacEqualizerContent(state, actions)
}

/** While a writable DAC's EQ is being read: the title and one line, nothing to edit yet. */
@Composable
fun DacEqReadingContent() {
    DapBackScaffold(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = DapSpacing.Md, start = DapSpacing.Gutter, end = DapSpacing.Gutter)) {
            DapScreenTitle(text = stringResource(R.string.settings_category_equalizer_title))
            Text(
                text = stringResource(R.string.dac_eq_reading),
                style = DapType.Body,
                color = Dap.secondaryText,
                modifier = Modifier.padding(top = 6.dp, bottom = DapSpacing.Lg),
            )
            DapIndeterminateBar()
        }
    }
}

private enum class DacEqMode { Off, On }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DacEqualizerContent(state: DacEqUiState, actions: DacEqScreenActions) {
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DacEqPreset?>(null) }
    var showRestoreFactory by remember { mutableStateOf(false) }

    val profileName = state.profileName ?: stringResource(R.string.settings_category_equalizer_title)
    val subtitle = state.dacName?.let { stringResource(R.string.eq_subtitle_with, profileName, it) } ?: profileName
    val frozen = state.frozenByPurity
    val showCurve = state.eqOn || state.pendingWrite || state.busy
    val modeLabels = mapOf(
        DacEqMode.Off to stringResource(R.string.headphone_eq_mode_off),
        DacEqMode.On to stringResource(R.string.dac_eq_mode_on),
    )

    DapBackScaffold(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = DapSpacing.Md)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = DapSpacing.Sm,
                    bottom = LocalDapPlayerShelfHeight.current + DapSpacing.SectionGap,
                    start = DapSpacing.Gutter,
                    end = DapSpacing.Gutter,
                ),
            ) {
                item(key = "dac_eq_title") {
                    Column {
                        DapScreenTitle(text = stringResource(R.string.settings_category_equalizer_title))
                        Text(
                            text = subtitle,
                            style = DapType.Body,
                            color = Dap.secondaryText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                item(key = "dac_eq_status") { DacEqStatusRow(state, actions) }

                state.conflict?.let { conflict ->
                    item(key = "dac_eq_conflict") {
                        Column(Modifier.fillMaxWidth().padding(top = DapSpacing.Md)) {
                            AndroidRouteHintLine(text = stringResource(conflict.textRes), maxLines = CONFLICT_LINES)
                            DapTextAction(
                                label = stringResource(R.string.dac_eq_open_audio),
                                onClick = actions::onOpenAudioSettings,
                                color = LocalDapAccent.current,
                            )
                        }
                    }
                }

                state.error?.let { error ->
                    item(key = "dac_eq_error") { DacEqErrorBlock(error, actions) }
                }

                state.operation?.let { operation ->
                    item(key = "dac_eq_operation") { DacEqOperationRow(operation, state) }
                }

                // Offered on the screen, never as a popup on connect (the owner's rule): the listener decides when.
                if (state.takeOverOffered) {
                    item(key = "dac_eq_takeover") { DacEqTakeOverCard(state, actions) }
                }

                item(key = "dac_eq_mode") {
                    DapRuledSection(title = stringResource(R.string.dac_eq_mode_on), labelGap = DapSpacing.SectionGap) {
                        Spacer(Modifier.height(DapSpacing.Sm))
                        DapSegmentedPicker(
                            options = DacEqMode.entries,
                            selected = if (state.eqOn) DacEqMode.On else DacEqMode.Off,
                            onSelect = { actions.onSetEnabled(it == DacEqMode.On) },
                            label = { modeLabels.getValue(it) },
                            enabled = !frozen && !state.busy && state.availability.canWrite,
                            fillWidth = true,
                        )
                    }
                }

                if (showCurve) {
                    item(key = "dac_eq_curve") {
                        Column(Modifier.fillMaxWidth().then(if (frozen) Modifier.alpha(FrozenAlpha) else Modifier)) {
                            DapDivider()
                            DapNavRow(
                                label = stringResource(R.string.dac_eq_preset_row),
                                value = state.presets.firstOrNull { it.presetId == state.selectedPresetId }?.name
                                    ?: stringResource(R.string.dac_eq_preset_custom),
                                valueStyle = DapType.CaptionMedium,
                                description = when (state.selectedPresetState) {
                                    SelectedPresetState.MATCHES_DAC -> stringResource(R.string.dac_eq_preset_matches)
                                    SelectedPresetState.DIFFERS_FROM_DAC -> stringResource(R.string.dac_eq_preset_differs)
                                    SelectedPresetState.NONE -> null
                                },
                                onClick = { showPresets = true },
                                enabled = !frozen,
                                divider = false,
                            )
                            if (state.eqOn && !frozen) {
                                DapTextAction(
                                    label = stringResource(R.string.dac_eq_save_preset),
                                    onClick = { showSave = true },
                                    color = LocalDapAccent.current,
                                )
                            }

                            DapRuledSection(title = stringResource(R.string.eq_section_response), labelGap = DapSpacing.SectionGap) {
                                Spacer(Modifier.height(DapSpacing.Sm))
                                val frequencies = remember { GraphicEqResponse.curveFrequenciesHz() }
                                val curve = remember(state.bands, state.preGainTenthsDb, state.preGainSense) {
                                    DacEqCurve.curveDb(state.bands, state.preGainTenthsDb, frequencies, state.preGainSense)
                                        .let { db -> FloatArray(db.size) { db[it].toFloat() } }
                                }
                                EqResponseGraph(curve)
                                Row(
                                    Modifier.fillMaxWidth().padding(top = DapSpacing.Xs),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    for (id in listOf(R.string.eq_axis_low, R.string.eq_axis_zero, R.string.eq_axis_high)) {
                                        Text(stringResource(id), style = DapType.Axis, color = Dap.tertiaryText, maxLines = 1)
                                    }
                                }
                            }

                            DapNavRow(
                                label = stringResource(R.string.dac_eq_pregain),
                                value = stringResource(R.string.dac_eq_pregain_value, formatGain(state.preGainTenthsDb)),
                                description = stringResource(
                                    if (state.senseBlocksCurve) R.string.dac_eq_pregain_sense_note else R.string.dac_eq_pregain_note,
                                ),
                                divider = false,
                            )

                            val range = state.capability?.maxGainDb?.roundToInt() ?: 12
                            DapRuledSection(title = stringResource(R.string.dac_eq_section_bands, range), labelGap = DapSpacing.SectionGap) {
                                Spacer(Modifier.height(DapSpacing.Sm))
                                DapEqBandSliders(
                                    gains = state.bands.map { (it.gainTenthsDb / 10.0).roundToInt().coerceIn(-range, range) },
                                    labels = state.bands.map { frequencyLabel(it.frequencyHz) },
                                    onGainChange = { index, db -> actions.onEditBand(index) { it.copy(gainTenthsDb = db * 10) } },
                                    rangeDb = range,
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    DapTextAction(
                                        label = stringResource(R.string.eq_reset_flat),
                                        onClick = actions::onResetFlat,
                                        enabled = !frozen,
                                        color = LocalDapAccent.current,
                                    )
                                }
                            }

                            DapRuledSection(title = stringResource(R.string.dac_eq_section_filters), labelGap = DapSpacing.SectionGap) {
                                state.bands.forEach { band ->
                                    DapNavRow(
                                        label = stringResource(R.string.dac_eq_filter_label, band.index + 1),
                                        value = stringResource(
                                            R.string.dac_eq_filter_value,
                                            stringResource(typeNameRes(band.type)),
                                            frequencyText(band.frequencyHz),
                                            formatGain(band.gainTenthsDb),
                                            formatQ(band.qHundredths),
                                        ),
                                        valueStyle = DapType.CaptionMedium,
                                        onClick = { editIndex = band.index },
                                        enabled = !frozen,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.dacPresets.isNotEmpty()) {
                    item(key = "dac_eq_dac_presets") { DacEqDacPresetsSection(state, actions) }
                }

                item(key = "dac_eq_footer") {
                    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.Sm)) {
                        state.appSlotName?.let {
                            Text(
                                text = stringResource(R.string.dac_eq_footer, it),
                                style = DapType.Caption,
                                color = Dap.tertiaryText,
                            )
                        }
                        if (state.slotOwned) {
                            val slot = state.appSlotName.orEmpty()
                            Text(
                                text = stringResource(R.string.dac_eq_owned_title, slot),
                                style = DapType.Caption,
                                color = Dap.secondaryText,
                                modifier = Modifier.padding(top = DapSpacing.Sm),
                            )
                            DapTextAction(
                                label = stringResource(R.string.dac_eq_restore_factory),
                                onClick = { showRestoreFactory = true },
                                enabled = state.canEdit,
                                color = LocalDapAccent.current,
                            )
                        }
                        if (state.debugOverride) {
                            Text(
                                text = stringResource(R.string.dac_eq_debug_override),
                                style = DapType.Caption,
                                color = Dap.warning,
                                modifier = Modifier.padding(top = DapSpacing.Sm),
                            )
                        }
                        DapTextAction(
                            label = stringResource(R.string.dac_eq_read_again),
                            onClick = actions::onRefresh,
                            color = LocalDapAccent.current,
                        )
                    }
                }
            }
        }
    }

    editIndex?.let { index ->
        state.bands.getOrNull(index)?.let { band ->
            DacEqFilterSheet(
                band = band,
                capability = state.capability,
                onChange = { change -> actions.onEditBand(index, change) },
                onDismiss = { editIndex = null },
            )
        }
    }
    if (showPresets) {
        DacEqPresetsSheet(
            state = state,
            actions = actions,
            onSave = { showSave = true },
            onRename = { renameTarget = it },
            onDismiss = { showPresets = false },
        )
    }
    if (showSave) {
        SavePresetDialog(
            onDismiss = { showSave = false },
            onSave = { name ->
                actions.onSavePreset(name)
                showSave = false
            },
        )
    }
    renameTarget?.let { target ->
        RenamePresetDialog(
            currentName = target.name,
            onDismiss = { renameTarget = null },
            onRename = { name ->
                actions.onRenamePreset(target.presetId, name)
                renameTarget = null
            },
        )
    }
    if (showRestoreFactory) {
        DacEqConfirmDialog(
            title = stringResource(R.string.dac_eq_restore_factory_title),
            body = stringResource(R.string.dac_eq_restore_factory_note, state.appSlotName.orEmpty()),
            confirmLabel = stringResource(R.string.dac_eq_restore_factory_confirm),
            onConfirm = {
                showRestoreFactory = false
                actions.onRestoreFactory()
            },
            onDismiss = { showRestoreFactory = false },
        )
    }
    state.confirmSelect?.let { row ->
        DacEqConfirmDialog(
            title = stringResource(R.string.dac_eq_select_confirm_title),
            body = stringResource(R.string.dac_eq_select_confirm_body, row.name),
            confirmLabel = stringResource(R.string.dac_eq_select_confirm_action),
            onConfirm = { actions.onSelectDacPreset(row.id, true) },
            onDismiss = actions::onDismissConfirmSelect,
        )
    }
}

/** A yes/no in the app's own words: an alert is a window of its own, so the volume-key bridge is routed in its title like the others. */
@Composable
private fun DacEqConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            ModalInputBridge()
            Text(title)
        },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
    )
}

/** B31 P1.3: the card that offers to take the app's slot over; the listener taps it when the music is paused. */
@Composable
private fun DacEqTakeOverCard(state: DacEqUiState, actions: DacEqScreenActions) {
    val slot = state.appSlotName.orEmpty()
    DapRuledSection(title = stringResource(R.string.dac_eq_takeover_title, slot), labelGap = DapSpacing.SectionGap) {
        Text(
            text = stringResource(R.string.dac_eq_takeover_body, slot),
            style = DapType.Body,
            color = Dap.secondaryText,
            modifier = Modifier.padding(top = DapSpacing.Sm),
        )
        DapTextAction(
            label = stringResource(R.string.dac_eq_takeover_action, slot),
            onClick = actions::onTakeOver,
            enabled = state.canEdit,
            color = LocalDapAccent.current,
        )
    }
}

/** What the screen is doing on the DAC that is not an edit: one line and the indeterminate bar. */
@Composable
private fun DacEqOperationRow(operation: DacEqOperation, state: DacEqUiState) {
    val slot = state.appSlotName.orEmpty()
    val text = when (operation) {
        DacEqOperation.TAKE_OVER -> stringResource(R.string.dac_eq_takeover_busy, slot)
        DacEqOperation.RESTORE_FACTORY -> stringResource(R.string.dac_eq_restore_factory)
        DacEqOperation.READ_PRESETS -> stringResource(R.string.dac_eq_dac_presets_read)
        DacEqOperation.SELECT_PRESET -> stringResource(R.string.dac_eq_reading)
    }
    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.Md)) {
        Text(text = text, style = DapType.Body, color = Dap.secondaryText, modifier = Modifier.padding(bottom = DapSpacing.Sm))
        DapIndeterminateBar()
    }
}

/**
 * B31 P1.4: the DAC's own presets by name, read from the inventory in the backup. A tap on one only **selects** it (the DAC's pointer);
 * nothing in it is changed. One that could play louder than the app allows is shown and not selectable, and says so.
 */
@Composable
private fun DacEqDacPresetsSection(state: DacEqUiState, actions: DacEqScreenActions) {
    DapRuledSection(title = stringResource(R.string.dac_eq_dac_presets_title), labelGap = DapSpacing.SectionGap) {
        Text(
            text = stringResource(R.string.dac_eq_dac_presets_note),
            style = DapType.Caption,
            color = Dap.tertiaryText,
            modifier = Modifier.padding(top = DapSpacing.Xs, bottom = DapSpacing.Sm),
        )
        state.dacPresets.forEach { row ->
            DapNavRow(
                label = row.name,
                description = dacPresetDescription(row),
                value = if (row.isActive) stringResource(R.string.dac_eq_dac_preset_active) else null,
                valueStyle = DapType.CaptionMedium,
                onClick = { actions.onSelectDacPreset(row.id, false) },
                enabled = row.selectable && state.canEdit,
            )
        }
        if (!state.presetsRead) {
            Text(
                text = stringResource(R.string.dac_eq_dac_presets_read_note),
                style = DapType.Caption,
                color = Dap.tertiaryText,
                modifier = Modifier.padding(top = DapSpacing.Sm),
            )
            DapTextAction(
                label = stringResource(R.string.dac_eq_dac_presets_read),
                onClick = actions::onReadDacPresets,
                enabled = state.canEdit,
                color = LocalDapAccent.current,
            )
        }
    }
}

@Composable
private fun dacPresetDescription(row: DacPresetRow): String? = when {
    row.isClosed -> stringResource(R.string.dac_eq_dac_preset_closed)
    !row.readFromDac -> stringResource(R.string.dac_eq_dac_preset_unread)
    row.locked -> stringResource(R.string.dac_eq_dac_preset_locked)
    else -> stringResource(R.string.dac_eq_dac_preset_detail, formatGain(row.preGainTenthsDb ?: 0), row.activeFilters ?: 0)
}

/** The one status line: what the DAC reports, and where it is applied. */
@Composable
private fun DacEqStatusRow(state: DacEqUiState, actions: DacEqScreenActions) {
    // Never "frozen": the DAC's EQ is not the app's DSP and the lock cannot hold it back, so what the DAC reports is what is said;
    // under Purity the hint below adds that it can only be looked at.
    val status = when {
        // No route tag: with a conflict the sentence under the status says which route and why (a tag beside "in the DAC" does not fit).
        state.eqOn -> FeatureStatus(FeatureStatusKind.APPLIED, R.string.dac_eq_status_on)
        state.verdict == com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqVerdict.UNKNOWN ->
            FeatureStatus(FeatureStatusKind.BYPASSED, R.string.dac_eq_status_unknown)
        else -> FeatureStatus.Off
    }
    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.Lg)) {
        DapDivider()
        val rowModifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .let { if (state.frozenByPurity) it.clickable(role = Role.Button, onClick = actions::onOpenAudioSettings) else it }
            .padding(vertical = DapSpacing.Sm)
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FeatureStatusLine(status)
                // The DAC's own selection on a line of its own: beside the status it ran into it in Spanish.
                val preset = state.activePresetName
                if (preset != null && !state.frozenByPurity) {
                    Text(
                        text = stringResource(R.string.dac_eq_active_preset, preset),
                        style = DapType.Caption,
                        color = Dap.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = DapSpacing.Xs),
                    )
                }
                if (state.frozenByPurity) {
                    Spacer(Modifier.height(DapSpacing.Xs))
                    Text(
                        text = stringResource(R.string.dac_eq_frozen_hint, stringResource(R.string.pristine_lock_name)),
                        style = DapType.Body,
                        color = Dap.secondaryText,
                    )
                }
            }
            if (state.frozenByPurity) {
                Spacer(Modifier.padding(start = DapSpacing.Sm))
                DapChevron()
            }
        }
        DapDivider()
    }
}

@Composable
private fun DacEqErrorBlock(error: DacEqError, actions: DacEqScreenActions) {
    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.Md)) {
        Text(stringResource(R.string.dac_eq_error_title), style = DapType.Body, color = Dap.warning)
        Text(
            text = when (error) {
                is DacEqError.Refused -> stringResource(R.string.dac_eq_error_refused, stringResource(error.refusal.textRes()))
                is DacEqError.ReadBackDiffers ->
                    stringResource(if (error.restored) R.string.dac_eq_error_readback_restored else R.string.dac_eq_error_readback_not_restored)
                is DacEqError.Failed -> stringResource(R.string.dac_eq_error_failed)
                DacEqError.Unavailable -> stringResource(R.string.dac_eq_error_unavailable)
                is DacEqError.Dropped -> stringResource(R.string.dac_eq_error_refused, stringResource(error.reason.textRes))
            },
            style = DapType.Body,
            color = Dap.secondaryText,
            modifier = Modifier.padding(top = DapSpacing.Xs),
        )
        DapTextAction(label = stringResource(R.string.dac_eq_done), onClick = actions::onDismissError, color = LocalDapAccent.current)
    }
}

// ── One filter ───────────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DacEqFilterSheet(
    band: DacEqBand,
    capability: DacEqCapability?,
    onChange: ((DacEqBand) -> DacEqBand) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        ModalInputBridge()
        DacEqFilterEditorContent(band, capability, onChange, onDismiss)
    }
}

/** One filter's editor: its type, and a frequency, a gain and a Q each moved by the tonal round buttons. Limits are the DAC's. */
@Composable
internal fun DacEqFilterEditorContent(
    band: DacEqBand,
    capability: DacEqCapability?,
    onChange: ((DacEqBand) -> DacEqBand) -> Unit,
    onDismiss: () -> Unit,
) {
    val minGain = ((capability?.minGainDb ?: -12.0) * 10).roundToInt()
    val maxGain = ((capability?.maxGainDb ?: 12.0) * 10).roundToInt()
    val minHz = capability?.minFrequencyHz ?: 20
    val maxHz = capability?.maxFrequencyHz ?: 20_000
    val minQ = ((capability?.minQ ?: 0.1) * 100).roundToInt()
    val maxQ = ((capability?.maxQ ?: 10.0) * 100).roundToInt()
    val types = DacEqUiTypes(band.type)
    val typeLabels = types.associateWith { stringResource(typeNameRes(it)) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DapSpacing.Gutter)
            .padding(bottom = DapSpacing.SectionGap),
        verticalArrangement = Arrangement.spacedBy(DapSpacing.Md),
    ) {
        Text(stringResource(R.string.dac_eq_filter_title, band.index + 1), style = DapType.Body, color = Dap.primaryText)
        Text(stringResource(R.string.dac_eq_field_type), style = DapType.Caption, color = Dap.tertiaryText)
        DapSegmentedPicker(
            options = types,
            selected = band.type ?: FiioFilterType.PEAK,
            onSelect = { type -> onChange { it.copy(typeCode = type.code) } },
            label = { typeLabels.getValue(it) },
            fillWidth = true,
        )
        StepperRow(
            label = stringResource(R.string.dac_eq_field_frequency),
            value = frequencyText(band.frequencyHz),
            onLower = { onChange { it.copy(frequencyHz = stepFrequency(it.frequencyHz, -1).coerceIn(minHz, maxHz)) } },
            onRaise = { onChange { it.copy(frequencyHz = stepFrequency(it.frequencyHz, +1).coerceIn(minHz, maxHz)) } },
        )
        StepperRow(
            label = stringResource(R.string.dac_eq_field_gain),
            value = "${formatGain(band.gainTenthsDb)} dB",
            onLower = { onChange { it.copy(gainTenthsDb = (it.gainTenthsDb - GAIN_STEP_TENTHS).coerceIn(minGain, maxGain)) } },
            onRaise = { onChange { it.copy(gainTenthsDb = (it.gainTenthsDb + GAIN_STEP_TENTHS).coerceIn(minGain, maxGain)) } },
        )
        StepperRow(
            label = stringResource(R.string.dac_eq_field_q),
            value = formatQ(band.qHundredths),
            onLower = { onChange { it.copy(qHundredths = stepQ(it.qHundredths, -1).coerceIn(minQ, maxQ)) } },
            onRaise = { onChange { it.copy(qHundredths = stepQ(it.qHundredths, +1).coerceIn(minQ, maxQ)) } },
        )
        DapTextAction(label = stringResource(R.string.dac_eq_done), onClick = onDismiss, color = LocalDapAccent.current)
    }
}

@Composable
private fun StepperRow(label: String, value: String, onLower: () -> Unit, onRaise: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = DapType.Body, color = Dap.primaryText, modifier = Modifier.weight(1f))
        DapTonalRoundButton(
            contentDescription = stringResource(R.string.dac_eq_decrease, label),
            onClick = onLower,
            size = 48.dp,
        ) { tint -> Icon(Icons.Rounded.Remove, contentDescription = null, tint = tint) }
        Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) {
            Text(value, style = DapType.Value, color = Dap.primaryText, maxLines = 1)
        }
        DapTonalRoundButton(
            contentDescription = stringResource(R.string.dac_eq_increase, label),
            onClick = onRaise,
            size = 48.dp,
        ) { tint -> Icon(Icons.Rounded.Add, contentDescription = null, tint = tint) }
    }
}

private fun DacEqUiTypes(current: FiioFilterType?): List<FiioFilterType> =
    if (current == null || current in DacEqUiState.offeredTypes) DacEqUiState.offeredTypes else DacEqUiState.offeredTypes + current

// ── Presets ──────────────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DacEqPresetsSheet(
    state: DacEqUiState,
    actions: DacEqScreenActions,
    onSave: () -> Unit,
    onRename: (DacEqPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        ModalInputBridge()
        DacEqPresetsContent(state, actions, onSave, onRename, onDismiss)
    }
}

/**
 * The presets of these headphones on this DAC (the pair), and under them the ones made for other DACs with how each would fit:
 * a tap on one of the pair's selects it; a tap on one made elsewhere reuses it, if it fits or can be adjusted. Nothing here
 * happens by itself.
 */
@Composable
internal fun DacEqPresetsContent(
    state: DacEqUiState,
    actions: DacEqScreenActions,
    onSave: () -> Unit,
    onRename: (DacEqPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val profileName = state.profileName ?: stringResource(R.string.settings_category_equalizer_title)
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Sm),
    ) {
        item(key = "title") {
            Text(
                text = stringResource(R.string.dac_eq_presets_title, profileName, state.dacName.orEmpty()),
                style = DapType.Body,
                color = Dap.primaryText,
                modifier = Modifier.padding(bottom = DapSpacing.Sm),
            )
        }
        item(key = "pair_header") {
            Text(stringResource(R.string.dac_eq_presets_for_pair), style = DapType.Caption, color = Dap.tertiaryText)
        }
        if (state.presets.isEmpty()) {
            item(key = "pair_empty") {
                Text(
                    text = stringResource(R.string.dac_eq_presets_empty),
                    style = DapType.Body,
                    color = Dap.secondaryText,
                    modifier = Modifier.padding(vertical = DapSpacing.Md),
                )
            }
        }
        state.presets.forEach { preset ->
            item(key = "pair_${preset.presetId}") {
                DapNavRow(
                    label = preset.name,
                    description = if (preset.presetId == state.selectedPresetId) {
                        when (state.selectedPresetState) {
                            SelectedPresetState.MATCHES_DAC -> stringResource(R.string.dac_eq_preset_matches)
                            else -> stringResource(R.string.dac_eq_preset_differs)
                        }
                    } else {
                        null
                    },
                    onClick = {
                        actions.onSelectPreset(preset.presetId)
                        onDismiss()
                    },
                    trailing = {
                        Row {
                            IconButton(onClick = { onRename(preset) }) {
                                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.presentation_batch_g_presets_cd_rename), tint = Dap.tertiaryText)
                            }
                            IconButton(onClick = { actions.onDeletePreset(preset.presetId) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.dac_eq_preset_delete), tint = Dap.tertiaryText)
                            }
                        }
                    },
                )
            }
        }
        if (state.eqOn) {
            item(key = "save") {
                DapTextAction(
                    label = stringResource(R.string.dac_eq_save_preset),
                    onClick = {
                        onSave()
                        onDismiss()
                    },
                    color = LocalDapAccent.current,
                )
            }
        }
        if (state.reusable.isNotEmpty()) {
            item(key = "other_header") {
                Text(
                    text = stringResource(R.string.dac_eq_presets_other),
                    style = DapType.Caption,
                    color = Dap.tertiaryText,
                    modifier = Modifier.padding(top = DapSpacing.Lg),
                )
            }
            state.reusable.forEach { reuse ->
                item(key = "other_${reuse.preset.presetId}") {
                    val usable = reuse.fit !is DacEqFit.Incompatible
                    DapNavRow(
                        label = reuse.preset.name,
                        description = stringResource(R.string.dac_eq_preset_made_for, reuse.preset.dacName) + " · " + fitText(reuse),
                        onClick = {
                            actions.onReusePreset(reuse.preset.presetId)
                            onDismiss()
                        },
                        enabled = usable,
                    )
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(DapSpacing.SectionGap)) }
    }
}

@Composable
private fun fitText(reuse: DacEqReuse): String = fitText(reuse.fit)

/** How a preset made for another DAC fits the one in use, in words ("Fits", "Would be adjusted…", "Not compatible: …"). */
@Composable
internal fun fitText(fit: DacEqFit): String = when (fit) {
    DacEqFit.Fits -> stringResource(R.string.dac_eq_fit_fits)
    is DacEqFit.Approximate -> stringResource(R.string.dac_eq_fit_approx, fit.changes.size)
    is DacEqFit.Incompatible -> stringResource(
        R.string.dac_eq_fit_incompatible,
        stringResource(
            when {
                DacEqIncompatibility.NO_USABLE_EQ in fit.kinds -> R.string.dac_eq_incompat_no_eq
                DacEqIncompatibility.TOO_MANY_FILTERS in fit.kinds -> R.string.dac_eq_incompat_count
                else -> R.string.dac_eq_incompat_type
            }
        ),
    )
}

// ── Formatting and steps ─────────────────────────────────────────────────────────────────────────────────

private const val GAIN_STEP_TENTHS = 5

/** The conflict sentence is long on purpose (what, what still applies, how to stop it): it is never cut. */
internal const val CONFLICT_LINES = 10

internal fun typeNameRes(type: FiioFilterType?): Int = when (type) {
    FiioFilterType.PEAK -> R.string.dac_eq_type_peak
    FiioFilterType.LOW_SHELF -> R.string.dac_eq_type_low_shelf
    FiioFilterType.HIGH_SHELF -> R.string.dac_eq_type_high_shelf
    FiioFilterType.BAND_PASS -> R.string.dac_eq_type_band_pass
    FiioFilterType.LOW_PASS -> R.string.dac_eq_type_low_pass
    FiioFilterType.HIGH_PASS -> R.string.dac_eq_type_high_pass
    FiioFilterType.ALL_PASS -> R.string.dac_eq_type_all_pass
    null -> R.string.dac_eq_type_unknown
}

internal fun formatGain(tenths: Int): String =
    if (tenths == 0) String.format(Locale.getDefault(), "%.1f", 0.0) else String.format(Locale.getDefault(), "%+.1f", tenths / 10.0)

internal fun formatQ(hundredths: Int): String = String.format(Locale.getDefault(), "%.2f", hundredths / 100.0)

/** "125 Hz", "1.2 kHz": the unit spelled out, for rows. */
internal fun frequencyText(hz: Int): String =
    if (hz >= 1000) String.format(Locale.getDefault(), "%s kHz", trimmed(hz / 1000.0)) else "$hz Hz"

/** "125Hz", "1.2kHz": what the slider columns print (the design system drops the unit from the column). */
internal fun frequencyLabel(hz: Int): String =
    if (hz >= 1000) String.format(Locale.US, "%skHz", trimmed(hz / 1000.0)) else "${hz}Hz"

private fun trimmed(value: Double): String = String.format(Locale.US, "%.1f", value).removeSuffix(".0")

/** A twelfth of an octave per tap, on whole hertz (and always at least one hertz, so low frequencies move). */
internal fun stepFrequency(hz: Int, direction: Int): Int {
    val factor = Math.pow(2.0, direction / 12.0)
    val next = (hz * factor).roundToInt()
    return if (next == hz) hz + direction else next
}

/** About 12 % per tap on Q, on hundredths (at least one hundredth). */
internal fun stepQ(hundredths: Int, direction: Int): Int {
    val next = (hundredths * if (direction > 0) 1.12 else 1 / 1.12).roundToInt()
    return if (next == hundredths) hundredths + direction else next
}
