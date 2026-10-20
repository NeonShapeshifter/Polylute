package com.lostf1sh.pixelplayeross.presentation.screens

import com.lostf1sh.pixelplayeross.ui.ds.dapDismissKeyboardActions
import com.lostf1sh.pixelplayeross.ui.ds.dapTextField
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import com.lostf1sh.pixelplayeross.data.dac.collisionSafeKeys
import com.lostf1sh.pixelplayeross.data.dac.DacIdentityScope
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacLiveStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfileUiItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfilesViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesViewModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapContentWidth
import com.lostf1sh.pixelplayeross.ui.ds.DapDestructiveAction
import com.lostf1sh.pixelplayeross.ui.ds.DapEmptyState
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import androidx.compose.ui.Alignment
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.ui.ds.DapScreen
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapState
import com.lostf1sh.pixelplayeross.ui.ds.DapStateKind
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapTitleMeta
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.DapValue
import com.lostf1sh.pixelplayeross.ui.ds.DapValueRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.lostf1sh.pixelplayeross.presentation.components.player.formatHz

/** The value the interface shows when a field the hardware did not report. */
private const val NO_VALUE = "—"

/**
 * State mapping for a saved DAC.
 *
 * Active is only ever reported when the runtime says this DAC is the one carrying audio; a profile
 * that merely exists is never active.
 */
internal fun DacLiveStatus.dapStateKind(): DapStateKind = when (this) {
    DacLiveStatus.ACTIVE -> DapStateKind.Active
    DacLiveStatus.CONNECTED -> DapStateKind.Connected
    DacLiveStatus.NOT_CONNECTED -> DapStateKind.NotConnected
}

@StringRes
internal fun DacLiveStatus.dapStateLabelRes(): Int = when (this) {
    DacLiveStatus.ACTIVE -> R.string.dac_status_active
    DacLiveStatus.CONNECTED -> R.string.dac_status_connected
    DacLiveStatus.NOT_CONNECTED -> R.string.dac_status_not_connected
}

/** The known-DACs route. B21: a host that joins the state and the actions for [KnownDacsContent]. */
@Composable
fun KnownDacsScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    viewModel: DacProfilesViewModel = hiltViewModel(),
) {
    val dacs by viewModel.uiState.collectAsStateWithLifecycle()
    val state = remember(dacs) { KnownDacsState.from(dacs) }
    val actions = remember(viewModel, onBack, onOpenProfile) { KnownDacsActionsImpl(viewModel, onBack, onOpenProfile) }
    KnownDacsContent(state, actions)
}

