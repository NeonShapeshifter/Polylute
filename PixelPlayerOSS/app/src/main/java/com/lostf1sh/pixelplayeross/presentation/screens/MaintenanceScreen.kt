package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.decent.usbaudio.media3.UsbDirectSinkState
import com.lostf1sh.pixelplayeross.presentation.model.labelRes
import com.lostf1sh.pixelplayeross.BuildConfig
import com.lostf1sh.pixelplayeross.R
import androidx.compose.ui.text.style.TextAlign
import com.lostf1sh.pixelplayeross.data.intelligence.TrackIntelligenceDiagnostic
import com.lostf1sh.pixelplayeross.data.collections.CollectionDiagnostic
import com.lostf1sh.pixelplayeross.data.collections.RuleEvaluation
import com.lostf1sh.pixelplayeross.data.metadata.MetadataDiagnostic
import com.lostf1sh.pixelplayeross.data.service.player.UsbDirectAudioDiagnostics
import com.lostf1sh.pixelplayeross.data.diagnostics.DeviceDiagnosticSnapshot
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverride
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrideKind
import com.lostf1sh.pixelplayeross.data.diagnostics.MaintenanceOverrides
import com.lostf1sh.pixelplayeross.dap.admin.DapDeviceOwnerController
import com.lostf1sh.pixelplayeross.dap.admin.DapDeviceOwnerState
import com.lostf1sh.pixelplayeross.dap.mode.DedicatedProvisioningEvidence
import com.lostf1sh.pixelplayeross.dap.mode.ProductLevel
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeResolver
import com.lostf1sh.pixelplayeross.presentation.settings.MaintenanceToolsActionsImpl
import com.lostf1sh.pixelplayeross.presentation.settings.MaintenanceToolsSection
import com.lostf1sh.pixelplayeross.presentation.settings.MaintenanceToolsState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsViewModel
import com.lostf1sh.pixelplayeross.ui.ds.DapScreen
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapTopBar
import kotlinx.collections.immutable.toImmutableList
import androidx.compose.ui.text.style.TextOverflow

/**
 * Maintenance: the logs and the technical tools (B32-Q6). Reached from Settings, System, as an ordinary page: **there is no PIN** (the
 * owner chose that on 2026-10-19, knowing that without one anyone holding the DAP can leave dedicated mode) and nothing here lifts
 * Lock Task: the page only reads and records.
 *
 * What it holds: the device's state and Lock Task / Device Owner readouts, the package and version, the audio route, the hardware picture
 * and its event log, the debug overrides, the per-track identity, metadata and collection detail, the device diagnostics report and the
 * three regeneration tools that used to be the Developer options page. What it no longer holds is everything that *does* something to
 * the device or the library (Android's own screens, rebuilding the database, leaving the DAP): those are the System page.
 */
