package com.lostf1sh.pixelplayeross.presentation.components.player

import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.LyricsSearchResult
import com.lostf1sh.pixelplayeross.utils.ValidatedLyricsImport
import kotlinx.collections.immutable.ImmutableList

/** The player's actions, all doing nothing: what a render test wants when it only looks. */
internal class NoActions : FullPlayerActions {
    override fun onPlayPause() {}
    override fun onSeek(positionMs: Long) {}
    override fun onSkipNext() {}
    override fun onSkipPrevious() {}
    override fun onNext() {}
    override fun onPrevious() {}
    override fun onShuffleToggle() {}
    override fun onRepeatToggle() {}
    override fun onFavoriteToggle() {}
    override fun onCollapse() {}
    override fun onShowDetails() {}
    override fun onQueueDragStart() {}
    override fun onQueueDrag(dragAmount: Float) {}
    override fun onQueueRelease(totalDrag: Float, velocity: Float) {}
    override fun onPlayFromCarousel(song: Song, queue: ImmutableList<Song>, queueName: String, index: Int) {}
    override fun onOpenAlbum(albumId: Long) {}
    override fun onOpenArtist(artistId: Long) {}
    override fun onAddBookmark(song: Song, title: String, positionMs: Long) {}
    override fun onEnsureLyricsResolved() {}
    override fun onFetchLyrics(forcePick: Boolean) {}
    override fun onAcceptLyricsResult(result: LyricsSearchResult) {}
    override fun onSearchLyricsManually(title: String, artist: String?) {}
    override fun onResetLyricsSearch() {}
    override fun onResetLyrics() {}
    override fun onLyricsSyncOffsetChange(songId: String, offsetMs: Int) {}
    override fun onLyricsAlignmentChange(alignment: String) {}
    override fun onShowLyricsTranslationChange(show: Boolean) {}
    override fun onShowLyricsRomanizationChange(show: Boolean) {}
    override fun onImmersiveTemporarilyDisabledChange(disabled: Boolean) {}
    override fun onSaveLyricsToFile(song: Song, lyrics: Lyrics, preferSynced: Boolean) {}
    override fun onImportLyricsFromFile(songId: Long, validated: ValidatedLyricsImport) {}
    override fun onToast(message: String) {}
    override fun previousDecisionPositionMs(): Long = 10_000L
}
