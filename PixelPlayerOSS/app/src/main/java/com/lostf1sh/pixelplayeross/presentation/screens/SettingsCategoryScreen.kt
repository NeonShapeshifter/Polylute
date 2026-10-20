package com.lostf1sh.pixelplayeross.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.presentation.components.FileExplorerDialog
import com.lostf1sh.pixelplayeross.presentation.model.SettingsCategory
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.settings.AboutSettingsActionsImpl
import com.lostf1sh.pixelplayeross.presentation.settings.AboutSettingsScreen
import com.lostf1sh.pixelplayeross.presentation.settings.AppearanceSettingsActionsImpl
import com.lostf1sh.pixelplayeross.presentation.settings.AppearanceSettingsScreen
import com.lostf1sh.pixelplayeross.presentation.settings.AudioSettingsActionsImpl
import com.lostf1sh.pixelplayeross.presentation.settings.AudioSettingsScreen
import com.lostf1sh.pixelplayeross.presentation.settings.AudioSettingsState
import com.lostf1sh.pixelplayeross.presentation.settings.BackupSettingsActionsImpl
import com.lostf1sh.pixelplayeross.presentation.settings.BackupSettingsScreen
import com.lostf1sh.pixelplayeross.presentation.settings.BackupTransferProgressDialogHost
import com.lostf1sh.pixelplayeross.presentation.settings.BehaviorSettingsActionsImpl
import com.lostf1sh.pixelplayeross.presentation.settings.BehaviorSettingsScreen
import com.lostf1sh.pixelplayeross.presentation.settings.DeviceSettingsHost
import com.lostf1sh.pixelplayeross.presentation.settings.LibrarySettingsActionsImpl
import com.lostf1sh.pixelplayeross.presentation.settings.LibrarySettingsScreen
import com.lostf1sh.pixelplayeross.presentation.settings.LyricsSettingsActionsImpl
import com.lostf1sh.pixelplayeross.presentation.settings.LyricsSettingsScreen
import com.lostf1sh.pixelplayeross.presentation.settings.HomeModeHost
import com.lostf1sh.pixelplayeross.presentation.settings.SystemSettingsHost
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioTransitionSummaryViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.BackupSettingsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfilesViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SafetyVolumeViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The Settings category host: the title, the scrolling page, and one page per category.
 *
 * B21: this was a 2,981-line `when` with every category's rows, dialogs and ViewModel calls in it. It is now
 * only the frame. Each category is its own file in `presentation/settings` (`AudioSettings.kt`,
 * `LyricsSettings.kt`, …) that draws a state and calls an actions interface; the hosts below hand each one its
 * state from the ViewModel that owns it and the actions from `SettingsActionsWiring.kt`. Nothing about what a
 * page shows, or in what order, changed.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SettingsCategoryScreen(
    categoryId: String,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    deviceControlViewModel: DeviceControlViewModel = hiltViewModel(),
) {
    val category = SettingsCategory.fromId(categoryId) ?: return

    LaunchedEffect(category) {
        deviceControlViewModel.refresh()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, deviceControlViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) deviceControlViewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showExplorerSheet by remember { mutableStateOf(false) }
    val navigate: (String) -> Unit = remember(navController) {
        { route -> navController.navigateSafely(route) }
    }
    val lazyListState = rememberLazyListState()

    val categoryTitle = stringResource(category.titleRes)
    DapBackScaffold(Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = DapSpacing.Md,
                // B22: the Audio page is drawn on the design's 20 dp gutter; the other pages keep their 16.
                start = if (category == SettingsCategory.AUDIO) DapSpacing.Gutter else 16.dp,
                end = if (category == SettingsCategory.AUDIO) DapSpacing.Gutter else 16.dp,
                bottom = LocalDapPlayerShelfHeight.current + 16.dp
            )
        ) {
            item(key = "settings_category_title") {
                // Clears the floating Back control rather than being drawn under it; see
                // SettingsScreen for why the title, and not the page, pays for that.
                DapScreenTitle(
                    text = categoryTitle,
                    modifier = Modifier.padding(
                        start = 0.dp,
                        end = 0.dp,
                    ),
                )
            }
            item {
                Column(
                    modifier = Modifier.background(Color.Transparent)
                ) {
                    when (category) {
                        SettingsCategory.DEVICE -> DeviceSettingsHost(deviceControlViewModel)
                        SettingsCategory.LIBRARY -> LibrarySettingsHost(settingsViewModel, navigate) { showExplorerSheet = true }
                        SettingsCategory.LYRICS -> LyricsSettingsHost(settingsViewModel)
                        SettingsCategory.APPEARANCE -> AppearanceSettingsHost(settingsViewModel, navigate)
                        SettingsCategory.AUDIO -> AudioSettingsHost(settingsViewModel, navigate, deviceControlViewModel)
                        SettingsCategory.BEHAVIOR -> BehaviorSettingsHost(settingsViewModel)
                        SettingsCategory.BACKUP_RESTORE -> BackupSettingsHost()
                        SettingsCategory.HOME_MODE -> HomeModeHost()
                        SettingsCategory.SYSTEM -> SystemSettingsHost(settingsViewModel, navigate)
                        SettingsCategory.ABOUT -> AboutSettingsScreen(AboutSettingsActionsImpl(navigate))
                        // Empty on purpose, and unreachable: this screen is only ever opened for
                        // a category the Settings root offers, and the root offers only product
                        // categories. Device diagnostics is an appliance tool owned by
                        // Maintenance and is reached at Screen.DeviceCapabilities, which the
                        // Maintenance panel opens directly. The arm exists because the `when`
                        // must be exhaustive, not because this screen has anything to show.
                        SettingsCategory.DEVICE_CAPABILITIES -> Unit
                    }
                }
            }

            item {
                Spacer(Modifier.height(1.dp))
            }
        }

        // Back, as an overlay. It stays where it is while the category name scrolls away above the
        // content it belongs to. Drawing it is the only thing the Back mode changes here: in
        // Gestures mode this control exists solely to go back, so it is not drawn.

        var isTransitioning by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            delay(com.lostf1sh.pixelplayeross.presentation.navigation.TRANSITION_DURATION.toLong())
            isTransitioning = false
        }

        if (isTransitioning) {
            Box(modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                        }
                    }
                }
            )
        }
    }

    if (category == SettingsCategory.LIBRARY) {
        FileExplorerDialogHost(
            settingsViewModel = settingsViewModel,
            visible = showExplorerSheet,
            explorerRoot = settingsViewModel.explorerRoot(),
            onDismiss = {
                settingsViewModel.applyPendingDirectoryRuleChanges()
                showExplorerSheet = false
            },
        )
    }
}

