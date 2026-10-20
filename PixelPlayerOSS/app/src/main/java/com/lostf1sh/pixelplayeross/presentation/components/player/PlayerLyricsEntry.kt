package com.lostf1sh.pixelplayeross.presentation.components.player

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.LocalMaterialTheme
import com.lostf1sh.pixelplayeross.presentation.components.LyricsSheet
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.FetchLyricsDialog
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.ui.ds.DapMotion
import com.lostf1sh.pixelplayeross.utils.LyricsImportFailureReason
import com.lostf1sh.pixelplayeross.utils.LyricsImportSecurity
import com.lostf1sh.pixelplayeross.utils.LyricsImportValidationResult
import com.lostf1sh.pixelplayeross.utils.ValidatedLyricsImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

internal suspend fun validateLyricsImport(
    context: Context,
    uri: Uri
): LyricsImportValidationResult = withContext(Dispatchers.IO) {
    val contentResolver = context.contentResolver

    var fileName = ""
    var fileSize: Long? = null
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            fileName = if (nameIndex != -1) cursor.getString(nameIndex) else ""
            fileSize = if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                cursor.getLong(sizeIndex)
            } else {
                null
            }
        }
    }

    contentResolver.openInputStream(uri)?.use { inputStream ->
        LyricsImportSecurity.validateImportedLyricsFile(
            fileName = fileName,
            mimeType = contentResolver.getType(uri),
            inputStream = inputStream,
            reportedSizeBytes = fileSize
        )
    } ?: LyricsImportValidationResult.Invalid(LyricsImportFailureReason.EMPTY_CONTENT)
}

/**
 * Where the lyrics surface is opened from, and what it needs to stay open: the "is it showing" flag, the fetch
 * dialog's, and the file picker the dialog and the sheet both offer. One object per player so the header's Lyrics
 * tab, the sheet and the dialog agree on all three.
 */
@Stable
internal class PlayerLyricsEntry {
    var isShowing by mutableStateOf(false)
    var isFetchDialogShowing by mutableStateOf(false)
    internal var openLyrics: () -> Unit = {}
    internal var pickFile: () -> Unit = {}

    /** The Lyrics tab, or a tap on the lyrics affordance. */
    fun open() = openLyrics()
    fun close() {
        isShowing = false
    }
}

@Composable
internal fun rememberPlayerLyricsEntry(
    currentSongId: String?,
    lyrics: PlayerLyricsState,
    actions: FullPlayerActions,
): PlayerLyricsEntry {
    val entry = remember { PlayerLyricsEntry() }
    val context = LocalContext.current
    // Resolved here, not inside the launcher: the import path is a coroutine with a try/catch,
    // and neither is a place a composable call can live.
    val lyricsImportNoSong = stringResource(R.string.lyrics_import_no_song)
    val lyricsImportReadError = stringResource(R.string.lyrics_import_read_error)
    val fileImportScope = rememberCoroutineScope()
    val latestSongId by rememberUpdatedState(currentSongId)
    val latestLyrics by rememberUpdatedState(lyrics)
    val latestActions by rememberUpdatedState(actions)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                fileImportScope.launch {
                    try {
                        val validation = validateLyricsImport(context, it)
                        val validatedImport: ValidatedLyricsImport = when (validation) {
                            is LyricsImportValidationResult.Valid -> validation.value
                            is LyricsImportValidationResult.Invalid -> {
                                latestActions.onToast(
                                    LyricsImportSecurity.messageFor(validation.reason)
                                )
                                return@launch
                            }
                        }

                        val currentSongId = latestSongId?.toLongOrNull()
                        if (currentSongId == null) {
                            latestActions.onToast(lyricsImportNoSong)
                            return@launch
                        }

                        latestActions.onImportLyricsFromFile(currentSongId, validatedImport)
                        entry.isFetchDialogShowing = false
                        entry.isShowing = true
                    } catch (e: Exception) {
                        Timber.e(e, "Error reading imported lyrics file")
                        latestActions.onToast(lyricsImportReadError)
                    }
                }
            }
        }
    )
    entry.pickFile = { filePickerLauncher.launch(LyricsImportSecurity.pickerMimeTypes()) }
    entry.openLyrics = {
        val current = latestLyrics.lyricsProvider()
        val hasLyrics = !current?.synced.isNullOrEmpty() || !current?.plain.isNullOrEmpty()
        when {
            hasLyrics -> entry.isShowing = true

            // Local sources have not been consulted yet for this song. Open the lyrics view,
            // which resolves local-first, rather than offering a network search up front.
            !latestLyrics.lyricsResolveCompletedProvider() -> {
                latestActions.onEnsureLyricsResolved()
                entry.isShowing = true
            }

            // Embedded tags, sidecar files and the local cache are all genuinely empty.
            else -> entry.isFetchDialogShowing = true
        }
    }

    LaunchedEffect(lyrics.searchState) {
        when (lyrics.searchState) {
            is LyricsSearchUiState.Success -> {
                if (entry.isFetchDialogShowing) {
                    entry.isFetchDialogShowing = false
                    entry.isShowing = true
                    latestActions.onResetLyricsSearch()
                }
            }
            is LyricsSearchUiState.Error -> {
            }
            else -> Unit
        }
    }
    return entry
}

