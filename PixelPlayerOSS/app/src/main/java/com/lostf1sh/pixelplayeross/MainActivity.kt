package com.lostf1sh.pixelplayeross

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import com.lostf1sh.pixelplayeross.ui.ds.dismissKeyboard
import com.lostf1sh.pixelplayeross.ui.ds.dapKeyboardDismissHost
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapKeyboardRegistry
import com.lostf1sh.pixelplayeross.ui.ds.DapKeyboardRegistry
import com.lostf1sh.pixelplayeross.ui.theme.resolveBaseTheme
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely

import android.annotation.SuppressLint
import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import com.lostf1sh.pixelplayeross.utils.traceSection
import android.provider.Settings
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.CallSuper
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerSheetState

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.lerp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.lostf1sh.pixelplayeross.data.preferences.AppThemeMode
import com.lostf1sh.pixelplayeross.data.preferences.DEFAULT_DAP_LOCK_SCREEN_ENABLED
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.NavBarStyle
import com.lostf1sh.pixelplayeross.data.preferences.sanitizeNavBarCornerRadius
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreferencesRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.preferences.DAP_BRIGHTNESS_FOLLOW_SYSTEM
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.volume.OverrideConfirmationHost
import com.lostf1sh.pixelplayeross.data.volume.SafetyOverridePolicy
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.data.volume.ScreenOffOverridePolicy
import com.lostf1sh.pixelplayeross.dap.admin.DapDeviceOwnerController
import com.lostf1sh.pixelplayeross.dap.admin.DapContainmentPolicy
import com.lostf1sh.pixelplayeross.dap.admin.DapHomeChangeTracker
import com.lostf1sh.pixelplayeross.dap.admin.DapHomePickerSession
import com.lostf1sh.pixelplayeross.dap.admin.HomeExitSettlement
import com.lostf1sh.pixelplayeross.dap.admin.DapLockTaskStateEvents
import com.lostf1sh.pixelplayeross.dap.admin.DapMaintenanceEntryPolicy
import com.lostf1sh.pixelplayeross.dap.admin.DapMaintenanceExitCoordinator
import com.lostf1sh.pixelplayeross.dap.admin.DapMaintenanceSession
import com.lostf1sh.pixelplayeross.dap.admin.DapReturnOutcome
import com.lostf1sh.pixelplayeross.dap.admin.DapReturnToDapCoordinator
import com.lostf1sh.pixelplayeross.dap.admin.DapSystemSurfaceCoordinator
import com.lostf1sh.pixelplayeross.dap.admin.SystemSurfaceHost
import com.lostf1sh.pixelplayeross.dap.admin.SystemSurfaceOutcome
import com.lostf1sh.pixelplayeross.dap.admin.DapTemporarySystemFlowSession
import com.lostf1sh.pixelplayeross.dap.admin.WindowReturn
import com.lostf1sh.pixelplayeross.dap.mode.AppLaunchPolicy
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeController
import com.lostf1sh.pixelplayeross.dap.mode.ProductLevel
import com.lostf1sh.pixelplayeross.dap.admin.DapReleaseOwnerCoordinator
import com.lostf1sh.pixelplayeross.dap.admin.DapReleaseResult
import com.lostf1sh.pixelplayeross.dap.admin.ModeLevelHost
import com.lostf1sh.pixelplayeross.dap.lifecycle.DapRecoveryTrigger
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.worker.SyncManager
import com.lostf1sh.pixelplayeross.data.worker.SyncProgress
import com.lostf1sh.pixelplayeross.presentation.components.AppSidebarDrawer
import com.lostf1sh.pixelplayeross.presentation.components.CrashReportDialog
import com.lostf1sh.pixelplayeross.presentation.components.DrawerDestination
import com.lostf1sh.pixelplayeross.presentation.components.MiniPlayerBottomSpacer
import com.lostf1sh.pixelplayeross.presentation.components.VolumeHudController
import com.lostf1sh.pixelplayeross.presentation.components.MiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.components.LocalMiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.components.rememberMiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.components.PlayerInternalNavigationBar
import com.lostf1sh.pixelplayeross.presentation.components.PlayerNavigationRail
import com.lostf1sh.pixelplayeross.presentation.components.UnifiedPlayerSheetV2
import com.lostf1sh.pixelplayeross.presentation.components.calculatePlayerSheetCollapsedTargetY
import com.lostf1sh.pixelplayeross.presentation.components.resolveNavBarOccupiedHeight
import com.lostf1sh.pixelplayeross.presentation.components.resolveNavBarSurfaceHeight
import com.lostf1sh.pixelplayeross.presentation.components.sanitizeNavigationBarBottomInset
import com.lostf1sh.pixelplayeross.presentation.navigation.DAP_DETAIL_HOST_ROUTE
import com.lostf1sh.pixelplayeross.presentation.navigation.isLibraryLocateRoute
import com.lostf1sh.pixelplayeross.presentation.navigation.shownRootRoute
import com.lostf1sh.pixelplayeross.presentation.navigation.DapBackAuthority
import com.lostf1sh.pixelplayeross.presentation.navigation.DapRootShell
import com.lostf1sh.pixelplayeross.presentation.navigation.DapRootTab
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalBackNavigationMode
import com.lostf1sh.pixelplayeross.presentation.navigation.LocalDapBackNavigation
import com.lostf1sh.pixelplayeross.presentation.navigation.BackEdge
import com.lostf1sh.pixelplayeross.presentation.navigation.dapEdgeBackGesture
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.ds.DapBottomNav
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapHeights
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapNavItem
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.navigation.DapSurfacePolicy
import com.lostf1sh.pixelplayeross.presentation.viewmodel.MainViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DapDeviceControlPolicy
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ScreenTimeoutDriftGuard
import com.lostf1sh.pixelplayeross.presentation.lockscreen.CoverSignal
import com.lostf1sh.pixelplayeross.presentation.lockscreen.DapLockSurface
import com.lostf1sh.pixelplayeross.presentation.lockscreen.FreezeWhileCovered
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.theme.PixelPlayerTheme
import com.lostf1sh.pixelplayeross.ui.theme.PixelPlayerWindowBackground
import com.lostf1sh.pixelplayeross.utils.AppLocaleManager
import com.lostf1sh.pixelplayeross.utils.LanguageChangePolicy
import com.lostf1sh.pixelplayeross.utils.LanguageRecreateGate
import com.lostf1sh.pixelplayeross.utils.SystemBarsDiagnostic
import com.lostf1sh.pixelplayeross.utils.CrashHandler
import com.lostf1sh.pixelplayeross.utils.LogUtils
import com.lostf1sh.pixelplayeross.data.service.player.UsbPermissionCoordinator
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import com.lostf1sh.pixelplayeross.presentation.utils.AppHapticsConfig
import com.lostf1sh.pixelplayeross.presentation.utils.LocalAppHapticsConfig
import com.lostf1sh.pixelplayeross.presentation.utils.NoOpHapticFeedback
import com.lostf1sh.pixelplayeross.utils.CrashLogData
import javax.annotation.concurrent.Immutable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.mutableFloatStateOf
import com.lostf1sh.pixelplayeross.presentation.components.EdgeBackGestureIndicator


@Immutable
data class BottomNavItem(
    val label: String,
    @DrawableRes val iconResId: Int,
    @DrawableRes val selectedIconResId: Int? = null,
    val screen: Screen
)

private const val LOCK_TASK_TRANSITION_TIMEOUT_MS = 3_000L