@Composable
private fun LibrarySettingsHost(
    settingsViewModel: SettingsViewModel,
    navigate: (String) -> Unit,
    showExplorer: () -> Unit,
) {
    val state by settingsViewModel.libraryState.collectAsStateWithLifecycle()
    val actions = remember(settingsViewModel, navigate) {
        LibrarySettingsActionsImpl(settingsViewModel, navigate, showExplorer)
    }
    LibrarySettingsScreen(state, actions)
}

@Composable
private fun LyricsSettingsHost(settingsViewModel: SettingsViewModel) {
    val state by settingsViewModel.lyricsState.collectAsStateWithLifecycle()
    val actions = remember(settingsViewModel) { LyricsSettingsActionsImpl(settingsViewModel) }
    LyricsSettingsScreen(state, actions)
}

@Composable
private fun AppearanceSettingsHost(settingsViewModel: SettingsViewModel, navigate: (String) -> Unit) {
    val state by settingsViewModel.appearanceState.collectAsStateWithLifecycle()
    val actions = remember(settingsViewModel, navigate) { AppearanceSettingsActionsImpl(settingsViewModel, navigate) }
    AppearanceSettingsScreen(state, actions)
}

@Composable
private fun BehaviorSettingsHost(settingsViewModel: SettingsViewModel) {
    val state by settingsViewModel.behaviorState.collectAsStateWithLifecycle()
    val actions = remember(settingsViewModel) { BehaviorSettingsActionsImpl(settingsViewModel) }
    BehaviorSettingsScreen(state, actions)
}

/**
 * The Audio page joins what several owners publish: the preferences it edits (`SettingsViewModel`), the DAC and
 * headphone lists, what is applied right now (`AudioStatus`), the device state the Pure Direct switch lives next to
 * (`DeviceControlViewModel`: the digital level, a rise to confirm, where the sound goes), the Safety Max step and the
 * global crossfade rule. The join is the same pure builder the tests call.
 */
