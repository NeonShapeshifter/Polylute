package com.lostf1sh.pixelplayeross.presentation.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.lostf1sh.pixelplayeross.data.backup.model.BackupHistoryEntry
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.dap.admin.DapReleaseOutcome
import com.lostf1sh.pixelplayeross.dap.admin.DapReleaseResult
import com.lostf1sh.pixelplayeross.dap.admin.DapReleaseRollback
import com.lostf1sh.pixelplayeross.dap.admin.DapReturnOutcome
import com.lostf1sh.pixelplayeross.dap.admin.ModeLevelHost
import com.lostf1sh.pixelplayeross.dap.admin.SystemSurfaceHost
import com.lostf1sh.pixelplayeross.dap.admin.SystemSurfaceOutcome
import com.lostf1sh.pixelplayeross.dap.mode.ProductLevel
import com.lostf1sh.pixelplayeross.dap.mode.ProductModeState
import com.lostf1sh.pixelplayeross.data.preferences.RandomMode
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.BackupSettingsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsViewModel
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.theme.DapAppearanceChoice
import com.lostf1sh.pixelplayeross.ui.theme.storedPairFor

/*
 * B21: where every Settings control meets the thing it writes.
 *
 * A page (`AudioSettingsScreen`, …) calls an `…Actions` interface and nothing else. These are the
 * implementations the host hands it: one line per control, each naming the ViewModel method (and so the
 * preference) it has always written, or the route it has always opened. They hold no state and make no
 * decisions of their own, with the one exception the theme row keeps from before (two writes, in an order).
 *
 * `navigate` is the host's `navController.navigateSafely(route)`, passed as a function so the wiring can be
 * exercised on the JVM with a recording lambda instead of a NavController.
 */

internal class AppearanceSettingsActionsImpl(
    private val vm: SettingsViewModel,
    private val navigate: (String) -> Unit,
) : AppearanceSettingsActions {
    // B16-G3: no recreate() here. MainActivity recreates itself once when the language it was created in is no
    // longer the one in force (LanguageRecreateGate).
    override fun onAppLanguageChange(value: String) = vm.setAppLanguage(value)
    override fun onAccentChange(value: DapAccent) = vm.setAccent(value)
    override fun onPlayerThemePreferenceChange(value: String) = vm.setPlayerThemePreference(value)
    override fun onShowPlayerFileInfoChange(value: Boolean) = vm.setShowPlayerFileInfo(value)
    override fun onAlbumArtQualityChange(value: AlbumArtQuality) = vm.setAlbumArtQuality(value)
    override fun onLaunchTabChange(value: String) = vm.setLaunchTab(value)
    override fun onLibraryNavigationModeChange(value: String) = vm.setLibraryNavigationMode(value)
    override fun onOpenPaletteStyle() = navigate(Screen.PaletteStyle.route)

    /**
     * Two writes, ordered so the moment between them already draws the destination: into Automatic the mode
     * goes first (Light is only its daytime half), otherwise the base does.
     */
    override fun onThemeChoiceChange(choice: DapAppearanceChoice) {
        val (baseTheme, mode) = storedPairFor(choice)
        if (choice == DapAppearanceChoice.Automatic) {
            vm.setAppThemeMode(mode)
            vm.setBaseTheme(baseTheme)
        } else {
            vm.setBaseTheme(baseTheme)
            vm.setAppThemeMode(mode)
        }
    }
}

internal class BehaviorSettingsActionsImpl(private val vm: SettingsViewModel) : BehaviorSettingsActions {
    override fun onBackNavigationModeChange(value: BackNavigationMode) = vm.setBackNavigationMode(value)
    override fun onKeepPlayingInBackgroundChange(value: Boolean) = vm.setKeepPlayingInBackground(value)
    override fun onResumeOnHeadsetReconnectChange(value: Boolean) = vm.setResumeOnHeadsetReconnect(value)
    override fun onDapLockScreenEnabledChange(value: Boolean) = vm.setDapLockScreenEnabled(value)
    override fun onShowQueueHistoryChange(value: Boolean) = vm.setShowQueueHistory(value)
    override fun onFolderBackGestureNavigationChange(value: Boolean) = vm.setFolderBackGestureNavigation(value)
    override fun onTapBackgroundClosesPlayerChange(value: Boolean) = vm.setTapBackgroundClosesPlayer(value)
    override fun onHapticsEnabledChange(value: Boolean) = vm.setHapticsEnabled(value)
}