@Composable
fun KnownDacsContent(state: KnownDacsState, actions: KnownDacsActions) {
    LaunchedEffect(Unit) { actions.onRefresh() }

    DapBackScaffold(Modifier.fillMaxSize(), onBack = actions::onBack) {
        Column(Modifier.fillMaxSize().padding(top = DapSpacing.Md)) {
            DapContentWidth(Modifier.weight(1f)) {
                if (state.profiles.isEmpty()) {
                    Column(Modifier.fillMaxSize()) {
                        DapScreenTitle(
                            text = stringResource(R.string.dac_more),
                            modifier = Modifier.padding(start = DapSpacing.Gutter, end = DapSpacing.Gutter),
                        )
                        DapEmptyState(
                            title = stringResource(R.string.dac_none_known),
                            message = stringResource(R.string.dac_no_connected),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                } else {
                    val keyedProfiles = remember(state.profiles) {
                        state.profiles.collisionSafeKeys { it.profile.profileId }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = DapSpacing.Gutter,
                            end = DapSpacing.Gutter,
                            bottom = LocalDapPlayerShelfHeight.current + 16.dp,
                        ),
                    ) {
                        item {
                            DapScreenTitle(
                                text = stringResource(R.string.dac_more),
                                trailing = { DapTitleMeta(state.profiles.size.toString()) },
                                modifier = Modifier,
                            )
                            Spacer(Modifier.height(DapSpacing.Sm))
                        }
                        items(
                            // Guard, not the fix — see collisionSafeKeys. The repository
                            // canonicalises at its decode boundary, so ids are already unique here.
                            items = keyedProfiles,
                            key = { it.second },
                        ) { (item, _) ->
                            DacProfileListRow(
                                item = item,
                                onClick = { actions.onOpenProfile(item.profile.profileId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One saved DAC. The identifier is mono; the live state is a state word, never a badge. */
@Composable
private fun DacProfileListRow(item: DacProfileUiItem, onClick: () -> Unit) {
    DapMediaRow(
        title = item.profile.displayName,
        duration = String.format(
            Locale.US,
            "%04X:%04X",
            item.profile.vendorId,
            item.profile.productId,
        ),
        trailing = {
            DapState(
                kind = item.status.dapStateKind(),
                label = stringResource(item.status.dapStateLabelRes()),
            )
        },
        onClick = onClick,
    )
}

/**
 * A DAC profile's route. B21: a host that joins what its ViewModels publish into [DacProfileScreenState] and
 * hands [DacProfileScreenContent] the actions.
 */
@Composable
fun DacProfileScreen(
    profileId: String,
    onBack: () -> Unit,
    /**
     * Opens the headphones this DAC is remembered with, through the existing known-headphones
     * flow. Supplied by the navigation graph.
     */
    onChangeHeadphone: (() -> Unit)? = null,
    viewModel: DacProfilesViewModel = hiltViewModel(),
    headphoneViewModel: HeadphoneProfilesViewModel = hiltViewModel(),
) {
    val dacs by viewModel.uiState.collectAsStateWithLifecycle()
    val headphones by headphoneViewModel.uiState.collectAsStateWithLifecycle()
    val state = remember(profileId, dacs, headphones, onChangeHeadphone != null) {
        DacProfileScreenState.from(profileId, dacs, headphones, canChangeHeadphone = onChangeHeadphone != null)
    }
    val actions = remember(viewModel, onBack, onChangeHeadphone) {
        DacProfileScreenActionsImpl(viewModel, onBack) { onChangeHeadphone?.invoke() }
    }
    DacProfileScreenContent(state, actions)
}

@Composable
fun DacProfileScreenContent(state: DacProfileScreenState, actions: DacProfileScreenActions) {
    val item = state.item
    var showRename by remember { mutableStateOf(false) }
    var showForget by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { actions.onRefresh() }

    DapBackScaffold(Modifier.fillMaxSize(), onBack = actions::onBack) {
        Column(Modifier.fillMaxSize().padding(top = DapSpacing.Md)) {
            DapContentWidth(Modifier.weight(1f)) {
                if (item == null) {
                    DapEmptyState(
                        title = stringResource(R.string.dac_profile_title),
                        message = stringResource(R.string.dac_profile_not_found),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val profile = item.profile
                    // Advertised capabilities are live diagnostics: they describe the DAC that is
                    // attached right now, so they are only stated for the profile in the current
                    // session. A stored profile gets no capability rows rather than stale ones.
                    val isConnectedNow = state.isConnectedNow
                    // Resolved through the stored association, which is keyed by DAC profile id (see
                    // DacProfileScreenState.from): a name comparison stands in for an identity lookup
                    // and breaks with two units of one model.
                    val headphone = state.headphone

                    Column(
                        Modifier
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = DapSpacing.Gutter)
                            .padding(
                                bottom = LocalDapPlayerShelfHeight.current + 16.dp,
                            ),
                    ) {
                        Spacer(Modifier.height(DapSpacing.Sm))
                        DapScreenTitle(
                            text = profile.displayName,
                            modifier = Modifier,
                            trailing = {
                                DapState(
                                    kind = item.status.dapStateKind(),
                                    label = stringResource(item.status.dapStateLabelRes()),
                                )
                            },
                        )

                        Spacer(Modifier.height(DapSpacing.SectionGap))
                        DapGroup(title = stringResource(R.string.dac_device_title)) {
                            RecordRow(
                                label = stringResource(R.string.dac_manufacturer),
                                value = profile.detectedManufacturer,
                            )
                            RecordRow(
                                label = stringResource(R.string.dac_product),
                                value = profile.detectedProduct,
                            )
                            DapValueRow(label = stringResource(R.string.dac_usb_id)) {
                                DapValue(
                                    String.format(
                                        Locale.US,
                                        "%04X:%04X",
                                        profile.vendorId,
                                        profile.productId,
                                    )
                                )
                            }
                            profile.serialNumber?.let { serial ->
                                DapValueRow(label = stringResource(R.string.dac_serial)) {
                                    DapValue(serial)
                                }
                            }
                            if (profile.createdAtEpochMs > 0L) {
                                DapValueRow(label = stringResource(R.string.dac_first_seen)) {
                                    DapValue(formatSeenDate(profile.createdAtEpochMs))
                                }
                            }
                            if (profile.identityScope == DacIdentityScope.MODEL_SCOPED) {
                                DapValueRow(label = stringResource(R.string.dac_identity)) {
                                    DapValue(
                                        text = stringResource(R.string.dac_identity_model_scoped),
                                        color = Dap.secondaryText,
                                    )
                                }
                            }
                        }

                        if (isConnectedNow &&
                            (state.advertisedRates.isNotEmpty() ||
                                state.bestBitDepth != null)
                        ) {
                            Spacer(Modifier.height(DapSpacing.GroupGap))
                            DapGroup(title = stringResource(R.string.dac_capabilities)) {
                                if (state.advertisedRates.isNotEmpty()) {
                                    DapValueRow(
                                        label = stringResource(R.string.dac_advertised_rates)
                                    ) {
                                        DapValue(
                                            // The shared rate formatter, like every other rate the
                                            // product prints. This inlined `it / 1000.0`, which
                                            // renders 48 kHz as "48.0 kHz" and was the last place a
                                            // sample rate could still appear in the old shape.
                                            state.advertisedRates.joinToString(" / ") {
                                                formatHz(it)
                                            },
                                            maxLines = Int.MAX_VALUE
                                        )
                                    }
                                }
                                state.bestBitDepth?.let { depth ->
                                    DapValueRow(
                                        label = stringResource(R.string.dac_advertised_depth)
                                    ) {
                                        DapValue("$depth-bit")
                                    }
                                }
                            }
                        }

                        // AUD-119: a DAC's own volume (the Melody's, measured) is not exposed over USB.
                        Spacer(Modifier.height(DapSpacing.GroupGap))
                        Text(
                            text = stringResource(R.string.dac_own_volume_not_controllable),
                            style = DapType.Note,
                            color = Dap.tertiaryText,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(Modifier.height(DapSpacing.GroupGap))
                        DapGroup(
                            title = stringResource(R.string.headphone_settings_title),
                            trailing = if (state.canChangeHeadphone) {
                                {
                                    DapTextAction(
                                        label = stringResource(R.string.presentation_batch_f_change),
                                        onClick = actions::onChangeHeadphone,
                                    )
                                }
                            } else {
                                null
                            },
                        ) {
                            if (headphone == null) {
                                DapValueRow(label = stringResource(R.string.headphone_no_selected))
                            } else {
                                DapValueRow(label = headphone.profile.displayName) {
                                    if (headphone.isSelected) {
                                        DapState(
                                            kind = DapStateKind.Selected,
                                            label = stringResource(
                                                R.string.headphone_status_selected
                                            ),
                                        )
                                    }
                                }
                            }
                        }

                        if (item.isSaved) {
                            Spacer(Modifier.height(DapSpacing.SectionGap))
                            Row(horizontalArrangement = Arrangement.spacedBy(DapSpacing.Lg)) {
                                DapTextAction(
                                    label = stringResource(R.string.dac_rename),
                                    onClick = { showRename = true },
                                )
                                DapDestructiveAction(
                                    label = stringResource(R.string.dac_forget),
                                    onClick = { showForget = true },
                                )
                            }
                        }

                        Spacer(Modifier.height(DapSpacing.GroupGap))
                    }
                }
            }
        }
    }

    if (showRename && item != null) {
        RenameDacDialog(
            currentName = item.profile.displayName,
            onDismiss = { showRename = false },
            onConfirm = { name ->
                actions.onRename(item.profile.profileId, name)
                showRename = false
            },
        )
    }
    if (showForget && item != null) {
        AlertDialog(
            onDismissRequest = { showForget = false },
            title = {
                // An alert is a window of its own, so the activity's volume-key path does not reach
                // it. The bridge routes Volume Up/Down to the same authority the activity uses.
                ModalInputBridge()
                Text(stringResource(R.string.dac_forget_title))
            },
            text = { Text(stringResource(R.string.dac_forget_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showForget = false
                    actions.onForget(item.profile.profileId)
                }) { Text(stringResource(R.string.dac_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { showForget = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** A device field: the label is descriptive, the value is mono, absent reads as unavailable. */
@Composable
private fun RecordRow(label: String, value: String?) {
    DapValueRow(label = label) {
        DapValue(
            text = value ?: NO_VALUE,
            color = if (value == null) Dap.disabledText else Dap.primaryText,
        )
    }
}

/** The date this profile was first written down. */
private fun formatSeenDate(epochMs: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMs))

@Composable
private fun RenameDacDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dac_rename)) },
        text = {
            // An alert is a window of its own, so the activity's volume-key path does not reach
            // it. The bridge routes Volume Up/Down to the same authority the activity uses.
            ModalInputBridge()
            OutlinedTextField(
                value = name,
                keyboardActions = dapDismissKeyboardActions(),
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.dac_name)) },
                modifier = Modifier.dapTextField(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