@Composable
private fun AudioSettingsHost(
    settingsViewModel: SettingsViewModel,
    navigate: (String) -> Unit,
    deviceControlViewModel: DeviceControlViewModel,
    dacProfilesViewModel: DacProfilesViewModel = hiltViewModel(),
    headphoneProfilesViewModel: HeadphoneProfilesViewModel = hiltViewModel(),
    safetyVolumeViewModel: SafetyVolumeViewModel = hiltViewModel(),
    transitionSummaryViewModel: AudioTransitionSummaryViewModel = hiltViewModel(),
) {
    val ui by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val playbackSpeed by settingsViewModel.playbackSpeed.collectAsStateWithLifecycle()
    val audio by settingsViewModel.audioStatus.collectAsStateWithLifecycle()
    val dacs by dacProfilesViewModel.uiState.collectAsStateWithLifecycle()
    val headphones by headphoneProfilesViewModel.uiState.collectAsStateWithLifecycle()
    val device by deviceControlViewModel.uiState.collectAsStateWithLifecycle()
    val volume by safetyVolumeViewModel.state.collectAsStateWithLifecycle()
    val transition by transitionSummaryViewModel.globalSettings.collectAsStateWithLifecycle()
    val state = remember(ui, playbackSpeed, audio, dacs, headphones, device, volume, transition) {
        AudioSettingsState.from(ui, playbackSpeed, audio, dacs, headphones, device, volume, transition)
    }
    val actions = remember(settingsViewModel, deviceControlViewModel, navigate) {
        AudioSettingsActionsImpl(settingsViewModel, deviceControlViewModel, navigate)
    }
    AudioSettingsScreen(state, actions)
}

@Composable
private fun BackupSettingsHost(backupViewModel: BackupSettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by backupViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(backupViewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            backupViewModel.dataTransferEvents.collectLatest { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val actions = remember(backupViewModel) { BackupSettingsActionsImpl(backupViewModel) }
    BackupSettingsScreen(state, actions)
    BackupTransferProgressHost(backupViewModel)
}

/**
 * The two hosts that own the state the page body never reads.
 *
 * Directory-explorer and transfer-progress state used to be collected at the top of the category screen. That
 * put nine flows in the same restart scope as the whole ~850-line page, so a directory listing arriving or a
 * transfer ticking recomposed every settings row on screen — the page does not read any of these values, and
 * never did. Each host now collects what it draws, and nothing else.
 *
 * `dataTransferProgress` matters most: it is a progress flow and emits repeatedly for as long as a backup or
 * restore is running.
 */
@Composable
private fun BackupTransferProgressHost(backupViewModel: BackupSettingsViewModel) {
    val progress by backupViewModel.dataTransferProgress.collectAsStateWithLifecycle()
    BackupTransferProgressDialogHost(progress = progress)
}

@Composable
private fun FileExplorerDialogHost(
    settingsViewModel: SettingsViewModel,
    visible: Boolean,
    explorerRoot: java.io.File,
    onDismiss: () -> Unit,
) {
    val currentPath by settingsViewModel.currentPath.collectAsStateWithLifecycle()
    val directoryChildren by settingsViewModel.currentDirectoryChildren.collectAsStateWithLifecycle()
    val availableStorages by settingsViewModel.availableStorages.collectAsStateWithLifecycle()
    val selectedStorageIndex by settingsViewModel.selectedStorageIndex.collectAsStateWithLifecycle()
    val isLoadingDirectories by settingsViewModel.isLoadingDirectories.collectAsStateWithLifecycle()
    val isExplorerPriming by settingsViewModel.isExplorerPriming.collectAsStateWithLifecycle()
    val isExplorerReady by settingsViewModel.isExplorerReady.collectAsStateWithLifecycle()
    val isCurrentDirectoryResolved by
        settingsViewModel.isCurrentDirectoryResolved.collectAsStateWithLifecycle()

    FileExplorerDialog(
        visible = visible,
        currentPath = currentPath,
        directoryChildren = directoryChildren,
        availableStorages = availableStorages,
        selectedStorageIndex = selectedStorageIndex,
        isLoading = isLoadingDirectories,
        isPriming = isExplorerPriming,
        isReady = isExplorerReady,
        isCurrentDirectoryResolved = isCurrentDirectoryResolved,
        isAtRoot = settingsViewModel.isAtRoot(),
        rootDirectory = explorerRoot,
        onNavigateTo = settingsViewModel::loadDirectory,
        onNavigateUp = settingsViewModel::navigateUp,
        onNavigateHome = { settingsViewModel.loadDirectory(explorerRoot) },
        onToggleAllowed = settingsViewModel::toggleDirectoryAllowed,
        onRefresh = settingsViewModel::refreshExplorer,
        onStorageSelected = settingsViewModel::selectStorage,
        onDone = {
            settingsViewModel.applyPendingDirectoryRuleChanges()
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}
