package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.text.KeyboardActions
import com.lostf1sh.pixelplayeross.ui.ds.dapDismissKeyboardActions
import com.lostf1sh.pixelplayeross.ui.ds.dapTextField
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import com.lostf1sh.pixelplayeross.presentation.components.scoped.OneShotAction
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapDestructiveAction
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapHeights
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapNote
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapTopBar
import java.net.URLEncoder
import timber.log.Timber
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.ui.theme.RoundedSans
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import dev.shreyaspatil.capturable.capturable
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusModifier
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader
import com.lostf1sh.pixelplayeross.data.media.CoverArtUpdate
import dev.shreyaspatil.capturable.controller.rememberCaptureController
import java.io.ByteArrayOutputStream
import java.util.Locale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow

private fun formatReplayGainForInput(gainDb: Float?): String {
    return gainDb?.let { String.format(Locale.US, "%.2f", it) }.orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EditSongSheet(
    visible: Boolean,
    song: Song,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
        composer: String,
        genre: String,
        lyrics: String,
        trackNumber: Int,
        discNumber: Int?,
        replayGainTrackGainDb: String,
        replayGainAlbumGainDb: String,
        coverArtUpdate: CoverArtUpdate?
    ) -> Unit
) {
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = visible

    if (transitionState.currentState || transitionState.targetState) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            // This window is its own surface, so the activity's volume-key path does not
            // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
            ModalInputBridge()
            AnimatedVisibility(
                visibleState = transitionState,
                enter = slideInVertically(initialOffsetY = { it / 6 }) + fadeIn(animationSpec = tween(220)),
                exit = slideOutVertically(targetOffsetY = { it / 6 }) + fadeOut(animationSpec = tween(200))
            ) {
                EditSongContent(
                    song = song,
                    onDismiss = onDismiss,
                    onSave = onSave
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSongContent(
    song: Song,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
        composer: String,
        genre: String,
        lyrics: String,
        trackNumber: Int,
        discNumber: Int?,
        replayGainTrackGainDb: String,
        replayGainAlbumGainDb: String,
        coverArtUpdate: CoverArtUpdate?
    ) -> Unit,
) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.displayArtist) }
    var album by remember { mutableStateOf(song.album) }
    var albumArtist by remember { mutableStateOf(song.albumArtist ?: "") }
    var composer by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf(song.genre ?: "") }
    var lyrics by remember { mutableStateOf(song.lyrics ?: "") }
    var trackNumber by remember { mutableStateOf(song.trackNumber.toString()) }
    var discNumber by remember { mutableStateOf(song.discNumber?.toString() ?: "") }
    var replayGainTrackGainDb by remember { mutableStateOf("") }
    var replayGainAlbumGainDb by remember { mutableStateOf("") }
    var coverArtPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var editedCoverArt by remember { mutableStateOf<CoverArtUpdate?>(null) }
    var isCoverArtDeleted by remember { mutableStateOf(false) }
    var showCoverArtCropper by remember { mutableStateOf(false) }
    var pendingCoverArtUri by remember { mutableStateOf<Uri?>(null) }

    var showInfoDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pickCoverArtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            pendingCoverArtUri = uri
            showCoverArtCropper = true
        }
    }

    LaunchedEffect(song) {
        title = song.title
        artist = song.displayArtist
        album = song.album
        albumArtist = song.albumArtist ?: ""
        composer = ""
        genre = song.genre ?: ""
        lyrics = song.lyrics ?: ""
        trackNumber = song.trackNumber.toString()
        discNumber = song.discNumber?.toString() ?: ""
        replayGainTrackGainDb = ""
        replayGainAlbumGainDb = ""
        coverArtPreview = null
        editedCoverArt = null
        isCoverArtDeleted = false

        if (song.path.isNotBlank()) {
            val embeddedMetadata = withContext(Dispatchers.IO) {
                try {
                    val file = java.io.File(song.path)
                    if (file.exists()) {
                        AudioMetadataReader.read(file, readArtwork = false)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to read embedded metadata for EditSongSheet")
                    null
                }
            }

            if (lyrics.isBlank()) {
                embeddedMetadata?.lyrics?.takeIf { it.isNotBlank() }?.let { embeddedLyrics ->
                    lyrics = embeddedLyrics
                }
            }

            embeddedMetadata?.composer?.takeIf { it.isNotBlank() }?.let { composer = it }
            replayGainTrackGainDb = formatReplayGainForInput(embeddedMetadata?.replayGainTrackGainDb)
            replayGainAlbumGainDb = formatReplayGainForInput(embeddedMetadata?.replayGainAlbumGainDb)
        }
    }

    if (showCoverArtCropper && pendingCoverArtUri != null) {
        CoverArtCropperDialog(
            sourceUri = pendingCoverArtUri!!,
            onDismiss = {
                showCoverArtCropper = false
                pendingCoverArtUri = null
            },
            onConfirm = { result ->
                coverArtPreview = result.preview
                editedCoverArt = result.update
                isCoverArtDeleted = false
                showCoverArtCropper = false
                pendingCoverArtUri = null
            }
        )
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            icon = { Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.cd_info_icon)) },
            title = {
                // An alert is a window of its own, so the activity's volume-key path does not reach
                // it. The bridge routes Volume Up/Down to the same authority the activity uses.
                ModalInputBridge()
                Text(stringResource(R.string.edit_song_info_dialog_title))
            },
            text = { Text(stringResource(R.string.edit_song_info_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text(stringResource(R.string.edit_song_got_it), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
    }

    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val isKeyboardVisible by remember { derivedStateOf { imeInsets.getBottom(density) > 0 } }

    // A save is a write to the file and its metadata row, and it cannot be taken back. Saving
    // dismisses the sheet, but the sheet stays composed through its exit animation, so a second
    // tap ~200 ms after the first used to land on a still-live Save button and start a second
    // write. Keyed to the song so opening the editor for a different track starts clean.
    val saveAction = remember(song.id) { OneShotAction() }

    // The save payload is built exactly where it always was: the same reads of the same state, the
    // same trims, the same fallback to the stored track number when the field does not parse.
    val saveEdits = {
        saveAction.invoke {
            val resolvedTrackNumber = trackNumber.toIntOrNull() ?: song.trackNumber
            val resolvedDiscNumber = discNumber.toIntOrNull()
            onSave(
                title.trim(),
                artist.trim(),
                album.trim(),
                albumArtist.trim(),
                composer.trim(),
                genre.trim(),
                lyrics,
                resolvedTrackNumber,
                resolvedDiscNumber,
                replayGainTrackGainDb.trim(),
                replayGainAlbumGainDb.trim(),
                editedCoverArt
            )
        }
    }

    Scaffold(
        topBar = {
            // Inventory of the bar this replaces: a title and one trailing action — the metadata
            // info button (`showInfoDialog = true`), with its content description. No navigation
            // icon. The action is re-hosted verbatim; the sheet is dismissed the way it always
            // was, by its own dismiss affordances rather than by the bar.
            // The bar carries the status-bar inset itself; the content insets are zeroed below so
            // the sheet's title does not get padded twice. Without this the action's label — which
            // is longest in Spanish — is drawn under the clock.
            Column(modifier = Modifier.statusBarsPadding()) {
                DapTopBar(
                    actions = {
                        DapTextAction(
                            label = stringResource(R.string.cd_show_metadata_info),
                            onClick = { showInfoDialog = true },
                        )
                    },
                )
                DapScreenTitle(
                    text = stringResource(R.string.edit_song_toolbar_title),
                    modifier = Modifier.padding(horizontal = DapSpacing.Gutter),
                )
            }
        },
        bottomBar = {
            // Was a `HorizontalFloatingToolbar` — an M3 floating pill holding a filled tonal
            // "Cancel" and a filled "Save", the latter of which is the only primary action on the
            // page and was drawn like every other button on it. It is a plain bar now: a hairline,
            // and the one filled button this surface is allowed.
            AnimatedVisibility(
                visible = !isKeyboardVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Dap.background)
                        .navigationBarsPadding()
                ) {
                    DapDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Md),
                        horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md),
                    ) {
                        DapSecondaryButton(
                            label = stringResource(R.string.cancel),
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                        DapPrimaryButton(
                            label = stringResource(R.string.action_save),
                            onClick = saveEdits,
                            modifier = Modifier.weight(1f),
                            // Disabled for the in-flight window only. Cancel stays live on
                            // purpose: dismissing never writes, and blocking it would trap the
                            // user in the editor if a save ever failed to settle.
                            enabled = !saveAction.isTaken,
                        )
                    }
                }
            }
        },
        containerColor = Dap.background,
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DapSpacing.Gutter)
                .padding(bottom = DapSpacing.SectionGap),
        ) {
            CoverArtSection(
                albumArtUri = song.albumArtUriString,
                preview = coverArtPreview,
                isDeleted = isCoverArtDeleted,
                onPickNewArt = {
                    pickCoverArtLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onDelete = {
                    coverArtPreview = null
                    isCoverArtDeleted = true
                    editedCoverArt = CoverArtUpdate(isDeletion = true)
                },
                onReset = {
                    coverArtPreview = null
                    editedCoverArt = null
                    isCoverArtDeleted = false
                }
            )

            Spacer(Modifier.height(DapSpacing.SectionGap))

            DapGroup(title = stringResource(R.string.edit_song_group_details)) {
                EditField(
                    label = stringResource(R.string.song_field_title),
                    value = title,
                    onValueChange = { title = it },
                )
                EditField(
                    label = stringResource(R.string.song_field_artist),
                    value = artist,
                    onValueChange = { artist = it },
                )
                EditField(
                    label = stringResource(R.string.song_field_album),
                    value = album,
                    onValueChange = { album = it },
                )
                EditField(
                    label = stringResource(R.string.song_field_album_artist),
                    value = albumArtist,
                    onValueChange = { albumArtist = it },
                )
                EditField(
                    label = stringResource(R.string.song_field_genre),
                    value = genre,
                    onValueChange = { genre = it },
                )
                EditField(
                    label = stringResource(R.string.song_field_composer),
                    value = composer,
                    onValueChange = { composer = it },
                )
                EditField(
                    label = stringResource(R.string.song_field_track_number),
                    value = trackNumber,
                    onValueChange = { trackNumber = it },
                    keyboardType = KeyboardType.Number,
                )
                EditField(
                    label = stringResource(R.string.song_field_disc_number),
                    value = discNumber,
                    onValueChange = { discNumber = it },
                    keyboardType = KeyboardType.Number,
                    divider = false,
                )
            }

            Spacer(Modifier.height(DapSpacing.SectionGap))

            DapGroup(title = stringResource(R.string.edit_song_group_replaygain)) {
                EditField(
                    label = stringResource(R.string.song_field_replaygain_track_db),
                    value = replayGainTrackGainDb,
                    onValueChange = { replayGainTrackGainDb = it },
                    placeholder = stringResource(R.string.placeholder_replaygain_track_example),
                    keyboardType = KeyboardType.Decimal,
                )
                EditField(
                    label = stringResource(R.string.song_field_replaygain_album_db),
                    value = replayGainAlbumGainDb,
                    onValueChange = { replayGainAlbumGainDb = it },
                    placeholder = stringResource(R.string.placeholder_replaygain_album_example),
                    keyboardType = KeyboardType.Decimal,
                    divider = false,
                )
            }

            Spacer(Modifier.height(DapSpacing.SectionGap))

            DapGroup(title = stringResource(R.string.lyrics)) {
                EditField(
                    label = stringResource(R.string.lyrics),
                    value = lyrics,
                    onValueChange = { lyrics = it },
                    singleLine = false,
                    minHeight = 140.dp,
                    divider = false,
                )
                // The lyric lookup is an outbound link, so it is a text action under the field
                // rather than a filled icon button beside it.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DapTextAction(
                        label = stringResource(R.string.edit_song_find_lyrics),
                        onClick = {
                            val encodedTitle = URLEncoder.encode(title, "UTF-8")
                            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
                            val url = "https://lrclib.net/search/$encodedTitle%20$encodedArtist"
                            val intent = Intent(Intent.ACTION_VIEW).setData(Uri.parse(url))
                            context.startActivity(intent)
                        },
                        color = Dap.secondaryText,
                    )
                }
                Spacer(Modifier.height(DapSpacing.Sm))
            }
        }
    }
}

