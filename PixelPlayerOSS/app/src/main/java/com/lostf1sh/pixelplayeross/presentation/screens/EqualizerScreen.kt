package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqBands
import com.lostf1sh.pixelplayeross.data.audio.dsp.GraphicEqResponse
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.presentation.components.CustomPresetsSheet
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.presentation.components.FeatureStatusLine
import com.lostf1sh.pixelplayeross.presentation.components.RenamePresetDialog
import com.lostf1sh.pixelplayeross.presentation.components.SavePresetDialog
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.settings.routeNameRes
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DapAudioRouteKind
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.FeatureStatusKind
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapChevron
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapEqBandSliders
import com.lostf1sh.pixelplayeross.ui.ds.DapNavRow
import com.lostf1sh.pixelplayeross.ui.ds.DapRuledSection
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSegmentedPicker
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.FrozenAlpha
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The equalizer. There is one of these.
 *
 * B22 (`Equalizer.dc.html`): the title says which headphones it edits and with which DAC; one status line says what the
 * output does with the curve (applied and where, or frozen by Pure Direct, with a way to the switch); the mode is saved in
 * the profile; and the bands are ten (or fifteen) vertical sliders, each one drawn where its value is.
 *
 * What is left out is what was never the equalizer: Bass Boost, Virtualizer and Loudness are `android.media.audiofx`
 * session effects and live on the headphone profile; volume belongs to the system and to Safety Max. The engine has no
 * preamp, so there is no preamp here.
 */

/** The DSP's own range. The engine has no preamp, so the graph must not draw one. */
internal const val EQ_RANGE_DB = 15

/** The response graph's height, as the design draws it. */
private val EqGraphHeight = 96.dp