internal class LyricsSettingsActionsImpl(private val vm: SettingsViewModel) : LyricsSettingsActions {
    override fun onExternalLyricsEnabledChange(value: Boolean) = vm.setExternalLyricsEnabled(value)
    override fun onLyricsSourcePreferenceChange(value: LyricsSourcePreference) = vm.setLyricsSourcePreference(value)
    override fun onAutoScanLrcFilesChange(value: Boolean) = vm.setAutoScanLrcFiles(value)
    override fun onImmersiveLyricsEnabledChange(value: Boolean) = vm.setImmersiveLyricsEnabled(value)
    override fun onImmersiveLyricsTimeoutChange(value: Long) = vm.setImmersiveLyricsTimeout(value)
    override fun onLyricsAlignmentChange(value: String) = vm.setLyricsAlignment(value)
    override fun onShowLyricsTranslationChange(value: Boolean) = vm.setShowLyricsTranslation(value)
    override fun onShowLyricsRomanizationChange(value: Boolean) = vm.setShowLyricsRomanization(value)
    override fun onUseAnimatedLyricsChange(value: Boolean) = vm.setUseAnimatedLyrics(value)
    override fun onAnimatedLyricsBlurEnabledChange(value: Boolean) = vm.setAnimatedLyricsBlurEnabled(value)
    override fun onAnimatedLyricsBlurStrengthChange(value: Float) = vm.setAnimatedLyricsBlurStrength(value)
}

internal class LibrarySettingsActionsImpl(
    private val vm: SettingsViewModel,
    private val navigate: (String) -> Unit,
    /** The host owns the explorer dialog; this raises it. */
    private val showExplorer: () -> Unit,
) : LibrarySettingsActions {
    override fun onMinSongDurationChange(value: Int) = vm.setMinSongDuration(value)
    override fun onMinTracksPerAlbumChange(value: Int) = vm.setMinTracksPerAlbum(value)
    override fun onAlbumArtCacheLimitMbChange(value: Int) = vm.setAlbumArtCacheLimitMb(value)
    override fun onExternalArtistImagesEnabledChange(value: Boolean) = vm.setExternalArtistImagesEnabled(value)
    override fun onOpenExcludedDirectories() {
        showExplorer()
        vm.openExplorer()
    }
    override fun onOpenArtistSettings() = navigate(Screen.ArtistSettings.route)
    override fun onOpenDuplicates() = navigate(Screen.Duplicates.route)
    override fun onRefreshLibrary() = vm.refreshLibrary()
    override fun onFullRescan() = vm.fullSyncLibrary()
}

