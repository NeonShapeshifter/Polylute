package com.lostf1sh.pixelplayeross.presentation.screens.search.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Genre
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.songArtistLabel
import com.lostf1sh.pixelplayeross.presentation.components.songTitleLabel
import com.lostf1sh.pixelplayeross.presentation.stats.InitialsAvatar
import com.lostf1sh.pixelplayeross.presentation.stats.RowCover
import com.lostf1sh.pixelplayeross.presentation.utils.GenreSectionContent
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreAlbum
import com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreArtist
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapChevron
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapMediaRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSkeletonList
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTitleMeta
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.DapSectionHeader
import com.lostf1sh.pixelplayeross.utils.currentLocale
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SEARCH_DEBOUNCE_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The query the genre section reads: the typed one after it has rested for [delayMs], like the search itself (one debounce, [SEARCH_DEBOUNCE_MS]),
 * and at once empty when the field is cleared, so the section never outlives the query.
 */
@Composable
internal fun rememberSettledQuery(query: String, delayMs: Long = SEARCH_DEBOUNCE_MS): String {
    var settled by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(delayMs)
        settled = query
    }
    return if (query.isBlank()) "" else settled
}

/** What an unfolded genre section has: nothing asked yet, loading, the content, or a failure. */
internal sealed interface GenreSectionUi {
    data object Idle : GenreSectionUi
    data object Loading : GenreSectionUi
    data class Loaded(val content: GenreSectionContent) : GenreSectionUi
    data object Failed : GenreSectionUi
}

/**
 * The content of one genre section, loaded **once, when the listener first unfolds it** (B33-U5), off the main thread: [load] reads the genre's
 * songs from the library (a suspend call) and the groups are built on the default dispatcher. Folding and unfolding again reuses what was
 * loaded; a section that is never unfolded never calls [load].
 */
@Composable
internal fun rememberGenreSectionUi(
    genreName: String,
    expanded: Boolean,
    load: suspend (String) -> List<Song>,
): State<GenreSectionUi> {
    var requested by remember(genreName) { mutableStateOf(false) }
    if (expanded && !requested) requested = true
    return produceState<GenreSectionUi>(GenreSectionUi.Idle, genreName, requested) {
        if (!requested) return@produceState
        value = GenreSectionUi.Loading
        value = try {
            val songs = load(genreName)
            GenreSectionUi.Loaded(withContext(Dispatchers.Default) { GenreSectionContent.from(songs) })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            GenreSectionUi.Failed
        }
    }
}

/**
 * The folded "Genre · X" sections at the foot of Search's All tab (B33-U5): one per genre the query names, best first, each folded until the
 * listener opens it. Unfolded, a section shows that genre's artists, albums and songs in compact groups; "See all" opens the genre's page.
 */
@Composable
internal fun GenreSearchSections(
    genres: List<Genre>,
    load: suspend (String) -> List<Song>,
    onSeeAll: (Genre) -> Unit,
    onOpenAlbum: (GenreAlbum) -> Unit,
    onOpenArtist: (GenreArtist) -> Unit,
    onPlaySong: (Song, List<Song>, Genre) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (genres.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(top = DapSpacing.SectionGap)) {
        genres.forEach { genre ->
            GenreSearchSection(
                genre = genre,
                load = load,
                onSeeAll = { onSeeAll(genre) },
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                onPlaySong = { song, list -> onPlaySong(song, list, genre) },
            )
        }
    }
}