@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class MainActivity : ComponentActivity(), SystemSurfaceHost, ModeLevelHost {

    private val playerViewModel: PlayerViewModel by viewModels()
    private val mainViewModel: MainViewModel by viewModels()
    private var isUIVisiblyReady = false
    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository
    @Inject
    lateinit var themePreferencesRepository: ThemePreferencesRepository
    @Inject
    lateinit var syncManager: SyncManager
    @Inject
    lateinit var dualPlayerEngine: DualPlayerEngine
    @Inject
    lateinit var dacProfileRepository: DacProfileRepository
    @Inject
    lateinit var safetyVolumeController: SafetyVolumeController
    private val _pendingPlaylistNavigation = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val _pendingShuffleAll = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val homeRootBackGuard = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = Unit
    }

    /**
     * The app's own volume readout.
     *
     * Created with the activity rather than with the composition, and for a reason: while a sheet or
     * a dialog of this app holds window focus the activity's composition stops producing new
     * windows, so a readout mounted as a composable was absent on exactly the surfaces it exists
     * for. This one owns its window outright.
     */
    private val volumeHud by lazy { VolumeHudController(this, safetyVolumeController) }

    /**
     * FIX-5: this activity is where the Safety Max override confirmation is drawn, so it is what the
     * controller asks to show it above the lock screen. Registered only while resumed. With the screen
     * off the activity is stopped, even under Lock Task (observed on the HONOR): the controller wakes the
     * screen, and this activity, which Lock Task brings to the front, registers itself on resume and
     * answers the pending request. Nothing is started from the background; Android does not list a
     * device owner among the apps allowed to do that. The flags are never left on: pausing puts them back.
     */
    private val overrideConfirmationHost = object : OverrideConfirmationHost {
        override fun setOverLockScreen(enabled: Boolean) {
            setShowWhenLocked(enabled)
            setTurnScreenOn(enabled)
        }
    }
    private val dapDeviceOwnerController by lazy { DapDeviceOwnerController(this) }
    private val productModeController by lazy { ProductModeController(this) }
    private var maintenanceExpiryJob: Job? = null

    // B32-fix1 R2: nothing about a window the System page opened is kept on this instance. Whether the app coming back owes a restore is
    // the window's own (`DapMaintenanceSession`, process-wide), so an activity recreated while the surface is out restores like the first.

    /** True from the tap on a System-page opener until its Lock Task lift has finished: a second tap during it is ignored. */
    private var systemSurfaceOpening = false

    /**
     * F-08: bumped when Android's HOME / Device Owner facts changed while this activity was alive, so
     * what is derived from them in composition (the product mode, the keyguard and lock-surface
     * policy) is read again instead of staying as it was when the activity was created.
     */
    private val ownerEpoch = androidx.compose.runtime.mutableIntStateOf(0)
    private val homeChangeTracker = DapHomeChangeTracker()

    private val requestAllFilesAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
    }

    private companion object {
        const val LANGUAGE_TAG = "AppLanguage"
        const val SYSTEM_BARS_TAG = "SystemBars"
    }

    @CallSuper
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    // ── B16-G3: a language change recreates this activity exactly once ─────────────────────────
    // The manifest declares `locale|layoutDirection` for this activity, so the platform no longer relaunches it (with its
    // window kept) when the per-app locale changes: it tells us (`onConfigurationChanged`), and so does the stored
    // preference. Both go through one gate, which says yes once. Nothing else calls `recreate()` for the language.
    private var creationNumber = 0
    private lateinit var languageGate: LanguageRecreateGate
    private var languageObserver: AutoCloseable? = null
    private var systemBarsLoggedOnResume = false
    private val languageGraceRecreate = Runnable {
        requestLanguageRecreate("no configuration event within ${LanguageChangePolicy.PLATFORM_EVENT_GRACE_MS} ms")
    }

    private fun effectiveLanguage(configuration: Configuration): String = LanguageChangePolicy.effective(
        AppLocaleManager.currentLanguageTag(this),
        configuration.locales.toLanguageTags(),
    )

    private fun requestLanguageRecreate(reason: String, configuration: Configuration = resources.configuration) {
        if (isFinishing || isDestroyed) return
        val now = effectiveLanguage(configuration)
        if (!languageGate.shouldRecreate(now)) return
        window.decorView.removeCallbacks(languageGraceRecreate)
        Timber.tag(LANGUAGE_TAG).i("language is now '%s' (%s): recreating once", now, reason)
        recreate()
    }

    private fun onStoredLanguageChanged() {
        if (LanguageChangePolicy.platformReportsChange(Build.VERSION.SDK_INT)) {
            // Android 13+: the platform reports it as a configuration event, handled below. If it never comes (an OEM
            // that does not deliver it), the language must not stay as it was.
            window.decorView.removeCallbacks(languageGraceRecreate)
            window.decorView.postDelayed(languageGraceRecreate, LanguageChangePolicy.PLATFORM_EVENT_GRACE_MS)
        } else {
            requestLanguageRecreate("stored language changed")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestLanguageRecreate("configuration event", newConfig)
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        LogUtils.d(this, "onCreate")
        creationNumber = SystemBarsDiagnostic.nextCreation()
        languageGate = LanguageRecreateGate(effectiveLanguage(resources.configuration))
        languageObserver = AppLocaleManager.observeLanguage(this, ::onStoredLanguageChanged)
        val splashScreen = installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        Timber.tag(SYSTEM_BARS_TAG).i(
            SystemBarsDiagnostic.describe(
                "onCreate",
                creationNumber,
                SystemBarsDiagnostic.decorReused(this),
                this,
                effectiveLanguage(resources.configuration),
                savedInstanceState != null,
            )
        )
        settleHomeExit()
        restoreDeviceOwnerPolicy()
        observeDapDisplayPolicy()
        onBackPressedDispatcher.addCallback(this, homeRootBackGuard)
        volumeHud.start()
        updateHomeLaunchState(intent)
        handleUsbAudioAttachIntent(intent)
        installUsbPermissionRequester()

        splashScreen.setKeepOnScreenCondition { false }

        val isBenchmarkMode = intent.getBooleanExtra("is_benchmark", false)
        // Only the benchmark build honours this; any app can send these extras to an exported activity.
        val shouldBenchmarkRebuildDatabase = BenchmarkLaunchPolicy.shouldRebuildDatabase(
            BuildConfig.BUILD_TYPE,
            isBenchmark = isBenchmarkMode,
            rebuildRequested = intent.getBooleanExtra("benchmark_rebuild_database", false),
        )
        Timber.tag("PixelPlayerBenchmark").i(
            "onCreate benchmark=$isBenchmarkMode rebuildDatabase=$shouldBenchmarkRebuildDatabase"
        )
        if (shouldBenchmarkRebuildDatabase) {
            lifecycleScope.launch {
                userPreferencesRepository.setInitialSetupDone(true)
                Timber.tag("PixelPlayerBenchmark").i("Enqueueing benchmark database rebuild")
                syncManager.rebuildDatabase()
                delay(1_500L)
                playerViewModel.prepareBenchmarkPlayerFromLibrary()
            }
        }

        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            val appThemeMode by themePreferencesRepository.appThemeModeFlow.collectAsStateWithLifecycle(initialValue = AppThemeMode.FOLLOW_SYSTEM)
            // Read at the shell so a change re-themes the whole tree: every palette reader and
            // every Material reader sits below this point.
            val dapAccent by themePreferencesRepository.accentFlow
                .collectAsStateWithLifecycle(initialValue = DapAccent.DEFAULT)
            val dapBaseTheme by themePreferencesRepository.baseThemeFlow
                .collectAsStateWithLifecycle(initialValue = DapBaseTheme.DEFAULT)
            // One theme answer: the base theme that is drawn decides light or dark too, so the
            // palette and the artwork-derived header schemes can no longer disagree.
            val drawnBaseTheme = resolveBaseTheme(appThemeMode, dapBaseTheme, systemDarkTheme)
            val useDarkTheme = !drawnBaseTheme.palette.isLight
            val isSetupComplete by mainViewModel.isSetupComplete.collectAsStateWithLifecycle()
            
            var showCrashReportDialog by remember { mutableStateOf(false) }
            var crashLogData by remember { mutableStateOf<CrashLogData?>(null) }
            
            val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            @OptIn(ExperimentalPermissionsApi::class)
            val mediaPermissionState = rememberPermissionState(mediaPermission)
            val mediaPermissionGranted = mediaPermissionState.status.isGranted
            val launchDecision = remember(
                isSetupComplete,
                mediaPermissionGranted,
                isBenchmarkMode,
            ) {
                AppLaunchPolicy.decide(
                    legacySetupComplete = isSetupComplete,
                    mediaPermissionGranted = mediaPermissionGranted,
                    benchmarkMode = isBenchmarkMode,
                )
            }

            LaunchedEffect(launchDecision) {
                if (launchDecision.markLegacySetupComplete) {
                    userPreferencesRepository.setInitialSetupDone(true)
                }
                if (launchDecision.startLibrarySync) {
                    LogUtils.i(this, "Music access available. Starting sync.")
                    mainViewModel.startSync()
                }
            }

            LaunchedEffect(Unit) {
                if (!isBenchmarkMode && CrashHandler.hasCrashLog()) {
                    crashLogData = CrashHandler.getCrashLog()
                    showCrashReportDialog = true
                }
            }

            PixelPlayerTheme(
                darkTheme = useDarkTheme,
                accent = dapAccent,
                baseTheme = drawnBaseTheme
            ) {
                // B16-G3: whatever the window shows where the content does not reach is the app's colour, never the default.
                PixelPlayerWindowBackground()
                val safetyVolumeState by safetyVolumeController.state.collectAsStateWithLifecycle()
                // One reader for the Back mode: the shell resolves the preference once and publishes
                // it, so the gesture and every Back affordance cannot disagree about which mode is
                // in force.
                val backNavigationMode by userPreferencesRepository.backNavigationModeFlow
                    .collectAsStateWithLifecycle(initialValue = BackNavigationMode.DEFAULT)
                val backAuthority = remember(onBackPressedDispatcher) {
                    DapBackAuthority(onBackPressedDispatcher)
                }

                // No reveal. The app used to wait 100 ms and then fade in over 600 ms: an
                // appliance becoming ready should simply be there on its first frame.

                // One keyboard registry per window: text fields register with it, and the root
                // below dismisses the keyboard on any touch that lands on none of them.
                val keyboardRegistry = remember { DapKeyboardRegistry() }
                CompositionLocalProvider(
                    LocalDapBackNavigation provides backAuthority,
                    LocalBackNavigationMode provides backNavigationMode,
                    LocalDapKeyboardRegistry provides keyboardRegistry,
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // The app's own left-edge Back gesture lives on the outermost content node, so it
                    // is the first thing to see a touch and the last thing to claim one. Its region
                    // is a narrow strip at the left edge; everything else is never examined.
                    // How far through the edge-Back gesture the finger is. Purely a readout of the
                    // gesture: the indicator below draws it and decides nothing.
                    var edgeBackProgress by remember { mutableFloatStateOf(0f) }
                    // B25-S6: and from which side, so the indicator is drawn where the finger is.
                    var edgeBackEdge by remember { mutableStateOf(BackEdge.LEFT) }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .dapKeyboardDismissHost()
                            .dapEdgeBackGesture(
                                enabled = backNavigationMode.edgeGestureEnabled,
                                onBack = backAuthority::back,
                                onProgress = { edgeBackProgress = it },
                                onEdge = { edgeBackEdge = it },
                            )
                    ) {
                        // While the lock surface is up, the app behind it is not there for a
                        // screen reader either: it used to stay in the accessibility tree, so
                        // TalkBack could focus and press controls nobody could see.
                        var lockSurfaceVisible by remember { mutableStateOf(false) }
                        // B22: ...nor is it recomposed or laid out. While the surface covers it the app hears
                        // nothing (its lifecycle is held at CREATED) and is not placed, so a song change costs the
                        // lock surface and nothing else. While the surface is swiped away it is placed again (still
                        // quiet), so it shows under the sliding surface as it was; when the surface is gone it hears
                        // the player again, and reads the latest.
                        var lockRevealing by remember { mutableStateOf(false) }
                        // B25-S4: the wake cycle says it has the app covered from `SCREEN_OFF`, before the surface is on
                        // screen: the app is held (lifecycle) and unplaced from then, so the wake's first frame is not the app's.
                        val lockCover = remember { CoverSignal() }
                        FreezeWhileCovered(
                            frozen = lockSurfaceVisible,
                            hidden = (lockSurfaceVisible || lockCover.covered) && !lockRevealing,
                            cover = lockCover,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (lockSurfaceVisible) Modifier.clearAndSetSemantics { }
                                    else Modifier
                                ),
                        ) {
                            MainAppContent(playerViewModel, mainViewModel)
                        }
                        // The DAP lock surface. Drawn inside this composition like every other
                        // screen — no WindowManager overlay, no separate window.
                        val lockScreenEnabled by userPreferencesRepository.dapLockScreenEnabledFlow
                            .collectAsStateWithLifecycle(initialValue = DEFAULT_DAP_LOCK_SCREEN_ENABLED)
                        // The mode derives from HOME and the containment facts, which change outside
                        // composition. Reading the epoch makes a change the activity noticed (F-08)
                        // recompose this, so it is derived again instead of staying as it was.
                        @Suppress("UNUSED_VARIABLE")
                        val ownerFactsEpoch = ownerEpoch.intValue
                        val productState = productModeController.currentState()
                        val dedicatedActive = productState.isDedicatedActive
                        // B34: the surface is a playback face for a device whose home is Polylute, at any level (Launcher, Pinned,
                        // Dedicated). Only the keyguard policy (below) is Dedicated's: it is Device Owner policy and a no-op without one.
                        val lockSurfaceAllowed = productState.allowsLockSurface
                        // Device Owner policy outlives this process, so the desired Lock Task mask
                        // is re-derived from persistent product state rather than remembered. This
                        // is idempotent: with the setting unchanged it writes the same mask again.
                        LaunchedEffect(lockScreenEnabled, dedicatedActive) {
                            dapDeviceOwnerController.setDapLockScreenEnabled(lockScreenEnabled)
                            dapDeviceOwnerController.reconcileKeyguardPolicy()
                        }
                        DapLockSurface(
                            playerViewModel = playerViewModel,
                            enabled = lockScreenEnabled,
                            dedicated = lockSurfaceAllowed,
                            onSurfaceVisibleChange = { visible ->
                                lockSurfaceVisible = visible
                                if (!visible) lockRevealing = false
                            },
                            onRevealingChange = { lockRevealing = it },
                            cover = lockCover,
                        )
                        if (!mediaPermissionGranted && !isBenchmarkMode) {
                            MusicAccessPrompt(
                                onAllow = mediaPermissionState::launchPermissionRequest,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(horizontal = 16.dp, vertical = 72.dp),
                            )
                        }
                        // Last child, so it floats over the shell while the gesture is in progress.
                        EdgeBackGestureIndicator(
                            progress = edgeBackProgress,
                            edge = edgeBackEdge,
                            modifier = Modifier.align(
                                if (edgeBackEdge == BackEdge.LEFT) Alignment.CenterStart else Alignment.CenterEnd
                            ),
                        )
                    }

                    if (showCrashReportDialog && crashLogData != null) {
                        CrashReportDialog(
                            crashLog = crashLogData!!,
                            onDismiss = {
                                CrashHandler.clearCrashLog()
                                crashLogData = null
                                showCrashReportDialog = false
                            }
                        )
                    }
                    if (safetyVolumeState.showOverrideConfirmation) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = safetyVolumeController::cancelOverrideDialog,
                            title = { Text(stringResource(R.string.safety_max_reached_title)) },
                            text = {
                                // This window is its own surface, so the activity's volume-key path does not
                                // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
                                ModalInputBridge()

                                Text(
                                    stringResource(
                                        R.string.safety_max_reached_message,
                                        safetyVolumeState.selectedProfileName.orEmpty(),
                                    ) + "\n\n" + stringResource(
                                        R.string.safety_max_override_lasts,
                                        (SafetyOverridePolicy.IDLE_END_MS / 60_000L).toInt(),
                                    ) + if (safetyVolumeState.overrideConfirmationOverLock) {
                                        // FIX-5: asked with the screen off; it closes by itself.
                                        "\n\n" + stringResource(
                                            R.string.safety_max_override_closes_soon,
                                            (ScreenOffOverridePolicy.CONFIRM_TIMEOUT_MS / 1_000L).toInt(),
                                        )
                                    } else {
                                        ""
                                    }
                                )
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = safetyVolumeController::allowTemporaryOverride,
                                ) { Text(stringResource(R.string.safety_max_temporary_override)) }
                            },
                            dismissButton = {
                                Column(horizontalAlignment = Alignment.End) {
                                    // Permanently disabling Safety Max for a headphone is not offered
                                    // from a locked screen (FIX-5): there the choice is the temporary
                                    // override or nothing.
                                    if (!safetyVolumeState.overrideConfirmationOverLock) {
                                        androidx.compose.material3.TextButton(
                                            onClick = { safetyVolumeController.setSafetyEnabled(false) },
                                        ) { Text(stringResource(R.string.safety_max_disable)) }
                                    }
                                    androidx.compose.material3.TextButton(
                                        onClick = safetyVolumeController::cancelOverrideDialog,
                                    ) { Text(stringResource(R.string.cancel)) }
                                }
                            },
                        )
                    }
                }
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A HOME press, or the system returning to HOME after the default was changed, arrives here.
        reconcileOwnerPolicyIfHomeChanged("onNewIntent")
        updateHomeLaunchState(intent)
        handleUsbAudioAttachIntent(intent)
        handleIntent(intent)
    }

    private fun observeDapDisplayPolicy() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    userPreferencesRepository.dapBrightnessPercentFlow.collect { percent ->
                        val attributes = window.attributes
                        attributes.screenBrightness = if (percent == DAP_BRIGHTNESS_FOLLOW_SYSTEM) {
                            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        } else {
                            (percent.coerceIn(5, 100) / 100f)
                        }
                        window.attributes = attributes
                    }
                }
                launch {
                    // F-05: the flag is held only while a song is playing and the player is in
                    // front, and this block lives inside repeatOnLifecycle(STARTED), so it is gone
                    // with the activity too. Nothing here touches the system's screen timeout.
                    combine(
                        userPreferencesRepository.dapScreenTimeoutModeFlow,
                        playerViewModel.stablePlayerState.map { it.isPlaying }.distinctUntilChanged(),
                        playerViewModel.sheetState.map { it == PlayerSheetState.EXPANDED }.distinctUntilChanged(),
                    ) { timeoutMode, isPlaying, playerVisible ->
                        DapDeviceControlPolicy.shouldKeepScreenOn(timeoutMode, isPlaying, playerVisible)
                    }.distinctUntilChanged().collect { keepScreenOn ->
                        if (keepScreenOn) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                }
            }
        }
    }

    /**
     * F-08: re-runs the (idempotent) owner-policy reconciliation when HOME ownership, Device Owner or
     * the containment facts differ from the last look.
     *
     * Reconciliation used to run at activity creation, boot and package replacement only. Changing the
     * default HOME app does none of those, so a HOME that left and came back left the containment flag
     * and the Lock Task allowlist as the HOME recovery had cleared them until a reboot ran it again.
     * Cheap when nothing moved: one read of the state, no policy write.
     */
    private fun reconcileOwnerPolicyIfHomeChanged(reason: String) {
        val state = runCatching { dapDeviceOwnerController.currentState() }.getOrNull() ?: return
        if (!homeChangeTracker.changed(state)) return
        Timber.tag("DapContainment").i(
            "Owner facts changed (%s): owner=%s home=%s flag=%s permitted=%s; reconciling",
            reason,
            state.isDeviceOwner,
            state.isPixelPlayerHome,
            state.isDedicatedModeEnabled,
            state.isLockTaskPermitted,
        )
        restoreDeviceOwnerPolicy()
        // B34: Polylute lost the home role while it holds an app pin: the pin ends with it (Dedicated ends through the policy, which
        // re-asserts HOME or was cleared by the listener's own choice).
        if (runCatching { productModeController.shouldReleasePin() }.getOrDefault(false)) releaseAppPinning()
        ownerEpoch.intValue++
    }

    /** B34: a pin the listener confirms (or drops) in Android's own dialog moves window focus, not the activity's lifecycle. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) reconcileOwnerPolicyIfHomeChanged("windowFocus")
    }

    /**
     * FIX-2 (B11-fix): the screen timeout the user chose against the one the system has. On the
     * appliance (Device Owner + HOME) a drift is put back and logged (`Screen timeout drifted from X to
     * Y; re-applied`); on a phone it is only logged, the system's value stands. Off the main thread:
     * it reads the preference and asks the Device Owner state.
     */
    private fun reconcileScreenTimeoutDrift(trigger: String) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val stored = runCatching { userPreferencesRepository.dapScreenTimeoutModeFlow.first() }
                .getOrNull() ?: return@launch
            val owner = runCatching { dapDeviceOwnerController.currentState() }.getOrNull()
            val dapMode = owner?.isDeviceOwner == true && owner.isPixelPlayerHome
            ScreenTimeoutDriftGuard.reconcile(applicationContext, stored, dapMode, trigger)
        }
    }

    private fun restoreDeviceOwnerPolicy() {
        runCatching {
            productModeController.restoreDedicatedPoliciesIfActive(
                DapRecoveryTrigger.ACTIVITY_START,
            )
        }.onSuccess { result ->
            Timber.tag("DapPolicyRestore").i("Activity-start recovery result=%s", result)
        }.onFailure { error ->
            Timber.tag("DapContainment").e(error, "Owner policy restoration failed")
        }
    }

    /**
     * Called only after the local Maintenance PIN has been authenticated.
     *
     * F-08: whether there is a containment to release is read from **Android's Lock Task state**, not
     * from the product mode. The mode is an inference (Device Owner plus HOME plus a persisted flag
     * plus the Lock Task allowlist) and the HOME recovery flow moves every one of those, so after the
     * owner changed the default HOME app and set the app back the mode could read "App" while the
     * activity was still held by Lock Task: the old gate here then began the session and released
     * nothing, and Settings stayed blocked until a reboot. Nothing locked means nothing to leave (App
     * Mode, and a device whose provisioning is half done, are the same case); anything locked is
     * released, with Device Owner's allowlist as the last resort ([DapMaintenanceEntryPolicy]).
     */
    fun enterAuthenticatedMaintenance(onResult: (Boolean) -> Unit) {
        DapMaintenanceSession.begin()
        scheduleMaintenanceExpiry()
        val lockState = dapDeviceOwnerController.lockTaskModeState()
        Timber.tag("DapContainment").i(
            "Maintenance entry: lockTask=%d dedicatedActive=%s",
            lockState,
            runCatching { productModeController.isDedicatedActive() }.getOrNull(),
        )
        if (DapMaintenanceEntryPolicy.plan(lockState) == DapMaintenanceEntryPolicy.Plan.BEGIN_SESSION) {
            onResult(true)
            return
        }
        lifecycleScope.launch {
            val released = releaseLockTaskForMaintenance()
            if (!released) {
                DapMaintenanceSession.end()
                cancelMaintenanceExpiry()
                restoreDedicatedModeAfterTransitionFailure()
            }
            onResult(released)
        }
    }

    /** Gets Lock Task to NONE: the activity's own request first, then Device Owner's allowlist. */
    private suspend fun releaseLockTaskForMaintenance(): Boolean {
        val requested = runCatching {
            if (dapDeviceOwnerController.lockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                DapLockTaskStateEvents.prepareForTransition()
                stopLockTask()
            }
            true
        }.getOrElse { error ->
            Timber.tag("DapContainment").e(error, "Unable to stop Lock Task for Maintenance")
            false
        }
        if (requested && awaitLockTaskModeState(ActivityManager.LOCK_TASK_MODE_NONE)) return true

        val escalation = DapMaintenanceEntryPolicy.escalation(
            lockTaskModeStateAfterStop = dapDeviceOwnerController.lockTaskModeState(),
            isDeviceOwner = runCatching { dapDeviceOwnerController.currentState().isDeviceOwner }
                .getOrDefault(false),
        )
        Timber.tag("DapContainment").w("Lock Task did not release on request; escalation=%s", escalation)
        return when (escalation) {
            DapMaintenanceEntryPolicy.Escalation.NONE_NEEDED -> true
            DapMaintenanceEntryPolicy.Escalation.GIVE_UP -> false
            DapMaintenanceEntryPolicy.Escalation.DEVICE_OWNER_RELEASE -> {
                DapLockTaskStateEvents.prepareForTransition()
                val allowlistCleared = runCatching { dapDeviceOwnerController.releaseLockTaskForMaintenance() }
                    .getOrDefault(false)
                allowlistCleared && awaitLockTaskModeState(ActivityManager.LOCK_TASK_MODE_NONE)
            }
        }
    }

    /** Explicit authenticated transition from Maintenance back to the contained DAP. */
    fun returnToDedicatedMode(onResult: (Boolean) -> Unit) {
        Timber.tag("DapContainment").i("Authenticated Maintenance exit requested")
        lifecycleScope.launch { onResult(returnToDedicatedModeNow()) }
    }

    private suspend fun returnToDedicatedModeNow(): Boolean {
        val coordinator = DapMaintenanceExitCoordinator(
            ensurePolicyReady = {
                runCatching { dapDeviceOwnerController.ensureDedicatedModeReady() }
                    .getOrElse { error ->
                        Timber.tag("DapContainment")
                            .e(error, "Unable to restore dedicated policy")
                        false
                    }
            },
            endMaintenanceSession = {
                DapMaintenanceSession.end()
                cancelMaintenanceExpiry()
            },
            prepareLockTaskTransition = DapLockTaskStateEvents::prepareForTransition,
            requestLockTask = ::maybeEnterDedicatedMode,
            awaitLocked = {
                awaitLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED)
            },
            recoverContainment = ::restoreDedicatedModeAfterTransitionFailure,
        )
        val locked = coordinator.exit()
        Timber.tag("DapContainment").i(
            "Authenticated Maintenance exit completed; locked=%s",
            locked,
        )
        return locked
    }

    fun exitAuthenticatedMaintenance(onResult: (Boolean) -> Unit) {
        if (!productModeController.isDedicatedActive()) {
            DapMaintenanceSession.end()
            cancelMaintenanceExpiry()
            onResult(true)
            return
        }
        returnToDedicatedMode(onResult)
    }

    /**
     * B32-fix1 R3: "Volver al DAP". The listener's explicit request, which is not [exitAuthenticatedMaintenance]: that one closes a
     * temporary window and asks the product mode whether there is a dedicated mode to go back to, and after the HONOR recovery the mode
     * reads App (the recovery cleared HOME, the dedicated flag and the allowlist, the evidence it infers from), so it reported success and
     * did nothing. [DapReturnToDapCoordinator] asks the question that matters (Device Owner?), puts the policy back and verifies Lock Task.
     */
    override fun returnToDap(onResult: (DapReturnOutcome) -> Unit) {
        Timber.tag("DapContainment").i("Explicit return to the DAP requested")
        lifecycleScope.launch {
            val outcome = DapReturnToDapCoordinator(
                isDeviceOwner = { runCatching { dapDeviceOwnerController.currentState().isDeviceOwner }.getOrDefault(false) },
                reassertPolicy = {
                    // HOME, the dedicated flag and the allowlist, whatever the HONOR recovery cleared (the flag and the allowlist are not rewritten
                    // when they are in place; the persistent HOME is asserted again, B35-fix1 R2); then the activity-start restore, which also puts
                    // the wake-surface policy the recovery released back
                    val ready = runCatching { dapDeviceOwnerController.reassertDedicatedPolicy() }.getOrDefault(false)
                    if (ready) restoreDeviceOwnerPolicy()
                    ready
                },
                isDedicatedActive = { productModeController.isDedicatedActive() },
                endWindow = {
                    DapMaintenanceSession.end()
                    cancelMaintenanceExpiry()
                },
                prepareLockTaskTransition = DapLockTaskStateEvents::prepareForTransition,
                requestLockTask = ::maybeEnterDedicatedMode,
                awaitLocked = { awaitLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED) },
                recoverContainment = ::restoreDedicatedModeAfterTransitionFailure,
            ).returnToDap()
            Timber.tag("DapContainment").i("Explicit return to the DAP: %s", outcome)
            onResult(outcome)
        }
    }

    private suspend fun exitAuthenticatedMaintenanceNow(): Boolean {
        if (!productModeController.isDedicatedActive()) {
            DapMaintenanceSession.end()
            cancelMaintenanceExpiry()
            return true
        }
        return returnToDedicatedModeNow()
    }

    /**
     * B32-Q6: opens an Android surface (Android Settings, Wi-Fi, Bluetooth, USB preferences) from the System page.
     *
     * No PIN, and the same Lock Task handling Maintenance has always had ([DapSystemSurfaceCoordinator] spells the order): the window
     * opens, Lock Task is lifted from what Android reports (`stopLockTask()`, then Device Owner's allowlist release if that did not
     * reach NONE), the surface starts as an external flow of that window. The way back is [reconcileMaintenanceSessionOnResume]: when
     * the app is in front again the Maintenance exit runs, which puts the allowlist, the policy and Lock Task back.
     */
    override fun openSystemSurface(intent: Intent, onResult: (SystemSurfaceOutcome) -> Unit) {
        openThroughLiftedLockTask(onResult = onResult) {
            launchAuthenticatedMaintenanceSystemFlow(intent, restoreOnReturn = true)
        }
    }

    /**
     * B32-Q6: "Restore HONOR Launcher as HOME". The same lift, then [prepareHonorHomeRecovery] (which needs the window open and Lock Task
     * at NONE, as before) and, once the DAP policy is cleared, Android's HOME selector through [openSelector]. [prepareHonorHomeRecovery]
     * ends the window once the policy is cleared, so nothing is owed when the app returns and nothing is put back: the policy this
     * leaves is the one the owner asked for. The way back is the explicit [returnToDap].
     *
     * B34-fix2 R1: the exit is a recorded fact until the picker is over ([DapDeviceOwnerController.leaveDedicatedByChoice]); the activity
     * settles it at its next start or resume ([settleHomeExit]). A picker that cannot open is settled here and now: nothing was chosen, so
     * Polylute is still the home app and locks again.
     */
    override fun restoreHonorLauncher(openSelector: () -> Boolean, onResult: (SystemSurfaceOutcome) -> Unit) {
        openThroughLiftedLockTask(onResult = onResult) {
            val cleared = prepareHonorHomeRecovery()
            if (cleared) {
                if (openSelector()) {
                    DapHomePickerSession.launched()
                } else {
                    settleHomeExit()
                    maybeEnterDedicatedMode()
                }
            }
            cleared
        }
    }

    /**
     * B34-fix2 R1: the picker the listener opened with "Choose home app" is over (the activity cannot be resumed while it is in front, and
     * a start with nothing in front means it never opened or the process died meanwhile). Another launcher is home: the exit stands and
     * nothing is put back, so nothing locks. Polylute is still home (cancelled, or picked again): the DAP policy is back and the lock that
     * follows ([maybeEnterDedicatedMode], right after this at every caller) is the one the dialog promised. Run before any reconcile, and
     * not on a window focus change: a focus change *during* the exit is what used to put the policy back.
     */
    private fun settleHomeExit() {
        // a resume between the picker's launch and the activity going away to it is not the picker coming back
        if (!DapHomePickerSession.shouldSettleNow()) return
        val settlement = runCatching { dapDeviceOwnerController.settleHomeExit() }
            .onFailure { error -> Timber.tag("DapContainment").e(error, "Unable to settle the home exit") }
            .getOrNull() ?: return
        DapHomePickerSession.settled()
        if (settlement == HomeExitSettlement.NONE) return
        Timber.tag("DapContainment").i("Home picker settled: %s", settlement)
        ownerEpoch.intValue++
    }

    // ── B34: the Pinned and Dedicated levels' actions ───────────────────────────────────────────────

    override fun requestAppPinning(): Boolean {
        val state = productModeController.currentState()
        // a Device Owner's Lock Task is Dedicated's; a pin only makes sense as the home app
        if (!state.isPolyluteHome || state.isDeviceOwner) return false
        return runCatching {
            startLockTask()
            true
        }.getOrElse { error ->
            Timber.tag("DapContainment").e(error, "Unable to ask Android to pin the app")
            false
        }
    }

    override fun releaseAppPinning(): Boolean {
        if (dapDeviceOwnerController.lockTaskModeState() != ActivityManager.LOCK_TASK_MODE_PINNED) return true
        runCatching { stopLockTask() }
            .onFailure { error -> Timber.tag("DapContainment").e(error, "Unable to release the app pin") }
        return dapDeviceOwnerController.lockTaskModeState() != ActivityManager.LOCK_TASK_MODE_PINNED
    }

    /**
     * "Dejar de ser propietario del dispositivo" ([DapReleaseOwnerCoordinator] spells the order and the endings).
     *
     * B34-fix1: the starting state is read before anything is touched ([DapDeviceOwnerController.ownerStart]) and a failed release puts
     * back *that*, not what [productModeController] infers: the mode reads "not dedicated" the moment the release has cleared the policies
     * it is inferred from (R1). Ownership is asked with a query that can say "unknown" ([DapDeviceOwnerController.ownerPresence]), never
     * `currentState()` turned into false (R2). A cancellation (this activity is destroyed) closes the window and settles the policies in
     * the coordinator, under NonCancellable; the Lock Task entry is the next activity's resume (R3).
     */
    override fun releaseDeviceOwner(onResult: (DapReleaseResult) -> Unit) {
        // one lift at a time: a second tap, or a surface opener, while this runs would share its window and its Lock Task
        if (systemSurfaceOpening) return
        systemSurfaceOpening = true
        Timber.tag("DapContainment").i("Release of the Device Owner role requested")
        lifecycleScope.launch {
            val result = try {
                newReleaseCoordinator().release()
            } finally {
                systemSurfaceOpening = false
            }
            Timber.tag("DapContainment").i("Release of the Device Owner role: %s", result)
            ownerEpoch.intValue++
            onResult(result)
        }
    }

    private fun newReleaseCoordinator() =
        DapReleaseOwnerCoordinator(
            ownerPresence = dapDeviceOwnerController::ownerPresence,
            readStart = dapDeviceOwnerController::ownerStart,
            beginWindow = {
                DapMaintenanceSession.begin()
                scheduleMaintenanceExpiry()
            },
            stopLockTask = ::releaseLockTaskForMaintenance,
            clearPolicies = dapDeviceOwnerController::clearAllOwnerPolicies,
            clearPersistentHome = dapDeviceOwnerController::clearPersistentHome,
            clearDeviceOwner = { dapDeviceOwnerController.clearDeviceOwner() },
            removeActiveAdmin = dapDeviceOwnerController::removeActiveAdminIfAny,
            resetRequestToLauncher = { productModeController.requestLevel(ProductLevel.LAUNCHER) },
            homeIsPolylute = dapDeviceOwnerController::isPolyluteHome,
            endWindow = {
                DapMaintenanceSession.end()
                cancelMaintenanceExpiry()
            },
            restorePolicies = dapDeviceOwnerController::restoreOwnerStart,
            relock = {
                DapLockTaskStateEvents.prepareForTransition()
                runCatching { startLockTask() }
                    .onFailure { error -> Timber.tag("DapContainment").e(error, "Unable to request Lock Task after a failed release") }
                awaitLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED)
            },
        )

    private fun openThroughLiftedLockTask(onResult: (SystemSurfaceOutcome) -> Unit, start: () -> Boolean) {
        if (systemSurfaceOpening) return
        systemSurfaceOpening = true
        lifecycleScope.launch {
            val outcome = try {
                DapSystemSurfaceCoordinator(
                    lockTaskModeState = dapDeviceOwnerController::lockTaskModeState,
                    beginWindow = {
                        DapMaintenanceSession.begin()
                        scheduleMaintenanceExpiry()
                    },
                    liftLockTask = ::releaseLockTaskForMaintenance,
                    start = start,
                    abort = {
                        DapMaintenanceSession.end()
                        cancelMaintenanceExpiry()
                        restoreDedicatedModeAfterTransitionFailure()
                    },
                    putContainmentBack = ::exitAuthenticatedMaintenanceNow,
                ).open()
            } finally {
                systemSurfaceOpening = false
            }
            Timber.tag("DapContainment").i("System surface: %s", outcome)
            onResult(outcome)
        }
    }

    /**
     * Launches a known user-requested system surface without persisting authentication.
     *
     * The authority is the Maintenance session, not the product mode. The mode check that used to
     * be here stood in for "we are inside a valid Maintenance context" and made Android Settings
     * unreachable from an App-Mode Maintenance session, which is a contradiction: opening the
     * system's own settings needs no Device Owner privilege and no containment to suspend, and
     * [DapMaintenanceSession.beginExternalFlow] already refuses unless the session is authenticated.
     */
    fun launchAuthenticatedMaintenanceSystemFlow(intent: Intent, restoreOnReturn: Boolean = false): Boolean {
        // the exit kind is written with the state change, into the window itself (R2): flag, session and exit kind move together
        if (!DapMaintenanceSession.beginExternalFlow(restoreOnReturn)) {
            return false
        }
        scheduleMaintenanceExpiry()
        return runCatching {
            startActivity(intent)
            true
        }.getOrElse { error ->
            Timber.tag("DapContainment").e(error, "Unable to open Maintenance system flow")
            DapMaintenanceSession.returnFromExternalFlow()
            scheduleMaintenanceExpiry()
            false
        }
    }

    private fun scheduleMaintenanceExpiry() {
        maintenanceExpiryJob?.cancel()
        val delayMs = DapMaintenanceSession.millisUntilExpiry() ?: return
        maintenanceExpiryJob = lifecycleScope.launch {
            delay(delayMs.coerceAtLeast(1L))
            if (DapMaintenanceSession.expireIfNeeded()) {
                Timber.tag("DapContainment").i("Maintenance session expired")
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    // `exitAuthenticatedMaintenance` is already mode-aware: it restores containment
                    // in Dedicated Mode and just ends the session otherwise. Going straight to
                    // `returnToDedicatedMode` asked an App-Mode device to re-enter a lock task it
                    // never had.
                    exitAuthenticatedMaintenance { }
                }
            } else {
                scheduleMaintenanceExpiry()
            }
        }
    }

    private fun cancelMaintenanceExpiry() {
        maintenanceExpiryJob?.cancel()
        maintenanceExpiryJob = null
    }

    private fun reconcileMaintenanceSessionOnResume() {
        when (DapMaintenanceSession.reconcileOnResume()) {
            WindowReturn.RESTORE_AFTER_EXPIRY -> {
                Timber.tag("DapContainment").i("Expired Maintenance return; restoring containment")
                exitAuthenticatedMaintenance { }
            }
            // B32-Q6: the app is back from a surface the System page opened: the window closes now, with the Maintenance exit, so Lock
            // Task, the allowlist and the policy are back before the listener has touched anything. (There is no Maintenance screen left
            // to press Back on and do it.) B32-fix1 R2: the obligation is read from the window, so this runs on a recreated activity too.
            WindowReturn.RESTORE_AFTER_SURFACE -> {
                Timber.tag("DapContainment").i("Back from a system surface; restoring containment")
                exitAuthenticatedMaintenance { }
            }
            WindowReturn.KEEP_OPEN -> scheduleMaintenanceExpiry()
            WindowReturn.NONE -> cancelMaintenanceExpiry()
        }
    }

    /**
     * Opens one user-initiated Android confirmation without broadly allowlisting Settings.
     * Any return to MainActivity ends this narrow window and restores Lock Task in onResume.
     */
    fun beginTemporarySystemConfirmation(onResult: (Boolean) -> Unit) {
        if (!productModeController.isDedicatedActive()) {
            onResult(true)
            return
        }
        DapTemporarySystemFlowSession.begin()
        lifecycleScope.launch {
            val requested = runCatching {
                if (dapDeviceOwnerController.lockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                    DapLockTaskStateEvents.prepareForTransition()
                    stopLockTask()
                }
                true
            }.getOrElse { error ->
                Timber.tag("DapContainment").e(error, "Unable to open temporary system flow")
                false
            }
            val stopped = requested && awaitLockTaskModeState(ActivityManager.LOCK_TASK_MODE_NONE)
            if (!stopped) {
                DapTemporarySystemFlowSession.end()
                restoreDedicatedModeAfterTransitionFailure()
            }
            onResult(stopped)
        }
    }

    fun finishTemporarySystemConfirmation() {
        DapTemporarySystemFlowSession.end()
        maybeEnterDedicatedMode()
    }

    /** Leaves all recovery components installed while clearing only DAP containment policy. */
    fun prepareHonorHomeRecovery(): Boolean {
        if (!productModeController.isDedicatedActive() ||
            !DapMaintenanceSession.isActive ||
            dapDeviceOwnerController.lockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE
        ) return false
        val cleared = runCatching {
            // B34-fix2 R1: records the exit first, then clears the flag, the allowlist, the keyguard hold and the persistent HOME
            dapDeviceOwnerController.leaveDedicatedByChoice()
        }.getOrElse { error ->
            Timber.tag("DapContainment").e(error, "Unable to prepare HONOR HOME recovery")
            false
        }
        if (cleared) DapMaintenanceSession.end()
        return cleared
    }

    private suspend fun awaitLockTaskModeState(expectedState: Int): Boolean {
        if (dapDeviceOwnerController.lockTaskModeState() == expectedState) return true
        val observed = withTimeoutOrNull(LOCK_TASK_TRANSITION_TIMEOUT_MS) {
            DapLockTaskStateEvents.await(expectedState)
            true
        } ?: false
        if (observed) return true
        return dapDeviceOwnerController.lockTaskModeState() == expectedState
    }

    private suspend fun restoreDedicatedModeAfterTransitionFailure() {
        if (!productModeController.isDedicatedActive()) return
        runCatching { dapDeviceOwnerController.enableDedicatedMode() }
            .onFailure { error ->
                Timber.tag("DapContainment").e(error, "Unable to restore policy after transition failure")
            }
        DapLockTaskStateEvents.prepareForTransition()
        maybeEnterDedicatedMode()
        if (!awaitLockTaskModeState(ActivityManager.LOCK_TASK_MODE_LOCKED)) {
            Timber.tag("DapContainment").e("Lock Task recovery timed out")
        }
    }

    private fun maybeEnterDedicatedMode() {
        if (!productModeController.isDedicatedActive()) return
        val state = runCatching { dapDeviceOwnerController.currentState() }.getOrElse { error ->
            Timber.tag("DapContainment").e(error, "Unable to inspect Lock Task state")
            return
        }
        if (!DapContainmentPolicy.shouldEnterLockTask(
                dedicatedModeEnabled = state.isDedicatedModeEnabled,
                maintenanceSessionActive = DapMaintenanceSession.isActive ||
                    DapTemporarySystemFlowSession.isActive,
                isDeviceOwner = state.isDeviceOwner,
                isLockTaskPermitted = state.isLockTaskPermitted,
                lockTaskModeState = state.lockTaskModeState,
            )
        ) return

        runCatching { startLockTask() }
            .onSuccess {
                Timber.tag("DapContainment").i(
                    "Lock Task requested; state=${dapDeviceOwnerController.lockTaskModeState()}"
                )
            }
            .onFailure { error ->
                Timber.tag("DapContainment").e(error, "Unable to enter Lock Task")
            }
    }

    private fun updateHomeLaunchState(intent: Intent?) {
        // B34: a home app swallows Back at its root at every level (a launcher that finished would be relaunched at once); App Mode does not.
        homeRootBackGuard.isEnabled =
            productModeController.currentState().level != null &&
                intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
    }

    private fun handleUsbAudioAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        dacProfileRepository.reconcile()
        // The engine owns the one permission path and claims the DAC only when Direct is chosen.
        // The helper used here force-claimed on attach — detaching snd-usb-audio while the route was
        // still Android — and raised a second, independent request (AUD-003, AUD-031).
        dualPlayerEngine.refreshUsbAudioDiagnostics()
    }

    /**
     * Lets the engine raise the USB permission dialog without duplicating containment logic.
     *
     * The dialog cannot be shown while Lock Task holds the task list: AOSP's `ActivityStarter`
     * refuses to launch the system's `UsbPermissionActivity` unless it carries
     * `ATTR_IS_USB_PERMISSION_ACTIVITY`, and the HONOR's `SystemUIOversea` variant does not. The
     * launch is issued and immediately rejected — measured here as `mLastStartActivityResult=101`
     * with the record left at `launchCount=0 ... windows=[]` — so nothing is drawn and the
     * permission callback never arrives.
     *
     * This routes that one request through [beginTemporarySystemConfirmation], the same supported
     * mechanism the container already uses for Bluetooth enablement and other required system UI:
     * suspend Lock Task, let the system dialog show, restore containment in `onResume`. Returns
     * false when the suspension could not be taken, so the engine knows nothing was shown and can
     * allow a later retry rather than treating the attachment as already asked.
     */
    /** Kept so `onDestroy` removes only this activity's requester (AUD-030). */
    private var usbPermissionRequester: ((() -> Unit) -> Boolean)? = null

    private fun installUsbPermissionRequester() {
        val requester: (() -> Unit) -> Boolean = requester@{ request ->
            // Only a resumed activity may raise the dialog: a destroyed one cannot, and suspending
            // Lock Task from the background or with the screen off is AUD-101. A refusal is
            // forgotten by the engine and asked again from onResume.
            if (!UsbPermissionCoordinator.requesterAdmits(
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                )) return@requester false
            val needsSuspension = DapContainmentPolicy.needsLockTaskSuspensionForSystemDialog(
                dedicatedModeEnabled = productModeController.isDedicatedActive(),
                lockTaskModeState = dapDeviceOwnerController.lockTaskModeState(),
            )
            if (!needsSuspension) {
                request()
                return@requester true
            }
            beginTemporarySystemConfirmation { flowReady ->
                if (flowReady) request()
            }
            true
        }
        usbPermissionRequester = requester
        dualPlayerEngine.setUsbPermissionRequester(requester)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        when {
            intent.action == MainActivityIntentContract.ACTION_SHUFFLE_ALL -> {
                Timber.tag("TileDebug").d("handleIntent: ACTION_SHUFFLE_ALL received")
                playerViewModel.triggerShuffleAllFromTile()
                intent.action = null
            }
            
            intent.action == MainActivityIntentContract.ACTION_OPEN_PLAYLIST -> {
                intent.getStringExtra(MainActivityIntentContract.EXTRA_PLAYLIST_ID)?.let { playlistId ->
                    _pendingPlaylistNavigation.value = playlistId
                }
                intent.action = null
            }

            intent.getBooleanExtra("ACTION_SHOW_PLAYER", false) -> {
                playerViewModel.showPlayer()
            }

            intent.action == android.content.Intent.ACTION_VIEW && intent.data != null -> {
                intent.data?.let { uri ->
                    persistUriPermissionIfNeeded(intent, uri)
                    playerViewModel.playExternalUri(uri)
                }
                clearExternalIntentPayload(intent)
            }

            intent.action == android.content.Intent.ACTION_SEND && intent.type?.startsWith("audio/") == true -> {
                resolveStreamUri(intent)?.let { uri ->
                    persistUriPermissionIfNeeded(intent, uri)
                    playerViewModel.playExternalUri(uri)
                }
                clearExternalIntentPayload(intent)
            }
            
            intent.action == "com.lostf1sh.pixelplayeross.ACTION_PLAY_SONG" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                     intent.getParcelableExtra("song", com.lostf1sh.pixelplayeross.data.model.Song::class.java)?.let { song ->
                         playerViewModel.playSong(song)
                     }
                } else {
                     @Suppress("DEPRECATION")
                     intent.getParcelableExtra<com.lostf1sh.pixelplayeross.data.model.Song>("song")?.let { song ->
                         playerViewModel.playSong(song)
                     }
                }
                intent.action = null
            }
        }
    }
    
    private fun resolveStreamUri(intent: Intent): android.net.Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)?.let { return it }
        } else {
            @Suppress("DEPRECATION")
            val legacyUri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
            if (legacyUri != null) return legacyUri
        }

        intent.clipData?.let { clipData ->
            if (clipData.itemCount > 0) {
                return clipData.getItemAt(0).uri
            }
        }

        return intent.data
    }

    private fun persistUriPermissionIfNeeded(intent: Intent, uri: android.net.Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val hasPersistablePermission = intent.flags and android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
            if (hasPersistablePermission) {
                val takeFlags = intent.flags and (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (takeFlags != 0) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (securityException: SecurityException) {
                        Timber.tag("MainActivity").w(securityException, "Unable to persist URI permission for $uri")
                    } catch (illegalArgumentException: IllegalArgumentException) {
                        Timber.tag("MainActivity").w(illegalArgumentException, "Persistable URI permission not granted for $uri")
                    }
                }
            }
        }
    }

    private fun clearExternalIntentPayload(intent: Intent) {
        intent.data = null
        intent.clipData = null
        intent.removeExtra(android.content.Intent.EXTRA_STREAM)
    }

    @Composable
    private fun MusicAccessPrompt(
        onAllow: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.music_access_prompt_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.music_access_prompt_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onAllow) {
                    Text(stringResource(R.string.music_access_prompt_action))
                }
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Composable
    private fun MainAppContent(
        playerViewModel: PlayerViewModel,
        mainViewModel: MainViewModel
    ) = traceSection("MainActivity.MainAppContent") {
        val navController = rememberNavController()
        val isSyncing by mainViewModel.isSyncing.collectAsStateWithLifecycle()
        val hasCompletedInitialSync by mainViewModel.hasCompletedInitialSync.collectAsStateWithLifecycle()
        val syncProgress by mainViewModel.syncProgress.collectAsStateWithLifecycle()

        val context = LocalContext.current
        LaunchedEffect(Unit) {
            mainViewModel.syncFailed.collect {
                Toast.makeText(
                    context,
                    context.getString(R.string.sync_failed_toast),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val isMediaControllerReady by playerViewModel.isMediaControllerReady.collectAsStateWithLifecycle()
        
        val pendingPlaylistNav by _pendingPlaylistNavigation.collectAsStateWithLifecycle()
        var processedPlaylistId by remember { mutableStateOf<String?>(null) }
        
        LaunchedEffect(pendingPlaylistNav, isMediaControllerReady) {
            val playlistId = pendingPlaylistNav
            if (playlistId != null && playlistId != processedPlaylistId && isMediaControllerReady) {
                processedPlaylistId = playlistId
                var success = false
                var attempts = 0
                while (!success && attempts < 50) {
                    try {
                        success = navController.navigateSafely(Screen.PlaylistDetail.createRoute(playlistId))
                        if (success) {
                            _pendingPlaylistNavigation.value = null
                        } else {
                            delay(100)
                            attempts++
                        }
                    } catch (e: IllegalArgumentException) {
                        delay(100)
                        attempts++
                    }
                }
            } else if (playlistId == null) {
                processedPlaylistId = null
            }
        }

        var canShowLoadingIndicator by remember { mutableStateOf(false) }
        var loadingShownTimestamp by remember { mutableStateOf(0L) }
        val minimumDisplayDuration = 1500L

        val shouldPotentiallyShowLoading = isSyncing && !hasCompletedInitialSync

        LaunchedEffect(shouldPotentiallyShowLoading) {
            if (shouldPotentiallyShowLoading) {
                delay(300L)
                if (mainViewModel.isSyncing.value && !mainViewModel.hasCompletedInitialSync.value) {
                    canShowLoadingIndicator = true
                    loadingShownTimestamp = System.currentTimeMillis()
                }
            } else {
                if (canShowLoadingIndicator && loadingShownTimestamp > 0) {
                    val elapsed = System.currentTimeMillis() - loadingShownTimestamp
                    val remaining = minimumDisplayDuration - elapsed
                    if (remaining > 0) {
                        delay(remaining)
                    }
                }
                canShowLoadingIndicator = false
                loadingShownTimestamp = 0L
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            MainUI(playerViewModel, navController)

            if (canShowLoadingIndicator) {
                LoadingOverlay(syncProgress)
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Composable
    private fun MainUI(
        playerViewModel: PlayerViewModel,
        navController: NavHostController
    ) = traceSection("MainActivity.MainUI") {
        val commonNavItems = remember {
            persistentListOf(
                BottomNavItem("Home", R.drawable.rounded_home_24, R.drawable.home_24_rounded_filled, Screen.Home),
                BottomNavItem("Search", R.drawable.rounded_search_24, R.drawable.rounded_search_24, Screen.Search),
                BottomNavItem("Library", R.drawable.rounded_library_music_24, R.drawable.round_library_music_24, Screen.Library)
            )
        }
        // The bar's glyphs. Words come from the routes themselves so they stay in one place.
        val dapNavItems = remember {
            listOf(
                DapNavItem(R.string.tab_home, DapIconKind.Home),
                DapNavItem(R.string.search, DapIconKind.Search),
                DapNavItem(R.string.tab_library, DapIconKind.Library),
            )
        }
        val savedRootRouteState = rememberSaveable { mutableStateOf<String?>(null) }

        LaunchedEffect(userPreferencesRepository) {
            if (savedRootRouteState.value == null) {
                savedRootRouteState.value = DapRootTab.fromLaunchTabOrHome(
                    userPreferencesRepository.launchTabFlow.first()
                ).route
            }
        }

        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val detailRoute = navBackStackEntry?.destination?.route
        // B32-Q2: the Library asked to show a song (from Stats or a collection) is a destination that draws nothing: the roots stay in
        // front, the Library is the one that shows, and the page that asked is under it on the stack, so Back returns there.
        val locatesLibrary = isLibraryLocateRoute(detailRoute)
        val isAtRootShell = detailRoute == null || detailRoute == DAP_DETAIL_HOST_ROUTE || locatesLibrary
        val detailRouteState = rememberUpdatedState(detailRoute)
        // The root that is in front: the one the listener chose, or the Library while it is showing a song for another page. The choice
        // itself is not touched, so Back from Stats lands where the listener was.
        val shownRootRouteState = remember {
            derivedStateOf { shownRootRoute(detailRouteState.value, savedRootRouteState.value) }
        }
        // The chrome policy is asked about the route the user is actually looking at. At the root
        // shell the navigation route is the synthetic host (`__dap_root_shell__`), which no policy
        // can classify — so passing it made Home, Search and Library look like unmapped pages and
        // stripped the bottom bar and the shelf off all three. The selected root tab is the real
        // route here; the host string only says which composable is hosting it.
        val currentRoute = if (isAtRootShell) {
            shownRootRouteState.value ?: Screen.Home.route
        } else {
            detailRoute
        }
        // Changing page closes the keyboard. The three root tabs stay composed while hidden, so a
        // focused Search field would otherwise keep the keyboard up on top of Home or Library.
        val shellFocusManager = LocalFocusManager.current
        val shellKeyboard = LocalSoftwareKeyboardController.current
        val keyboardRouteSeen = remember { booleanArrayOf(false) }
        LaunchedEffect(currentRoute) {
            if (keyboardRouteSeen[0]) dismissKeyboard(shellFocusManager, shellKeyboard)
            keyboardRouteSeen[0] = true
        }
        val onRootTabSelected: (String) -> Unit = { requestedRoute ->
            val targetTab = DapRootTab.fromRouteOrHome(requestedRoute)
            savedRootRouteState.value = targetTab.route
            if (detailRoute != null && detailRoute != DAP_DETAIL_HOST_ROUTE) {
                navController.popBackStack(DAP_DETAIL_HOST_ROUTE, inclusive = false)
            }
        }
        var isSearchBarActive by remember { mutableStateOf(false) }
        val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
        val useNavigationRail =
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

        // Bottom-navigation visibility belongs to the same route policy as the player shelf, so the
        // two can never disagree. The old list here omitted every profile and EQ route — so the bar
        // showed under Headphone Profile, DAC Profile, Safety Max and the EQ editor.
        val shouldHideNavigationBar by remember(currentRoute, isSearchBarActive) {
            derivedStateOf {
                if (isAtRootShell && isSearchBarActive) {
                    true
                } else {
                    !DapSurfacePolicy.showsBottomNavigation(currentRoute)
                }
            }
        }


        val navBarStyle by playerViewModel.navBarStyle.collectAsStateWithLifecycle()
        val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
        val navBarCornerRadiusRaw by playerViewModel.navBarCornerRadius.collectAsStateWithLifecycle()
        val navBarCornerRadius = sanitizeNavBarCornerRadius(navBarCornerRadiusRaw)
        val useSmoothCorners by playerViewModel.useSmoothCorners.collectAsStateWithLifecycle()
        val hapticsEnabled by playerViewModel.hapticsEnabled.collectAsStateWithLifecycle()
        val rootView = LocalView.current
        val platformHapticFeedback = LocalHapticFeedback.current
        val appHapticsConfig = remember(hapticsEnabled) {
            AppHapticsConfig(enabled = hapticsEnabled)
        }
        val scopedHapticFeedback = remember(platformHapticFeedback, appHapticsConfig.enabled) {
            if (appHapticsConfig.enabled) platformHapticFeedback else NoOpHapticFeedback
        }

        val systemNavBarInset = sanitizeNavigationBarBottomInset(
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        )

        LaunchedEffect(hapticsEnabled, rootView) {
            rootView.isHapticFeedbackEnabled = hapticsEnabled
            rootView.rootView?.isHapticFeedbackEnabled = hapticsEnabled
        }

        val horizontalPadding = if (navBarStyle == NavBarStyle.DEFAULT) {
            if (systemNavBarInset > 30.dp) 14.dp else systemNavBarInset
        } else {
            0.dp
        }
        val animatedBottomBarPadding by animateDpAsState(
            targetValue = if (navBarStyle == NavBarStyle.FULL_WIDTH) 0.dp else systemNavBarInset,
            animationSpec = tween(400),
            label = "BottomBarPadding"
        )
        val bottomBarPadding = animatedBottomBarPadding
        val navBarHeight = resolveNavBarSurfaceHeight(navBarStyle, systemNavBarInset, navBarCompactMode)
        val navBarOccupiedHeight by remember(systemNavBarInset, navBarCompactMode, useNavigationRail) {
            derivedStateOf {
                if (useNavigationRail) {
                    0.dp
                } else {
                    resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
                }
            }
        }
        val navBarVisibilityProgressState = animateFloatAsState(
            targetValue = if (shouldHideNavigationBar) 0f else 1f,
            animationSpec = tween(
                durationMillis = 220,
                easing = LinearOutSlowInEasing
            ),
            label = "NavBarVisibilityProgress"
        )
        val navBarVisibilityProgress by navBarVisibilityProgressState
        val visibleNavBarOccupiedHeight by remember(navBarOccupiedHeight, navBarVisibilityProgress) {
            derivedStateOf { navBarOccupiedHeight * navBarVisibilityProgress }
        }
        val miniPlayerBottomMargin by remember(systemNavBarInset, visibleNavBarOccupiedHeight) {
            derivedStateOf {
                if (visibleNavBarOccupiedHeight > systemNavBarInset) {
                    visibleNavBarOccupiedHeight
                } else {
                    systemNavBarInset
                }
            }
        }
        val shouldRenderNavigationBar by remember(shouldHideNavigationBar, navBarVisibilityProgress) {
            derivedStateOf {
                !shouldHideNavigationBar || navBarVisibilityProgress > 0.01f
            }
        }
        val isNavBarEffectivelyHidden by remember(shouldHideNavigationBar, navBarVisibilityProgress) {
            derivedStateOf {
                shouldHideNavigationBar && navBarVisibilityProgress <= 0.01f
            }
        }

        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        LaunchedEffect(userPreferencesRepository) {
            userPreferencesRepository.clearDeprecatedPlayerSheetPreference()
        }

        CompositionLocalProvider(
            LocalAppHapticsConfig provides appHapticsConfig,
            LocalHapticFeedback provides scopedHapticFeedback
        ) {
            AppSidebarDrawer(
                drawerState = drawerState,
                selectedRoute = {
                    if (isAtRootShell) {
                        DapRootTab.fromRouteOrHome(shownRootRouteState.value).route
                    } else {
                        currentRoute
                    }
                },
                onDestinationSelected = { destination ->
                    scope.launch { drawerState.close() }
                    when (destination) {
                        DrawerDestination.Home -> onRootTabSelected(Screen.Home.route)
                        DrawerDestination.Settings -> navController.navigateSafely(Screen.Settings.route)
                    }
                }
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (useNavigationRail) {
                    AnimatedVisibility(
                        visible = !shouldHideNavigationBar,
                        enter = expandHorizontally() + fadeIn(),
                        exit = shrinkHorizontally() + fadeOut()
                    ) {
                        val onSearchIconDoubleTap = remember(playerViewModel) {
                            { playerViewModel.onSearchNavIconDoubleTapped() }
                        }
                        PlayerNavigationRail(
                            navItems = commonNavItems,
                            selectedRootRouteState = shownRootRouteState,
                            onRootTabSelected = onRootTabSelected,
                            onSearchIconDoubleTap = onSearchIconDoubleTap
                        )
                    }
                }

                Scaffold(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                bottomBar = {
                    if (!useNavigationRail && shouldRenderNavigationBar) {
                        val currentSongId by remember {
                            playerViewModel.stablePlayerState
                                .map { it.currentSong?.id }
                                .distinctUntilChanged()
                        }.collectAsStateWithLifecycle(initialValue = null)
                        val showPlayerContentArea = currentSongId != null
                        val navBarElevation = 3.dp

                        val animatedNavBarCornerRadius = animateDpAsState(
                            targetValue = navBarCornerRadius.dp,
                            animationSpec = tween(400),
                            label = "NavBarCornerRadius"
                        )

                        val animatedDefaultTopCornerRadius = animateDpAsState(
                            targetValue = if (showPlayerContentArea) 10.dp else navBarCornerRadius.dp,
                            animationSpec = tween(400),
                            label = "NavBarDefaultTopCornerRadius"
                        )

                        var componentHeightPx by remember { mutableStateOf(0) }
                        val density = LocalDensity.current
                        val shadowOverflowPx = remember(navBarElevation, density) {
                            with(density) { (navBarElevation * 8).toPx() }
                        }
                        val bottomBarPaddingPx = remember(bottomBarPadding, density) {
                            with(density) { bottomBarPadding.toPx() }
                        }
                        val navBarElevationPx = remember(navBarElevation, density) {
                            with(density) { navBarElevation.toPx() }
                        }
                        val navBarShapeCache = remember { NavBarShapeCache() }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(navBarOccupiedHeight)
                                .clipToBounds()
                        ) {
                            val onSearchIconDoubleTap = remember(playerViewModel) {
                                { playerViewModel.onSearchNavIconDoubleTapped() }
                            }

                            // One bar treatment: flat, full width, a hairline on top. The bar
                            // never floats and never rounds; it is part of the device's face.
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .onSizeChanged { componentHeightPx = it.height }
                                    .graphicsLayer {
                                        val expansionHide = if (showPlayerContentArea) {
                                            playerViewModel.playerContentExpansionFraction.value.coerceIn(0f, 1f)
                                        } else {
                                            0f
                                        }
                                        val routeHide = (1f - navBarVisibilityProgressState.value).coerceIn(0f, 1f)
                                        val hideFraction = maxOf(expansionHide, routeHide)
                                        translationY = (componentHeightPx + bottomBarPaddingPx) * hideFraction
                                        alpha = 1f
                                    }
                                    .height(DapHeights.Nav + systemNavBarInset),
                                color = Dap.surface
                            ) {
                                DapBottomNav(
                                    items = dapNavItems,
                                    selectedIndex = DapRootTab
                                        .fromRouteOrHome(shownRootRouteState.value)
                                        .ordinal,
                                    onSelect = { index ->
                                        onRootTabSelected(commonNavItems[index].screen.route)
                                    },
                                    contentPadding = PaddingValues(bottom = systemNavBarInset),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
                ) { innerPadding ->
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val density = LocalDensity.current
                        val containerHeight = this.maxHeight
                        val screenHeightPx = remember(containerHeight, density) {
                            with(density) { containerHeight.toPx() }
                        }

                        val miniPlayerSong by remember {
                            playerViewModel.stablePlayerState.map { it.currentSong }.distinctUntilChanged()
                        }.collectAsStateWithLifecycle(initialValue = null)
                        val preparingMiniSongId by remember {
                            playerViewModel.playerUiState.map { it.preparingSongId }.distinctUntilChanged()
                        }.collectAsStateWithLifecycle(initialValue = null)
                        val showPlayerContentInitially = miniPlayerSong != null
                        val measuredMiniHeight = rememberMiniPlayerHeight(miniPlayerSong,
                            preparingMiniSongId != null && preparingMiniSongId == miniPlayerSong?.id, maxWidth)
                        // Which surfaces carry the player shelf is a property of the route, owned by
                        // one policy object rather than by a set each screen contributes to. An
                        // empty set here is what let the shelf sit on top of every Settings subpage.
                        val shouldHideMiniPlayer by remember(currentRoute) {
                            derivedStateOf { !DapSurfacePolicy.showsPlayerShelf(currentRoute) }
                        }

                        val miniPlayerH = with(density) { measuredMiniHeight.toPx() }
                        val totalSheetHeightWhenContentCollapsedPx = if (showPlayerContentInitially && !shouldHideMiniPlayer) miniPlayerH else 0f

                        val bottomMargin = miniPlayerBottomMargin

                        val bottomMarginPx = with(density) { bottomMargin.toPx() }
                        val sheetCollapsedTargetY = calculatePlayerSheetCollapsedTargetY(
                            containerHeightPx = screenHeightPx,
                            collapsedContentHeightPx = totalSheetHeightWhenContentCollapsedPx,
                            bottomMarginPx = bottomMarginPx,
                            // No spacer. The collapsed sheet used to be lifted an extra 8 dp off the
                            // navigation bar, which left a dead band between the mini player and the
                            // bar — and, because the sheet's own content sits directly below the mini
                            // player, a sliver of that content showed in the band: measured on the
                            // device as a 1 dp strip of artwork and glyph pixels at the mini player's
                            // bottom edge, with 8 dp of empty page below it. The shelf is flush now,
                            // and the content below it is behind the bar where it belongs.
                            bottomSpacerPx = 0f
                        )

                        Box(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // The shell decides whether a shelf exists on this route, so it also
                            // publishes the space that shelf occupies. Screens reserve exactly that
                            // and nothing more — this is the one thing that used to be assumed.
                            CompositionLocalProvider(
                                LocalMiniPlayerHeight provides measuredMiniHeight,
                                LocalDapPlayerShelfHeight provides
                                    if (shouldHideMiniPlayer || !showPlayerContentInitially) 0.dp else measuredMiniHeight
                            ) {
                                DapRootShell(
                                    selectedRootRouteState = shownRootRouteState,
                                    navController = navController,
                                    paddingValues = innerPadding,
                                    playerViewModel = playerViewModel,
                                    isDetailVisible = !isAtRootShell,
                                    onSearchBarActiveChange = { isSearchBarActive = it },
                                    onOpenSidebar = { scope.launch { drawerState.open() } },
                                )
                            }
                        }

                        val isExpandedOrExpanding by remember {
                            derivedStateOf {
                                playerViewModel.playerContentExpansionFraction.value > 0.01f
                            }
                        }
                        // The scrim reads the sheet's own progress, so the background and the
                        // player are one motion. It used to run its own 350 ms fade, which kept
                        // the background changing after a 140-220 ms sheet had already settled.
                        if (isExpandedOrExpanding) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = playerViewModel.playerContentExpansionFraction.value
                                            .coerceIn(0f, 1f)
                                    }
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerLowest.copy(
                                            alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.35f else 0.6f
                                        )
                                    )
                                    .pointerInput(Unit) {
                                        detectTapGestures {
                                            playerViewModel.collapsePlayerSheet()
                                        }
                                    }
                            )
                        }

                        CompositionLocalProvider(
                            LocalMiniPlayerHeight provides measuredMiniHeight,
                            LocalDapPlayerShelfHeight provides
                                if (shouldHideMiniPlayer || !showPlayerContentInitially) 0.dp else measuredMiniHeight
                        ) {
                            UnifiedPlayerSheetV2(
                                playerViewModel = playerViewModel,
                                sheetCollapsedTargetY = sheetCollapsedTargetY,
                                collapsedStateHorizontalPadding = horizontalPadding,
                                hideMiniPlayer = shouldHideMiniPlayer,
                                containerHeight = containerHeight,
                                navController = navController,
                                isNavBarHidden = isNavBarEffectivelyHidden
                            )
                        }
                    }
                }
            }
            }
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun LoadingOverlay(syncProgress: SyncProgress) {
        val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
            targetValue = syncProgress.progress,
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessLow
            ),
            label = "SyncProgressAnimation"
        )
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f))
                .clickable(enabled = false, onClick = {}),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                CircularWavyProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.sync_preparing_library),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                
                if (syncProgress.hasProgress) {
                    Spacer(modifier = Modifier.height(16.dp))
                    androidx.compose.material3.LinearWavyProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.sync_scanned_count,
                            syncProgress.currentCount,
                            syncProgress.totalCount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }


    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onStart() {
        super.onStart()
        LogUtils.d(this, "onStart")
        playerViewModel.onMainActivityStart()

        if (intent.getBooleanExtra("is_benchmark", false)) {
        }

        // B23-P1: no MediaController is built here. The activity used to connect one at every start and release it at
        // every stop, with an empty listener and no reader. In DAP mode every wake is an activity start, so every wake
        // opened a second full-timeline connection (4,870 items) that nothing used. The app's one controller is the
        // PlayerViewModel's: built once with the ViewModel and released in onCleared, so it already keeps the session
        // service bound across stops. Pinned by B23WiringBoundaryTest.
    }

    override fun onStop() {
        super.onStop()
        LogUtils.d(this, "onStop")
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && DapMaintenanceSession.touch()) {
            scheduleMaintenanceExpiry()
        }
        return super.dispatchTouchEvent(event)
    }

    // Overriding Activity.dispatchKeyEvent and delegating to super is the contract of the method. androidx marks
    // ComponentActivity's own override @RestrictTo(LIBRARY_GROUP_PREFIX) to keep callers off it, and lint reads the
    // super call as such a caller.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (safetyVolumeController.handleVolumeKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (!systemBarsLoggedOnResume) {
            systemBarsLoggedOnResume = true
            // Posted: the window has its insets by the time the first frame after the resume is built.
            window.decorView.post {
                Timber.tag(SYSTEM_BARS_TAG).i(
                    SystemBarsDiagnostic.describe(
                        "firstResume",
                        creationNumber,
                        decorReused = false,
                        activity = this,
                        language = effectiveLanguage(resources.configuration),
                        restoredState = false,
                    )
                )
            }
        }
        if (DapTemporarySystemFlowSession.isActive) {
            DapTemporarySystemFlowSession.end()
        }
        settleHomeExit()
        reconcileOwnerPolicyIfHomeChanged("onResume")
        reconcileScreenTimeoutDrift("onResume")
        reconcileMaintenanceSessionOnResume()
        maybeEnterDedicatedMode()
        playerViewModel.onMainActivityResume()
        // A permission request refused while this activity was not resumed is asked now.
        dualPlayerEngine.refreshUsbAudioDiagnostics()
        // FIX-5: also answers a screen-off override request that was waiting for the app to come up.
        safetyVolumeController.attachConfirmationHost(overrideConfirmationHost)
    }

    override fun onPause() {
        DapHomePickerSession.activityPaused()
        safetyVolumeController.detachConfirmationHost(overrideConfirmationHost)
        // Over the lock screen only while resumed: a pause (screen off, the Keyguard handoff) puts the
        // flags back, and the next resume asks the controller whether they are still wanted.
        overrideConfirmationHost.setOverLockScreen(false)
        super.onPause()
    }

    override fun onDestroy() {
        languageObserver?.close()
        languageObserver = null
        window.decorView.removeCallbacks(languageGraceRecreate)
        safetyVolumeController.detachConfirmationHost(overrideConfirmationHost)
        // The readout owns a window; it must not outlive the activity that lent it a token.
        volumeHud.stop()
        cancelMaintenanceExpiry()
        // The engine is a singleton; it must not keep this activity alive through the requester.
        usbPermissionRequester?.let(dualPlayerEngine::clearUsbPermissionRequester)
        usbPermissionRequester = null
        super.onDestroy()
    }


}

