package com.lostf1sh.pixelplayeross.presentation.settings

import com.lostf1sh.pixelplayeross.data.preferences.LyricsMode
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.RandomMode
import com.lostf1sh.pixelplayeross.data.worker.SyncProgress
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DacProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.HeadphoneProfilesUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import org.junit.jupiter.api.Test

/**
 * B21: the page states are built from the one `SettingsUiState` by pure functions. Each carries exactly what its
 * page draws, so (a) a control still reads the value its preference holds, and (b) a change to another page's
 * preference does not change this page's state, which is what lets a page that did not change skip.
 */
class SettingsStatesTest {

    private val ui = SettingsUiState(
        appLanguageTag = "es",
        appThemeMode = "dark",
        accent = DapAccent.entries.last(),
        playerThemePreference = "dynamic",
        showPlayerFileInfo = false,
        albumArtQuality = AlbumArtQuality.HIGH,
        launchTab = "library",
        libraryNavigationMode = "compact_pill",
        keepPlayingInBackground = false,
        resumeOnHeadsetReconnect = true,
        dapLockScreenEnabled = true,
        coverGlassMoving = false,
        lyricsMode = LyricsMode.FULL_PAGE,
        persistentShuffleEnabled = true,
        randomMode = RandomMode.FAST,
        showQueueHistory = false,
        folderBackGestureNavigation = false,
        tapBackgroundClosesPlayer = true,
        hapticsEnabled = false,
        backNavigationMode = BackNavigationMode.entries.last(),
        externalLyricsEnabled = true,
        lyricsSourcePreference = LyricsSourcePreference.API_FIRST,
        autoScanLrcFiles = true,
        immersiveLyricsEnabled = true,
        immersiveLyricsTimeout = 6000L,
        lyricsAlignment = "right",
        showLyricsTranslation = false,
        showLyricsRomanization = false,
        useAnimatedLyrics = true,
        animatedLyricsBlurEnabled = false,
        animatedLyricsBlurStrength = 3.5f,
        minSongDuration = 30000,
        minTracksPerAlbum = 3,
        albumArtCacheLimitMb = 400,
        musicInfoDescriptionsEnabled = true,
        replayGainEnabled = true,
        replayGainUseAlbumGain = true,
        isCrossfadeEnabled = true,
        smartCrossfadeEnabled = true,
    )

    @Test
    fun `the appearance page reads the appearance preferences`() {
        val state = AppearanceSettingsState.from(ui)
        assertThat(state.appLanguageTag).isEqualTo("es")
        assertThat(state.appThemeMode).isEqualTo("dark")
        assertThat(state.accent).isEqualTo(ui.accent)
        assertThat(state.playerThemePreference).isEqualTo("dynamic")
        assertThat(state.showPlayerFileInfo).isFalse()
        assertThat(state.albumArtQuality).isEqualTo(AlbumArtQuality.HIGH)
        assertThat(state.launchTab).isEqualTo("library")
        assertThat(state.libraryNavigationMode).isEqualTo("compact_pill")
    }

    @Test
    fun `B41 - the appearance page also reads what moved to it, from the same preferences`() {
        val state = AppearanceSettingsState.from(ui)
        // the two new switches
        assertThat(state.coverGlassMoving).isFalse()
        assertThat(state.lyricsMode).isEqualTo(LyricsMode.FULL_PAGE)
        // how the lyrics read and look (it was Settings, Lyrics)
        assertThat(state.immersiveLyricsEnabled).isTrue()
        assertThat(state.immersiveLyricsTimeout).isEqualTo(6000L)
        assertThat(state.lyricsAlignment).isEqualTo("right")
        assertThat(state.showLyricsTranslation).isFalse()
        assertThat(state.showLyricsRomanization).isFalse()
        assertThat(state.useAnimatedLyrics).isTrue()
        assertThat(state.animatedLyricsBlurEnabled).isFalse()
        assertThat(state.animatedLyricsBlurStrength).isEqualTo(3.5f)
        // the lock screen (it was Settings, Behaviour)
        assertThat(state.dapLockScreenEnabled).isTrue()
    }

    @Test
    fun `the behavior page reads the behavior preferences, and no longer the two that moved to Audio`() {
        val state = BehaviorSettingsState.from(ui)
        assertThat(state.showQueueHistory).isFalse()
        assertThat(state.keepPlayingInBackground).isFalse()
        assertThat(state.resumeOnHeadsetReconnect).isTrue()
        assertThat(state.folderBackGestureNavigation).isFalse()
        assertThat(state.tapBackgroundClosesPlayer).isTrue()
        assertThat(state.hapticsEnabled).isFalse()
        assertThat(state.backNavigationMode).isEqualTo(BackNavigationMode.entries.last())
    }

