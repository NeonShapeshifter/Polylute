package com.lostf1sh.pixelplayeross.presentation.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.media.CoverArtUpdate
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicbrainz.AlbumFillController
import com.lostf1sh.pixelplayeross.data.musicbrainz.AlbumFillState
import com.lostf1sh.pixelplayeross.data.musicbrainz.AlbumFormState
import com.lostf1sh.pixelplayeross.data.musicbrainz.AlbumFormTrack
import com.lostf1sh.pixelplayeross.data.musicbrainz.FieldProposal
import com.lostf1sh.pixelplayeross.presentation.components.scoped.OneShotAction
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AlbumTrackEdit
import com.lostf1sh.pixelplayeross.presentation.viewmodel.MetadataBatchProgress
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapProgressBar
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSectionHeader
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.dapDismissKeyboardActions
import com.lostf1sh.pixelplayeross.ui.ds.dapTextField
import kotlinx.collections.immutable.ImmutableList

/**
 * B57 1.3: edit a whole album at once, from the album page's menu.
 *
 * Album-level fields once (album, album artist, year / date, genre, cover), then a table of the three fields that differ per track (disc, number, title). One
 * Save writes every track through the same editor a single-song edit uses, with one progress bar and one result: "12 saved / 1 failed: why".
 * "Fill from MusicBrainz" compares the album with a release and offers a diff with a checkbox per field; checked fields go into this form and **nothing is
 * written until Save**.
 *
 * Built from the closest existing pattern: the multi-song editor's full-screen dialog and `OutlinedTextField` rows, with `ui/ds` buttons, progress and headers.
 */
@Composable
fun EditAlbumSheet(
    visible: Boolean,
    songs: ImmutableList<Song>,
    fill: AlbumFillController,
    progress: MetadataBatchProgress?,
    onDismiss: () -> Unit,
    onSave: (album: String, albumArtist: String, date: String?, genre: String, tracks: Map<String, AlbumTrackEdit>, cover: CoverArtUpdate?) -> Unit,
) {
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = visible
    if (!(transitionState.currentState || transitionState.targetState)) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ModalInputBridge()
        AnimatedVisibility(
            visibleState = transitionState,
            enter = slideInVertically(initialOffsetY = { it / 6 }) + fadeIn(animationSpec = tween(220)),
            exit = slideOutVertically(targetOffsetY = { it / 6 }) + fadeOut(animationSpec = tween(200)),
        ) {
            EditAlbumContent(songs, fill, progress, onDismiss, onSave)
        }
    }
}

/**
 * The album editor as the album page uses it: its own view model (the MusicBrainz fill), the save through [PlayerViewModel], and the close when the save ends. It stays
 * open while the save runs, because the progress bar is the sheet's own, and closes once a save that started has finished.
 */
@Composable
fun EditAlbumHost(songs: List<Song>, playerViewModel: com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel, onClose: () -> Unit) {
    val editViewModel: com.lostf1sh.pixelplayeross.presentation.viewmodel.AlbumEditViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val progress by playerViewModel.batchEditProgress.collectAsStateWithLifecycle()
    var sawProgress by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(progress) {
        if (progress != null) sawProgress = true else if (sawProgress) onClose()
    }
    EditAlbumSheet(
        visible = true,
        songs = kotlinx.collections.immutable.persistentListOf<Song>().addAll(songs),
        fill = editViewModel.fill,
        progress = progress,
        onDismiss = onClose,
        onSave = { album, albumArtist, date, genre, tracks, cover ->
            playerViewModel.saveAlbumMetadata(songs, album, albumArtist, date, genre, tracks, cover)
        },
    )
}

/** The form every track of [songs] starts from: the album's shared values and each track's own. */
internal fun initialAlbumFormForRender(songs: List<Song>): AlbumFormState = initialAlbumForm(songs)

internal fun initialAlbumForm(songs: List<Song>): AlbumFormState {
    fun common(values: List<String>) = values.distinct().singleOrNull().orEmpty()
    val year = songs.map { it.year }.filter { it > 0 }.distinct().singleOrNull()
    return AlbumFormState(
        album = common(songs.map { it.album }),
        albumArtist = common(songs.map { it.albumArtist.orEmpty() }),
        date = year?.toString().orEmpty(),
        genre = common(songs.map { it.genre.orEmpty() }),
        tracks = songs.sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber }, { it.title }))
            .map { AlbumFormTrack(it.id, it.title, it.trackNumber, it.discNumber) },
    )
}

