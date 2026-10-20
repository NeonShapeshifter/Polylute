package com.lostf1sh.pixelplayeross.presentation.lockscreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.data.preferences.ThemePreference
import com.lostf1sh.pixelplayeross.presentation.screens.dapOutputSignalLine
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.ui.ds.AlbumAccent
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent

/**
 * The DAP lock surface and the state it reads, in a composable of its own (B22, Part 2).
 *
 * `MainActivity` used to collect the player's state and the device state inline, in the lambda that also holds the
 * app. Anything that lambda read, a song change recomposed it with the app inside. Here the surface reads what it
 * draws and nothing above it does, so a song change restarts this function and nothing else.
 *
 * The wake cycle is [DapLockScreenHost]'s and is untouched: this only hands it what it was handed before.
 */
@Composable
fun DapLockSurface(
    playerViewModel: PlayerViewModel,
    enabled: Boolean,
    dedicated: Boolean,
    onSurfaceVisibleChange: (Boolean) -> Unit,
    onRevealingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** B25-S4: see [DapLockScreenHost]; handed to the shell so it can hold the app from `SCREEN_OFF`. */
    cover: CoverSignal? = null,
) {
    val playbackState by playerViewModel.stablePlayerState
        .collectAsStateWithLifecycle(initialValue = StablePlayerState())
    // B25-S3: the lock surface's own position source. It used to read `currentPlaybackPosition.value`, but that flow is
    // refreshed only while something collects it (`PlaybackStateHolder.startProgressUpdates`), and with the app under the
    // surface held (B22) nothing does: the value sat where the last collector left it, and the elapsed time stood still
    // from the wake on. This asks the player itself, once per tick the surface makes while it is on screen.
    val positionProvider = remember(playerViewModel) { { playerViewModel.samplePlaybackPositionNow() } }
    // B29-L11: the lock surface wears the cover's accent, from the palette the theme holder already extracted for the song (one extraction per
    // cover, shared with the player): the colour of the song before until the new one is ready, none for a song with no cover or with the
    // player colour set to neutral. Collected with the surface's lifecycle, so with the screen off nothing here is read.
    val themePreference by playerViewModel.playerThemePreference.collectAsStateWithLifecycle()
    val schemePair by playerViewModel.activePlayerColorSchemePair.collectAsStateWithLifecycle()
    val surface = Dap.background
    val hasCover = !playbackState.currentSong?.albumArtUriString.isNullOrBlank()
    val albumAccent = remember(schemePair, themePreference, hasCover, surface) {
        val scheme = if (themePreference == ThemePreference.ALBUM_ART && hasCover) {
            schemePair?.let { if (surface.luminance() < 0.5f) it.dark else it.light }
        } else {
            null
        }
        AlbumAccent.fromScheme(scheme, surface)
    }
    // The signal path and battery the lock surface shows, read from the same state Settings shows them from. Only
    // created while the surface can appear.
    val device = if (enabled && dedicated) hiltViewModel<DeviceControlViewModel>() else null
    val deviceState = device?.uiState?.collectAsStateWithLifecycle()?.value
    val outputLine = deviceState?.let { dapOutputSignalLine(it) }
    // B30: untouched also needs the DAC's own EQ known off or flat (`purityHolds`), not just the app leaving the PCM alone.
    val untouched = deviceState?.audio?.purityHolds == true
    val battery = deviceState?.batteryPercent?.takeIf { it > 0 }
    CompositionLocalProvider(LocalDapAccent provides (albumAccent ?: LocalDapAccent.current)) {
    DapLockScreenHost(
        state = playbackState,
        enabled = enabled,
        dedicated = dedicated,
        // The flow's own value, not the composed one: with the screen off nothing recomposes.
        currentSongPresent = { playerViewModel.stablePlayerState.value.currentSong != null },
        currentPositionProvider = positionProvider,
        onPrevious = { playerViewModel.previousSong() },
        onPlayPause = { playerViewModel.playPause() },
        onNext = { playerViewModel.nextSong() },
        onToggleShuffle = { playerViewModel.toggleShuffle() },
        onToggleRepeat = { playerViewModel.cycleRepeatMode() },
        outputLine = outputLine,
        outputUntouched = untouched,
        batteryPercent = battery,
        onSurfaceVisibleChange = onSurfaceVisibleChange,
        onRevealingChange = onRevealingChange,
        modifier = modifier,
        cover = cover,
    )
    }
}