@Composable
internal fun GenreSearchSection(
    genre: Genre,
    load: suspend (String) -> List<Song>,
    onSeeAll: () -> Unit,
    onOpenAlbum: (GenreAlbum) -> Unit,
    onOpenArtist: (GenreArtist) -> Unit,
    onPlaySong: (Song, List<Song>) -> Unit,
) {
    var expanded by rememberSaveable(genre.id) { mutableStateOf(false) }
    val ui by rememberGenreSectionUi(genre.name, expanded, load)
    // The section is at the foot of a scrolling list, so what unfolds grows below the screen: bring it into view once its groups are there.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded, ui is GenreSectionUi.Loaded) {
        if (expanded && ui is GenreSectionUi.Loaded) bringIntoView.bringIntoView()
    }
    val title = stringResource(R.string.search_genre_section_title, genre.name)
    val stateLabel = stringResource(if (expanded) R.string.search_genre_section_unfolded else R.string.search_genre_section_folded)
    val actionLabel = stringResource(if (expanded) R.string.search_genre_section_fold else R.string.search_genre_section_unfold)

    Column(Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView)) {
        DapDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable { expanded = !expanded }
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = title
                    stateDescription = stateLabel
                    onClick(label = actionLabel) { expanded = !expanded; true }
                }
                .padding(horizontal = DapSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title.uppercase(currentLocale()),
                style = DapType.SectionHeader,
                color = Dap.tertiaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DapIcon(
                kind = if (expanded) DapIconKind.ChevronUp else DapIconKind.ChevronDown,
                tint = Dap.secondaryText,
                size = 16.dp,
            )
        }
        if (expanded) {
            when (val state = ui) {
                GenreSectionUi.Idle, GenreSectionUi.Loading -> Column(Modifier.padding(bottom = DapSpacing.Md)) { DapSkeletonList(count = 3) }
                GenreSectionUi.Failed -> Text(
                    text = stringResource(R.string.search_genre_load_failed),
                    style = DapType.Body,
                    color = Dap.secondaryText,
                    modifier = Modifier.padding(vertical = DapSpacing.Md, horizontal = DapSpacing.Xs),
                )
                is GenreSectionUi.Loaded -> GenreSectionGroups(
                    genre = genre,
                    content = state.content,
                    onSeeAll = onSeeAll,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    onPlaySong = { song -> onPlaySong(song, state.content.songs) },
                )
            }
        }
    }
}

@Composable
private fun GenreSectionGroups(
    genre: Genre,
    content: GenreSectionContent,
    onSeeAll: () -> Unit,
    onOpenAlbum: (GenreAlbum) -> Unit,
    onOpenArtist: (GenreArtist) -> Unit,
    onPlaySong: (Song) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs)) {
        if (content.artists.isNotEmpty()) {
            GroupHeader(stringResource(R.string.search_results_artists), content.artists.size)
            content.artistRows.forEach { artist ->
                val name = artist.name.ifBlank { stringResource(R.string.unknown_artist) }
                DapMediaRow(
                    title = name,
                    subtitle = pluralStringResource(R.plurals.genre_count_songs, artist.songCount, artist.songCount) +
                        " · " + pluralStringResource(R.plurals.genre_count_albums, artist.albumCount, artist.albumCount),
                    leading = { InitialsAvatar(name) },
                    onClick = { onOpenArtist(artist) },
                )
            }
        }
        if (content.albums.isNotEmpty()) {
            GroupHeader(stringResource(R.string.search_results_albums), content.albums.size)
            content.albumRows.forEach { album ->
                val albumTitle = album.title.ifBlank { stringResource(R.string.unknown_album) }
                DapMediaRow(
                    title = albumTitle,
                    subtitle = listOfNotNull(
                        album.artist.takeIf { it.isNotBlank() },
                        pluralStringResource(R.plurals.genre_count_songs, album.songCount, album.songCount),
                    ).joinToString(" · "),
                    leading = { RowCover(album.artUri, albumTitle) },
                    onClick = { onOpenAlbum(album) },
                )
            }
        }
        if (content.songs.isNotEmpty()) {
            GroupHeader(stringResource(R.string.search_results_songs), content.songs.size)
            content.songRows.forEach { song ->
                DapMediaRow(
                    title = songTitleLabel(song),
                    subtitle = songArtistLabel(song),
                    leading = { RowCover(song.albumArtUriString, song.title) },
                    onClick = { onPlaySong(song) },
                )
            }
            SeeAllGenreRow(
                text = stringResource(R.string.search_genre_see_all),
                description = stringResource(R.string.search_genre_see_all_cd, genre.name),
                onOpen = onSeeAll,
            )
        }
    }
}

@Composable
private fun GroupHeader(title: String, count: Int) {
    DapSectionHeader(
        text = title.uppercase(currentLocale()),
        modifier = Modifier.padding(start = DapSpacing.Xs, end = DapSpacing.Xs, top = DapSpacing.Md),
        trailing = { DapTitleMeta(count.toString()) },
    )
}

/** "See all": the tappable text row at the end of a list, drawn as the Stats lists draw theirs, with its own description. */
@Composable
private fun SeeAllGenreRow(text: String, description: String, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onOpen)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
                onClick { onOpen(); true }
            }
            .padding(horizontal = DapSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = DapType.Action, color = Dap.secondaryText, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        DapChevron()
    }
}