    @Test
    fun `B41 - the lyrics page reads the lyrics sources, and no longer how lyrics look`() {
        val state = LyricsSettingsState.from(ui)
        assertThat(state.externalLyricsEnabled).isTrue()
        assertThat(state.lyricsSourcePreference).isEqualTo(LyricsSourcePreference.API_FIRST)
        assertThat(state.autoScanLrcFiles).isTrue()
    }

    @Test
    fun `the library page reads the library preferences and the scan`() {
        val progress = SyncProgress()
        val state = LibrarySettingsState.from(ui, isSyncing = true, syncProgress = progress)
        assertThat(state.minSongDuration).isEqualTo(30000)
        assertThat(state.minTracksPerAlbum).isEqualTo(3)
        assertThat(state.albumArtCacheLimitMb).isEqualTo(400)
        assertThat(state.musicInfoDescriptionsEnabled).isTrue()
        assertThat(state.musicInfoCoversEnabled).isFalse()
        assertThat(state.isSyncing).isTrue()
        assertThat(state.syncProgress).isSameInstanceAs(progress)
    }

    @Test
    fun `the audio page joins the preferences, the speed, the devices and the status`() {
        val audio = AudioStatus(purityPreference = true)
        val state = AudioSettingsState.from(ui, playbackSpeed = 1.25f, audio = audio, dacs = DacProfilesUiState(), headphones = HeadphoneProfilesUiState())
        assertThat(state.replayGainEnabled).isTrue()
        assertThat(state.replayGainUseAlbumGain).isTrue()
        assertThat(state.isCrossfadeEnabled).isTrue()
        assertThat(state.smartCrossfadeEnabled).isTrue()
        // B22: the random mode and the persistent mix are read from the same preferences, on this page now.
        assertThat(state.randomMode).isEqualTo(RandomMode.FAST)
        assertThat(state.persistentShuffleEnabled).isTrue()
        assertThat(state.playbackSpeed).isEqualTo(1.25f)
        assertThat(state.audio).isSameInstanceAs(audio)
        assertThat(state.currentDac).isNull()
        assertThat(state.selectedHeadphone).isNull()
    }

    @Test
    fun `a page's state does not move when another page's preference does`() {
        // The reason a page that did not change can skip: haptics is Behavior's, not Lyrics', Library's or Appearance's.
        val changed = ui.copy(hapticsEnabled = true, showQueueHistory = true)
        assertThat(LyricsSettingsState.from(changed)).isEqualTo(LyricsSettingsState.from(ui))
        assertThat(AppearanceSettingsState.from(changed)).isEqualTo(AppearanceSettingsState.from(ui))
        assertThat(LibrarySettingsState.from(changed, false, SyncProgress())).isEqualTo(LibrarySettingsState.from(ui, false, SyncProgress()))
        assertThat(BehaviorSettingsState.from(changed)).isNotEqualTo(BehaviorSettingsState.from(ui))
        // And the other way: a lyrics source leaves Behavior alone.
        val sourceChanged = ui.copy(autoScanLrcFiles = false)
        assertThat(BehaviorSettingsState.from(sourceChanged)).isEqualTo(BehaviorSettingsState.from(ui))
        assertThat(LyricsSettingsState.from(sourceChanged)).isNotEqualTo(LyricsSettingsState.from(ui))
        // B41: how lyrics look is Appearance's, not Lyrics': the alignment moves Appearance's state and leaves the sources' alone
        val lookChanged = ui.copy(lyricsAlignment = "left", immersiveLyricsTimeout = 3000L)
        assertThat(LyricsSettingsState.from(lookChanged)).isEqualTo(LyricsSettingsState.from(ui))
        assertThat(AppearanceSettingsState.from(lookChanged)).isNotEqualTo(AppearanceSettingsState.from(ui))
        // and the lock screen moved with it: it is no longer Behavior's
        val lockChanged = ui.copy(dapLockScreenEnabled = false)
        assertThat(BehaviorSettingsState.from(lockChanged)).isEqualTo(BehaviorSettingsState.from(ui))
        assertThat(AppearanceSettingsState.from(lockChanged)).isNotEqualTo(AppearanceSettingsState.from(ui))
    }

    @Test
    fun `the defaults are what the store's defaults are`() {
        val defaults = SettingsUiState()
        assertThat(AudioSettingsState.from(defaults, 1f, AudioStatus(), DacProfilesUiState(), HeadphoneProfilesUiState()).randomMode)
            .isEqualTo(RandomMode.CLASSIC)
        assertThat(BehaviorSettingsState.from(defaults).backNavigationMode).isEqualTo(BackNavigationMode.DEFAULT)
        assertThat(AppearanceSettingsState.from(defaults).animatedLyricsBlurStrength).isEqualTo(defaults.animatedLyricsBlurStrength)
        // B41: both new switches are on until the listener turns them off
        assertThat(defaults.coverGlassMoving).isTrue()
        assertThat(defaults.lyricsMode).isEqualTo(LyricsMode.OVER_COVER)
    }
}