@Composable
internal fun EditAlbumContent(
    songs: ImmutableList<Song>,
    fill: AlbumFillController,
    progress: MetadataBatchProgress?,
    onDismiss: () -> Unit,
    onSave: (String, String, String?, String, Map<String, AlbumTrackEdit>, CoverArtUpdate?) -> Unit,
) {
    val start = remember(songs) { initialAlbumForm(songs) }
    var form by remember(songs) { mutableStateOf(start) }
    var cover by remember(songs) { mutableStateOf<CoverArtUpdate?>(null) }
    var coverPicked by remember(songs) { mutableStateOf(false) }
    var pendingCoverUri by remember { mutableStateOf<Uri?>(null) }
    val fillState by fill.state.collectAsStateWithLifecycle()
    val saving = progress != null
    val saveOnce = remember(songs) { OneShotAction() }

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) pendingCoverUri = uri }
    pendingCoverUri?.let { uri ->
        CoverArtCropperDialog(
            sourceUri = uri,
            onDismiss = { pendingCoverUri = null },
            onConfirm = { result -> cover = result.update; coverPicked = true; pendingCoverUri = null },
        )
    }
    // The fill belongs to this editor: leaving it (or opening it for other songs) closes whatever MusicBrainz panel was open.
    androidx.compose.runtime.DisposableEffect(songs) { onDispose { fill.dismiss() } }

    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().background(Dap.background).statusBarsPadding()) {
        DapScreenTitle(text = stringResource(R.string.edit_album_title), modifier = Modifier.padding(horizontal = 16.dp))
        if (progress != null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = DapSpacing.Sm)) {
                Text(stringResource(R.string.edit_album_saving_progress, progress.done, progress.total), style = DapType.Caption, color = Dap.secondaryText)
                Spacer(Modifier.padding(top = DapSpacing.Xs))
                DapProgressBar(progress = if (progress.total == 0) 0f else progress.done.toFloat() / progress.total, modifier = Modifier.fillMaxWidth())
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                DapSecondaryButton(
                    label = stringResource(R.string.edit_album_fill),
                    onClick = {
                        fill.start(form, songs.map { it.musicBrainzIds.release }, artistForSearch = form.albumArtist.ifBlank { songs.firstOrNull()?.displayArtist.orEmpty() })
                    },
                    enabled = !saving && fillState !is AlbumFillState.Searching,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { FillPanel(fillState, fill, form, onApply = { next -> form = next }) }

            item { DapSectionHeader(stringResource(R.string.edit_album_section_album)) }
            item { AlbumField(stringResource(R.string.song_field_album), form.album, !saving) { form = form.copy(album = it) } }
            item { AlbumField(stringResource(R.string.song_field_album_artist), form.albumArtist, !saving) { form = form.copy(albumArtist = it) } }
            item { AlbumField(stringResource(R.string.song_field_date), form.date, !saving, keyboardType = KeyboardType.Number) { form = form.copy(date = it) } }
            item { AlbumField(stringResource(R.string.song_field_genre), form.genre, !saving) { form = form.copy(genre = it) } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.edit_album_cover), style = DapType.Caption, color = Dap.tertiaryText)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DapTextAction(label = stringResource(R.string.edit_album_cover_replace), onClick = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !saving)
                        DapTextAction(label = stringResource(R.string.edit_album_cover_remove), onClick = { cover = CoverArtUpdate(isDeletion = true); coverPicked = true }, enabled = !saving)
                        if (coverPicked) DapTextAction(label = stringResource(R.string.edit_album_cover_keep), onClick = { cover = null; coverPicked = false }, enabled = !saving)
                    }
                }
            }

            item { DapSectionHeader(stringResource(R.string.edit_album_section_tracks)) }
            items(form.tracks, key = { it.songId }) { track ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumField(stringResource(R.string.edit_album_disc_number_short), track.discNumber?.toString().orEmpty(), !saving, Modifier.width(64.dp), KeyboardType.Number) { text ->
                        form = form.copy(tracks = form.tracks.map { if (it.songId == track.songId) it.copy(discNumber = text.toIntOrNull()) else it })
                    }
                    AlbumField(stringResource(R.string.edit_album_track_number_short), track.trackNumber.takeIf { it > 0 }?.toString().orEmpty(), !saving, Modifier.width(64.dp), KeyboardType.Number) { text ->
                        form = form.copy(tracks = form.tracks.map { if (it.songId == track.songId) it.copy(trackNumber = text.toIntOrNull() ?: 0) else it })
                    }
                    AlbumField(stringResource(R.string.song_field_title), track.title, !saving, Modifier.weight(1f)) { text ->
                        form = form.copy(tracks = form.tracks.map { if (it.songId == track.songId) it.copy(title = text) else it })
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navBar + 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DapSecondaryButton(label = stringResource(R.string.cancel), onClick = onDismiss, enabled = !saving, modifier = Modifier.weight(1f))
            DapPrimaryButton(
                label = stringResource(R.string.edit_album_save),
                enabled = !saving && form.album.isNotBlank() && form.tracks.none { it.title.isBlank() },
                onClick = {
                    saveOnce.invoke {
                        val dateChanged = form.date.trim() != start.date.trim()
                        onSave(
                            form.album.trim(), form.albumArtist.trim(), form.date.trim().takeIf { dateChanged }, form.genre.trim(),
                            form.tracks.associate { it.songId to AlbumTrackEdit(it.title.trim(), it.trackNumber, it.discNumber) },
                            cover,
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AlbumField(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        keyboardActions = dapDismissKeyboardActions(),
        modifier = modifier.fillMaxWidth().dapTextField(),
    )
}

/** The MusicBrainz panel: searching, the release choice, the per-field diff, or the reason there is nothing to show. */
@Composable
private fun FillPanel(state: AlbumFillState, fill: AlbumFillController, form: AlbumFormState, onApply: (AlbumFormState) -> Unit) {
    when (state) {
        AlbumFillState.Idle -> Unit
        AlbumFillState.Searching -> Text(stringResource(R.string.edit_album_fill_searching), style = DapType.Caption, color = Dap.secondaryText)
        is AlbumFillState.PickRelease -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.edit_album_fill_pick), style = DapType.Caption, color = Dap.tertiaryText)
            state.candidates.forEach { candidate ->
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { fill.choose(candidate, form) }.padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(candidate.title, style = DapType.RowSmall, color = Dap.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.edit_album_fill_release_line, candidate.artist, candidate.date.orEmpty().ifBlank { "—" }, candidate.trackCount),
                        style = DapType.Caption, color = Dap.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DapTextAction(label = stringResource(R.string.edit_album_fill_close), onClick = fill::dismiss)
        }
        is AlbumFillState.Diff -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.edit_album_fill_diff_hint), style = DapType.Caption, color = Dap.tertiaryText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DapTextAction(label = stringResource(R.string.edit_album_fill_check_all), onClick = { fill.setAll(true) })
                DapTextAction(label = stringResource(R.string.edit_album_fill_check_none), onClick = { fill.setAll(false) })
            }
            state.proposals.forEach { proposal ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { fill.toggle(proposal.id) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = proposal.id in state.accepted, onCheckedChange = { fill.toggle(proposal.id) })
                    Column(Modifier.weight(1f)) {
                        Text(proposalLabel(proposal), style = DapType.Caption, color = Dap.tertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.edit_album_fill_change, proposal.current.ifBlank { "—" }, proposal.proposed),
                            style = DapType.RowSmall, color = Dap.primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DapPrimaryButton(
                    label = stringResource(R.string.edit_album_fill_apply),
                    enabled = state.accepted.isNotEmpty(),
                    onClick = { onApply(com.lostf1sh.pixelplayeross.data.musicbrainz.AlbumFill.apply(form, state.proposals, state.accepted)); fill.dismiss() },
                )
                DapTextAction(label = stringResource(R.string.edit_album_fill_close), onClick = fill::dismiss)
            }
        }
        AlbumFillState.NoMatch -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.edit_album_fill_no_match), style = DapType.Caption, color = Dap.secondaryText, modifier = Modifier.weight(1f))
            DapTextAction(label = stringResource(R.string.edit_album_fill_close), onClick = fill::dismiss)
        }
        is AlbumFillState.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.edit_album_fill_failed, state.message), style = DapType.Caption, color = Dap.secondaryText, modifier = Modifier.weight(1f))
            DapTextAction(label = stringResource(R.string.edit_album_fill_close), onClick = fill::dismiss)
        }
    }
}

@Composable
private fun proposalLabel(proposal: FieldProposal): String = when (proposal.kind) {
    FieldProposal.Kind.ALBUM -> stringResource(R.string.song_field_album)
    FieldProposal.Kind.ALBUM_ARTIST -> stringResource(R.string.song_field_album_artist)
    FieldProposal.Kind.DATE -> stringResource(R.string.song_field_date)
    FieldProposal.Kind.GENRE -> stringResource(R.string.song_field_genre)
    FieldProposal.Kind.TRACK_TITLE -> stringResource(R.string.song_field_title)
    FieldProposal.Kind.TRACK_NUMBER -> stringResource(R.string.song_field_track_number)
    FieldProposal.Kind.DISC_NUMBER -> stringResource(R.string.song_field_disc_number)
}