/**
 * A labelled text input in the DAP language.
 *
 * The design system has no text-input primitive: every other surface it covers is either read-only
 * or driven by switches, sliders and choices, and this sheet is the only free-text form in the app.
 * It is built here from the same tokens those primitives use rather than promoted into `ui.ds`
 * speculatively. If a second form appears, this is what should move there.
 *
 * No leading icon. The system's rows are text-led, and the Material glyphs this replaces (Album,
 * Person, Category, Notes, Repeat…) do not exist in the DAP icon set — deliberately, since they were
 * decoration on a field whose label already said the same thing.
 */
@Composable
private fun EditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minHeight: Dp = DapHeights.SettingsRow,
    divider: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = DapType.Caption,
            color = Dap.tertiaryText,
            modifier = Modifier.padding(top = DapSpacing.Md),
        )
        Spacer(Modifier.height(DapSpacing.Sm))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = DapType.Body.copy(color = Dap.primaryText),
            cursorBrush = SolidColor(Dap.primaryText),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            // One-line fields finish on Enter; a multi-line field keeps Enter as a newline.
            keyboardActions = if (singleLine) dapDismissKeyboardActions() else KeyboardActions.Default,
            modifier = Modifier.fillMaxWidth().dapTextField(),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = minHeight)
                        .clip(RoundedCornerShape(DapRadius.Surface))
                        .background(Dap.surfaceRaised)
                        .padding(horizontal = DapSpacing.Md, vertical = DapSpacing.Sm),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = DapType.Body,
                            color = Dap.disabledText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        Spacer(Modifier.height(DapSpacing.Md))
        if (divider) DapDivider()
    }
}

