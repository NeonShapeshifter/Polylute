package com.lostf1sh.pixelplayeross.presentation.settings

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.worker.SyncProgress
import com.lostf1sh.pixelplayeross.presentation.screens.RefreshLibraryItem
import com.lostf1sh.pixelplayeross.presentation.screens.SettingsItem
import com.lostf1sh.pixelplayeross.presentation.screens.SliderSettingsItem
import com.lostf1sh.pixelplayeross.presentation.screens.SwitchSettingItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapChevron

/** What the Library page draws. */
@Immutable
data class LibrarySettingsState(
    val minSongDuration: Int,
    val minTracksPerAlbum: Int,
    val albumArtCacheLimitMb: Int,
    val externalArtistImagesEnabled: Boolean,
    val isSyncing: Boolean,
    val syncProgress: SyncProgress,
) {
    companion object {
        fun from(ui: SettingsUiState, isSyncing: Boolean, syncProgress: SyncProgress) = LibrarySettingsState(
            minSongDuration = ui.minSongDuration,
            minTracksPerAlbum = ui.minTracksPerAlbum,
            albumArtCacheLimitMb = ui.albumArtCacheLimitMb,
            externalArtistImagesEnabled = ui.externalArtistImagesEnabled,
            isSyncing = isSyncing,
            syncProgress = syncProgress,
        )
    }
}

/** Everything the Library page can do. */
interface LibrarySettingsActions {
    fun onMinSongDurationChange(value: Int)
    fun onMinTracksPerAlbumChange(value: Int)
    fun onAlbumArtCacheLimitMbChange(value: Int)
    fun onExternalArtistImagesEnabledChange(value: Boolean)
    /** Opens the directory explorer over this page (the host owns the dialog). */
    fun onOpenExcludedDirectories()
    fun onOpenArtistSettings()
    fun onOpenDuplicates()
    /** An incremental scan. */
    fun onRefreshLibrary()
    /** A full rescan from scratch. */
    fun onFullRescan()
}

@Composable
fun LibrarySettingsScreen(state: LibrarySettingsState, actions: LibrarySettingsActions) {
    val context = LocalContext.current
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
            title = stringResource(R.string.setcat_excluded_directories_title),
            subtitle = stringResource(R.string.setcat_excluded_directories_subtitle),
            leadingIcon = { Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.secondary) },
            trailingIcon = { DapChevron() },
            onClick = { actions.onOpenExcludedDirectories() }
        )
        SettingsItem(
            title = stringResource(R.string.setcat_artists_title),
            subtitle = stringResource(R.string.setcat_artists_subtitle),
            leadingIcon = { Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.secondary) },
            trailingIcon = { DapChevron() },
            onClick = { actions.onOpenArtistSettings() }
        )
    }

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

    // Online metadata for the library itself. Lyrics sources used to sit
    // beside this under one "Online services" heading, which made the
    // heading mean two different subjects: where track metadata comes from,
    // and how lyrics are looked up. Lyrics has its own category now.
    SettingsSubsection(
        title = stringResource(R.string.setcat_online_services),
        addBottomSpace = false
    ) {
        SwitchSettingItem(
            title = stringResource(R.string.setcat_external_artist_images_title),
            subtitle = stringResource(R.string.setcat_external_artist_images_subtitle),
            checked = state.externalArtistImagesEnabled,
            onCheckedChange = { actions.onExternalArtistImagesEnabledChange(it) },
            leadingIcon = { Icon(painterResource(R.drawable.rounded_artist_24), null, tint = MaterialTheme.colorScheme.secondary) },
        )
    }
}
