package com.lostf1sh.pixelplayeross.presentation.settings

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.preferences.MUSIC_INFO_CACHE_CHOICES_MB
import com.lostf1sh.pixelplayeross.data.worker.SyncManager
import com.lostf1sh.pixelplayeross.data.worker.SyncProgress
import com.lostf1sh.pixelplayeross.data.worker.SyncState
import com.lostf1sh.pixelplayeross.presentation.screens.RefreshLibraryItem
import com.lostf1sh.pixelplayeross.presentation.screens.SettingsItem
import com.lostf1sh.pixelplayeross.presentation.screens.SliderSettingsItem
import com.lostf1sh.pixelplayeross.presentation.screens.SwitchSettingItem
import com.lostf1sh.pixelplayeross.presentation.screens.ThemeSelectorItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapChevron
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/** What the Library page draws. */
@Immutable
data class LibrarySettingsState(
    val minSongDuration: Int,
    val minTracksPerAlbum: Int,
    val albumArtCacheLimitMb: Int,
    val musicInfoCoversEnabled: Boolean,
    val musicInfoArtistPhotosEnabled: Boolean,
    val musicInfoDescriptionsEnabled: Boolean,
    val musicInfoCacheLimitMb: Int,
    val musicInfoUsedBytes: Long,
    val isSyncing: Boolean,
    val syncProgress: SyncProgress,
    val folderCoversEnabled: Boolean = true,
) {
    companion object {
        fun from(ui: SettingsUiState, isSyncing: Boolean, syncProgress: SyncProgress) = LibrarySettingsState(
            folderCoversEnabled = ui.folderCoversEnabled,
            minSongDuration = ui.minSongDuration,
            minTracksPerAlbum = ui.minTracksPerAlbum,
            albumArtCacheLimitMb = ui.albumArtCacheLimitMb,
            musicInfoCoversEnabled = ui.musicInfoCoversEnabled,
            musicInfoArtistPhotosEnabled = ui.musicInfoArtistPhotosEnabled,
            musicInfoDescriptionsEnabled = ui.musicInfoDescriptionsEnabled,
            musicInfoCacheLimitMb = ui.musicInfoCacheLimitMb,
            musicInfoUsedBytes = ui.musicInfoUsedBytes,
            isSyncing = isSyncing,
            syncProgress = syncProgress,
        )
    }
}

/**
 * The state the Library page draws, from the preferences it edits and the library sync ([SyncState], one reading of both the
 * "is it running" and the "how far" half).
 *
 * B39-A1: the page's state is held only while the page is listened to ([SharingStarted.WhileSubscribed] with
 * `replayExpirationMillis = 0`): once nobody has been listening for [SyncManager.SYNC_STOP_TIMEOUT_MS] it goes back to idle instead of
 * keeping the last thing it was told, so a page that comes back (the screen was off, another page was on top) starts from "idle" and the
 * sync's current state, never from the running snapshot of a sync that has finished since. The controls it enables ([LibrarySettingsState.isSyncing])
 * are exactly as live as the state they come from.
 */
internal fun libraryStateFlow(
    ui: Flow<SettingsUiState>,
    sync: Flow<SyncState>,
    scope: CoroutineScope,
    initialUi: SettingsUiState,
): StateFlow<LibrarySettingsState> =
    combine(ui, sync) { preferences, state -> LibrarySettingsState.from(preferences, state.isSyncing, state.progress) }
        .distinctUntilChanged()
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(SyncManager.SYNC_STOP_TIMEOUT_MS, replayExpirationMillis = 0),
            LibrarySettingsState.from(initialUi, false, SyncProgress()),
        )