/**
 * The cover-art block: the current art, the two things you can do to it, and the hint.
 *
 * Was a `Surface` at `tonalElevation = 6.dp` with a 12 dp smooth corner, a centred 220 dp art well
 * with a decorative gradient scrim over it, a centred title, and two full-width filled buttons —
 * one of them `errorContainer` red for a delete. It is a group now, in the same compact
 * artwork-plus-actions shape the rest of the app uses for a thing that has art.
 */
@Composable
private fun CoverArtSection(
    albumArtUri: String?,
    preview: ImageBitmap?,
    isDeleted: Boolean,
    onPickNewArt: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
) {
    DapGroup(title = stringResource(R.string.edit_song_cover_art_heading)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DapSpacing.Md),
            horizontalArrangement = Arrangement.spacedBy(DapSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(DapRadius.Surface))
                    .background(Dap.surfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isDeleted -> DapIcon(
                        kind = DapIconKind.Note,
                        tint = Dap.disabledText,
                        size = 40.dp,
                    )

                    preview != null -> Image(
                        bitmap = preview,
                        contentDescription = stringResource(R.string.cd_cover_art_preview),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    albumArtUri != null -> SmartImage(
                        model = albumArtUri,
                        contentDescription = stringResource(R.string.cd_current_song_cover_art),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholderResId = R.drawable.rounded_music_note_24,
                        errorResId = R.drawable.rounded_broken_image_24
                    )

                    else -> DapIcon(
                        kind = DapIconKind.Note,
                        tint = Dap.disabledText,
                        size = 40.dp,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                DapTextAction(
                    label = stringResource(R.string.edit_song_change_cover_art),
                    onClick = onPickNewArt,
                    color = Dap.primaryText,
                )
                if (preview != null || isDeleted) {
                    Spacer(Modifier.height(DapSpacing.Xs))
                    DapTextAction(
                        label = stringResource(R.string.action_reset),
                        onClick = onReset,
                        color = Dap.secondaryText,
                    )
                } else if (albumArtUri != null) {
                    Spacer(Modifier.height(DapSpacing.Xs))
                    // The one genuinely destructive action on this surface, in the established
                    // destructive treatment rather than an errorContainer filled button.
                    DapDestructiveAction(
                        label = stringResource(R.string.edit_song_delete_cover_art),
                        onClick = onDelete,
                    )
                }
            }
        }

        Spacer(Modifier.height(DapSpacing.Md))
        DapNote(stringResource(R.string.edit_song_cover_art_hint))
        Spacer(Modifier.height(DapSpacing.Md))
    }
}

