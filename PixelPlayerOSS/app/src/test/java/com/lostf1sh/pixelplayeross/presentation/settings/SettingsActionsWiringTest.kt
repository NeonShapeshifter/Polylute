package com.lostf1sh.pixelplayeross.presentation.settings

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.backup.model.BackupHistoryEntry
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.RandomMode
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.viewmodel.BackupSettingsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsViewModel
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.theme.DapAppearanceChoice
import com.lostf1sh.pixelplayeross.ui.theme.storedPairFor
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B21: every control of every Settings page still reaches the same ViewModel method (and so the same
 * preference) or opens the same route. The pages call an actions interface; these are its implementations, and
 * here each one is called and the thing it must reach is checked.
 */
class SettingsActionsWiringTest {

    private val vm = mockk<SettingsViewModel>(relaxed = true)
    private val routes = mutableListOf<String>()
    private val navigate: (String) -> Unit = { routes += it }

    @Test
    fun `appearance controls write the preferences they always wrote`() {
        val actions = AppearanceSettingsActionsImpl(vm, navigate)
        actions.onAppLanguageChange("sv")
        actions.onAccentChange(DapAccent.entries.first())
        actions.onPlayerThemePreferenceChange("dynamic")
        actions.onShowPlayerFileInfoChange(false)
        actions.onAlbumArtQualityChange(AlbumArtQuality.ORIGINAL)
        actions.onLaunchTabChange("search")
        actions.onLibraryNavigationModeChange("compact_pill")
        actions.onOpenPaletteStyle()
        verify(exactly = 1) { vm.setAppLanguage("sv") }
        verify(exactly = 1) { vm.setAccent(DapAccent.entries.first()) }
        verify(exactly = 1) { vm.setPlayerThemePreference("dynamic") }
        verify(exactly = 1) { vm.setShowPlayerFileInfo(false) }
        verify(exactly = 1) { vm.setAlbumArtQuality(AlbumArtQuality.ORIGINAL) }
        verify(exactly = 1) { vm.setLaunchTab("search") }
        verify(exactly = 1) { vm.setLibraryNavigationMode("compact_pill") }
        assertThat(routes).containsExactly(Screen.PaletteStyle.route)
    }

    @Test
    fun `the theme row is two writes, in the order that never draws the wrong destination`() {
        val actions = AppearanceSettingsActionsImpl(vm, navigate)
        for (choice in DapAppearanceChoice.entries) {
            val (base, mode) = storedPairFor(choice)
            io.mockk.clearMocks(vm, answers = false)
            actions.onThemeChoiceChange(choice)
            if (choice == DapAppearanceChoice.Automatic) {
                // Into Automatic the mode goes first (Light is only its daytime half).
                verifyOrder { vm.setAppThemeMode(mode); vm.setBaseTheme(base) }
            } else {
                verifyOrder { vm.setBaseTheme(base); vm.setAppThemeMode(mode) }
            }
        }
    }

    @Test
    fun `behavior controls write the preferences they always wrote`() {
        val actions = BehaviorSettingsActionsImpl(vm)
        actions.onBackNavigationModeChange(BackNavigationMode.entries.last())
        actions.onKeepPlayingInBackgroundChange(false)
        actions.onResumeOnHeadsetReconnectChange(true)
        actions.onDapLockScreenEnabledChange(true)
        actions.onShowQueueHistoryChange(false)
        actions.onFolderBackGestureNavigationChange(false)
        actions.onTapBackgroundClosesPlayerChange(true)
        actions.onHapticsEnabledChange(false)
        verify(exactly = 1) { vm.setBackNavigationMode(BackNavigationMode.entries.last()) }
        verify(exactly = 1) { vm.setKeepPlayingInBackground(false) }
        verify(exactly = 1) { vm.setResumeOnHeadsetReconnect(true) }
        verify(exactly = 1) { vm.setDapLockScreenEnabled(true) }
        verify(exactly = 1) { vm.setShowQueueHistory(false) }
        verify(exactly = 1) { vm.setFolderBackGestureNavigation(false) }
        verify(exactly = 1) { vm.setTapBackgroundClosesPlayer(true) }
        verify(exactly = 1) { vm.setHapticsEnabled(false) }
    }

