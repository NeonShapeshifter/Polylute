package com.lostf1sh.pixelplayeross.presentation.components.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.LyricsSearchResult
import com.lostf1sh.pixelplayeross.presentation.viewmodel.AudioBookmarksViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.utils.ValidatedLyricsImport
import kotlinx.collections.immutable.ImmutableList

/**
 * What the sheet around the full player already does for it: transport, collapsing, the queue and details
 * entries, the queue drag. The sheet hands these to `FullPlayerContent`; they are gathered here so the actions
 * can reach the latest of them without the full player taking thirteen parameters.
 */
@Stable
internal class PlayerSheetCallbacks(
    val onPlayPause: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onCollapse: () -> Unit,
    val onShowDetails: () -> Unit,
    val onQueueDragStart: () -> Unit,
    val onQueueDrag: (Float) -> Unit,
    val onQueueRelease: (Float, Float) -> Unit,
    val onShuffleToggle: () -> Unit,
    val onRepeatToggle: () -> Unit,
    val onFavoriteToggle: () -> Unit,
)

/**
 * B21: every control of the full player meets what it calls here. One line each: the sheet's own callback, or the
 * PlayerViewModel / bookmarks method it has always called. No state and no decisions.
 *
 * [callbacks] is a [State] the host updates every composition, so a long-lived instance of this class always
 * calls the sheet's latest lambdas. The skip actions ([onSkipNext], [onSkipPrevious]) are the plain skips here;
 * the screen overrides them with the coordinator that keeps the artwork in step (see `FullPlayerScreen`).
 */
internal class PlayerViewModelFullPlayerActions(
    private val player: PlayerViewModel,
    private val bookmarks: AudioBookmarksViewModel,
    private val callbacks: State<PlayerSheetCallbacks>,
) : FullPlayerActions {
    override fun onPlayPause() = callbacks.value.onPlayPause()
    override fun onSeek(positionMs: Long) = callbacks.value.onSeek(positionMs)
    override fun onSkipNext() = callbacks.value.onNext()
    override fun onSkipPrevious() = callbacks.value.onPrevious()
    override fun onNext() = callbacks.value.onNext()
    override fun onPrevious() = callbacks.value.onPrevious()
    override fun onShuffleToggle() = callbacks.value.onShuffleToggle()
    override fun onRepeatToggle() = callbacks.value.onRepeatToggle()
    override fun onFavoriteToggle() = callbacks.value.onFavoriteToggle()

    override fun onCollapse() = callbacks.value.onCollapse()
    override fun onShowDetails() = callbacks.value.onShowDetails()
    override fun onQueueDragStart() = callbacks.value.onQueueDragStart()
    override fun onQueueDrag(dragAmount: Float) = callbacks.value.onQueueDrag(dragAmount)
    override fun onQueueRelease(totalDrag: Float, velocity: Float) = callbacks.value.onQueueRelease(totalDrag, velocity)

    override fun onPlayFromCarousel(song: Song, queue: ImmutableList<Song>, queueName: String, index: Int) =
        player.showAndPlaySong(
            song = song,
            contextSongs = queue,
            queueName = queueName,
            indexInQueue = index
        )
    override fun onOpenAlbum(albumId: Long) = player.triggerAlbumNavigationFromPlayer(albumId)
    override fun onOpenArtist(artistId: Long) = player.triggerArtistNavigationFromPlayer(artistId)
    override fun onAddBookmark(song: Song, title: String, positionMs: Long) = bookmarks.addBookmark(song, title, positionMs)

    override fun onEnsureLyricsResolved() = player.ensureLyricsResolvedForCurrentSong()
    override fun onFetchLyrics(forcePick: Boolean) = player.fetchLyricsForCurrentSong(forcePick)
    override fun onAcceptLyricsResult(result: LyricsSearchResult) = player.acceptLyricsSearchResultForCurrentSong(result)
    override fun onSearchLyricsManually(title: String, artist: String?) = player.searchLyricsManually(title, artist)
    override fun onResetLyricsSearch() = player.resetLyricsSearchState()
    override fun onResetLyrics() = player.resetLyricsForCurrentSong()
    override fun onLyricsSyncOffsetChange(songId: String, offsetMs: Int) = player.setLyricsSyncOffset(songId, offsetMs)
    override fun onLyricsAlignmentChange(alignment: String) = player.setLyricsAlignment(alignment)
    override fun onShowLyricsTranslationChange(show: Boolean) = player.setShowLyricsTranslation(show)
    override fun onShowLyricsRomanizationChange(show: Boolean) = player.setShowLyricsRomanization(show)
    override fun onImmersiveTemporarilyDisabledChange(disabled: Boolean) = player.setImmersiveTemporarilyDisabled(disabled)
    override fun onSaveLyricsToFile(song: Song, lyrics: Lyrics, preferSynced: Boolean) =
        player.saveLyricsToFile(song, lyrics, preferSynced)
    override fun onImportLyricsFromFile(songId: Long, validated: ValidatedLyricsImport) =
        player.importLyricsFromFile(songId, validated)
    override fun onToast(message: String) = player.sendToast(message)

    override fun previousDecisionPositionMs(): Long = player.previousDecisionPositionMs()
}