internal class AudioSettingsActionsImpl(
    private val vm: SettingsViewModel,
    private val device: DeviceControlViewModel,
    private val navigate: (String) -> Unit,
) : AudioSettingsActions {
    // B22: the Pure Direct switch lives on this page now. It is the same preference and the same action the Settings
    // root had: `DeviceControlViewModel.setPristineMode`, with its confirmation of a rise.
    override fun onPureDirectChange(value: Boolean) = device.setPristineMode(value)
    override fun onConfirmPureDirectRise() = device.confirmPristineRise()
    override fun onDismissPureDirectRise() = device.dismissPristineRise()
    override fun onResetDigitalGain() = device.setDigitalGainPercent(100)
    override fun onReplayGainEnabledChange(value: Boolean) = vm.setReplayGainEnabled(value)
    override fun onReplayGainUseAlbumGainChange(value: Boolean) = vm.setReplayGainUseAlbumGain(value)
    override fun onCrossfadeEnabledChange(value: Boolean) = vm.setCrossfadeEnabled(value)
    override fun onSmartCrossfadeEnabledChange(value: Boolean) = vm.setSmartCrossfadeEnabled(value)
    override fun onPlaybackSpeedChange(value: Float) = vm.setPlaybackSpeed(value)
    // B22: random mode and the persistent mix moved here from Behavior; same preferences, same methods.
    override fun onRandomModeChange(value: RandomMode) = vm.setRandomMode(value)
    override fun onPersistentShuffleEnabledChange(value: Boolean) = vm.setPersistentShuffleEnabled(value)
    override fun onOpenDacProfile(profileId: String) = navigate(Screen.DacProfile.createRoute(profileId))
    override fun onOpenKnownDacs() = navigate(Screen.KnownDacs.route)
    override fun onOpenHeadphoneProfile(profileId: String) = navigate(Screen.HeadphoneProfile.createRoute(profileId))
    override fun onOpenKnownHeadphones() = navigate(Screen.KnownHeadphones.route)
    override fun onOpenEqualizer(profileId: String) = navigate(Screen.Equalizer.createRoute(profileId))
    override fun onOpenSafetyMax(profileId: String) = navigate(Screen.SafetyMaxVolume.createRoute(profileId))
    override fun onOpenGlobalTransitionEditor() = navigate(Screen.EditTransition.createRoute(null))
}

internal class AboutSettingsActionsImpl(private val navigate: (String) -> Unit) : AboutSettingsActions {
    override fun onOpenAbout() = navigate(Screen.About.route)
}

internal class BackupSettingsActionsImpl(private val vm: BackupSettingsViewModel) : BackupSettingsActions {
    override fun onDismissInfo() = vm.setBackupInfoDismissed(true)
    override fun onExport(uri: Uri, sections: Set<BackupSection>, passphrase: String?) =
        vm.exportAppData(uri, sections, passphrase)
    override fun onInspectBackup(uri: Uri, passphrase: String?) = vm.inspectBackupFile(uri, passphrase)
    override fun onDismissEncryptedBackupPrompt() = vm.dismissEncryptedBackupPrompt()
    override fun onClearRestorePlan() = vm.clearRestorePlan()
    override fun onUpdateRestorePlanSelection(selected: Set<BackupSection>) = vm.updateRestorePlanSelection(selected)
    override fun onRestore(uri: Uri) = vm.restoreFromPlan(uri)
    override fun onRemoveBackupHistoryEntry(entry: BackupHistoryEntry) = vm.removeBackupHistoryEntry(entry)
}

internal class MaintenanceToolsActionsImpl(
    private val settings: SettingsViewModel,
    private val player: PlayerViewModel,
    private val stats: StatsViewModel,
) : MaintenanceToolsActions {
    override suspend fun onRegeneratePalette(song: Song): Boolean = player.forceRegenerateAlbumPaletteForSong(song)
    override fun onRegenerateDailyMix() = player.forceUpdateDailyMix()
    override fun onRegenerateStats() = stats.forceRegenerateStats()
    override fun onTriggerTestCrash() = settings.triggerTestCrash()
}

/**
 * The System page's actions (B32-Q6): what each row did on Maintenance's recovery half, with the PIN gone. Confirmation is the page's
 * (a "yes" is what calls these). The openers go through the activity's Lock Task lift ([SystemSurfaceHost]); nothing here touches
 * Lock Task itself. B34: the leave-the-DAP rows are "Inicio y modo DAP"'s ([HomeModeActionsImpl]).
 */
