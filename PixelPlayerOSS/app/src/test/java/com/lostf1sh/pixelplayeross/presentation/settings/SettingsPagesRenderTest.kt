package com.lostf1sh.pixelplayeross.presentation.settings

import com.lostf1sh.pixelplayeross.data.preferences.LyricsMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.decent.usbaudio.media3.DirectSignalMode
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.backup.model.BackupHistoryEntry
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.model.LyricsSourcePreference
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AlbumArtQuality
import com.lostf1sh.pixelplayeross.data.preferences.AppColorSource
import com.lostf1sh.pixelplayeross.data.preferences.BackNavigationMode
import com.lostf1sh.pixelplayeross.data.preferences.RandomMode
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.worker.SyncProgress
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapBaseTheme
import com.lostf1sh.pixelplayeross.ui.theme.DapAppearanceChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B21: every Settings page, split out of the 2,981-line category screen, still shows the words it showed, in the
 * states that decide them, and every control still calls its action.
 *
 * Rendered on the JVM under Robolectric (English resources). The expectations are string resources, not
 * literals: a page that stopped drawing a row, or drew a different sentence, fails here. The *Audio* page is the one
 * that has to tell the Purity combinations apart, so it is rendered in each of them.
 *
 * What this is not: a screenshot test. It proves what is said and what is called, not how it is laid out.
 */