@Composable
fun MaintenanceScreen(
    onExit: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    playerViewModel: PlayerViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    statsViewModel: StatsViewModel = hiltViewModel(),
) {
    MaintenancePanel(
        playerViewModel = playerViewModel,
        settingsViewModel = settingsViewModel,
        statsViewModel = statsViewModel,
        onExit = onExit,
        onOpenDiagnostics = onOpenDiagnostics,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaintenancePanel(
    playerViewModel: PlayerViewModel,
    settingsViewModel: SettingsViewModel,
    statsViewModel: StatsViewModel,
    onExit: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val diagnostics by settingsViewModel.usbAudioDiagnostics.collectAsStateWithLifecycle()
    val route by settingsViewModel.audioRouteState.collectAsStateWithLifecycle()
    val hardwareDiagnostic by settingsViewModel.deviceDiagnostics.collectAsStateWithLifecycle()
    val overrides by settingsViewModel.maintenanceOverrides.collectAsStateWithLifecycle()
    val trackDiagnostic by settingsViewModel.trackDiagnostic.collectAsStateWithLifecycle()
    val metadataDiagnostic by settingsViewModel.metadataDiagnostic.collectAsStateWithLifecycle()
    val collectionDiagnostic by settingsViewModel.collectionDiagnostic.collectAsStateWithLifecycle()
    val paletteTargets by playerViewModel.paletteRegenerationTargets.collectAsStateWithLifecycle()
    // Refreshed on demand rather than observed: the diagnostic is engineering detail, and opening
    // Maintenance should not put a query on a path playback shares.
    LaunchedEffect(Unit) { settingsViewModel.refreshTrackDiagnostic() }
    val deviceOwnerController = remember(context.applicationContext) {
        DapDeviceOwnerController(context.applicationContext)
    }
    var currentHome by remember { mutableStateOf(resolveCurrentHome(context)) }
    var deviceOwnerState by remember {
        mutableStateOf(deviceOwnerController.currentState())
    }
    val toolsState = remember(paletteTargets) { MaintenanceToolsState(paletteTargets.toImmutableList()) }
    val toolsActions = remember(settingsViewModel, playerViewModel, statsViewModel) {
        MaintenanceToolsActionsImpl(settingsViewModel, playerViewModel, statsViewModel)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentHome = resolveCurrentHome(context)
                deviceOwnerState = deviceOwnerController.currentState()
                settingsViewModel.refreshUsbAudioDiagnostics()
                settingsViewModel.refreshDeviceDiagnostics()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    MaintenanceContent(
        state = MaintenanceState(
            currentHome = currentHome,
            deviceOwner = deviceOwnerState,
            audio = diagnostics,
            route = route,
            hardware = hardwareDiagnostic,
            overrides = overrides,
            track = trackDiagnostic,
            metadata = metadataDiagnostic,
            collections = collectionDiagnostic,
            tools = toolsState,
        ),
        toolsActions = toolsActions,
        onSetOverride = settingsViewModel::setMaintenanceOverride,
        onClearAllOverrides = settingsViewModel::clearAllMaintenanceOverrides,
        onExit = onExit,
        onOpenDiagnostics = onOpenDiagnostics,
    )
}

/** What the Maintenance page draws (B32-Q6): readouts, all of them; nothing here can change the device or the library. */
internal data class MaintenanceState(
    val currentHome: String,
    val deviceOwner: DapDeviceOwnerState,
    val audio: UsbDirectAudioDiagnostics,
    val route: com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState,
    val hardware: DeviceDiagnosticSnapshot,
    val overrides: MaintenanceOverrides,
    val track: TrackIntelligenceDiagnostic?,
    val metadata: MetadataDiagnostic?,
    val collections: CollectionDiagnostic?,
    val tools: MaintenanceToolsState,
)

/** The page as a function of its state: what the route above feeds and the render tests draw. */
@Composable
internal fun MaintenanceContent(
    state: MaintenanceState,
    toolsActions: com.lostf1sh.pixelplayeross.presentation.settings.MaintenanceToolsActions,
    onSetOverride: (MaintenanceOverride, Boolean) -> Unit,
    onClearAllOverrides: () -> Unit,
    onExit: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    offersTestCrash: Boolean = com.lostf1sh.pixelplayeross.presentation.settings.MaintenanceToolsPolicy.offersTestCrash(),
) {
    DapScreen(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            DapTopBar(onBack = onExit)

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    DapScreenTitle(text = stringResource(R.string.maintenance_title))
                }

                item {
                    MaintenanceSection(stringResource(R.string.maintenance_status_section)) {
                        MaintenanceInfoRow(
                            Icons.Rounded.Home,
                            stringResource(R.string.maintenance_current_home),
                            state.currentHome,
                        )
                        MaintenanceInfoRow(
                            Icons.Rounded.Info,
                            stringResource(R.string.maintenance_package_version),
                            "${BuildConfig.APPLICATION_ID} · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        )
                        MaintenanceDeviceOwnerDiagnostics(state.deviceOwner)
                    }
                }

                item {
                    MaintenanceSection(stringResource(R.string.maintenance_audio_section)) {
                        MaintenanceAudioDiagnostics(state.audio, state.route)
                    }
                }

                item {
                    MaintenanceSection(stringResource(R.string.maintenance_hardware_section)) {
                        MaintenanceHardwareDiagnostics(state.hardware)
                    }
                }

                item {
                    MaintenanceSection(stringResource(R.string.maintenance_overrides_section)) {
                        MaintenanceOverridePanel(
                            overrides = state.overrides,
                            onSetOverride = onSetOverride,
                            onClearAll = onClearAllOverrides,
                        )
                    }
                }

                item {
                    MaintenanceSection(stringResource(R.string.maintenance_section_track_identity)) {
                        MaintenanceTrackDiagnostic(state.track)
                    }
                }

                item {
                    MaintenanceSection(stringResource(R.string.maintenance_section_metadata)) {
                        MaintenanceMetadataDiagnostic(state.metadata)
                    }
                }

                item {
                    MaintenanceSection(stringResource(R.string.maintenance_section_smart_collections)) {
                        MaintenanceCollectionDiagnostic(state.collections)
                    }
                }

                item {
                    MaintenanceSection(stringResource(R.string.maintenance_internal_tools_section)) {
                        MaintenanceAction(
                            Icons.Rounded.DeveloperBoard,
                            stringResource(R.string.maintenance_device_diagnostics),
                            subtitle = stringResource(R.string.maintenance_device_diagnostics_subtitle),
                        ) { onOpenDiagnostics() }
                    }
                }

                // The Developer options page's tools (B32-Q6): regenerate the Daily Mix, the statistics and the album palettes; and, in a
                // debug build only, the test crash.
                item {
                    MaintenanceToolsSection(state = state.tools, actions = toolsActions, offersTestCrash = offersTestCrash)
                }

                item { Spacer(Modifier.navigationBarsPadding().height(8.dp)) }
            }
        }
    }
}

@Composable
private fun MaintenanceDeviceOwnerDiagnostics(state: DapDeviceOwnerState) {
    val active = stringResource(
        if (state.isDeviceOwner) R.string.maintenance_device_owner_active
        else R.string.maintenance_device_owner_inactive,
    )
    val homePolicy = stringResource(
        if (state.isPixelPlayerHome) R.string.maintenance_persistent_home_pixelplayer
        else R.string.maintenance_persistent_home_other,
    )
    val lockTask = stringResource(
        if (state.isLockTaskPermitted) R.string.maintenance_lock_task_ready
        else R.string.maintenance_lock_task_not_configured,
    )
    val dedicatedMode = stringResource(
        if (state.isDedicatedModeEnabled) R.string.maintenance_dedicated_mode_active
        else R.string.maintenance_dedicated_mode_inactive,
    )
    val lockTaskState = stringResource(
        if (state.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED) {
            R.string.maintenance_lock_task_locked
        } else {
            R.string.maintenance_lock_task_none
        },
    )
    MaintenanceInfoRow(
        Icons.Rounded.Security,
        stringResource(R.string.maintenance_device_owner),
        active,
    )
    MaintenanceInfoRow(
        Icons.Rounded.Home,
        stringResource(R.string.maintenance_persistent_home),
        homePolicy,
    )
    MaintenanceInfoRow(
        Icons.Rounded.Lock,
        stringResource(R.string.maintenance_lock_task_readiness),
        lockTask,
    )
    MaintenanceInfoRow(
        Icons.Rounded.Security,
        stringResource(R.string.maintenance_dedicated_mode),
        dedicatedMode,
    )
    MaintenanceInfoRow(
        Icons.Rounded.Lock,
        stringResource(R.string.maintenance_lock_task_state),
        lockTaskState,
    )
    // B34: the level Android confirms right now (evidence only: the requested level is a preference and is not a diagnostic)
    val level = ProductModeResolver.resolve(
        requestedMode = null,
        evidence = DedicatedProvisioningEvidence(
            isDeviceOwner = state.isDeviceOwner,
            isPixelPlayerHome = state.isPixelPlayerHome,
            isDedicatedModeEnabled = state.isDedicatedModeEnabled,
            isLockTaskPermitted = state.isLockTaskPermitted,
            lockTaskModeState = state.lockTaskModeState,
        ),
    ).level
    MaintenanceInfoRow(
        Icons.Rounded.Home,
        stringResource(R.string.maintenance_product_level),
        when (level) {
            ProductLevel.LAUNCHER -> stringResource(R.string.home_mode_level_launcher)
            ProductLevel.PINNED -> stringResource(R.string.home_mode_level_pinned)
            ProductLevel.DEDICATED -> stringResource(R.string.home_mode_level_dedicated)
            null -> stringResource(R.string.maintenance_product_level_app)
        },
    )
}

/**
 * The identity/engagement diagnostic for the current track.
 *
 * Read-only, and deliberately plain: labelled rows, with the section headings rendered as headings.
 * Missing values say *why* they are missing — "not recorded yet" for matching evidence the scan has
 * not computed, "no projection" for an item that is known but currently absent — because a diagnostic
 * that shows a blank where a fact should be is worse than one that shows nothing at all.
 */
@Composable
private fun MaintenanceTrackDiagnostic(diagnostic: TrackIntelligenceDiagnostic?) {
    if (diagnostic == null) {
        Text(
            text = stringResource(R.string.maintenance_no_track_to_describe),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        return
    }

    Column {
        diagnostic.rows().forEach { row ->
            if (row.value == null) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = row.value.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1.4f),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Raw, resolved and grouping values for the current track, side by side.
 *
 * The raw column is the point. When a track lands in the wrong album, the answer is almost always a
 * difference between what the file says and what the app decided it means — and a diagnostic that
 * shows only the resolved value cannot show that difference at all.
 */
@Composable
private fun MaintenanceMetadataDiagnostic(diagnostic: MetadataDiagnostic?) {
    if (diagnostic == null) {
        Text(
            text = stringResource(R.string.maintenance_no_track_to_describe),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        return
    }

    Column {
        diagnostic.rows().forEach { row ->
            if (row.value == null) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = row.value.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1.5f),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Why each saved collection does or does not contain the track being diagnosed.
 *
 * The Collections surface shows what matched. This shows what did not, and which rule decided it —
 * the difference between "my rules are wrong" and "the tag is missing", which look identical from
 * the surface. Every verdict here comes from the engine's own `explain()`, so it cannot disagree
 * with the collection it is describing.
 */
@Composable
private fun MaintenanceCollectionDiagnostic(diagnostic: CollectionDiagnostic?) {
    if (diagnostic == null) {
        MaintenanceNote(stringResource(R.string.maintenance_no_track_to_diagnose))
        return
    }
    if (!diagnostic.isActiveCandidate) {
        // Distinct from "no collection matched", and worth saying: no rule could ever be satisfied
        // by a track that has no live projection, so a user debugging their rules would be looking
        // in the wrong place.
        MaintenanceNote(stringResource(R.string.maintenance_note_inactive_library_item))
        return
    }
    if (diagnostic.explanations.isEmpty()) {
        MaintenanceNote(stringResource(R.string.maintenance_note_no_collections))
        return
    }

    Column {
        diagnostic.explanations.forEach { explanation ->
            Text(
                text = buildString {
                    append(if (explanation.isMember) stringResource(R.string.maintenance_collection_member_in) else stringResource(R.string.maintenance_collection_not_in))
                    append(" · ")
                    append(explanation.name)
                    if (explanation.isBuiltIn) append(stringResource(R.string.maintenance_collection_built_in))
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (explanation.isMember) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
            )
            if (explanation.needsAttention) {
                Text(
                    text = stringResource(R.string.maintenance_rules_unreadable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            explanation.evaluations.forEach { evaluation ->
                val rule = evaluation.rule
                val operand = buildString {
                    rule.textValue?.let { append(" \"").append(it).append("\"") }
                    rule.numberValue?.let { append(" ").append(formatDiagnosticNumber(it)) }
                }
                Text(
                    text = "  ${evaluation.result.name}  ${stringResource(rule.target.labelRes())} " +
                        "${stringResource(rule.operation.labelRes())}$operand",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (evaluation.result == RuleEvaluation.Verdict.PASS) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                )
                // The value the engine actually saw. This is the line that distinguishes a wrong
                // rule from a tag that was never there.
                Text(
                    text = stringResource(R.string.maintenance_rule_actual_format, evaluation.actual),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** A diagnostic's "there is nothing to show, and here is why" line. */
@Composable
private fun MaintenanceNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** Whole numbers print as whole numbers; a play count of `3.0` reads like a bug. */
private fun formatDiagnosticNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun MaintenanceSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun MaintenanceAction(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        tonalElevation = 0.dp,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}

@Composable
private fun MaintenanceInfoRow(icon: ImageVector, label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

@Composable
private fun MaintenanceAudioDiagnostics(
    diagnostics: UsbDirectAudioDiagnostics,
    route: com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState,
) {
    val output = if (route.actualBackend ==
        com.lostf1sh.pixelplayeross.data.service.player.AudioBackend.USB_DIRECT
    ) {
        stringResource(R.string.usb_diag_output_direct)
    } else {
        stringResource(R.string.usb_diag_output_android)
    }
    val directState = when (diagnostics.state) {
        UsbDirectSinkState.ANDROID_AUDIO -> stringResource(R.string.usb_diag_state_inactive)
        UsbDirectSinkState.USB_DIRECT_AVAILABLE -> stringResource(R.string.usb_diag_state_ready)
        UsbDirectSinkState.USB_DIRECT_ACTIVE -> stringResource(R.string.usb_diag_state_active)
        UsbDirectSinkState.USB_DIRECT_ERROR -> stringResource(R.string.usb_diag_state_error)
        UsbDirectSinkState.USB_PERMISSION_REQUIRED -> stringResource(R.string.usb_diag_permission_required)
    }
    MaintenanceDiagnosticRow(stringResource(R.string.usb_diag_audio_output), output)
    MaintenanceDiagnosticRow(
        stringResource(R.string.usb_diag_dac),
        diagnostics.deviceName ?: stringResource(R.string.usb_diag_none),
    )
    MaintenanceDiagnosticRow(stringResource(R.string.usb_diag_direct_state), directState)
    MaintenanceDiagnosticRow(stringResource(R.string.usb_diag_route_transition), route.transition.name)
    MaintenanceDiagnosticRow(stringResource(R.string.usb_diag_route_reason), route.reason.name)
    diagnostics.errorReason?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun MaintenanceDiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

/**
 * The read-only hardware picture.
 *
 * Every value is read from the runtime, never from a user setting, and an unanswered platform
 * question prints as "unknown" rather than as a plausible default. A device diagnostic that
 * agrees with what the user configured is not a diagnostic.
 */
@Composable
private fun MaintenanceHardwareDiagnostics(snapshot: DeviceDiagnosticSnapshot) {
    if (snapshot.groups.isEmpty()) {
        MaintenanceNote(stringResource(R.string.maintenance_hardware_loading))
        return
    }
    snapshot.groups.forEachIndexed { index, group ->
        if (index > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        }
        Text(
            text = group.title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
        )
        group.rows.forEach { row ->
            MaintenanceDiagnosticRow(
                label = row.label,
                value = if (row.isUnknown) "—" else row.value,
            )
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    Text(
        text = stringResource(R.string.maintenance_hardware_event_log),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
    )
    if (snapshot.events.isEmpty()) {
        MaintenanceDiagnosticRow(
            label = stringResource(R.string.maintenance_hardware_events_none),
            value = "",
        )
    } else {
        snapshot.events.take(HARDWARE_EVENT_ROWS).forEach { event ->
            MaintenanceDiagnosticRow(
                label = event.uptimeLabel,
                value = event.detail?.let { "${event.type.name} · $it" } ?: event.type.name,
            )
        }
    }
    if (snapshot.droppedEventCount > 0) {
        MaintenanceNote(
            stringResource(R.string.maintenance_hardware_events_dropped, snapshot.droppedEventCount)
        )
    }
}

/**
 * The Maintenance override switches.
 *
 * Feature kills and diagnostic overrides are shown as two separate groups on purpose: a supported
 * configuration decision and a temporary measurement instrument are not the same act, and the
 * panel must not let them read as one list of preferences.
 */
@Composable
private fun MaintenanceOverridePanel(
    overrides: MaintenanceOverrides,
    onSetOverride: (MaintenanceOverride, Boolean) -> Unit,
    onClearAll: () -> Unit,
) {
    val featureKills = MaintenanceOverride.entries
        .filter { it.kind == MaintenanceOverrideKind.FEATURE_KILL }
    val diagnostics = MaintenanceOverride.entries
        .filter { it.kind == MaintenanceOverrideKind.DIAGNOSTIC_OVERRIDE }

    MaintenanceOverrideGroup(
        title = stringResource(R.string.maintenance_overrides_feature_kills),
        overrides = featureKills,
        active = overrides,
        onSetOverride = onSetOverride,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    MaintenanceOverrideGroup(
        title = stringResource(R.string.maintenance_overrides_diagnostic),
        overrides = diagnostics,
        active = overrides,
        onSetOverride = onSetOverride,
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (overrides.isEmpty) {
                stringResource(R.string.maintenance_overrides_none)
            } else {
                stringResource(
                    R.string.maintenance_overrides_summary,
                    overrides.count,
                    overrides.diagnosticCount,
                )
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onClearAll, enabled = !overrides.isEmpty) {
            Text(stringResource(R.string.maintenance_overrides_clear_all))
        }
    }
    MaintenanceNote(stringResource(R.string.maintenance_overrides_persistence_note))
}

@Composable
private fun MaintenanceOverrideGroup(
    title: String,
    overrides: List<MaintenanceOverride>,
    active: MaintenanceOverrides,
    onSetOverride: (MaintenanceOverride, Boolean) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
    )
    overrides.forEach { override ->
        val isOn = active.isActive(override)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(overrideLabelRes(override)), fontWeight = FontWeight.SemiBold)
                Text(
                    text = stringResource(overrideConsequenceRes(override)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = isOn, onCheckedChange = { onSetOverride(override, it) })
        }
    }
}

private fun overrideLabelRes(override: MaintenanceOverride): Int = when (override) {
    MaintenanceOverride.DAP_LOCK_SCREEN_INTEGRATION -> R.string.maintenance_override_lock_screen_label
    MaintenanceOverride.FORCE_ANDROID_AUDIO -> R.string.maintenance_override_force_android_label
    MaintenanceOverride.DISABLE_DIRECT_TRANSITION -> R.string.maintenance_override_disable_transition_label
    MaintenanceOverride.DISABLE_DSP_GRAPH -> R.string.maintenance_override_disable_dsp_label
}

private fun overrideConsequenceRes(override: MaintenanceOverride): Int = when (override) {
    MaintenanceOverride.DAP_LOCK_SCREEN_INTEGRATION -> R.string.maintenance_override_lock_screen_consequence
    MaintenanceOverride.FORCE_ANDROID_AUDIO -> R.string.maintenance_override_force_android_consequence
    MaintenanceOverride.DISABLE_DIRECT_TRANSITION -> R.string.maintenance_override_disable_transition_consequence
    MaintenanceOverride.DISABLE_DSP_GRAPH -> R.string.maintenance_override_disable_dsp_consequence
}

/** Bounded on screen as well as in memory: the panel shows the tail, not the whole buffer. */
private const val HARDWARE_EVENT_ROWS = 25

internal fun resolveCurrentHome(context: Context): String {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return resolved?.activityInfo?.let {
        ComponentName(it.packageName, it.name).flattenToShortString()
    } ?: context.getString(R.string.maintenance_unresolved)
}