    @Test
    fun `lyrics controls write the preferences they always wrote`() {
        val actions = LyricsSettingsActionsImpl(vm)
        actions.onExternalLyricsEnabledChange(true)
        actions.onLyricsSourcePreferenceChange(LyricsSourcePreference.LOCAL_FIRST)
        actions.onAutoScanLrcFilesChange(true)
        actions.onImmersiveLyricsEnabledChange(true)
        actions.onImmersiveLyricsTimeoutChange(5000L)
        actions.onLyricsAlignmentChange("left")
        actions.onShowLyricsTranslationChange(false)
        actions.onShowLyricsRomanizationChange(false)
        actions.onUseAnimatedLyricsChange(true)
        actions.onAnimatedLyricsBlurEnabledChange(false)
        actions.onAnimatedLyricsBlurStrengthChange(1.5f)
        verify(exactly = 1) { vm.setExternalLyricsEnabled(true) }
        verify(exactly = 1) { vm.setLyricsSourcePreference(LyricsSourcePreference.LOCAL_FIRST) }
        verify(exactly = 1) { vm.setAutoScanLrcFiles(true) }
        verify(exactly = 1) { vm.setImmersiveLyricsEnabled(true) }
        verify(exactly = 1) { vm.setImmersiveLyricsTimeout(5000L) }
        verify(exactly = 1) { vm.setLyricsAlignment("left") }
        verify(exactly = 1) { vm.setShowLyricsTranslation(false) }
        verify(exactly = 1) { vm.setShowLyricsRomanization(false) }
        verify(exactly = 1) { vm.setUseAnimatedLyrics(true) }
        verify(exactly = 1) { vm.setAnimatedLyricsBlurEnabled(false) }
        verify(exactly = 1) { vm.setAnimatedLyricsBlurStrength(1.5f) }
    }

    @Test
    fun `library controls write the preferences they always wrote and open what they always opened`() {
        var explorerShown = 0
        val actions = LibrarySettingsActionsImpl(vm, navigate) { explorerShown++ }
        actions.onMinSongDurationChange(20000)
        actions.onMinTracksPerAlbumChange(2)
        actions.onAlbumArtCacheLimitMbChange(300)
        actions.onExternalArtistImagesEnabledChange(true)
        actions.onRefreshLibrary()
        actions.onFullRescan()
        actions.onOpenArtistSettings()
        actions.onOpenDuplicates()
        actions.onOpenExcludedDirectories()
        verify(exactly = 1) { vm.setMinSongDuration(20000) }
        verify(exactly = 1) { vm.setMinTracksPerAlbum(2) }
        verify(exactly = 1) { vm.setAlbumArtCacheLimitMb(300) }
        verify(exactly = 1) { vm.setExternalArtistImagesEnabled(true) }
        verify(exactly = 1) { vm.refreshLibrary() }
        verify(exactly = 1) { vm.fullSyncLibrary() }
        // The explorer: the dialog is raised first, then the explorer is opened at its root.
        assertThat(explorerShown).isEqualTo(1)
        verify(exactly = 1) { vm.openExplorer() }
        assertThat(routes).containsExactly(Screen.ArtistSettings.route, Screen.Duplicates.route).inOrder()
    }

    @Test
    fun `audio controls write the preferences they always wrote and open what they always opened`() {
        val device = mockk<DeviceControlViewModel>(relaxed = true)
        val actions = AudioSettingsActionsImpl(vm, device, navigate)
        actions.onReplayGainEnabledChange(true)
        actions.onReplayGainUseAlbumGainChange(true)
        actions.onCrossfadeEnabledChange(true)
        actions.onSmartCrossfadeEnabledChange(true)
        actions.onPlaybackSpeedChange(1.5f)
        verify(exactly = 1) { vm.setReplayGainEnabled(true) }
        verify(exactly = 1) { vm.setReplayGainUseAlbumGain(true) }
        verify(exactly = 1) { vm.setCrossfadeEnabled(true) }
        verify(exactly = 1) { vm.setSmartCrossfadeEnabled(true) }
        verify(exactly = 1) { vm.setPlaybackSpeed(1.5f) }

        actions.onOpenDacProfile("dac-1")
        actions.onOpenKnownDacs()
        actions.onOpenHeadphoneProfile("hp-1")
        actions.onOpenKnownHeadphones()
        actions.onOpenEqualizer("hp-1")
        actions.onOpenSafetyMax("hp-1")
        actions.onOpenGlobalTransitionEditor()
        assertThat(routes).containsExactly(
            Screen.DacProfile.createRoute("dac-1"),
            Screen.KnownDacs.route,
            Screen.HeadphoneProfile.createRoute("hp-1"),
            Screen.KnownHeadphones.route,
            // The equalizer is one tap from Audio, for the headphones in use: the same route the profile page opens.
            Screen.Equalizer.createRoute("hp-1"),
            Screen.SafetyMaxVolume.createRoute("hp-1"),
            Screen.EditTransition.createRoute(null),
        ).inOrder()
    }

