package com.lostf1sh.pixelplayeross.data.stats

import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.presentation.model.RecentlyPlayedSongUiModel
import com.lostf1sh.pixelplayeross.utils.splitArtistsByDelimiters
import java.util.Locale

/** Recents is a browsing rule. Stats and mixes continue to use PlayRule's half-or-four-minutes rule. */
object RecentListenRule {
    const val MIN_LISTEN_MS = 30_000L
    fun counts(listenedMs: Long, lengthMs: Long): Boolean =
        listenedMs >= if(lengthMs > 0) minOf(MIN_LISTEN_MS,lengthMs) else MIN_LISTEN_MS
}

/** The whole ledger, current library identities, newest qualifying listen of each song. */
fun recentListening(ledger: ListeningLedger, nowMs: Long): List<RecentlyPlayedSongUiModel> {
    val seen=hashSetOf<String>()
    return ledger.occurrences.asReversed().mapNotNull { occurrence ->
        val item=occurrence.item ?: return@mapNotNull null
        if(!RecentListenRule.counts(occurrence.listenedMs,occurrence.lengthMs) || !seen.add(item.identity)) return@mapNotNull null
        // Keep the original timestamp in the ledger. A future event is presented as now after a clock rollback.
        RecentlyPlayedSongUiModel(item.song,occurrence.endMs.coerceIn(0,nowMs.coerceAtLeast(0)))
    }
}

fun recentArtistCredits(
    recent: List<RecentlyPlayedSongUiModel>,
    delimiters: List<String> = UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS,
): List<ArtistRef> {
    val seen=hashSetOf<String>()
    val result=ArrayList<ArtistRef>(10)
    for(entry in recent) {
        val song=entry.song
        val refs=song.artists.sortedByDescending { it.isPrimary }.ifEmpty {
            song.artist.splitArtistsByDelimiters(delimiters).mapIndexed { index,name -> ArtistRef(
                if(index == 0) song.artistId else -1,name,index == 0) }
        }
        for(ref in refs) if(ref.name.isNotBlank() && seen.add(ref.name.trim().lowercase(Locale.ROOT))) {
            result+=ref
            if(result.size == 10) return result
        }
    }
    return result
}