data class CoverArtCropResult(
    val preview: ImageBitmap,
    val update: CoverArtUpdate,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CoverArtCropperDialog(
    sourceUri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (CoverArtCropResult) -> Unit,
) {
    val context = LocalContext.current
    val dialogScope = rememberCoroutineScope()
    val captureController = rememberCaptureController()
    var loadedBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(0f) }

    LaunchedEffect(sourceUri) {
        isLoading = true
        loadError = null
        val bitmap = withContext(Dispatchers.IO) { decodeBitmapFromUri(context, sourceUri) }
        if (bitmap != null) {
            loadedBitmap = bitmap.asImageBitmap()
        } else {
            loadError = context.getString(R.string.edit_song_unable_to_load_image)
        }
        isLoading = false
        scale = 1f
        offset = Offset.Zero
    }

    LaunchedEffect(containerSize, scale, loadedBitmap) {
        loadedBitmap?.let { bitmap ->
            offset = clampOffset(offset, scale, containerSize, bitmap.width, bitmap.height)
        }
    }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 4f)
        scale = newScale
        loadedBitmap?.let { bitmap ->
            offset = clampOffset(offset + panChange, newScale, containerSize, bitmap.width, bitmap.height)
        }
    }

    val gridColor = Dap.primaryText.copy(alpha = 0.18f)

    // The DAP dialog surface: bg/1 at the sheet radius, over a scrim — the same shell
    // FetchLyricsDialog uses, rather than an elevated 32 dp Material card.
    Dialog(
        onDismissRequest = {
            if (!isSaving) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // This window is its own surface, so the activity's volume-key path does not
        // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
        ModalInputBridge()
        Surface(
            modifier = Modifier
                .padding(DapSpacing.SectionGap)
                .fillMaxWidth(),
            shape = RoundedCornerShape(DapRadius.SheetTop),
            color = Dap.surface
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .padding(DapSpacing.SectionGap),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DapSpacing.Lg)
            ) {
                Text(
                    text = stringResource(R.string.edit_song_crop_dialog_title),
                    style = DapType.HeadingSmall,
                    color = Dap.primaryText,
                    textAlign = TextAlign.Center
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    val cropSide = minOf(maxWidth, 320.dp)
                    val density = LocalDensity.current
                    val cropSidePx = remember(cropSide) { with(density) { cropSide.toPx() } }

                    LaunchedEffect(cropSidePx) {
                        containerSize = cropSidePx
                    }

                    Box(
                        modifier = Modifier
                            .size(cropSide)
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(DapRadius.SheetTop))
                            .background(Dap.surfaceRaised)
                            .padding(DapSpacing.Md)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(DapRadius.Surface))
                                .background(Dap.background)
                                .clipToBounds()
                        ) {
                            when {
                                isLoading -> {
                                    LoadingIndicator(modifier = Modifier.align(Alignment.Center))
                                }

                                loadError != null -> {
                                    Text(
                                        text = loadError!!,
                                        style = DapType.Body,
                                        color = Dap.secondaryText,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .padding(16.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }

                                 loadedBitmap != null -> {
                                    val bitmap = loadedBitmap!!
                                    val baseScale = maxOf(containerSize / bitmap.width, containerSize / bitmap.height)
                                    val displayWidth = with(LocalDensity.current) { (bitmap.width * baseScale).toDp() }
                                    val displayHeight = with(LocalDensity.current) { (bitmap.height * baseScale).toDp() }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(DapRadius.Surface))
                                            .background(Dap.background)
                                            .clipToBounds()
                                            .capturable(controller = captureController)
                                            .transformable(transformableState)
                                    ) {
                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .requiredSize(displayWidth, displayHeight)
                                                .graphicsLayer {
                                                    scaleX = scale
                                                    scaleY = scale
                                                    translationX = offset.x
                                                    translationY = offset.y
                                                }
                                        )
                                    }

                                    Canvas(modifier = Modifier.matchParentSize()) {
                                        val step = size.width / 3f
                                        for (index in 1 until 3) {
                                            val lineOffset = step * index
                                            drawLine(
                                                color = gridColor,
                                                start = Offset(lineOffset, 0f),
                                                end = Offset(lineOffset, size.height),
                                                strokeWidth = 2f
                                            )
                                            drawLine(
                                                color = gridColor,
                                                start = Offset(0f, lineOffset),
                                                end = Offset(size.width, lineOffset),
                                                strokeWidth = 2f
                                            )
                                        }
                                        drawRect(color = gridColor, style = Stroke(width = 3f))
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.edit_song_crop_dialog_hint),
                    style = DapType.Note,
                    color = Dap.tertiaryText,
                    textAlign = TextAlign.Center
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md)
                ) {
                    // Unequal weights on purpose: the confirm label is the longer of the two in
                    // several locales ("Aplicar portada" against "Cancelar"), and equal halves
                    // ellipsised it to "Aplicar por…". The confirm takes the larger share.
                    DapSecondaryButton(
                        label = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = !isSaving,
                    )

                    val canConfirm = !isLoading && loadError == null && loadedBitmap != null
                    DapPrimaryButton(
                        label = stringResource(R.string.edit_song_apply_cover_art),
                        modifier = Modifier.weight(1.4f),
                        enabled = canConfirm && !isSaving,
                        onClick = {
                            if (!canConfirm) return@DapPrimaryButton
                            dialogScope.launch {
                                isSaving = true
                                val captured = captureController.captureAsync().await()
                                val bytes = withContext(Dispatchers.IO) {
                                    imageBitmapToJpeg(captured)
                                }
                                if (bytes != null) {
                                    onConfirm(
                                        CoverArtCropResult(
                                            preview = captured,
                                            update = CoverArtUpdate(bytes, COVER_ART_MIME_TYPE)
                                        )
                                    )
                                } else {
                                    Timber.w("Failed to convert captured cover art to JPEG")
                                }
                                isSaving = false
                            }
                        }
                    )
                }
            }
        }
    }
}