    @Test
    fun `the two settings that moved to Audio write the same preferences from there`() {
        val actions = AudioSettingsActionsImpl(vm, mockk(relaxed = true), navigate)
        actions.onPersistentShuffleEnabledChange(true)
        actions.onRandomModeChange(RandomMode.FAST)
        verify(exactly = 1) { vm.setPersistentShuffleEnabled(true) }
        verify(exactly = 1) { vm.setRandomMode(RandomMode.FAST) }
    }

    @Test
    fun `the Pure Direct switch and its dialog call the device actions they always called`() {
        val device = mockk<DeviceControlViewModel>(relaxed = true)
        val actions = AudioSettingsActionsImpl(vm, device, navigate)
        actions.onPureDirectChange(true)
        actions.onPureDirectChange(false)
        actions.onConfirmPureDirectRise()
        actions.onDismissPureDirectRise()
        actions.onResetDigitalGain()
        verify(exactly = 1) { device.setPristineMode(true) }
        verify(exactly = 1) { device.setPristineMode(false) }
        verify(exactly = 1) { device.confirmPristineRise() }
        verify(exactly = 1) { device.dismissPristineRise() }
        verify(exactly = 1) { device.setDigitalGainPercent(100) }
    }

    @Test
    fun `about opens the about screen`() {
        AboutSettingsActionsImpl(navigate).onOpenAbout()
        assertThat(routes).containsExactly(Screen.About.route)
    }

    @Test
    fun `backup controls reach the backup ViewModel`() {
        val backup = mockk<BackupSettingsViewModel>(relaxed = true)
        val actions = BackupSettingsActionsImpl(backup)
        val uri = mockk<Uri>()
        val sections = setOf(BackupSection.entries.first())
        val entry = mockk<BackupHistoryEntry>()
        actions.onDismissInfo()
        actions.onExport(uri, sections, "pw")
        actions.onInspectBackup(uri, null)
        actions.onDismissEncryptedBackupPrompt()
        actions.onClearRestorePlan()
        actions.onUpdateRestorePlanSelection(sections)
        actions.onRestore(uri)
        actions.onRemoveBackupHistoryEntry(entry)
        verify(exactly = 1) { backup.setBackupInfoDismissed(true) }
        verify(exactly = 1) { backup.exportAppData(uri, sections, "pw") }
        verify(exactly = 1) { backup.inspectBackupFile(uri, null) }
        verify(exactly = 1) { backup.dismissEncryptedBackupPrompt() }
        verify(exactly = 1) { backup.clearRestorePlan() }
        verify(exactly = 1) { backup.updateRestorePlanSelection(sections) }
        verify(exactly = 1) { backup.restoreFromPlan(uri) }
        verify(exactly = 1) { backup.removeBackupHistoryEntry(entry) }
    }

    @Test
    fun `the maintenance tools reach the three owners the developer controls always reached`() = runTest {
        val player = mockk<PlayerViewModel>(relaxed = true)
        val stats = mockk<StatsViewModel>(relaxed = true)
        val song = mockk<Song>()
        coEvery { player.forceRegenerateAlbumPaletteForSong(song) } returns true
        val actions = MaintenanceToolsActionsImpl(vm, player, stats)
        assertThat(actions.onRegeneratePalette(song)).isTrue()
        actions.onRegenerateDailyMix()
        actions.onRegenerateStats()
        actions.onTriggerTestCrash()
        verify(exactly = 1) { player.forceUpdateDailyMix() }
        verify(exactly = 1) { stats.forceRegenerateStats() }
        verify(exactly = 1) { vm.triggerTestCrash() }
    }

    @Test
    fun `a DapBaseTheme is still what the theme row stores for Light`() {
        // The pair is the contract between the theme row and the theme: Automatic is Light + follow-system.
        assertThat(storedPairFor(DapAppearanceChoice.Light).first).isEqualTo(DapBaseTheme.Light)
        assertThat(storedPairFor(DapAppearanceChoice.Automatic).first).isEqualTo(DapBaseTheme.Light)
    }
}
