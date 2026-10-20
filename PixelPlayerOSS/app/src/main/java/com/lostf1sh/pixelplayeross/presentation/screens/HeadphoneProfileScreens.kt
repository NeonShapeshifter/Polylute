package com.lostf1sh.pixelplayeross.presentation.screens

import com.lostf1sh.pixelplayeross.ui.ds.dapDismissKeyboardActions
import com.lostf1sh.pixelplayeross.ui.ds.dapTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.saveable.rememberSaveable
import com.lostf1sh.pixelplayeross.data.dac.collisionSafeKeys
import com.lostf1sh.pixelplayeross.data.equalizer.EqualizerPreset
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqMode
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerViewModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapDestructiveAction
import com.lostf1sh.pixelplayeross.ui.ds.DapEmptyState
import com.lostf1sh.pixelplayeross.ui.ds.DapEqEditor
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapScreen
import com.lostf1sh.pixelplayeross.ui.ds.DapSectionHeader
import com.lostf1sh.pixelplayeross.ui.ds.DapSegmentedControl
import com.lostf1sh.pixelplayeross.ui.ds.DapNavRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSliderRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSwitchRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import com.lostf1sh.pixelplayeross.ui.ds.DapState
import com.lostf1sh.pixelplayeross.ui.ds.DapStateKind
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.DapValue
import com.lostf1sh.pixelplayeross.ui.ds.DapValueRow
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SafetyVolumeViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.safetyMaxOutputCompatible
import com.lostf1sh.pixelplayeross.presentation.viewmodel.safetyMaxOutputState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.EqualizerOutputState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.volume.SafetyMaxCalibrationResult
import androidx.annotation.StringRes
import kotlin.math.roundToInt

/** The known-headphones route. B21: a host that joins the state and the actions for [KnownHeadphonesContent]. */
@Composable
fun KnownHeadphonesScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    viewModel: HeadphoneProfilesViewModel = hiltViewModel(),
) {
    val profiles by viewModel.uiState.collectAsStateWithLifecycle()
    val state = remember(profiles) { KnownHeadphonesState.from(profiles) }
    val actions = remember(viewModel, onBack, onOpenProfile) { KnownHeadphonesActionsImpl(viewModel, onBack, onOpenProfile) }
    KnownHeadphonesContent(state, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnownHeadphonesContent(state: KnownHeadphonesState, actions: KnownHeadphonesActions) {
    var showCreate by remember { mutableStateOf(false) }
    DapBackScaffold(Modifier.fillMaxSize(), onBack = actions::onBack) {
        val keyedProfiles = remember(state.profiles) {
            state.profiles.collisionSafeKeys { it.profile.profileId }
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = DapSpacing.Md,
                bottom = LocalDapPlayerShelfHeight.current + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "headphones_title") {
                DapScreenTitle(
                    text = stringResource(R.string.headphone_more),
                    modifier = Modifier.padding(
                        start = 0.dp,
                        end = 0.dp,
                        bottom = DapSpacing.Sm,
                    ),
                )
            }
            item {
                FilledTonalButton(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.LibraryAdd, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.headphone_new))
                }
            }
            if (state.profiles.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Rounded.Headphones, null)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.headphone_none_known))
                    }
                }
            } else {
                items(
                    // Same guard as the DAC list — see collisionSafeKeys.
                    items = keyedProfiles,
                    key = { it.second },
                ) { (item, _) ->
                    HeadphoneProfileListRow(item, onClick = { actions.onOpenProfile(item.profile.profileId) })
                }
            }
        }
    }
    if (showCreate) {
        HeadphoneNameDialog(
            title = stringResource(R.string.headphone_new),
            initialName = "",
            confirmLabel = stringResource(R.string.headphone_create),
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                actions.onCreate(name)
                showCreate = false
            },
        )
    }
}