private const val COVER_ART_MIME_TYPE = "image/jpeg"

private fun clampOffset(
    offset: Offset,
    scale: Float,
    containerSize: Float,
    bitmapWidth: Int,
    bitmapHeight: Int
): Offset {
    if (containerSize <= 0f || bitmapWidth <= 0 || bitmapHeight <= 0) return Offset.Zero

    val baseScale = maxOf(containerSize / bitmapWidth, containerSize / bitmapHeight)
    val totalScale = baseScale * scale

    val scaledWidth = bitmapWidth * totalScale
    val scaledHeight = bitmapHeight * totalScale

    val maxX = maxOf(0f, (scaledWidth - containerSize) / 2f)
    val maxY = maxOf(0f, (scaledHeight - containerSize) / 2f)

    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY)
    )
}

private fun imageBitmapToJpeg(imageBitmap: ImageBitmap): ByteArray? {
    return try {
        val bitmap = imageBitmap.asAndroidBitmap()
        ByteArrayOutputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            outputStream.toByteArray()
        }
    } catch (error: Exception) {
        Timber.e(error, "Unable to compress image bitmap to JPEG")
        null
    }
}

private fun decodeBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val maxDimension = 2048
            val width = info.size.width
            val height = info.size.height
            val largerSide = maxOf(width, height)
            if (largerSide > maxDimension) {
                val scale = largerSide.toFloat() / maxDimension
                decoder.setTargetSize(
                    (width / scale).roundToInt(),
                    (height / scale).roundToInt()
                )
            }
            decoder.isMutableRequired = false
        }
    } catch (error: Exception) {
        Timber.e(error, "Failed to decode bitmap from $uri")
        null
    }
}