/** The fetch dialog and the lyrics sheet. Composed after the player's own surface, over it. */
@Composable
internal fun PlayerLyricsLayer(
    entry: PlayerLyricsEntry,
    song: Song,
    lyrics: PlayerLyricsState,
    actions: FullPlayerActions,
    modifier: Modifier = Modifier,
) {
    if (entry.isFetchDialogShowing) {
        MaterialTheme(
            colorScheme = LocalMaterialTheme.current,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes
        ) {
            FetchLyricsDialog(
                uiState = lyrics.searchState,
                currentSong = song,
                onConfirm = { forcePick ->
                    actions.onFetchLyrics(forcePick)
                },
                onPickResult = { result ->
                    actions.onAcceptLyricsResult(result)
                },
                onManualSearch = { title, artist ->
                    actions.onSearchLyricsManually(title, artist)
                },
                onDismiss = {
                    entry.isFetchDialogShowing = false
                    actions.onResetLyricsSearch()
                },
                onImport = {
                    entry.pickFile()
                }
            )
        }
    }

    AnimatedVisibility(
        visible = entry.isShowing,
        modifier = modifier,
        enter = slideInVertically(
            initialOffsetY = { it / 5 },
            animationSpec = DapMotion.surface()
        ) + fadeIn(animationSpec = DapMotion.content()),
        exit = slideOutVertically(
            targetOffsetY = { it / 6 },
            animationSpec = DapMotion.surfaceExit()
        ) + fadeOut(animationSpec = DapMotion.contentExit())
    ) {
        LyricsSheet(
            stablePlayerStateFlow = lyrics.stablePlayerStateFlow,
            playbackPositionFlow = lyrics.playbackPositionFlow,
            lyricsSearchUiState = lyrics.searchState,
            resetLyricsForCurrentSong = {
                entry.close()
                actions.onResetLyrics()
            },
            onSearchLyrics = { forcePick -> actions.onFetchLyrics(forcePick) },
            onPickResult = { actions.onAcceptLyricsResult(it) },
            onManualSearch = { title, artist -> actions.onSearchLyricsManually(title, artist) },
            onImportLyrics = { entry.pickFile() },
            onDismissLyricsSearch = { actions.onResetLyricsSearch() },
            lyricsSyncOffset = lyrics.lyricsSyncOffset,
            onLyricsSyncOffsetChange = { actions.onLyricsSyncOffsetChange(song.id, it) },
            lyricsTextStyle = MaterialTheme.typography.titleLarge,
            colorScheme = LocalMaterialTheme.current,
            // Display preferences are edited from the lyrics surface too, but they are written
            // through the ViewModel so the repository stays the only writer of these keys.
            onLyricsAlignmentChange = { actions.onLyricsAlignmentChange(it) },
            onShowLyricsTranslationChange = { actions.onShowLyricsTranslationChange(it) },
            onShowLyricsRomanizationChange = { actions.onShowLyricsRomanizationChange(it) },
            onBackClick = { entry.close() },
            onSaveLyricsToFile = actions::onSaveLyricsToFile,
            onSeekTo = { actions.onSeek(it) },
            onPlayPause = {
                actions.onPlayPause()
            },
            onNext = actions::onNext,
            onPrev = actions::onPrevious,
            immersiveLyricsEnabled = lyrics.immersiveLyricsEnabled,
            immersiveLyricsTimeout = lyrics.immersiveLyricsTimeout,
            isImmersiveTemporarilyDisabled = lyrics.isImmersiveTemporarilyDisabled,
            externalLyricsEnabled = lyrics.externalLyricsEnabled
        )
    }
}
