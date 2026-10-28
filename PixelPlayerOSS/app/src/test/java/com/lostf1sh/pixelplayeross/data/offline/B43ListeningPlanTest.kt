package com.lostf1sh.pixelplayeross.data.offline

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import org.junit.Test

class B43ListeningPlanTest {
    @Test fun `most played favorites and recent songs precede unplayed songs in the plan`() {
        val songs = (1..4).map { Song.emptySong().copy(id = "$it", artist = "Artist $it", album = "Album $it") }
        val ranks = mapOf("4" to OfflineListeningRank(plays = 10), "3" to OfflineListeningRank(favorite = true), "2" to OfflineListeningRank(lastHeardMs = 100))
        val plan = OfflinePlan.build(songs, setOf(OfflineKind.ARTIST_PHOTOS), ranks)
        assertThat(plan.map { it.title }).containsExactly("Artist 4", "Artist 3", "Artist 2", "Artist 1").inOrder()
    }
    @Test fun `the estimate uses completed work divided by measured elapsed pace`() {
        val batch = OfflineBatch("job", emptyList(), setOf(OfflineKind.LYRICS), next = 2,
            targets = (1..5).map { OfflineTarget(OfflineKind.LYRICS, "$it", "$it") }, measuredMs = 6000, measuredCount = 2)
        assertThat(batch.estimatedRemainingMs).isEqualTo(9000L)
        assertThat(batch.copy(next = 5).estimatedRemainingMs).isEqualTo(0L)
        assertThat(batch.copy(measuredCount = 0).estimatedRemainingMs).isNull()
    }
}
