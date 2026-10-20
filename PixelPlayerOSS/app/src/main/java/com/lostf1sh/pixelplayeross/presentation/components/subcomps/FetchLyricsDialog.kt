package com.lostf1sh.pixelplayeross.presentation.components.subcomps

import com.lostf1sh.pixelplayeross.utils.currentLocale
import com.lostf1sh.pixelplayeross.ui.ds.dapDismissKeyboardActions
import com.lostf1sh.pixelplayeross.ui.ds.dapTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lostf1sh.pixelplayeross.presentation.components.ModalInputBridge
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.LyricsSearchResult
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.utils.ProviderText
import kotlinx.collections.immutable.ImmutableList
import java.util.Locale
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapPrimaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapSwitch
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.DapIndeterminateBar
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent

@Composable
fun FetchLyricsDialog(
    uiState: LyricsSearchUiState,
    currentSong: Song?,
    onConfirm: (Boolean) -> Unit,
    onPickResult: (LyricsSearchResult) -> Unit,
    onManualSearch: (String, String?) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit
) {
    if (uiState is LyricsSearchUiState.Success) return

    var forcePickResults by rememberSaveable { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // This window is its own surface, so the activity's volume-key path does not
        // reach it. The bridge routes Volume Up/Down to the same authority the activity uses.
        ModalInputBridge()
        Surface(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(DapRadius.SheetTop),
            color = Dap.surface,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (uiState) {
                    LyricsSearchUiState.Idle -> {
                        IdleContent(
                            currentSong = currentSong,
                            forcePickResults = forcePickResults,
                            onToggleForcePickResults = { forcePickResults = it },
                            onSearch = { onConfirm(forcePickResults) },
                            onImport = onImport,
                            onCancel = onDismiss
                        )
                    }
                    LyricsSearchUiState.Loading -> {
                        LoadingContent()
                    }
                    is LyricsSearchUiState.PickResult -> {
                        PickResultContent(
                            results = uiState.results,
                            onPickResult = onPickResult,
                            onCancel = onDismiss
                        )
                    }
                    is LyricsSearchUiState.NotFound -> {
                        NotFoundContent(
                            message = uiState.message,
                            initialTitle = currentSong?.title.orEmpty(),
                            initialArtist = currentSong?.displayArtist,
                            onManualSearch = { title, artist ->
                                onManualSearch(title, artist)
                            },
                            onCancel = onDismiss
                        )
                    }
                    is LyricsSearchUiState.Error -> {
                        ErrorContent(
                            message = uiState.message,
                            onDismiss = onDismiss
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IdleContent(
    currentSong: Song?,
    forcePickResults: Boolean,
    onToggleForcePickResults: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit
) {
    // Inventory of the surface this replaces: a star-shaped badge, the track title and artist,
    // a prompt, a labelled switch bound to `forcePickResults`, and three actions — Search
    // (`onSearch`), Import (`onImport`) and Cancel (`onCancel`). Every callback is re-hosted.
    // What goes is the star geometry, the giant Material headline, the elevated lavender card
    // and the pill/filled buttons.
    Text(
        text = stringResource(R.string.dap_lyrics_empty_title).uppercase(currentLocale()),
        style = DapType.SectionHeader,
        color = Dap.tertiaryText,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(DapSpacing.Md))
    Text(
        text = currentSong?.title ?: stringResource(R.string.lyrics_not_found),
        style = DapType.Heading,
        color = Dap.primaryText,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
    if (currentSong != null) {
        Text(
            text = currentSong.displayArtist,
            style = DapType.Body,
            color = Dap.tertiaryText,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(DapSpacing.Md))
    Text(
        text = stringResource(R.string.search_lyrics_online_prompt),
        style = DapType.Body,
        color = Dap.secondaryText,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(DapSpacing.SectionGap))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.fetch_lyrics_show_options_title),
                style = DapType.SettingLabel,
                color = Dap.primaryText,
            )
            Text(
                text = stringResource(R.string.fetch_lyrics_show_options_subtitle),
                style = DapType.Caption,
                color = Dap.tertiaryText,
            )
        }
        Spacer(Modifier.width(DapSpacing.Md))
        DapSwitch(
            checked = forcePickResults,
            onCheckedChange = onToggleForcePickResults,
        )
    }

    Spacer(Modifier.height(DapSpacing.SectionGap))

    DapPrimaryButton(
        label = stringResource(R.string.search),
        onClick = onSearch,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(DapSpacing.Md))
    DapSecondaryButton(
        label = stringResource(R.string.import_file),
        onClick = onImport,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(DapSpacing.Sm))
    DapTextAction(
        label = stringResource(R.string.cancel),
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier.padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The design system's own "busy" mark, not Material's wavy circular indicator.
        DapIndeterminateBar(modifier = Modifier.width(120.dp))
        Spacer(modifier = Modifier.height(DapSpacing.Lg))
        Text(
            text = stringResource(R.string.searching_lyrics),
            style = DapType.Row,
            color = Dap.secondaryText
        )
    }
}

@Composable
private fun PickResultContent(
    results: ImmutableList<LyricsSearchResult>,
    onPickResult: (LyricsSearchResult) -> Unit,
    onCancel: () -> Unit
) {
    Text(
        text = stringResource(R.string.found_n_matches_format).format(results.size)
            .uppercase(currentLocale()),
        style = DapType.SectionHeader,
        color = Dap.tertiaryText,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(DapSpacing.Lg))

    LazyColumn(
        modifier = Modifier.heightIn(max = 350.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 8.dp)
    ) {
        items(results, key = { it.record.id }) { result ->
            ResultItemCard(result = result, onClick = { onPickResult(result) })
        }

        item {
            ProviderText(
                providerText = stringResource(R.string.lyrics_provided_by),
                uri = stringResource(R.string.lrclib_uri),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    DapTextAction(
        label = stringResource(R.string.cancel),
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ResultItemCard(
    result: LyricsSearchResult,
    onClick: () -> Unit
) {
    val hasSyncedLyrics = !result.record.syncedLyrics.isNullOrEmpty()
    
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(DapRadius.Surface),
        color = Dap.surfaceRaised,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (hasSyncedLyrics) Dap.surface
                        else Dap.surfaceRaised
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (hasSyncedLyrics) LocalDapAccent.current
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = result.record.name,
                        style = DapType.MediaRow,
                        fontWeight = FontWeight.SemiBold,
                        color = Dap.primaryText,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (hasSyncedLyrics) {
                        Surface(
                            shape = RoundedCornerShape(DapRadius.Surface),
                            color = Dap.surface,
                            contentColor = Dap.secondaryText
                        ) {
                            Text(
                                text = stringResource(R.string.lyrics_synced_badge),
                                style = DapType.StateWord,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(
                        R.string.presentation_batch_g_list_song_artist_album,
                        result.record.artistName,
                        result.record.albumName
                    ),
                    style = DapType.Caption,
                    color = Dap.tertiaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun NotFoundContent(
    initialTitle: String,
    initialArtist: String?,
    message: String,
    onManualSearch: (String, String?) -> Unit,
    onCancel: () -> Unit
) {
    val normalizedArtist = rememberSaveable(initialArtist) {
        initialArtist
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { it.equals("<unknown>", ignoreCase = true) }
            .orEmpty()
    }

    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var artist by rememberSaveable { mutableStateOf(normalizedArtist) }

    // The badge tile goes; the state is stated in words.
    Text(
        text = stringResource(R.string.lyrics_not_found).uppercase(currentLocale()),
        style = DapType.SectionHeader,
        color = Dap.tertiaryText,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(DapSpacing.Md))

    Text(
        text = stringResource(R.string.lyrics_custom_search_hint),
        style = DapType.Body,
        textAlign = TextAlign.Center,
        color = Dap.secondaryText,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(DapSpacing.Lg))

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.song_field_title),
            color = Dap.tertiaryText,
            style = DapType.SectionHeader
        )
        OutlinedTextField(
            value = title,
            keyboardActions = dapDismissKeyboardActions(),
            onValueChange = { title = it },
            placeholder = { Text(stringResource(R.string.song_field_title)) },
            modifier = Modifier.fillMaxWidth().dapTextField(),
            singleLine = true
        )
    }

    Spacer(Modifier.height(8.dp))

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.song_field_artist_optional),
            color = Dap.tertiaryText,
            style = DapType.SectionHeader
        )
        OutlinedTextField(
            value = artist,
            keyboardActions = dapDismissKeyboardActions(),
            onValueChange = { artist = it },
            placeholder = { Text(stringResource(R.string.song_field_artist_optional)) },
            modifier = Modifier.fillMaxWidth().dapTextField(),
            singleLine = true
        )
    }

    Spacer(Modifier.height(24.dp))

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        DapPrimaryButton(
            label = stringResource(R.string.search),
            onClick = {
                onManualSearch(
                    title,
                    artist.takeIf { it.isNotBlank() }
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        DapTextAction(
            label = stringResource(R.string.cancel),
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onDismiss: () -> Unit
) {
    // No badge tile and no error-coloured container: failure is stated in words, with the one
    // action that applies to it.
    Text(
        text = stringResource(R.string.error).uppercase(currentLocale()),
        style = DapType.SectionHeader,
        color = Dap.warning,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(DapSpacing.Md))

    Text(
        text = message,
        style = DapType.Body,
        textAlign = TextAlign.Center,
        color = Dap.secondaryText,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(DapSpacing.SectionGap))

    DapTextAction(
        label = stringResource(R.string.ok),
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    )
}