/**
 * Returns a cached Shape instance for a quantized (top, bottom) radius pair.
 * Because the instance identity is stable while the radii don't move past a
 * sub-pixel threshold, the graphics layer reuses its cached Outline between
 * frames and only re-clips when the radius actually changes.
 */
private class NavBarShapeCache {
    private var lastTopPx: Float = Float.NaN
    private var lastBottomPx: Float = Float.NaN
    private var lastSmooth: Boolean = true
    private var cached: androidx.compose.ui.graphics.Shape = RectangleShape

    fun get(
        density: androidx.compose.ui.unit.Density,
        topPx: Float,
        bottomPx: Float,
        smooth: Boolean
    ): androidx.compose.ui.graphics.Shape {
        if (smooth == lastSmooth &&
            !lastTopPx.isNaN() &&
            kotlin.math.abs(topPx - lastTopPx) < 0.5f &&
            kotlin.math.abs(bottomPx - lastBottomPx) < 0.5f
        ) {
            return cached
        }
        lastTopPx = topPx
        lastBottomPx = bottomPx
        lastSmooth = smooth
        cached = with(density) {
            DynamicSmoothCornerShape(
                useSmoothCorners = smooth,
                topRadius = topPx.toDp(),
                bottomRadius = bottomPx.toDp()
            )
        }
        return cached
    }
}

