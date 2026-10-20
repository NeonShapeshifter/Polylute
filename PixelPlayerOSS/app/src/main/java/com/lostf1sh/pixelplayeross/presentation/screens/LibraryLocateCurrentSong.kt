package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * Whether the Library's "locate current song" button is wanted (B24-Q2).
 *
 * Wanted when there is a list and a current song and that song is not one of the rows on screen. [isCurrentSongVisible] is asked
 * only then, with the current song's id.
 *
 * B31 P2.1: **whether the library is still loading does not decide it.** The button was hidden while `loading` was true, and `loading`
 * is the library flow's own flag, which has nothing to do with the rows the list is showing: a list with rows on screen has a song to
 * locate in it whatever the flag says, and a flag that is slow to clear (or that a restart of the library flow sets again) hid the button
 * under exactly the conditions the owner saw (music playing, the song far above the rows on screen). An empty list has nothing to
 * locate, which [itemCount] already says. [loading] stays in the signature so a caller that wants it can still say it, and the log
 * line prints it.
 */
internal fun locateButtonWanted(
    itemCount: Int,
    @Suppress("UNUSED_PARAMETER") loading: Boolean,
    currentSongId: String?,
    isCurrentSongVisible: (String) -> Boolean,
): Boolean {
    if (itemCount == 0 || currentSongId == null) return false
    return !isCurrentSongVisible(currentSongId)
}

/**
 * Whether the row keyed [id] is one the listener can see: it lies at least partly inside the viewport **minus the list's own content
 * padding**. `LazyListLayoutInfo.visibleItemsInfo` also lists the rows under that padding (the Library's is the mini player, the nav bar and the
 * floating buttons: about 200 dp at the foot of the list), which are laid out and drawn behind the chrome: a song there counted as "on
 * screen" and the button stayed hidden for a song nobody could see.
 */
internal fun isRowOnScreen(layoutInfo: androidx.compose.foundation.lazy.LazyListLayoutInfo, id: String): Boolean {
    val start = layoutInfo.viewportStartOffset + layoutInfo.beforeContentPadding
    val end = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
    return layoutInfo.visibleItemsInfo.any { it.key == id && it.offset + it.size > start && it.offset < end }
}

/**
 * The id of the song that is playing, as a state to be read **inside** effects and `snapshotFlow`, not in a composable's body.
 * Read in a body it invalidates the whole composable on every song change, which is not what the reader needs.
 */
@Composable
internal fun rememberCurrentSongIdState(playerViewModel: PlayerViewModel): State<String?> {
    val ids = remember(playerViewModel) {
        playerViewModel.stablePlayerState
            .map { it.currentSong?.id }
            .distinctUntilChanged()
    }
    return ids.collectAsStateWithLifecycle(initialValue = null)
}

/**
 * The "locate current song" button of a song list: whether it is shown, and the action behind it (B24-Q2).
 *
 * Before, each list read the current song's id in its own body. Every song change therefore re-ran the body of the whole list,
 * scanned the list's loaded rows for the song's index, and — because the action was built `remember(currentSongId)` — handed the
 * Library screen a *new lambda* to store, which invalidated the entire Library screen (`songsLocateAction` is state it reads in its
 * body) on every media-next as well as every tap.
 *
 * Now the id is only read in [snapshotFlow], so a song change touches nothing but the button's own state. The action is the
 * same lambda for as long as there is a current song, and whether the song is on screen is answered from the keys of the
 * rows the list is showing (the rows are keyed by song id), not from a scan of the whole loaded list.
 */
@Composable
internal fun LocateCurrentSongEffects(
    listState: LazyListState,
    currentSongId: State<String?>,
    itemCount: () -> Int,
    isLoading: Boolean,
    onVisibilityChanged: (Boolean) -> Unit,
    onRegisterAction: ((() -> Unit)?) -> Unit,
    requestLocate: () -> Unit,
) {
    val visibilityCallback by rememberUpdatedState(onVisibilityChanged)
    val registerActionCallback by rememberUpdatedState(onRegisterAction)
    val loading = rememberUpdatedState(isLoading)
    val itemCountProvider = rememberUpdatedState(itemCount)
    val locate = rememberUpdatedState(requestLocate)

    // Only whether there is a current song matters here, and that rarely changes.
    val hasCurrentSong by remember(currentSongId) { derivedStateOf { currentSongId.value != null } }
    val action: (() -> Unit)? = remember(hasCurrentSong) {
        if (hasCurrentSong) ({ locate.value() }) else null
    }

    LaunchedEffect(action) {
        registerActionCallback(action)
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            locateButtonWanted(
                itemCount = itemCountProvider.value(),
                loading = loading.value,
                currentSongId = currentSongId.value,
                isCurrentSongVisible = { id -> isRowOnScreen(listState.layoutInfo, id) },
            )
        }
            .distinctUntilChanged()
            .collect { wanted ->
                // B31 P2.1: the state that decided it, on every change, so a phone that hides the button says why (debug builds).
                Timber.tag("LocateButton").d(
                    "wanted=%s items=%d loading=%s id=%s rowsOnScreen=%d",
                    wanted, itemCountProvider.value(), loading.value, currentSongId.value, listState.layoutInfo.visibleItemsInfo.size,
                )
                visibilityCallback(wanted)
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            visibilityCallback(false)
            registerActionCallback(null)
        }
    }
}