/**
 * The equalizer's route: joins the ViewModels' state into [EqualizerScreenState] and hands
 * [EqualizerScreenContent] the actions. B21: it used to draw straight from the ViewModel.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun EqualizerScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    equalizerViewModel: EqualizerViewModel = hiltViewModel(),
    headphoneProfilesViewModel: HeadphoneProfilesViewModel = hiltViewModel(),
    deviceControlViewModel: DeviceControlViewModel = hiltViewModel(),
) {
    val eq by equalizerViewModel.uiState.collectAsStateWithLifecycle()
    val outputContext by equalizerViewModel.outputContext.collectAsStateWithLifecycle()
    val audioStatus by equalizerViewModel.audioStatus.collectAsStateWithLifecycle()
    val profiles by headphoneProfilesViewModel.uiState.collectAsStateWithLifecycle()
    // Only where the sound goes: the device state moves with the volume, and the equalizer does not follow that.
    val outputKind by remember(deviceControlViewModel) {
        deviceControlViewModel.uiState.map { it.audioRouteKind }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = DapAudioRouteKind.ANDROID_AUDIO)
    val profile = profiles.profiles.firstOrNull { it.profile.profileId == equalizerViewModel.editedProfileId }
    val state = remember(eq, outputContext, audioStatus, profile, outputKind) {
        EqualizerScreenState.from(eq, outputContext, audioStatus, profile, outputKind)
    }
    val actions = remember(equalizerViewModel, navController) {
        EqualizerScreenActionsImpl(equalizerViewModel) { route -> navController.navigateSafely(route) }
    }
    EqualizerScreenContent(state, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreenContent(state: EqualizerScreenState, actions: EqualizerScreenActions) {
    val uiState = state.eq
    var showPresetSheet by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<EqualizerPreset?>(null) }

    val modeLabels = mapOf(
        EqModeChoice.Off to stringResource(R.string.headphone_eq_mode_off),
        EqModeChoice.Ten to stringResource(R.string.headphone_eq_mode_10),
        EqModeChoice.Fifteen to stringResource(R.string.headphone_eq_mode_15),
    )
    val selectedMode = when {
        !uiState.isEnabled -> EqModeChoice.Off
        uiState.mode == HeadphoneEqMode.GRAPHIC_15 -> EqModeChoice.Fifteen
        else -> EqModeChoice.Ten
    }
    val frequenciesHz = remember(uiState.mode) {
        GraphicEqBands.frequenciesFor(if (uiState.mode == HeadphoneEqMode.GRAPHIC_15) 15 else 10)
    }
    val curve = remember(frequenciesHz, uiState.bandLevels) {
        GraphicEqResponse.curveDb(frequenciesHz, uiState.bandLevels)
    }
    val profileName = state.profileName ?: stringResource(R.string.settings_category_equalizer_title)
    val frozen = state.status.kind == FeatureStatusKind.FROZEN
    val subtitle = state.dacName?.let { stringResource(R.string.eq_subtitle_with, profileName, it) } ?: profileName

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
                item(key = "eq_title") {
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

                item(key = "eq_status") {
                    EqualizerStatusRow(state, frozen) { actions.onOpenPurityLock() }
                }

                item(key = "eq_mode") {
                    DapRuledSection(
                        title = stringResource(R.string.eq_mode_saved_in, profileName),
                        labelGap = DapSpacing.SectionGap,
                    ) {
                        Spacer(Modifier.height(DapSpacing.Sm))
                        DapSegmentedPicker(
                            options = EqModeChoice.entries,
                            selected = selectedMode,
                            onSelect = { choice ->
                                when (choice) {
                                    EqModeChoice.Off -> actions.onEnabledChange(false)
                                    EqModeChoice.Ten -> {
                                        if (uiState.mode != HeadphoneEqMode.GRAPHIC_10) {
                                            actions.onModeChange(HeadphoneEqMode.GRAPHIC_10)
                                        }
                                        actions.onEnabledChange(true)
                                    }
                                    EqModeChoice.Fifteen -> {
                                        if (uiState.mode != HeadphoneEqMode.GRAPHIC_15) {
                                            actions.onModeChange(HeadphoneEqMode.GRAPHIC_15)
                                        }
                                        actions.onEnabledChange(true)
                                    }
                                }
                            },
                            label = { modeLabels.getValue(it) },
                            fillWidth = true,
                        )
                    }
                }

                if (uiState.isEnabled) {
                    // Frozen is kept and editable, drawn at 40 %: the curve can still be edited, it just is not applied.
                    item(key = "eq_curve") {
                        Column(Modifier.fillMaxWidth().then(if (frozen) Modifier.alpha(FrozenAlpha) else Modifier)) {
                            // Presets are ten-band curves, and the model refuses to stretch one across the
                            // fifteen-band set rather than guessing at an interpolation the engine has no
                            // rule for. So the selector is shown exactly where it can be honoured.
                            if (uiState.mode == HeadphoneEqMode.GRAPHIC_10) {
                                Spacer(Modifier.height(DapSpacing.Md))
                                DapDivider()
                                DapNavRow(
                                    label = stringResource(R.string.eq_preset_row),
                                    value = equalizerPresetLabel(uiState.currentPreset),
                                    valueStyle = DapType.CaptionMedium,
                                    description = if (uiState.currentPreset.isCustom) {
                                        stringResource(R.string.eq_preset_custom_description)
                                    } else {
                                        null
                                    },
                                    onClick = { showPresetSheet = true },
                                    divider = false,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(DapSpacing.Lg)) {
                                    DapTextAction(
                                        label = stringResource(R.string.eq_save_preset),
                                        onClick = { showSaveDialog = true },
                                        color = LocalDapAccent.current,
                                    )
                                    if (uiState.currentPreset.isCustom) {
                                        DapTextAction(
                                            label = stringResource(R.string.eq_preset_update),
                                            onClick = { actions.onUpdateCustomPresetBands(uiState.currentPreset.name) },
                                            color = LocalDapAccent.current,
                                        )
                                    }
                                }
                            }

                            DapRuledSection(
                                title = stringResource(R.string.eq_section_response),
                                labelGap = DapSpacing.SectionGap,
                            ) {
                                Spacer(Modifier.height(DapSpacing.Sm))
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

                            DapRuledSection(
                                title = stringResource(R.string.eq_section_bands),
                                labelGap = DapSpacing.SectionGap,
                            ) {
                                Spacer(Modifier.height(DapSpacing.Sm))
                                DapEqBandSliders(
                                    gains = uiState.bandLevels,
                                    labels = uiState.bandFrequencies,
                                    onGainChange = { index, gain -> actions.onBandLevelChange(index, gain) },
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    DapTextAction(
                                        label = stringResource(R.string.eq_reset_flat),
                                        onClick = { actions.onResetBandsToFlat() },
                                        color = LocalDapAccent.current,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item(key = "eq_off_note") {
                        Text(
                            text = stringResource(R.string.eq_off_note),
                            style = DapType.Body,
                            color = Dap.tertiaryText,
                            modifier = Modifier.fillMaxWidth().padding(top = DapSpacing.SectionGap),
                        )
                    }
                }

                item(key = "eq_saved_note") {
                    Text(
                        text = stringResource(R.string.eq_saved_note, profileName),
                        style = DapType.Caption,
                        color = Dap.tertiaryText,
                        modifier = Modifier.fillMaxWidth().padding(top = DapSpacing.Sm),
                    )
                }
            }
        }
    }

    if (showPresetSheet) {
        CustomPresetsSheet(
            presets = uiState.allAvailablePresets,
            pinnedPresetsNames = uiState.pinnedPresetsNames,
            onPresetSelected = {
                actions.onSelectPreset(it)
                showPresetSheet = false
            },
            onPinToggled = { actions.onTogglePinPreset(it.name) },
            onRename = { renameTarget = it },
            onDelete = { actions.onDeleteCustomPreset(it) },
            onDismiss = { showPresetSheet = false },
        )
    }

    if (showSaveDialog) {
        SavePresetDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                actions.onSaveCurrentAsCustomPreset(name)
                showSaveDialog = false
            },
        )
    }

    renameTarget?.let { target ->
        RenamePresetDialog(
            currentName = target.name,
            onDismiss = { renameTarget = null },
            onRename = { newName ->
                actions.onRenameCustomPreset(target.name, newName)
                renameTarget = null
            },
        )
    }
}

/** The three states of the equalizer, as the engine models them: off, ten filters, fifteen. */
private enum class EqModeChoice { Off, Ten, Fifteen }