/** Everything the Library page can do. */
interface LibrarySettingsActions {
    fun onFolderCoversChange(value: Boolean) {}
    fun onMinSongDurationChange(value: Int)
    fun onMinTracksPerAlbumChange(value: Int)
    fun onAlbumArtCacheLimitMbChange(value: Int)
    /** B40-F4: album covers that are missing, from the Cover Art Archive. Off by default. */
    fun onMusicInfoCoversChange(value: Boolean)
    /** B40-F4: artist photos, from Wikimedia Commons by way of MusicBrainz and Wikidata. Off by default. */
    fun onMusicInfoArtistPhotosChange(value: Boolean)
    /** B40-F4: artist and album descriptions, from Wikipedia. Off by default. */
    fun onMusicInfoDescriptionsChange(value: Boolean)
    fun onMusicInfoCacheLimitChange(megabytes: Int)
    /** B40-F4: removes everything that was downloaded; [onDone] gets the bytes freed. */
    fun onDeleteMusicInfo(onDone: (Long) -> Unit)
    fun onDeleteMusicInfoResult(onDone: (Result<Long>) -> Unit) {
        onDeleteMusicInfo { onDone(Result.success(it)) }
    }
    /** Opens the folders sheet over this page (the host owns the dialog): the folders that are in the library. */
    fun onOpenLibraryFolders()
    /** B39-A9: looks for folders that hold music and are not in the library, and offers them (the host owns the dialog). */
    fun onFindFolders()
    fun onOpenArtistSettings()
    fun onOpenDuplicates()
    /** An incremental scan. */
    fun onRefreshLibrary()
    /** A full rescan from scratch. */
    fun onFullRescan()
}