internal class SystemSettingsActionsImpl(
    private val vm: SettingsViewModel,
    private val host: SystemSurfaceHost?,
    private val navigate: (String) -> Unit,
    private val notify: (Int) -> Unit,
    private val notifyRemoved: (Int) -> Unit,
    private val onRebuildStarted: () -> Unit = {},
) : SystemSettingsActions {

    private fun open(intent: Intent) {
        val surfaces = host
        if (surfaces == null) {
            notify(R.string.maintenance_not_available)
            return
        }
        surfaces.openSystemSurface(intent) { outcome ->
            when (outcome) {
                SystemSurfaceOutcome.OPENED -> Unit
                SystemSurfaceOutcome.NOT_LIFTED -> notify(R.string.maintenance_lock_task_exit_failed)
                SystemSurfaceOutcome.NOT_STARTED -> notify(R.string.maintenance_not_available)
            }
        }
    }

    override fun onOpenAndroidSettings() = open(Intent(Settings.ACTION_SETTINGS))
    override fun onOpenUsbSettings() = open(Intent(USB_SETTINGS_ACTION))
    override fun onOpenWifiSettings() = open(Intent(Settings.ACTION_WIFI_SETTINGS))
    override fun onOpenBluetoothSettings() = open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    override fun onRebuildDatabase() {
        notify(R.string.toast_rebuilding_database)
        onRebuildStarted()
        vm.rebuildDatabase()
    }

    override fun onDeleteImportedLyrics() {
        notify(R.string.setcat_reset_imported_lyrics_title)
        vm.resetAllLyrics()
    }

    override fun onCleanUpItemsWithoutFile() = vm.purgeDormantItems { removed -> notifyRemoved(removed) }

    override fun onOpenMaintenance() = navigate(Screen.Maintenance.route)

    companion object {
        const val USB_SETTINGS_ACTION = "android.settings.USB_SETTINGS"
    }
}

/**
 * The "Inicio y modo DAP" page's actions (B34). Confirmation is the page's (a "yes" is what calls the leave / return / release ones). The
 * platform is reached through seams, so what each tap asks for can be checked on the JVM:
 *  - [host]: the activity's Lock Task lift and "Volver al DAP" ([SystemSurfaceHost]);
 *  - [modeHost]: pinning and giving up Device Owner ([ModeLevelHost]);
 *  - [openHomePicker]: Android's home picker ([HomePicker]: no package, no component), false when the device has none;
 *  - [openSecuritySettings]: where Android's "App pinning" switch lives.
 */