/**
 * Fixed-radius corner shape. Swaps AbsoluteSmoothCornerShape for a plain
 * RoundedCornerShape when smooth corners are disabled in settings. The radius
 * values are identical in both branches, so the animated radius behavior is
 * unchanged regardless of which delegate is active. The resulting Outline is
 * cached per (size, layoutDirection) so repeated draws are cheap.
 */
private class DynamicSmoothCornerShape(
    private val useSmoothCorners: Boolean,
    private val topRadius: androidx.compose.ui.unit.Dp,
    private val bottomRadius: androidx.compose.ui.unit.Dp
) : androidx.compose.ui.graphics.Shape {

    private var cachedSize: androidx.compose.ui.geometry.Size =
        androidx.compose.ui.geometry.Size.Unspecified
    private var cachedLayoutDirection: androidx.compose.ui.unit.LayoutDirection? = null
    private var cachedOutline: androidx.compose.ui.graphics.Outline? = null

    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        cachedOutline?.let {
            if (cachedSize == size && cachedLayoutDirection == layoutDirection) return it
        }

        val delegate: androidx.compose.ui.graphics.Shape = if (useSmoothCorners) {
            AbsoluteSmoothCornerShape(
                cornerRadiusTL = topRadius,
                smoothnessAsPercentTL = 60,
                cornerRadiusTR = topRadius,
                smoothnessAsPercentTR = 60,
                cornerRadiusBL = bottomRadius,
                smoothnessAsPercentBL = 60,
                cornerRadiusBR = bottomRadius,
                smoothnessAsPercentBR = 60
            )
        } else {
            RoundedCornerShape(
                topStart = topRadius,
                topEnd = topRadius,
                bottomEnd = bottomRadius,
                bottomStart = bottomRadius
            )
        }

        return delegate.createOutline(size, layoutDirection, density).also {
            cachedSize = size
            cachedLayoutDirection = layoutDirection
            cachedOutline = it
        }
    }
}
