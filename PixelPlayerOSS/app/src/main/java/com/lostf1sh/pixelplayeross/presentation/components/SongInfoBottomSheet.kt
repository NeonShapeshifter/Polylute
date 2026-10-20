package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.BuildConfig
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.diagnostics.DebugHooks
import com.lostf1sh.pixelplayeross.data.media.CoverArtUpdate
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.player.formatHz
import com.lostf1sh.pixelplayeross.presentation.components.player.formatKbps
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SongInfoBottomSheetViewModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapDestructiveRow
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapNavRow
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.DapUnavailableRow
import com.lostf1sh.pixelplayeross.ui.ds.DapValue
import com.lostf1sh.pixelplayeross.ui.ds.DapValueRow
import com.lostf1sh.pixelplayeross.utils.AudioMetaUtils
import java.util.Locale
import com.lostf1sh.pixelplayeross.presentation.components.songArtistLabel

/**
 * The song action surface: everything you can *do* to a track, and nothing about what it is.
 *
 * Two things this deliberately is not:
 *
 * - It is **not a second details page.** It used to be a two-page pager whose second page repeated
 *   the tags, the format and the path that [com.lostf1sh.pixelplayeross.presentation.components.player.TrackDetailsSheet]
 *   already answers from live runtime state. The two could disagree — this one printed
 *   "48.0 kHz" beside the other's "48 kHz" — and the duplicated surface was the one a user reached
 *   by accident, by backing out of the real one. "Track details" below is a route to that page.
 * - It is **not a dashboard of circles.** One primary action (Play), two text actions, then
 *   hairline-separated groups. A row you tap is a row with a label, not a 66 dp filled pill with an
 *   icon in it.
 *
 * The only technical reading on the surface is the format line under the title, and it goes through
 * the same formatter the details page uses so the two cannot drift apart again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongInfoBottomSheet(
    song: Song,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    onPlaySong: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddNextToQueue: () -> Unit,
    onAddToPlayList: () -> Unit,
    onDeleteFromDevice: (activity: Activity, song: Song, onResult: (Boolean) -> Unit) -> Unit,
    onNavigateToAlbum: () -> Unit,
    onNavigateToArtist: () -> Unit,
    onNavigateToArtistById: (Long) -> Unit = { onNavigateToArtist() },
    onNavigateToGenre: () -> Unit,
    /**
     * Opens the Track Details sibling view. Null on hosts that are list context menus rather than
     * the Now Playing More menu, in which case the row is not offered at all.
     */
    onViewTrackDetails: (() -> Unit)? = null,
    onViewCredits: (() -> Unit)? = null,
    onEditSong: (
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
    removeFromListTrigger: () -> Unit,
    songInfoViewModel: SongInfoBottomSheetViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var showEditSheet by remember { mutableStateOf(false) }
    var showArtistPicker by remember { mutableStateOf(false) }
    val audioMeta by songInfoViewModel.audioMeta.collectAsStateWithLifecycle()
    val resolvedArtists by songInfoViewModel.resolvedArtists.collectAsStateWithLifecycle()
    val musicBrainzState by songInfoViewModel.musicBrainzState.collectAsStateWithLifecycle()

    val sheetState = rememberModalSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { true }
    )

    // The one reading this surface reports, formatted by the shared helper so it cannot disagree
    // with the same number on the details page.
    val audioMetaLabel = remember(audioMeta) {
        val meta = audioMeta ?: return@remember null
        val formatLabel = AudioMetaUtils.mimeTypeToFormat(meta.mimeType)
            .takeIf { it != "-" }
            ?.uppercase(Locale.getDefault())
        val parts = buildList {
            meta.sampleRate?.takeIf { it > 0 }?.let { add(formatHz(it)) }
            meta.bitrate?.takeIf { it > 0 }?.let { add(formatKbps(it)) }
            formatLabel?.let { add(it) }
        }
        parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
    val songLocationInfo = remember(song.path, song.contentUriString) {
        songInfoViewModel.getSongLocationInfo(song)
    }
    val isEditable = remember(song) { songInfoViewModel.isSongEditable(song) }

    LaunchedEffect(song.id) {
        songInfoViewModel.bindSong(song)
        songInfoViewModel.loadAudioMeta(song)
        songInfoViewModel.loadArtistsForSong(song)
    }

    // The toast states what was actually persisted, which is not the same claim as "the lookup
    // succeeded": a match that agrees with the stored tags writes nothing a user would notice.
    val appliedFieldNames = (musicBrainzState as? SongInfoBottomSheetViewModel.MusicBrainzUiState.Applied)
        ?.changedFields
        .orEmpty()
        .map { stringResource(it.labelRes) }

    LaunchedEffect(musicBrainzState) {
        val applied = musicBrainzState as? SongInfoBottomSheetViewModel.MusicBrainzUiState.Applied
            ?: return@LaunchedEffect
        val message = when {
            applied.changedFields.isNotEmpty() ->
                context.getString(R.string.musicbrainz_updated, appliedFieldNames.joinToString(", "))
            applied.identifiersChanged -> context.getString(R.string.musicbrainz_updated_ids)
            else -> context.getString(R.string.musicbrainz_no_changes)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        songInfoViewModel.dismissMusicBrainz()
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!showEditSheet) {
                onDismiss()
            }
        },
        sheetState = sheetState,
        containerColor = Dap.surface,
        contentColor = Dap.primaryText,
        shape = RoundedCornerShape(topStart = DapRadius.SheetTop, topEnd = DapRadius.SheetTop),
        contentWindowInsets = { WindowInsets(top = 0, bottom = 0) },
    ) {
        // This window is its own surface, so the activity's volume-key path does not
        // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
        ModalInputBridge()
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DapSpacing.Gutter)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = DapSpacing.SectionGap)
            ) {
                SongInfoHeader(song = song, metaLine = audioMetaLabel)

                Spacer(Modifier.height(DapSpacing.SectionGap))

                // One primary action. Play is what this surface is for; everything else is a row.
                DapPrimaryButton(
                    label = stringResource(R.string.play_playback),
                    onClick = onPlaySong,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(DapSpacing.Sm))
                Row(horizontalArrangement = Arrangement.spacedBy(DapSpacing.Lg)) {
                    DapTextAction(
                        label = stringResource(
                            if (isFavorite) R.string.song_info_favorite_remove
                            else R.string.song_info_favorite_add
                        ),
                        onClick = onToggleFavorite,
                        color = if (isFavorite) LocalDapAccent.current else Dap.secondaryText,
                    )
                    DapTextAction(
                        label = stringResource(R.string.action_share),
                        onClick = {
                            try {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/*"
                                    putExtra(Intent.EXTRA_STREAM, song.contentUriString.toUri())
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(
                                        shareIntent,
                                        context.getString(R.string.song_info_share_chooser_title)
                                    )
                                )
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.error_share_song_format, e.localizedMessage ?: ""),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        color = Dap.secondaryText,
                    )
                }

                Spacer(Modifier.height(DapSpacing.SectionGap))

                DapGroup(title = stringResource(R.string.more_group_queue)) {
                    DapNavRow(
                        label = stringResource(R.string.action_add_to_queue),
                        onClick = onAddToQueue,
                    )
                    DapNavRow(
                        label = stringResource(R.string.action_queue_next),
                        onClick = onAddNextToQueue,
                    )
                }

                Spacer(Modifier.height(DapSpacing.SectionGap))

                DapGroup(title = stringResource(R.string.more_group_playlist_title)) {
                    DapNavRow(
                        label = stringResource(R.string.more_group_playlist),
                        onClick = onAddToPlayList,
                    )
                }

                // Local library navigation. Every destination resolves against the on-device
                // library, so none of these needs a network connection.
                Spacer(Modifier.height(DapSpacing.SectionGap))

                DapGroup(title = stringResource(R.string.more_group_navigation)) {
                    DapNavRow(
                        label = stringResource(R.string.more_go_to_album),
                        description = song.album.takeIf { it.isNotBlank() },
                        onClick = onNavigateToAlbum,
                    )
                    DapNavRow(
                        label = stringResource(R.string.more_go_to_artist),
                        // Placeholder-aware, not blank-aware: the value a device actually produces
                        // for an untagged track is `<unknown>`, which is not blank and so passed
                        // the old test straight through onto the row.
                        description = songArtistLabel(song),
                        onClick = {
                            if (song.artists.size > 1) {
                                showArtistPicker = true
                            } else {
                                onNavigateToArtist()
                            }
                        },
                    )
                    if (!song.genre.isNullOrBlank()) {
                        DapNavRow(
                            label = stringResource(R.string.more_go_to_genre),
                            description = song.genre,
                            onClick = onNavigateToGenre,
                        )
                    }
                }

                if (onViewTrackDetails != null || onViewCredits != null) {
                    Spacer(Modifier.height(DapSpacing.SectionGap))

                    DapGroup(title = stringResource(R.string.more_group_information)) {
                        if (onViewTrackDetails != null) {
                            DapNavRow(
                                label = stringResource(R.string.more_track_details),
                                description = stringResource(R.string.more_track_details_supporting),
                                onClick = onViewTrackDetails,
                            )
                        }
                        if (onViewCredits != null) {
                            DapNavRow(
                                label = stringResource(R.string.more_view_credits),
                                description = stringResource(R.string.more_view_credits_supporting),
                                onClick = onViewCredits,
                            )
                        }
                        // Album and artist blurbs have no local store and no enrichment path in
                        // this codebase, so rather than offering a tap that can only fail, the rows
                        // stay visible and inert. They light up if such data is ever cached.
                        DapUnavailableRow(
                            label = stringResource(R.string.more_album_information),
                            description = stringResource(R.string.more_information_unavailable),
                        )
                        DapUnavailableRow(
                            label = stringResource(R.string.more_artist_information),
                            description = stringResource(R.string.more_information_unavailable),
                            divider = false,
                        )
                    }
                }

                Spacer(Modifier.height(DapSpacing.SectionGap))

                DapGroup(title = stringResource(R.string.more_group_enrichment)) {
                    DapNavRow(
                        label = stringResource(R.string.musicbrainz_lookup),
                        onClick = { songInfoViewModel.searchMusicBrainz(song) },
                        divider = false,
                    )
                }

                Spacer(Modifier.height(DapSpacing.SectionGap))

                DapGroup(title = stringResource(R.string.more_group_file)) {
                    DapValueRow(label = songLocationInfo.label) {
                        DapValue(songLocationInfo.value, color = Dap.tertiaryText)
                    }
                    if (isEditable) {
                        DapNavRow(
                            label = stringResource(R.string.edit_song_toolbar_title),
                            onClick = { showEditSheet = true },
                        )
                    }
                    // B32-Q5: one row, one word. It was a row called "Delete" with a "Delete" button inside it; the confirmation
                    // (the dialog `onDeleteFromDevice` raises, which names the song and says it cannot be undone) is unchanged.
                    DapDestructiveRow(
                        label = stringResource(R.string.more_delete_from_device),
                        description = stringResource(R.string.more_delete_supporting),
                        divider = false,
                        onClick = {
                            (context as? Activity)?.let { activity ->
                                onDeleteFromDevice(activity, song) { result ->
                                    if (result) {
                                        removeFromListTrigger()
                                        onDismiss()
                                    }
                                }
                            }
                        },
                    )
                }

                // B27-A5 (N-07): debug builds only, and only in the Now Playing menu. "I heard noise just now": writes the last
                // seconds of the USB output's summaries and the stream's state to a file (see NoiseMark in the debug source set).
                val markNoise = DebugHooks.markNoise
                if (BuildConfig.DEBUG && onViewTrackDetails != null && markNoise != null) {
                    Spacer(Modifier.height(DapSpacing.SectionGap))

                    val markedText = stringResource(R.string.debug_mark_noise_done)
                    val markFailedText = stringResource(R.string.debug_mark_noise_failed)
                    DapGroup(title = stringResource(R.string.more_group_debug)) {
                        DapNavRow(
                            label = stringResource(R.string.debug_mark_noise),
                            description = stringResource(R.string.debug_mark_noise_supporting),
                            onClick = {
                                val name = markNoise(context)
                                Toast.makeText(context, if (name != null) "$markedText $name" else markFailedText, Toast.LENGTH_LONG).show()
                            },
                            divider = false,
                        )
                    }
                }
            }
        }
    }

    EditSongSheet(
        visible = showEditSheet,
        song = song,
        onDismiss = { showEditSheet = false },
        onSave = { title, artist, album, albumArtist, composer, genre, lyrics, trackNumber, discNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArt ->
            onEditSong(
                title,
                artist,
                album,
                albumArtist,
                composer,
                genre,
                lyrics,
                trackNumber,
                discNumber,
                replayGainTrackGainDb,
                replayGainAlbumGainDb,
                coverArt
            )
            showEditSheet = false
        },
    )

    when (val state = musicBrainzState) {
        SongInfoBottomSheetViewModel.MusicBrainzUiState.Idle,
        is SongInfoBottomSheetViewModel.MusicBrainzUiState.Applied -> Unit

        SongInfoBottomSheetViewModel.MusicBrainzUiState.Loading -> {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text(stringResource(R.string.musicbrainz_lookup)) },
                text = {
                    // An alert is a window of its own, so the activity's volume-key path does not reach
                    // it. The bridge routes Volume Up/Down to the same authority the activity uses.
                    ModalInputBridge()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Text(stringResource(R.string.musicbrainz_searching))
                    }
                }
            )
        }

        is SongInfoBottomSheetViewModel.MusicBrainzUiState.Error -> {
            AlertDialog(
                onDismissRequest = songInfoViewModel::dismissMusicBrainz,
                confirmButton = {
                    TextButton(onClick = { songInfoViewModel.searchMusicBrainz(song) }) {
                        Text(stringResource(R.string.musicbrainz_retry))
                    }
                },
                dismissButton = {
                    TextButton(onClick = songInfoViewModel::dismissMusicBrainz) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                title = {
                    // An alert is a window of its own, so the activity's volume-key path does not reach
                    // it. The bridge routes Volume Up/Down to the same authority the activity uses.
                    ModalInputBridge()
                    Text(stringResource(R.string.musicbrainz_lookup_failed))
                },
                text = { Text(state.message) }
            )
        }

        is SongInfoBottomSheetViewModel.MusicBrainzUiState.Results -> {
            AlertDialog(
                onDismissRequest = songInfoViewModel::dismissMusicBrainz,
                confirmButton = {
                    TextButton(onClick = songInfoViewModel::dismissMusicBrainz) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                title = { Text(stringResource(R.string.musicbrainz_choose_match)) },
                text = {
                    // An alert is a window of its own, so the activity's volume-key path does not reach
                    // it. The bridge routes Volume Up/Down to the same authority the activity uses.
                    ModalInputBridge()
                    if (state.matches.isEmpty()) {
                        Text(stringResource(R.string.musicbrainz_no_results))
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.matches, key = { it.recordingId + (it.releaseId ?: "") }) { match ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            songInfoViewModel.applyMusicBrainzMatch(song, match)
                                        },
                                    color = Dap.surfaceRaised,
                                    shape = RoundedCornerShape(DapRadius.Surface)
                                ) {
                                    Column(Modifier.padding(DapSpacing.Md)) {
                                        Text(match.title, fontWeight = FontWeight.Bold)
                                        Text(
                                            listOfNotNull(
                                                match.artist.takeIf { it.isNotBlank() },
                                                match.album.takeIf { it.isNotBlank() },
                                                match.year.takeIf { it > 0 }?.toString()
                                            ).joinToString(" · "),
                                            style = DapType.Caption,
                                            color = Dap.secondaryText
                                        )
                                        Text(
                                            stringResource(R.string.musicbrainz_match_score, match.score),
                                            style = DapType.Caption,
                                            color = LocalDapAccent.current
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    val artistPickerSheetState = rememberModalSheetState(skipPartiallyExpanded = true)
    if (showArtistPicker && resolvedArtists.isNotEmpty()) {
        com.lostf1sh.pixelplayeross.presentation.components.player.PlayerArtistPickerBottomSheet(
            song = song,
            artists = resolvedArtists,
            sheetState = artistPickerSheetState,
            onDismiss = { showArtistPicker = false },
            onArtistClick = { artist ->
                showArtistPicker = false
                onNavigateToArtistById(artist.id)
            }
        )
    }
}

/**
 * Artwork, title, artist, and the one technical reading this surface reports.
 *
 * The format line is mono because it is a measurement the system observed, not prose.
 */
@Composable
private fun SongInfoHeader(
    song: Song,
    metaLine: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DapSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmartImage(
            model = song.albumArtUriString,
            contentDescription = stringResource(R.string.widget_album_art),
            shape = RoundedCornerShape(DapRadius.Surface),
            modifier = Modifier.size(64.dp),
            contentScale = ContentScale.Crop,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = DapType.HeadingSmall,
                color = Dap.primaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            // Drawn only when the track names an artist — no `<unknown>`, and no empty line where
            // one would have been.
            songArtistLabel(song)?.let { artistLabel ->
                Text(
                    text = artistLabel,
                    style = DapType.Caption,
                    color = Dap.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (metaLine != null) {
                Spacer(Modifier.height(DapSpacing.Xs))
                Text(
                    text = metaLine,
                    style = DapType.Value,
                    color = Dap.tertiaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