@Composable
private fun HeadphoneProfileListRow(item: HeadphoneProfileUiItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (item.isSelected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Rounded.Headphones, null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.profile.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.isSelected) {
                    Text(
                        selectedHeadphoneLabel(item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A headphone profile's route. B21: a host that joins what its ViewModels publish into
 * [HeadphoneProfileScreenState] and hands [HeadphoneProfileScreenContent] the actions.
 */
@Composable
fun HeadphoneProfileScreen(
    profileId: String,
    onBack: () -> Unit,
    onOpenEqualizer: (String) -> Unit,
    onOpenSafetyMax: (String) -> Unit,
    viewModel: HeadphoneProfilesViewModel = hiltViewModel(),
    volumeViewModel: SafetyVolumeViewModel = hiltViewModel(),
    equalizerViewModel: EqualizerViewModel = hiltViewModel(),
) {
    val profiles by viewModel.uiState.collectAsStateWithLifecycle()
    val volumeState by volumeViewModel.state.collectAsStateWithLifecycle()
    val eqState by equalizerViewModel.uiState.collectAsStateWithLifecycle()
    val audio by equalizerViewModel.audioStatus.collectAsStateWithLifecycle()
    val state = remember(profileId, profiles, eqState, audio, volumeState) {
        HeadphoneProfileScreenState.from(profileId, profiles, eqState, audio, volumeState)
    }
    val actions = remember(viewModel, equalizerViewModel, onBack, onOpenEqualizer, onOpenSafetyMax) {
        HeadphoneProfileScreenActionsImpl(
            profiles = viewModel,
            eq = equalizerViewModel,
            back = onBack,
            openEqualizer = onOpenEqualizer,
            openSafetyMax = onOpenSafetyMax,
        )
    }
    HeadphoneProfileScreenContent(state, actions)
}

@Composable
fun HeadphoneProfileScreenContent(state: HeadphoneProfileScreenState, actions: HeadphoneProfileScreenActions) {
    val uiItem = state.item
    val eqState = state.eq
    var showRename by remember { mutableStateOf(false) }
    var showForget by remember { mutableStateOf(false) }
    var selectedBand by rememberSaveable { mutableStateOf<Int?>(null) }
    // Resolved here rather than inside the control's label lambda, which is not a composable
    // scope.
    val modeLabels = mapOf(
        EqModeOption.Off to stringResource(EqModeOption.Off.labelRes),
        EqModeOption.Ten to stringResource(EqModeOption.Ten.labelRes),
        EqModeOption.Fifteen to stringResource(EqModeOption.Fifteen.labelRes),
    )

    DapBackScaffold(Modifier.fillMaxSize(), onBack = actions::onBack) {
        Column(Modifier.fillMaxSize().padding(top = DapSpacing.Md)) {

            if (uiItem == null) {
                DapEmptyState(
                    title = stringResource(R.string.headphone_profile_not_found),
                    message = "",
                    modifier = Modifier.weight(1f)
                )
                return@Column
            }

            val profile = uiItem.profile
            // The equalizer authority is the *selected* profile's effective state, so a profile
            // that is not selected must not offer edits that would land on a different pair.
            val isSelected = uiItem.isSelected

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = DapSpacing.MaxContentWidth),
                    contentPadding = PaddingValues(
                        start = DapSpacing.Gutter,
                        end = DapSpacing.Gutter,
                        bottom = LocalDapPlayerShelfHeight.current + DapSpacing.SectionGap,
                    ),
                    verticalArrangement = Arrangement.spacedBy(DapSpacing.SectionGap),
                ) {
                    item {
                        Column(Modifier.padding(top = DapSpacing.Sm)) {
                            DapSectionHeader(stringResource(R.string.headphone_title_label))
                            Spacer(Modifier.height(DapSpacing.Xs))
                            Text(
                                text = profile.displayName,
                                style = DapType.Title,
                                color = Dap.primaryText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(DapSpacing.Sm))
                            if (isSelected) {
                                DapState(
                                    kind = DapStateKind.Selected,
                                    label = stringResource(R.string.headphone_state_selected)
                                )
                            } else {
                                DapState(
                                    kind = DapStateKind.Off,
                                    label = stringResource(R.string.headphone_state_not_selected)
                                )
                            }
                        }
                    }

                    item {
                        // Processing is everything that shapes the signal on the Android path.
                        //
                        // The equalizer is a link rather than an editor. It used to be edited here
                        // *and* on its own screen, from two copies of the same view model, which is
                        // how the same profile ended up showing one curve in one place and another
                        // curve in the other. There is now one editor and this row points at it.
                        //
                        // Bass Boost and Virtualizer are android.media.audiofx session effects, not
                        // equalizer bands, so they live here rather than inside the EQ editor.
                        DapGroup(title = stringResource(R.string.headphone_processing_title)) {
                            DapNavRow(
                                label = stringResource(R.string.settings_category_equalizer_title),
                                description = state.eqSummary?.let { equalizerProcessingSummary(it) },
                                enabled = isSelected,
                                divider = profile.equalizer.enabled,
                                onClick = { actions.onOpenEqualizer(profile.profileId) },
                            )

                            if (profile.equalizer.enabled) {
                                DapNavRow(
                                    label = stringResource(R.string.headphone_eq_preset_label),
                                    description = state.presetForRow?.let { equalizerPresetLabel(it) },
                                    divider = true,
                                )
                            }

                            DapSwitchRow(
                                label = stringResource(R.string.presentation_batch_d_eq_bass_boost),
                                description = effectSupportNote(eqState.isBassBoostSupported, eqState.bassBoostEnabled, eqState.bassBoostStrength),
                                checked = eqState.bassBoostEnabled,
                                enabled = eqState.isBassBoostSupported,
                                divider = true,
                                onCheckedChange = { actions.onBassBoostEnabledChange(it) },
                            )
                            if (eqState.bassBoostEnabled && eqState.isBassBoostSupported) {
                                DapSliderRow(
                                    label = stringResource(R.string.headphone_processing_strength),
                                    value = eqState.bassBoostStrength / 1000f,
                                    valueText = formatEffectStrength(eqState.bassBoostStrength),
                                    onValueChange = { actions.onBassBoostStrengthChange((it * 1000f).roundToInt()) },
                                    divider = true,
                                )
                            }

                            DapSwitchRow(
                                label = stringResource(R.string.presentation_batch_d_eq_virtualizer),
                                description = effectSupportNote(eqState.isVirtualizerSupported, eqState.virtualizerEnabled, eqState.virtualizerStrength),
                                checked = eqState.virtualizerEnabled,
                                enabled = eqState.isVirtualizerSupported,
                                divider = true,
                                onCheckedChange = { actions.onVirtualizerEnabledChange(it) },
                            )
                            if (eqState.virtualizerEnabled && eqState.isVirtualizerSupported) {
                                DapSliderRow(
                                    label = stringResource(R.string.headphone_processing_strength),
                                    value = eqState.virtualizerStrength / 1000f,
                                    valueText = formatEffectStrength(eqState.virtualizerStrength),
                                    onValueChange = { actions.onVirtualizerStrengthChange((it * 1000f).roundToInt()) },
                                    divider = true,
                                )
                            }

                            DapSwitchRow(
                                label = stringResource(R.string.presentation_batch_d_eq_loudness),
                                description = effectSupportNote(eqState.isLoudnessEnhancerSupported, eqState.loudnessEnhancerEnabled, eqState.loudnessEnhancerStrength),
                                checked = eqState.loudnessEnhancerEnabled,
                                enabled = eqState.isLoudnessEnhancerSupported,
                                divider = false,
                                onCheckedChange = { actions.onLoudnessEnhancerEnabledChange(it) },
                            )
                            if (eqState.loudnessEnhancerEnabled && eqState.isLoudnessEnhancerSupported) {
                                DapSliderRow(
                                    label = stringResource(R.string.headphone_processing_strength),
                                    value = eqState.loudnessEnhancerStrength / 1000f,
                                    valueText = formatEffectStrength(eqState.loudnessEnhancerStrength),
                                    onValueChange = { actions.onLoudnessEnhancerStrengthChange((it * 1000f).roundToInt()) },
                                    divider = false,
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            text = stringResource(R.string.profile_audio_route_note),
                            style = DapType.Note,
                            color = Dap.tertiaryText,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        val safety = state.safety ?: return@item
                        DapGroup(title = stringResource(R.string.safety_max_title)) {
                            Spacer(Modifier.height(DapSpacing.Md))
                            DapValueRow(
                                label = stringResource(R.string.profile_audio_saved_configuration)
                            ) {
                                DapState(
                                    kind = if (safety.enabled) {
                                        DapStateKind.Selected
                                    } else {
                                        DapStateKind.Off
                                    },
                                    label = stringResource(
                                        if (safety.enabled) R.string.headphone_eq_on
                                        else R.string.headphone_eq_off
                                    )
                                )
                            }
                            DapValueRow(label = stringResource(R.string.safety_max_saved_limit)) {
                                val ceiling = safety.ceilingRaw
                                DapValue(
                                    text = when {
                                        ceiling != null ->
                                            stringResource(
                                                R.string.headphone_safety_ceiling_value,
                                                ceiling
                                            )
                                        safety.hasOtherOutputCalibration ->
                                            stringResource(R.string.safety_max_other_output)
                                        else -> stringResource(R.string.safety_max_not_set)
                                    },
                                    color = if (ceiling != null) Dap.primaryText else Dap.disabledText
                                )
                            }
                            Spacer(Modifier.height(DapSpacing.Md))
                            Text(
                                stringResource(R.string.profile_audio_current_effect),
                                style = DapType.Caption,
                                color = Dap.secondaryText,
                            )
                            Spacer(Modifier.height(DapSpacing.Xs))
                            SafetyMaxOutputStatus(safety.outputState, safety.backendKind)
                            Spacer(Modifier.height(DapSpacing.Md))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                DapTextAction(
                                    label = stringResource(R.string.headphone_manage),
                                    onClick = { actions.onOpenSafetyMax(profile.profileId) },
                                    enabled = isSelected
                                )
                            }
                        }
                    }

                    // B30: the presets of the DAC's own EQ that belong to these headphones, each with the DAC it was made for. Found here
                    // and reused by choice (the row opens the equalizer, which is where they are applied): nothing is applied from a list.
                    if (state.dacEqPresets.isNotEmpty()) {
                        item {
                            DapGroup(title = stringResource(R.string.headphone_dac_eq_presets_title)) {
                                state.dacEqPresets.forEach { row ->
                                    DapNavRow(
                                        label = row.preset.name,
                                        description = buildString {
                                            append(stringResource(R.string.dac_eq_preset_made_for, row.preset.dacName))
                                            when {
                                                row.forCurrentDac -> append(" · ").append(stringResource(R.string.headphone_dac_eq_preset_this_dac))
                                                row.fit != null -> append(" · ").append(fitText(row.fit))
                                            }
                                        },
                                        enabled = isSelected,
                                        onClick = { actions.onOpenEqualizer(profile.profileId) },
                                    )
                                }
                            }
                        }
                    }

                    uiItem.associatedDacName?.let { dacName ->
                        item {
                            DapGroup(title = stringResource(R.string.headphone_dac_section)) {
                                Spacer(Modifier.height(DapSpacing.Md))
                                DapValueRow(
                                    label = stringResource(R.string.headphone_status_associated_dac)
                                ) {
                                    DapValue(text = dacName, color = Dap.secondaryText)
                                }
                            }
                        }
                    }

                    if (!isSelected) {
                        item {
                            DapPrimaryButton(
                                label = stringResource(R.string.headphone_select),
                                onClick = { actions.onSelect(profile.profileId) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = DapSpacing.Sm),
                            horizontalArrangement = Arrangement.spacedBy(DapSpacing.SectionGap)
                        ) {
                            DapTextAction(
                                label = stringResource(R.string.headphone_rename),
                                onClick = { showRename = true }
                            )
                            DapDestructiveAction(
                                label = stringResource(R.string.headphone_forget),
                                onClick = { showForget = true }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRename) {
        HeadphoneNameDialog(
            title = stringResource(R.string.headphone_rename),
            initialName = uiItem?.profile?.displayName.orEmpty(),
            confirmLabel = stringResource(R.string.action_save),
            onDismiss = { showRename = false },
            onConfirm = { name ->
                uiItem?.let { actions.onRename(it.profile.profileId, name) }
                showRename = false
            }
        )
    }

    if (showForget && uiItem != null) {
        val forgetId = uiItem.profile.profileId
        AlertDialog(
            onDismissRequest = { showForget = false },
            title = {
                // An alert is a window of its own, so the activity's volume-key path does not reach
                // it. The bridge routes Volume Up/Down to the same authority the activity uses.
                ModalInputBridge()
                Text(stringResource(R.string.headphone_forget_confirm_title))
            },
            text = { Text(stringResource(R.string.headphone_forget_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showForget = false
                    actions.onForget(forgetId)
                }) { Text(stringResource(R.string.headphone_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { showForget = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/** The three-way control the real model supports: enabled off, or one of the two band tables. */
internal enum class EqModeOption(val labelRes: Int) {
    Off(R.string.headphone_eq_mode_off),
    Ten(R.string.headphone_eq_mode_10),
    Fifteen(R.string.headphone_eq_mode_15),
}

internal val EQ_MODE_OPTIONS = listOf(
    EqModeOption.Off,
    EqModeOption.Ten,
    EqModeOption.Fifteen,
)

private fun currentEqMode(state: EqualizerUiState): EqModeOption = when {
    !state.isEnabled -> EqModeOption.Off
    state.mode == HeadphoneEqMode.GRAPHIC_15 -> EqModeOption.Fifteen
    else -> EqModeOption.Ten
}

private fun formatGainDb(gain: Int): String =
    if (gain > 0) "+$gain dB" else "$gain dB"

/** The Safety Max route. B21: a host that joins the state and the actions for [SafetyMaxScreenContent]. */
@Composable
fun SafetyMaxVolumeScreen(
    profileId: String,
    onBack: () -> Unit,
    headphoneViewModel: HeadphoneProfilesViewModel = hiltViewModel(),
    volumeViewModel: SafetyVolumeViewModel = hiltViewModel(),
) {
    val headphones by headphoneViewModel.uiState.collectAsStateWithLifecycle()
    val volumeState by volumeViewModel.state.collectAsStateWithLifecycle()
    val state = remember(profileId, headphones, volumeState) { SafetyMaxScreenState.from(profileId, headphones, volumeState) }
    val actions = remember(volumeViewModel, onBack) { SafetyMaxScreenActionsImpl(volumeViewModel, onBack) }
    SafetyMaxScreenContent(state, actions)
}

@Composable
fun SafetyMaxScreenContent(state: SafetyMaxScreenState, actions: SafetyMaxScreenActions) {
    val profileId = state.profileId
    val volume = state.volume
    val profile = state.profile
    // RT-01: why the last tap saved nothing, as the controller answered it; it is forgotten with the output it was asked on.
    var notSaved by remember(profileId, volume.backend.outputContextId, volume.backend.kind, volume.selectedProfileId) {
        mutableStateOf<SafetyMaxCalibrationResult?>(null)
    }
    val outputCompatible = safetyMaxOutputCompatible(volume.backend)
    DapBackScaffold(Modifier.fillMaxSize(), onBack = actions::onBack) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = DapSpacing.Md,
                bottom = LocalDapPlayerShelfHeight.current + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "safety_max_title") {
                DapScreenTitle(
                    text = stringResource(R.string.safety_max_title),
                    modifier = Modifier.padding(
                        start = 0.dp,
                        end = 0.dp,
                        bottom = DapSpacing.Sm,
                    ),
                )
            }
            item {
                Text(
                    stringResource(R.string.safety_max_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // AUD-119: the DAC's front-panel volume is outside what Safety Max can see or cap.
                Text(
                    stringResource(R.string.safety_max_dac_own_volume_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                HeadphoneProfileSection(stringResource(R.string.safety_max_current_output)) {
                    Text(volume.backend.outputLabel, fontWeight = FontWeight.SemiBold)
                    if (profile != null) {
                        SafetyMaxOutputStatus(safetyMaxOutputState(profile, volume), volume.backend.kind)
                    }
                    if (outputCompatible) {
                        Text(
                            stringResource(
                                R.string.safety_max_current_status,
                                volume.displayPercent,
                                volume.backend.rawLevel,
                                volume.backend.rawMax,
                            ),
                            color = if (volume.displayPercent > 100) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (volume.backend.controllable) {
                        Slider(
                            value = volume.backend.rawLevel.toFloat(),
                            onValueChange = { actions.onSetRawVolume(it.toInt()) },
                            valueRange = volume.backend.rawMin.toFloat()..volume.backend.rawMax.toFloat(),
                            steps = (volume.backend.rawMax - volume.backend.rawMin - 1).coerceAtLeast(0),
                        )
                    }
                }
            }
            if (profile != null) {
                item(key = "safety_max_saved") {
                    HeadphoneProfileSection(stringResource(R.string.profile_audio_saved_configuration)) {
                        Text(profile.displayName, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.safety_max_saved_enabled), fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.safety_max_saved_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = profile.safetyMax.enabled,
                                onCheckedChange = actions::onSafetyMaxEnabledChange,
                                enabled = volume.selectedProfileId == profileId && profile.safetyMax.calibrations.isNotEmpty(),
                            )
                        }
                        Text(stringResource(R.string.safety_max_saved_limit), style = MaterialTheme.typography.bodySmall)
                        val saved = profile.safetyMax.calibrationFor(volume.backend.outputContextId, volume.backend.kind)
                        Text(when {
                            saved != null -> stringResource(R.string.headphone_safety_ceiling_value, saved.ceilingRaw)
                            profile.safetyMax.calibrations.isNotEmpty() -> stringResource(R.string.safety_max_other_output)
                            else -> stringResource(R.string.safety_max_not_set)
                        })
                    }
                }
            }
            if (profile != null) {
                item {
                    // AUD-119: the DAC's internal volume is invisible over USB; calibrate at its worst case.
                    Text(
                        stringResource(when {
                            volume.selectedProfileId != profileId -> R.string.profile_audio_not_selected
                            !outputCompatible -> R.string.safety_max_calibration_unavailable
                            !volume.backend.levelVerified -> R.string.safety_max_level_unconfirmed
                            else -> R.string.safety_max_calibrate_dac_at_max
                        }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = DapSpacing.Sm),
                    )
                    FilledTonalButton(
                        onClick = {
                            actions.onCalibrate { result -> notSaved = result.takeUnless { it == SafetyMaxCalibrationResult.SAVED } }
                        },
                        enabled = outputCompatible && volume.selectedProfileId == profileId,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.safety_max_use_current)) }
                    safetyMaxNotSavedMessage(notSaved)?.let { message ->
                        Text(
                            stringResource(message),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/** RT-01: what to tell the listener when the calibration saved nothing; each refusal has its own words, none stands in for another. */
@StringRes
internal fun safetyMaxNotSavedMessage(result: SafetyMaxCalibrationResult?): Int? = when (result) {
    null, SafetyMaxCalibrationResult.SAVED -> null
    SafetyMaxCalibrationResult.LEVEL_ZERO -> R.string.safety_max_zero_rejected
    SafetyMaxCalibrationResult.LEVEL_UNCONFIRMED -> R.string.safety_max_not_saved_unconfirmed
    SafetyMaxCalibrationResult.OUTPUT_CHANGED -> R.string.safety_max_not_saved_output_changed
    SafetyMaxCalibrationResult.NOT_AVAILABLE -> R.string.safety_max_calibration_unavailable
}

@Composable
private fun HeadphoneProfileSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun selectedHeadphoneLabel(item: HeadphoneProfileUiItem): String =
    item.associatedDacName?.let { stringResource(R.string.headphone_status_selected_for, it) }
        ?: stringResource(R.string.headphone_status_selected)

@Composable
private fun HeadphoneNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // An alert is a window of its own, so the activity's volume-key path does not reach
            // it. The bridge routes Volume Up/Down to the same authority the activity uses.
            ModalInputBridge()
            OutlinedTextField(
                value = name,
                keyboardActions = dapDismissKeyboardActions(),
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.headphone_name)) },
                modifier = Modifier.dapTextField(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * What the equalizer row says: the saved mode, and what it does on the current output.
 *
 * One line of prose rather than a mono value, because both parts are names rather than
 * measurements. With the EQ off there is nothing to add: it says "off" once (B21; it used to
 * say "Saved: Off · OFF").
 */
@Composable
private fun equalizerProcessingSummary(summary: EqualizerSummary): String {
    val output = summary.output ?: return stringResource(R.string.profile_audio_saved_eq_off)
    return stringResource(R.string.profile_audio_saved_eq_summary, stringResource(summary.modeRes), equalizerOutputLabel(output))
}

/**
 * What the profile page says about one Android session effect.
 *
 * A device that cannot run the effect says so instead of offering a switch that does nothing.
 */
@Composable
private fun effectSupportNote(supported: Boolean, enabled: Boolean, strength: Float): String = when {
    !supported -> stringResource(R.string.presentation_batch_d_eq_effect_not_supported_device)
    !enabled -> stringResource(R.string.headphone_processing_effect_off)
    else -> formatEffectStrength(strength)
}

/** The effect strength as the engine stores it: 0..1000, shown as a percentage. */
private fun formatEffectStrength(strength: Float): String =
    "${strength.coerceIn(0f, 1000f).roundToInt() / 10}%"

/**
 * The preset's name, in the user's language.
 *
 * Built-in presets are named by their stable key, so their label is a string resource; a preset the
 * user named themselves keeps the name they gave it.
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