internal class HomeModeActionsImpl(
    private val state: () -> ProductModeState,
    private val requestLevel: (ProductLevel) -> Unit,
    private val refresh: () -> Unit,
    private val host: SystemSurfaceHost?,
    private val modeHost: ModeLevelHost?,
    private val openHomePicker: () -> Boolean,
    private val openSecuritySettings: () -> Boolean,
    private val dpmCommand: String,
    private val copy: (String) -> Unit,
    private val notify: (Int) -> Unit,
) : HomeModeActions {

    override fun onChooseHomeApp() {
        val current = state()
        when {
            // Lock Task and the persistent HOME policy stand in the way of Android's picker: lift and clear them first (what "Restaurar
            // HONOR Launcher" did, without naming a launcher), then show the picker. B34-fix2: the exit is recorded until the picker is over;
            // if the listener cancels it (or picks Polylute again) the next start or resume locks again, and if another launcher is picked
            // nothing re-arms.
            current.isDedicatedActive -> {
                val surfaces = host
                if (surfaces == null) {
                    notify(R.string.maintenance_not_available)
                    return
                }
                surfaces.restoreHonorLauncher(
                    openSelector = { openHomePicker().also { opened -> if (!opened) notify(R.string.maintenance_not_available) } },
                ) { outcome ->
                    when (outcome) {
                        SystemSurfaceOutcome.OPENED -> Unit
                        SystemSurfaceOutcome.NOT_LIFTED -> notify(R.string.maintenance_lock_task_exit_failed)
                        SystemSurfaceOutcome.NOT_STARTED -> notify(R.string.maintenance_not_available)
                    }
                }
            }
            // A pin blocks other apps' screens; choosing another home is the end of it
            current.level == ProductLevel.PINNED -> {
                modeHost?.releaseAppPinning()
                if (!openHomePicker()) notify(R.string.maintenance_not_available)
                refresh()
            }
            else -> if (!openHomePicker()) notify(R.string.maintenance_not_available)
        }
    }

    override fun onSelectLevel(level: ProductLevel) {
        // leaving Pinned for another level lets go of the pin
        if (level != ProductLevel.PINNED && state().level == ProductLevel.PINNED) modeHost?.releaseAppPinning()
        requestLevel(level)
        refresh()
    }

    override fun onPinNow() {
        val asked = modeHost?.requestAppPinning() == true
        notify(if (asked) R.string.home_mode_pin_asked else R.string.maintenance_not_available)
        refresh()
    }

    override fun onUnpin() {
        modeHost?.releaseAppPinning()
        refresh()
    }

    override fun onOpenSecuritySettings() {
        if (!openSecuritySettings()) notify(R.string.maintenance_not_available)
    }

    override fun onCopyDpmCommand() {
        copy(dpmCommand)
        notify(R.string.home_mode_guide_copied)
    }

    override fun onReturnToDap() {
        val surfaces = host
        if (surfaces == null) {
            notify(R.string.maintenance_lock_task_entry_failed)
            return
        }
        // B32-fix1 R3: success is said only for confirmed containment; every other ending is said as what it is
        surfaces.returnToDap { outcome ->
            when (outcome) {
                DapReturnOutcome.CONTAINED -> notify(R.string.system_return_done)
                DapReturnOutcome.NOTHING_TO_CONTAIN -> Unit
                DapReturnOutcome.POLICY_NOT_APPLIED -> notify(R.string.maintenance_lock_task_entry_failed)
                DapReturnOutcome.NOT_LOCKED -> notify(R.string.system_return_not_locked)
            }
            refresh()
        }
    }

    override fun onReleaseDeviceOwner() {
        val hostForOwner = modeHost
        if (hostForOwner == null) {
            notify(R.string.maintenance_not_available)
            return
        }
        hostForOwner.releaseDeviceOwner { result ->
            notify(releaseMessage(result))
            refresh()
        }
    }

    companion object {
        /**
         * B34-fix1: what a release says, from what is true. "Liberado" only for a confirmed absence; a read that kept failing is "no se pudo
         * comprobar", which is neither released nor failed; and a failed release says whether the containment is confirmed back, not back, or
         * was never there ([DapReleaseRollback]).
         */
        @androidx.annotation.StringRes
        fun releaseMessage(result: DapReleaseResult): Int {
            val rollback = result.rollback
            return when (result.outcome) {
                DapReleaseOutcome.RELEASED ->
                    if (result.homeStillPolylute) R.string.home_mode_release_done_home_still else R.string.home_mode_release_done
                DapReleaseOutcome.NOT_DEVICE_OWNER -> R.string.maintenance_not_available
                DapReleaseOutcome.UNREADABLE_BEFORE -> R.string.home_mode_release_unreadable
                DapReleaseOutcome.UNVERIFIED -> R.string.home_mode_release_unverified
                DapReleaseOutcome.LOCK_NOT_STOPPED -> when (rollback) {
                    DapReleaseRollback.RESTORED -> R.string.home_mode_release_lock_not_stopped
                    DapReleaseRollback.NOT_RESTORED -> R.string.home_mode_release_lock_not_stopped_unrestored
                    DapReleaseRollback.NOT_NEEDED, DapReleaseRollback.NONE -> R.string.home_mode_release_lock_not_stopped_plain
                }
                DapReleaseOutcome.POLICY_NOT_CLEARED -> when (rollback) {
                    DapReleaseRollback.RESTORED -> R.string.home_mode_release_policy_failed
                    DapReleaseRollback.NOT_RESTORED -> R.string.home_mode_release_policy_failed_unrestored
                    DapReleaseRollback.NOT_NEEDED, DapReleaseRollback.NONE -> R.string.home_mode_release_policy_failed_plain
                }
                DapReleaseOutcome.STILL_OWNER -> when (rollback) {
                    DapReleaseRollback.RESTORED -> R.string.home_mode_release_still_owner
                    DapReleaseRollback.NOT_RESTORED -> R.string.home_mode_release_still_owner_unrestored
                    DapReleaseRollback.NOT_NEEDED, DapReleaseRollback.NONE -> R.string.home_mode_release_still_owner_plain
                }
            }
        }
    }
}
