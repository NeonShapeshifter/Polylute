package com.lostf1sh.pixelplayeross.data.sync

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.PURGEABLE_DORMANT_WHERE
import org.junit.jupiter.api.Test

/**
 * AUD-106, owner decision: dormant items are never purged automatically. Maintenance offers a manual
 * "clean up items without a file", and it may only remove items that carry no user state at all.
 *
 * The count shown and the delete run share one condition ([PURGEABLE_DORMANT_WHERE]), so what the
 * listener is told is what gets removed. `DormantPurgeRoomTest` (androidTest) runs it on SQLite.
 */
class DormantPurgeConditionTest {

    @Test
    fun `only dormant items without a projection qualify`() {
        assertThat(PURGEABLE_DORMANT_WHERE).contains("state = 'DORMANT'")
        assertThat(PURGEABLE_DORMANT_WHERE).contains("FROM songs")
    }

    @Test
    fun `any favourite, lyric, edit, play, playlist entry or bookmark keeps the item`() {
        listOf(
            "FROM favorites",
            "FROM lyrics",
            "FROM library_item_overrides",
            "FROM song_engagements",
            "FROM playlist_songs",
            "FROM audio_bookmarks",
        ).forEach { table -> assertThat(PURGEABLE_DORMANT_WHERE).contains(table) }
        assertThat(Regex("NOT EXISTS").findAll(PURGEABLE_DORMANT_WHERE).count()).isEqualTo(7)
    }
}
