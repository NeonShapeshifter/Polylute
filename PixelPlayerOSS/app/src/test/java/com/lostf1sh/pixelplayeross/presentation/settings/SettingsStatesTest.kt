package com.lostf1sh.pixelplayeross.presentation.settings

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
        externalArtistImagesEnabled = true,
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
    fun `the behavior page reads the behavior preferences, and no longer the two that moved to Audio`() {
        val state = BehaviorSettingsState.from(ui)
        assertThat(state.showQueueHistory).isFalse()
        assertThat(state.keepPlayingInBackground).isFalse()
        assertThat(state.resumeOnHeadsetReconnect).isTrue()
        assertThat(state.dapLockScreenEnabled).isTrue()
        assertThat(state.folderBackGestureNavigation).isFalse()
        assertThat(state.tapBackgroundClosesPlayer).isTrue()
        assertThat(state.hapticsEnabled).isFalse()
        assertThat(state.backNavigationMode).isEqualTo(BackNavigationMode.entries.last())
    }

    @Test
    fun `the lyrics page reads the lyrics preferences`() {
        val state = LyricsSettingsState.from(ui)
        assertThat(state.externalLyricsEnabled).isTrue()
        assertThat(state.lyricsSourcePreference).isEqualTo(LyricsSourcePreference.API_FIRST)
        assertThat(state.autoScanLrcFiles).isTrue()
        assertThat(state.immersiveLyricsEnabled).isTrue()
        assertThat(state.immersiveLyricsTimeout).isEqualTo(6000L)
        assertThat(state.lyricsAlignment).isEqualTo("right")
        assertThat(state.showLyricsTranslation).isFalse()
        assertThat(state.showLyricsRomanization).isFalse()
        assertThat(state.useAnimatedLyrics).isTrue()
        assertThat(state.animatedLyricsBlurEnabled).isFalse()
        assertThat(state.animatedLyricsBlurStrength).isEqualTo(3.5f)
    }

    @Test
    fun `the library page reads the library preferences and the scan`() {
        val progress = SyncProgress()
        val state = LibrarySettingsState.from(ui, isSyncing = true, syncProgress = progress)
        assertThat(state.minSongDuration).isEqualTo(30000)
        assertThat(state.minTracksPerAlbum).isEqualTo(3)
        assertThat(state.albumArtCacheLimitMb).isEqualTo(400)
        assertThat(state.externalArtistImagesEnabled).isTrue()
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
        // And the other way: a lyrics preference leaves Behavior alone.
        val lyricsChanged = ui.copy(lyricsAlignment = "left", immersiveLyricsTimeout = 3000L)
        assertThat(BehaviorSettingsState.from(lyricsChanged)).isEqualTo(BehaviorSettingsState.from(ui))
        assertThat(LyricsSettingsState.from(lyricsChanged)).isNotEqualTo(LyricsSettingsState.from(ui))
    }

    @Test
    fun `the defaults are what the store's defaults are`() {
        val defaults = SettingsUiState()
        assertThat(AudioSettingsState.from(defaults, 1f, AudioStatus(), DacProfilesUiState(), HeadphoneProfilesUiState()).randomMode)
            .isEqualTo(RandomMode.CLASSIC)
        assertThat(BehaviorSettingsState.from(defaults).backNavigationMode).isEqualTo(BackNavigationMode.DEFAULT)
        assertThat(LyricsSettingsState.from(defaults).animatedLyricsBlurStrength).isEqualTo(defaults.animatedLyricsBlurStrength)
    }
}