@Composable
fun LibrarySettingsScreen(state: LibrarySettingsState, actions: LibrarySettingsActions, mountInputBridge: Boolean = true) {
    var showOfflineDownloads by remember { mutableStateOf(false) }
    if (showOfflineDownloads) com.lostf1sh.pixelplayeross.presentation.components.MusicInfoDownloadsScreen(null, { showOfflineDownloads = false })
    val context = LocalContext.current
    val resources = LocalResources.current
    var refreshRequested by remember { mutableStateOf(false) }
    var syncRequestObservedRunning by remember { mutableStateOf(false) }
    var syncIndicatorLabel by remember { mutableStateOf<String?>(null) }
    // A drag is drafted locally and written once, when it ends (the scan the duration triggers is not cheap).
    var minSongDurationDraft by remember(state.minSongDuration) {
        mutableStateOf(state.minSongDuration.toFloat())
    }
    var minTracksPerAlbumDraft by remember(state.minTracksPerAlbum) {
        mutableStateOf(state.minTracksPerAlbum.toFloat())
    }
    var albumArtCacheLimitDraft by remember(state.albumArtCacheLimitMb) {
        mutableStateOf(state.albumArtCacheLimitMb.toFloat())
    }

    LaunchedEffect(state.isSyncing, state.syncProgress, refreshRequested) {
        if (!refreshRequested) return@LaunchedEffect

        if (state.isSyncing) {
            syncRequestObservedRunning = true
        } else if (syncRequestObservedRunning) {
            if (state.syncProgress.isCompleted) {
                Toast.makeText(context, context.getString(R.string.toast_library_sync_finished), Toast.LENGTH_SHORT).show()
            }
            refreshRequested = false
            syncRequestObservedRunning = false
            syncIndicatorLabel = null
        }
    }

    SettingsSubsection(title = stringResource(R.string.setcat_library_structure)) {
        SettingsItem(
            title = stringResource(R.string.library_folders_title),
            subtitle = stringResource(R.string.library_folders_subtitle),
            leadingIcon = { Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.secondary) },
            trailingIcon = { DapChevron() },
            onClick = { actions.onOpenLibraryFolders() }
        )
        SettingsItem(
            title = stringResource(R.string.library_find_folders_title),
            subtitle = stringResource(R.string.library_find_folders_subtitle),
            leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.secondary) },
            trailingIcon = { DapChevron() },
            onClick = { actions.onFindFolders() }
        )
        SettingsItem(
            title = stringResource(R.string.setcat_artists_title),
            subtitle = stringResource(R.string.setcat_artists_subtitle),
            leadingIcon = { Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.secondary) },
            trailingIcon = { DapChevron() },
            onClick = { actions.onOpenArtistSettings() }
        )
    }

    SettingsSubsection(title = stringResource(R.string.folder_covers_title)) {
        SwitchSettingItem(
            title = stringResource(R.string.folder_covers_title),
            subtitle = stringResource(R.string.folder_covers_note),
            checked = state.folderCoversEnabled,
            onCheckedChange = actions::onFolderCoversChange,
            leadingIcon = { Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.secondary) }
        )
    }
    LyricsFolderGrants()

    SettingsSubsection(title = stringResource(R.string.setcat_filtering)) {
        SliderSettingsItem(
            label = stringResource(R.string.setcat_min_song_duration),
            value = minSongDurationDraft,
            valueRange = 0f..120000f,
            steps = 23,
            onValueChange = { minSongDurationDraft = it },
            onValueChangeFinished = {
                val selectedDuration = minSongDurationDraft.toInt()
                if (selectedDuration != state.minSongDuration) {
                    actions.onMinSongDurationChange(selectedDuration)
                }
            },
            valueText = { value -> "${(value / 1000).toInt()}s" },
        )
        SliderSettingsItem(
            label = stringResource(R.string.setcat_min_tracks_per_album),
            value = minTracksPerAlbumDraft,
            valueRange = 1f..5f,
            steps = 3,
            onValueChange = { minTracksPerAlbumDraft = it },
            onValueChangeFinished = {
                val selectedTracks = minTracksPerAlbumDraft.toInt()
                if (selectedTracks != state.minTracksPerAlbum) {
                    actions.onMinTracksPerAlbumChange(selectedTracks)
                }
            },
            valueText = { value -> "${value.toInt()}" },
        )
        SliderSettingsItem(
            label = stringResource(R.string.setcat_album_art_cache_limit),
            value = albumArtCacheLimitDraft,
            valueRange = 50f..1500f,
            steps = 28,
            onValueChange = { albumArtCacheLimitDraft = it },
            onValueChangeFinished = {
                val selectedLimit = albumArtCacheLimitDraft.toInt()
                if (selectedLimit != state.albumArtCacheLimitMb) {
                    actions.onAlbumArtCacheLimitMbChange(selectedLimit)
                }
            },
            valueText = { value -> "${value.toInt()} MB" },
        )
    }

    SettingsSubsection(title = stringResource(R.string.setcat_sync_scanning)) {
        RefreshLibraryItem(
            isSyncing = state.isSyncing,
            syncProgress = state.syncProgress,
            activeOperationLabel = if (state.isSyncing) syncIndicatorLabel else null,
            onUpdateLibrary = {
                if (state.isSyncing) return@RefreshLibraryItem
                refreshRequested = true
                syncRequestObservedRunning = false
                syncIndicatorLabel = context.getString(R.string.dap_library_scan)
                actions.onRefreshLibrary()
            },
            onFullRescan = {
                if (state.isSyncing) return@RefreshLibraryItem
                refreshRequested = true
                syncRequestObservedRunning = false
                syncIndicatorLabel = context.getString(R.string.setcat_sync_full_rescan_label)
                Toast.makeText(context, context.getString(R.string.toast_full_rescan_started), Toast.LENGTH_SHORT).show()
                actions.onFullRescan()
            }
        )
        SettingsItem(
            title = stringResource(R.string.setcat_find_duplicates_title),
            subtitle = stringResource(R.string.setcat_find_duplicates_subtitle),
            leadingIcon = { Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.secondary) },
            trailingIcon = { DapChevron() },
            onClick = { actions.onOpenDuplicates() }
        )
    }

    // B40-F4: music info from the internet. Three switches, each off until the listener turns it on; everything downloaded stays on the
    // phone within the limit set here and is deleted from here. (Lyrics have their own category.) Deezer, which used to be the one switch
    // here, is gone.
    MusicInfoSettingsSection(state, actions, onOpen = { showOfflineDownloads = true }, mountInputBridge = mountInputBridge)
}
