package com.lostf1sh.pixelplayeross.presentation.components.player

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
 * Where the lyrics page is opened from, and what it needs to stay open: the fetch dialog's flag, and the file picker the dialog and the page
 * both offer. One object per player so the header's Lyrics icon, the page and the dialog agree on both.
 *
 * B42-fix1: the lyrics are a page of the player's pager, so "is it showing" is the pager's (`PlayerPagesState`), and opening them is
 * [showPage]: a jump to that page.
 */
@Stable
internal class PlayerLyricsEntry {
    var isFetchDialogShowing by mutableStateOf(false)
    internal var showPage: () -> Unit = {}
    internal var openLyrics: () -> Unit = {}
    internal var pickFile: () -> Unit = {}

    /** The Lyrics icon: the page, or the search when the song has no lyrics at all. */
    fun open() = openLyrics()
}

@Composable
internal fun rememberPlayerLyricsEntry(
    currentSongId: String?,
    lyrics: PlayerLyricsState,
    actions: FullPlayerActions,
    showPage: () -> Unit,
): PlayerLyricsEntry {
    val entry = remember { PlayerLyricsEntry() }
    val latestShowPage by rememberUpdatedState(showPage)
    entry.showPage = { latestShowPage() }
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
                        entry.showPage()
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
            hasLyrics -> entry.showPage()

            // Local sources have not been consulted yet for this song. Open the lyrics page,
            // which resolves local-first, rather than offering a network search up front.
            !latestLyrics.lyricsResolveCompletedProvider() -> {
                latestActions.onEnsureLyricsResolved()
                entry.showPage()
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
                    entry.showPage()
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

/** The fetch dialog: a window of its own, so it is composed beside the pages, not on one of them (a page is only composed while it is near). */
@Composable
internal fun PlayerLyricsDialogs(
    entry: PlayerLyricsEntry,
    song: Song,
    lyrics: PlayerLyricsState,
    actions: FullPlayerActions,
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
}

/**
 * The lyrics page (B42-fix1): the lyrics view as one of the player's pages. It is composed only while it is on or next to the screen, so
 * arriving on it (a swipe, or the header's icon) is what makes sure the song's lyrics have been looked for locally, as opening them used to.
 */
@Composable
internal fun PlayerLyricsPage(
    entry: PlayerLyricsEntry,
    song: Song,
    lyrics: PlayerLyricsState,
    actions: FullPlayerActions,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(song.id) {
        if (!lyrics.lyricsResolveCompletedProvider()) actions.onEnsureLyricsResolved()
    }
    LyricsSheet(
        stablePlayerStateFlow = lyrics.stablePlayerStateFlow,
        playbackPositionFlow = lyrics.playbackPositionFlow,
        lyricsSearchUiState = lyrics.searchState,
        resetLyricsForCurrentSong = { actions.onResetLyrics() },
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
        onSaveLyricsToFile = actions::onSaveLyricsToFile,
        onSeekTo = { actions.onSeek(it) },
        onPlayPause = {
            actions.onPlayPause()
        },
        immersiveLyricsEnabled = lyrics.immersiveLyricsEnabled,
        immersiveLyricsTimeout = lyrics.immersiveLyricsTimeout,
        isImmersiveTemporarilyDisabled = lyrics.isImmersiveTemporarilyDisabled,
        externalLyricsEnabled = lyrics.externalLyricsEnabled,
        modifier = modifier,
    )
}