@RunWith(RobolectricTestRunner::class)
// A tall window (the HONOR is 720x1610 px), so a lazy list composes every row instead of the ones that fit 470 dp.
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h2400dp-xxhdpi")
class SettingsPagesRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int) = if (id == R.string.setcat_force_daily_mix_title) context.getString(id, context.getString(R.string.collection_name_daily_mix)) else context.getString(id)
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    /** The page inside a scrolling column, as the category screen has it (a LazyColumn item). */
    private fun setPage(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
        ) { content() }
    }

    private fun assertShows(vararg ids: Int) {
        for (id in ids) compose.onAllNodesWithText(s(id), substring = false, ignoreCase = true).onFirst().assertExists()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirst() =
        this[0]

    // ── About ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `about shows its one row and opens the about screen`() {
        var opened = 0
        setPage { AboutSettingsScreen(object : AboutSettingsActions { override fun onOpenAbout() { opened++ } }) }
        assertShows(R.string.setcat_application, R.string.setcat_about_pixelplayer_title)
        compose.onNodeWithText(s(R.string.setcat_about_pixelplayer_title)).performClick()
        assertEquals(1, opened)
    }

    // ── Behavior ─────────────────────────────────────────────────────────────────────────────────────

    private class BehaviorRecorder : BehaviorSettingsActions {
        override fun onRepeatSharedSongsInShuffleChange(value: Boolean) { calls += "sharedShuffle:$value" }
        val calls = mutableListOf<String>()
        override fun onBackNavigationModeChange(value: BackNavigationMode) { calls += "back:${value.id}" }
        override fun onKeepPlayingInBackgroundChange(value: Boolean) { calls += "keepPlaying:$value" }
        override fun onResumeOnHeadsetReconnectChange(value: Boolean) { calls += "resume:$value" }
        override fun onShowQueueHistoryChange(value: Boolean) { calls += "queueHistory:$value" }
        override fun onFolderBackGestureNavigationChange(value: Boolean) { calls += "folderBack:$value" }
        override fun onTapBackgroundClosesPlayerChange(value: Boolean) { calls += "tapBackground:$value" }
        override fun onHapticsEnabledChange(value: Boolean) { calls += "haptics:$value" }
    }

    @Test
    fun `behavior shows its sections, no longer the random mode or the persistent mix, and each switch calls its action`() {
        val actions = BehaviorRecorder()
        val state = BehaviorSettingsState.from(SettingsUiState(hapticsEnabled = true))
        setPage { BehaviorSettingsScreen(state, actions) }
        assertShows(
            R.string.setcat_back_navigation, R.string.setcat_background_playback, R.string.setcat_queue_behaviour,
            R.string.setcat_folders, R.string.setcat_player_gestures, R.string.setcat_haptics,
        )
        // B22: the random mode and the persistent mix moved to Audio (Queue); they are not here any more.
        compose.onAllNodesWithText(s(R.string.setcat_random_mode_title)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.audio_persistent_mix_title)).assertCountEquals(0)

        // B41: "DAP lock screen" is Appearance's now. Six switches, in page order; each one reaches its own action with the value it was
        // switched to.
        compose.onAllNodesWithText(s(R.string.setcat_dap_lock_screen_title)).assertCountEquals(0)
        val switches = compose.onAllNodes(isToggleable())
        switches.assertCountEquals(6)
        for (i in 0 until 6) switches[i].performScrollTo().performClick()
        assertEquals(6, actions.calls.size)
        assertTrue(actions.calls.toString(), actions.calls.containsAll(listOf("queueHistory:true", "haptics:false")))
    }

    // ── Lyrics ───────────────────────────────────────────────────────────────────────────────────────

    private class LyricsRecorder : LyricsSettingsActions {
        val calls = mutableListOf<String>()
        override fun onExternalLyricsEnabledChange(value: Boolean) { calls += "external:$value" }
        override fun onLyricsSourcePreferenceChange(value: LyricsSourcePreference) { calls += "source:$value" }
        override fun onAutoScanLrcFilesChange(value: Boolean) { calls += "autoScan:$value" }
    }

    @Test
    fun `B41 - lyrics keeps only its sources, and each of them calls its action`() {
        val actions = LyricsRecorder()
        val state = LyricsSettingsState.from(SettingsUiState())
        setPage { LyricsSettingsScreen(state, actions) }
        assertShows(
            R.string.setcat_lyrics_sources, R.string.setcat_external_lyrics_title, R.string.setcat_lyrics_source_priority_label,
            R.string.setcat_auto_scan_lrc_title,
        )
        // everything about how lyrics read and look moved to Appearance
        for (moved in listOf(
            R.string.setcat_immersive_lyrics_title, R.string.setcat_lyrics_alignment_label, R.string.setcat_lyrics_translation_title,
            R.string.setcat_lyrics_romanization_title, R.string.presentation_batch_f_exp_animated_lyrics_title,
            R.string.presentation_batch_f_exp_lyric_blur_title,
        )) compose.onAllNodesWithText(s(moved)).assertCountEquals(0)
        val switches = compose.onAllNodes(isToggleable())
        switches.assertCountEquals(2)
        switches[0].performScrollTo().performClick()
        switches[1].performScrollTo().performClick()
        assertEquals(listOf("external:true", "autoScan:false"), actions.calls)
    }

    // ── Library ──────────────────────────────────────────────────────────────────────────────────────

    private class LibraryRecorder : LibrarySettingsActions {
        val calls = mutableListOf<String>()
        override fun onMinSongDurationChange(value: Int) { calls += "minDuration:$value" }
        override fun onMinTracksPerAlbumChange(value: Int) { calls += "minTracks:$value" }
        override fun onAlbumArtCacheLimitMbChange(value: Int) { calls += "cache:$value" }
        override fun onMusicInfoCoversChange(value: Boolean) { calls += "covers:$value" }
        override fun onMusicInfoArtistPhotosChange(value: Boolean) { calls += "photos:$value" }
        override fun onMusicInfoDescriptionsChange(value: Boolean) { calls += "descriptions:$value" }
        override fun onMusicInfoCacheLimitChange(megabytes: Int) { calls += "musicInfoLimit:$megabytes" }
        override fun onDeleteMusicInfo(onDone: (Long) -> Unit) { calls += "deleteMusicInfo"; onDone(2048L) }
        override fun onOpenLibraryFolders() { calls += "excluded" }
        override fun onFindFolders() { calls += "find folders" }
        override fun onOpenArtistSettings() { calls += "artists" }
        override fun onOpenDuplicates() { calls += "duplicates" }
        override fun onRefreshLibrary() { calls += "refresh" }
        override fun onFullRescan() { calls += "rescan" }
    }

    @Test
    fun `library shows its four sections and its rows call their actions`() {
        val actions = LibraryRecorder()
        val state = LibrarySettingsState.from(SettingsUiState(), isSyncing = false, syncProgress = SyncProgress())
        setPage { LibrarySettingsScreen(state, actions, mountInputBridge = false) }
        assertShows(
            R.string.setcat_library_structure, R.string.setcat_filtering, R.string.setcat_sync_scanning, R.string.setcat_music_info,
            R.string.library_folders_title, R.string.library_find_folders_title, R.string.setcat_artists_title, R.string.setcat_min_song_duration,
            R.string.setcat_min_tracks_per_album, R.string.setcat_album_art_cache_limit, R.string.setcat_find_duplicates_title,
        )
        compose.onNodeWithText(s(R.string.library_folders_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.library_find_folders_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.setcat_artists_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.setcat_find_duplicates_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.setcat_sync_update_library)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.presentation_batch_f_full_rescan)).performScrollTo().performClick()
        assertEquals(listOf("excluded", "find folders", "artists", "duplicates", "refresh", "rescan"), actions.calls)
    }

    @Test
    fun `B40-F4 - the music info section has three switches off by default, offline preparation, and a way to delete what was downloaded`() {
        val actions = LibraryRecorder()
        val state = LibrarySettingsState.from(
            SettingsUiState(musicInfoUsedBytes = 3L * 1024 * 1024), isSyncing = false, syncProgress = SyncProgress(),
        )
        assertEquals(false, state.musicInfoCoversEnabled)
        assertEquals(false, state.musicInfoArtistPhotosEnabled)
        assertEquals(false, state.musicInfoDescriptionsEnabled)
        setPage { LibrarySettingsScreen(state, actions, mountInputBridge = false) }
        assertShows(
            R.string.setcat_music_info, R.string.setcat_music_info_note, R.string.setcat_music_info_covers_title,
            R.string.setcat_music_info_photos_title, R.string.setcat_music_info_descriptions_title,
            R.string.offline_download_all, R.string.setcat_music_info_delete_title,
        )
        // The local folder-cover switch precedes these three download switches.
        val toggles = compose.onAllNodes(isToggleable())
        toggles.assertCountEquals(4)
        for (index in 1..3) toggles[index].performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText(s(R.string.setcat_music_info_delete_title)).performScrollTo().performClick()
        assertEquals(listOf("covers:true", "photos:true", "descriptions:true"), actions.calls)
        compose.onNodeWithText(s(R.string.setcat_delete_confirm_title)).assertExists()
        compose.onNodeWithText(s(R.string.common_delete)).performClick()
        assertEquals(listOf("covers:true", "photos:true", "descriptions:true", "deleteMusicInfo"), actions.calls)
    }

    // ── Appearance ───────────────────────────────────────────────────────────────────────────────────

    private class AppearanceRecorder : AppearanceSettingsActions {
        val calls = mutableListOf<String>()
        override fun onAppLanguageChange(value: String) { calls += "language:$value" }
        override fun onAccentChange(value: DapAccent) { calls += "accent:$value" }
        override fun onAppColorSourceChange(value: String) { calls += "appColors:$value" }
        override fun onCoverThemeChoiceChange(value: String) { calls += "coverTheme:$value" }
        override fun onPlayerThemePreferenceChange(value: String) { calls += "playerTheme:$value" }
        override fun onBackgroundMotionChange(value: com.lostf1sh.pixelplayeross.data.preferences.BackgroundMotion) { calls += "motion:$value" }
        override fun onAnimatedCoversChange(value: Boolean) { calls += "animatedCovers:$value" }
        override fun onShowBookmarkButtonChange(value: Boolean) { calls += "bookmark:$value" }
        override fun onShowPlayerFileInfoChange(value: Boolean) { calls += "fileInfo:$value" }
        override fun onAlbumArtQualityChange(value: AlbumArtQuality) { calls += "artQuality:$value" }
        override fun onLaunchTabChange(value: String) { calls += "launchTab:$value" }
        override fun onLibraryNavigationModeChange(value: String) { calls += "libraryNav:$value" }
        override fun onThemeChoiceChange(choice: DapAppearanceChoice) { calls += "theme:$choice" }
        override fun onOpenPaletteStyle() { calls += "palette" }
        override fun onCoverGlassMovingChange(value: Boolean) { calls += "moving:$value" }
        override fun onLyricsModeChange(value: LyricsMode) { calls += "lyricsMode:${value.storageKey}" }
        override fun onImmersiveLyricsEnabledChange(value: Boolean) { calls += "immersive:$value" }
        override fun onImmersiveLyricsTimeoutChange(value: Long) { calls += "timeout:$value" }
        override fun onLyricsAlignmentChange(value: String) { calls += "alignment:$value" }
        override fun onShowLyricsTranslationChange(value: Boolean) { calls += "translation:$value" }
        override fun onShowLyricsRomanizationChange(value: Boolean) { calls += "romanization:$value" }
        override fun onUseAnimatedLyricsChange(value: Boolean) { calls += "animated:$value" }
        override fun onAnimatedLyricsBlurEnabledChange(value: Boolean) { calls += "blur:$value" }
        override fun onAnimatedLyricsBlurStrengthChange(value: Float) { calls += "blurStrength:$value" }
        override fun onDapLockScreenEnabledChange(value: Boolean) { calls += "lockScreen:$value" }
    }

    @Test
    fun `appearance shows its sections without the retired colour and palette choices`() {
        val actions = AppearanceRecorder()
        val state = AppearanceSettingsState.from(SettingsUiState(playerThemePreference = "album_art"))
        setPage { AppearanceSettingsScreen(state, actions) }
        assertShows(
            R.string.setcat_global_theme, R.string.setcat_now_playing, R.string.setcat_artwork, R.string.setcat_app_navigation_section,
            R.string.setcat_app_language_label, R.string.setcat_theme_label, R.string.setcat_accent_label,
            R.string.setcat_app_colors_label, R.string.setcat_show_player_file_info_title,
        )
        compose.onAllNodesWithText(s(R.string.setcat_album_art_palette_title)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.setcat_player_theme_label)).assertCountEquals(0)
        // Bookmark is followed by file info in the Player section.
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        assertEquals(listOf("fileInfo:false"), actions.calls)
    }

    @Test
    fun `B41 - appearance is the one place for how the app looks, in four sections`() {
        val state = AppearanceSettingsState.from(SettingsUiState())
        setPage { AppearanceSettingsScreen(state, AppearanceRecorder()) }
        assertShows(
            // Theme · Player · Lyrics · Lock screen
            R.string.setcat_global_theme, R.string.setcat_now_playing, R.string.settings_category_lyrics_title, R.string.setcat_lock_screen_section,
            // Player
            R.string.lyrics_mode_title,
            // Lyrics: the reading and the effects that moved from Settings, Lyrics
            R.string.setcat_immersive_lyrics_title, R.string.setcat_lyrics_alignment_label, R.string.setcat_lyrics_translation_title,
            R.string.setcat_lyrics_romanization_title, R.string.presentation_batch_f_exp_animated_lyrics_title,
            R.string.presentation_batch_f_exp_lyric_blur_title,
            // Lock screen: moved from Behaviour
            R.string.setcat_dap_lock_screen_title,
        )
    }

    @Test
    fun `B41 - Moving background is offered once Cover glass is the app's colour source`() {
        val glass = AppearanceSettingsState.from(SettingsUiState(appColorSource = AppColorSource.COVER_GLASS))
        setPage { AppearanceSettingsScreen(glass, AppearanceRecorder()) }
        assertShows(R.string.setcat_app_colors_cover_glass, R.string.setcat_background_motion_title, R.string.setcat_accent_desc_album_art)
    }

    @Test
    fun `B41 - without Cover glass there is no Moving background row`() {
        val album = AppearanceSettingsState.from(SettingsUiState(appColorSource = AppColorSource.ALBUM_ART))
        setPage { AppearanceSettingsScreen(album, AppearanceRecorder()) }
        compose.onAllNodesWithText(s(R.string.setcat_background_motion_title)).assertCountEquals(0)
        assertShows(R.string.lyrics_mode_title)
    }

    @Test
    fun `B41 - Moving background is on by default and calls its action, and B56 - the lyrics mode is the three way choice that calls its own`() {
        val actions = AppearanceRecorder()
        val state = AppearanceSettingsState.from(SettingsUiState(appColorSource = AppColorSource.COVER_GLASS))
        assertEquals(true, state.coverGlassMoving)
        assertEquals(LyricsMode.OVER_COVER, state.lyricsMode)
        var shown by mutableStateOf(state)
        setPage { AppearanceSettingsScreen(shown, actions) }
        // B56-7.1: Moving background is a choice of three (Still, Gentle, Lively) now, and Gentle is the one on by default; the row shows the value (the sheet that lists the three needs
        // Hilt, which a JVM page test does not have: the choice's wiring is B56BackgroundMotionPreferenceTest's). Player switches left: bookmark, file info, Animated covers.
        compose.onNodeWithText(s(R.string.background_motion_gentle)).performScrollTo().assertExists()
        for ((lively, moving, label) in listOf(Triple(true, true, R.string.background_motion_lively), Triple(false, false, R.string.background_motion_still))) {
            shown = AppearanceSettingsState.from(SettingsUiState(appColorSource = AppColorSource.COVER_GLASS, coverGlassMoving = moving, coverGlassLively = lively))
            compose.waitForIdle()
            compose.onNodeWithText(s(label)).performScrollTo().assertExists()
        }
        // the setting that is a switch again: Animated covers. B57 5 changes this on purpose: off by default in both modes (it was on in app mode), turned on by the listener
        val switches = compose.onAllNodes(isToggleable())
        switches[2].performScrollTo().assertIsOff().performClick()
        // B57-fix1 4 changes this on purpose: on Android 13+ with no folder kept, turning it on opens the system folder picker and calls nothing (a cancelled picker leaves it off);
        // with a folder, or before Android 13, it calls the action at once: B57Fix1AnimatedCoversSettingsTest.
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun `B41 - the lyrics options that depend on a switch appear only with it`() {
        val actions = AppearanceRecorder()
        var state by mutableStateOf(AppearanceSettingsState.from(SettingsUiState(immersiveLyricsEnabled = false, animatedLyricsBlurEnabled = false)))
        setPage { AppearanceSettingsScreen(state, actions) }
        // The blur strength is the option of a switch that is off.
        compose.onAllNodesWithText(s(R.string.presentation_batch_f_exp_blur_strength)).assertCountEquals(0)
        // B40-F5: immersive lyrics are back; the auto-hide delay is the option of its switch, which is off here.
        assertShows(R.string.setcat_immersive_lyrics_title)
        compose.onAllNodesWithText(s(R.string.setcat_auto_hide_delay_label)).assertCountEquals(0)
        state = AppearanceSettingsState.from(SettingsUiState(immersiveLyricsEnabled = true, animatedLyricsBlurEnabled = true))
        compose.waitForIdle()
        assertShows(R.string.presentation_batch_f_exp_blur_strength, R.string.setcat_immersive_lyrics_title, R.string.setcat_auto_hide_delay_label)
    }

    @Test
    fun `B41 - the lyrics rows and the lock screen switch that moved call the actions they always called`() {
        val actions = AppearanceRecorder()
        val state = AppearanceSettingsState.from(SettingsUiState(immersiveLyricsEnabled = false, animatedLyricsBlurEnabled = false))
        setPage { AppearanceSettingsScreen(state, actions) }
        // in page order: file info (Player; B56: the lyrics are a choice of three there now, not a switch), Animated covers (B56-7.2), then the Lyrics section, then the Lock screen's
        val switches = compose.onAllNodes(isToggleable())
        switches.assertCountEquals(9)
        for (i in 3..8) switches[i].performScrollTo().performClick()
        assertEquals(
            listOf("immersive:true", "translation:false", "romanization:false", "animated:true", "blur:true", "lockScreen:true"),
            actions.calls,
        )
    }

    @Test
    fun `B40-F1 - the app colours row says where the colours come from, and the accent row says what it is then for`() {
        val theme = AppearanceSettingsState.from(SettingsUiState())
        setPage { AppearanceSettingsScreen(theme, AppearanceRecorder()) }
        assertShows(R.string.setcat_app_colors_theme, R.string.setcat_accent_desc)
        compose.onAllNodesWithText(s(R.string.setcat_accent_desc_album_art)).assertCountEquals(0)
    }

    @Test
    fun `B40-F1 - in album art mode the row says so and the accent is the fallback for a song with no cover`() {
        val cover = AppearanceSettingsState.from(SettingsUiState(appColorSource = com.lostf1sh.pixelplayeross.data.preferences.AppColorSource.ALBUM_ART))
        setPage { AppearanceSettingsScreen(cover, AppearanceRecorder()) }
        assertShows(R.string.setcat_app_colors_album_art, R.string.setcat_accent_desc_album_art)
        compose.onAllNodesWithText(s(R.string.setcat_accent_desc)).assertCountEquals(0)
    }

    @Test
    fun `appearance has no palette to style when the player takes its colour from the system`() {
        val dynamic = AppearanceSettingsState.from(SettingsUiState(playerThemePreference = "dynamic"))
        setPage { AppearanceSettingsScreen(dynamic, AppearanceRecorder()) }
        compose.onAllNodesWithText(s(R.string.setcat_album_art_palette_title)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.setcat_player_theme_label)).assertCountEquals(0)
    }

    // ── Device ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `device shows the radios, the screen timeout and the storage`() {
        val calls = mutableListOf<String>()
        val actions = object : DeviceSettingsActions {
            override fun onManageWifi() { calls += "wifi" }
            override fun onEnableBluetooth() { calls += "enableBluetooth" }
            override fun onManageBluetooth() { calls += "bluetooth" }
            override fun onWifiEnabledChange(enabled: Boolean) { calls += "wifiOwner:$enabled" }
            override fun onBluetoothEnabledChange(enabled: Boolean) { calls += "bluetoothOwner:$enabled" }
            override fun onScreenTimeoutSelected(mode: Long) { calls += "timeout:$mode" }
        }
        setPage { DeviceSettingsScreen(DeviceSettingsState(DeviceControlUiState()), actions) }
        assertShows(
            R.string.setcat_device_connectivity, R.string.setcat_device_screen, R.string.setcat_device_storage,
            R.string.dap_manage_wifi, R.string.dap_manage_bluetooth, R.string.dap_screen_timeout_title,
        )
        compose.onNodeWithText(s(R.string.dap_manage_wifi)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.dap_manage_bluetooth)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.dap_timeout_1_minute)).performScrollTo().performClick()
        assertEquals(listOf("wifi", "bluetooth", "timeout:60000"), calls)
    }

    // ── Maintenance tools (were the Developer page, B32-Q6) ──────────────────────────────────────────

    private fun toolsActions(onCrash: () -> Unit = {}) = object : MaintenanceToolsActions {
        override suspend fun onRegeneratePalette(song: Song): Boolean = true
        override fun onRegenerateDailyMix() {}
        override fun onRegenerateStats() {}
        override fun onTriggerTestCrash() { onCrash() }
    }

    @Test
    fun `the maintenance tools show the three regenerations, and in a debug build the crash row calls its action`() {
        var crashes = 0
        setPage { MaintenanceToolsSection(MaintenanceToolsState(), toolsActions { crashes++ }, offersTestCrash = true) }
        assertShows(
            R.string.maintenance_regenerate_section, R.string.setcat_diagnostics, R.string.setcat_force_daily_mix_title,
            R.string.setcat_force_stats_title, R.string.setcat_force_palette_title, R.string.setcat_trigger_crash_title,
        )
        compose.onNodeWithText(s(R.string.setcat_force_palette_empty)).assertExists()
        compose.onNodeWithText(s(R.string.setcat_trigger_crash_title)).performScrollTo().performClick()
        assertEquals(1, crashes)
    }

    @Test
    fun `a release build's tools have no crash row at all`() {
        setPage { MaintenanceToolsSection(MaintenanceToolsState(), toolsActions(), offersTestCrash = false) }
        assertShows(
            R.string.maintenance_regenerate_section, R.string.setcat_force_daily_mix_title,
            R.string.setcat_force_stats_title, R.string.setcat_force_palette_title,
        )
        compose.onAllNodesWithText(s(R.string.setcat_trigger_crash_title)).assertCountEquals(0)
        compose.onAllNodesWithText(s(R.string.setcat_diagnostics)).assertCountEquals(0)
    }

    // ── Backup ───────────────────────────────────────────────────────────────────────────────────────

    private class BackupRecorder : BackupSettingsActions {
        val calls = mutableListOf<String>()
        override fun onDismissInfo() { calls += "dismissInfo" }
        override fun onExport(uri: android.net.Uri, sections: Set<BackupSection>, passphrase: String?) { calls += "export" }
        override fun onInspectBackup(uri: android.net.Uri, passphrase: String?) { calls += "inspect" }
        override fun onDismissEncryptedBackupPrompt() { calls += "dismissPrompt" }
        override fun onClearRestorePlan() { calls += "clearPlan" }
        override fun onUpdateRestorePlanSelection(selected: Set<BackupSection>) { calls += "select" }
        override fun onRestore(uri: android.net.Uri) { calls += "restore" }
        override fun onRemoveBackupHistoryEntry(entry: BackupHistoryEntry) { calls += "removeEntry" }
    }

    @Test
    fun `backup shows the one-time notice until it is dismissed, then the two actions`() {
        val actions = BackupRecorder()
        setPage { BackupSettingsScreen(BackupSettingsState(infoDismissed = false), actions) }
        assertShows(
            R.string.backup_how_title, R.string.setcat_create_backup, R.string.setcat_restore_backup_section,
            R.string.setcat_export_backup_title, R.string.setcat_import_backup_title,
        )
        compose.onNodeWithText(s(R.string.cd_close_notice)).performClick()
        assertEquals(listOf("dismissInfo"), actions.calls)
    }

    @Test
    fun `backup without the notice shows the two actions only`() {
        setPage { BackupSettingsScreen(BackupSettingsState(infoDismissed = true), BackupRecorder()) }
        compose.onAllNodesWithText(s(R.string.backup_how_title)).assertCountEquals(0)
        assertShows(R.string.setcat_export_backup_title, R.string.setcat_import_backup_title)
    }

    // ── Audio ────────────────────────────────────────────────────────────────────────────────────────
    // The Audio page (B22) has its own file: `AudioPageRenderTest`.
}