/**
 * The one status line of the equalizer, under the title.
 *
 * Frozen by Pure Direct it is a row that says the curve can still be edited and leads to the switch (on the Audio page).
 * Otherwise it is the status, and, when the curve is applied, where: Resonance, USB Android, or the Android output.
 */
@Composable
private fun EqualizerStatusRow(state: EqualizerScreenState, frozen: Boolean, onOpenSwitch: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.Lg)) {
        DapDivider()
        if (frozen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .clickable(role = Role.Button, onClick = onOpenSwitch)
                    .padding(vertical = DapSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    FeatureStatusLine(state.status)
                    Spacer(Modifier.height(DapSpacing.Xs))
                    Text(
                        text = stringResource(R.string.eq_frozen_hint, stringResource(R.string.pristine_lock_name)),
                        style = DapType.Body,
                        color = Dap.secondaryText,
                    )
                }
                Spacer(Modifier.padding(start = DapSpacing.Sm))
                DapChevron()
            }
        } else {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(vertical = DapSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { FeatureStatusLine(state.status) }
                if (state.status.kind == FeatureStatusKind.APPLIED) {
                    Text(
                        text = equalizerRouteLabel(state),
                        style = DapType.Value,
                        color = Dap.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DapDivider()
    }
}

/** Where an applied curve goes: "Resonancia", "USB Android", or "Altavoz · Android". */
@Composable
private fun equalizerRouteLabel(state: EqualizerScreenState): String = when {
    state.audio.resonance -> stringResource(R.string.eq_route_resonance)
    state.audio.dacConnected -> stringResource(R.string.eq_route_usb_android)
    else -> stringResource(R.string.eq_route_android, stringResource(routeNameRes(state.outputKind)))
}

/**
 * The response the engine will produce, drawn from the filters themselves.
 *
 * A flat curve is a straight line on the zero rule, so "nothing is being done" looks like nothing rather than like a
 * shape. The rules are the design's: zero, and ±15 dB, faint.
 */
@Composable
internal fun EqResponseGraph(curve: FloatArray) {
    val frequencies = remember { GraphicEqResponse.curveFrequenciesHz() }
    val description = stringResource(R.string.eq_response_description)
    // Captured in composition: a Canvas draw scope is not a composable context, so the palette has to be read out
    // here and closed over.
    val zeroRuleColor = Dap.interactiveTrack
    val edgeRuleColor = Dap.dividerFaint
    val curveColor = Dap.primaryText
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(EqGraphHeight)
            .semantics { contentDescription = description }
    ) {
        if (curve.isEmpty()) return@Canvas
        val zeroY = size.height / 2f
        // ±15 dB sits 8 dp short of the edges, as the design draws it.
        val half = size.height / 2f - 8.dp.toPx()
        val maxDb = EQ_RANGE_DB.toFloat()
        drawLine(edgeRuleColor, Offset(0f, zeroY - half), Offset(size.width, zeroY - half), strokeWidth = 1f)
        drawLine(edgeRuleColor, Offset(0f, zeroY + half), Offset(size.width, zeroY + half), strokeWidth = 1f)
        drawLine(zeroRuleColor, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1f)
        val path = Path()
        curve.forEachIndexed { index, db ->
            val x = size.width * index.toFloat() / (curve.size - 1).coerceAtLeast(1)
            val y = zeroY - (db.coerceIn(-maxDb, maxDb) / maxDb) * half
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = curveColor, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * The preset's name, in the user's language.
 *
 * The built-in presets are named by their stable storage key, so their display name is a string
 * resource rather than the uppercase token the model carries. A user's own preset has no resource
 * and keeps the name they gave it.
 */
@Composable
private fun equalizerPresetLabel(preset: EqualizerPreset): String {
    val res = when (preset.name) {
        "flat" -> R.string.eq_preset_flat
        "rock" -> R.string.eq_preset_rock
        "pop" -> R.string.eq_preset_pop
        "hip_hop" -> R.string.eq_preset_hip_hop
        "jazz" -> R.string.eq_preset_jazz
        "classical" -> R.string.eq_preset_classical
        "electronic" -> R.string.eq_preset_electronic
        "bass_boost" -> R.string.eq_preset_bass_boost
        "treble_boost" -> R.string.eq_preset_treble_boost
        "vocal" -> R.string.eq_preset_vocal
        "custom" -> R.string.eq_preset_custom
        else -> null
    }
    return if (res != null) stringResource(res) else preset.displayName
}
