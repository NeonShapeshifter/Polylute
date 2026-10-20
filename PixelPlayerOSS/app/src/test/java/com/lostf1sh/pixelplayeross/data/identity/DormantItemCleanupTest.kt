package com.lostf1sh.pixelplayeross.data.identity

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.FavoritesEntity
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.LibraryIdentityLookup
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ListeningStatsTracker
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The manual "clean up items without a file" of Maintenance, against the listening history (R28-01).
 *
 * Real Room (in memory), the real library sync, the real tracker writing the real history file, and the real clean-up. Since B28 a
 * play lives in the history, not in `song_engagements`; the clean-up used to look only at the table, and removed the item an event
 * names, so the track that came back had a new identity and none of its plays. A listen whose identity is still being looked up is
 * `DormantItemCleanupPendingLookupTest`. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DormantItemCleanupTest {
    private lateinit var rig: DormantCleanupRig
    private lateinit var tracker: ListeningStatsTracker
    private lateinit var cleanup: DormantItemCleanup

    @Before fun setup() {
        rig = DormantCleanupRig()
        tracker = rig.trackerWith()
        cleanup = rig.cleanupOver(tracker)
    }

    @After fun close() = rig.close()

    // --- the finding --------------------------------------------------------------------------------------------------

    @Test fun a_track_listened_to_after_B28_survives_the_clean_up_and_keeps_its_identity_plays_and_time_when_its_file_returns() = runBlocking {
        rig.libraryOf(1L to "Known")
        val identity = rig.identityOf(1)
        rig.listenTo(tracker, 1)
        val events = rig.history.exportEventsForBackup()
        assertThat(events.single().libraryItemId).isEqualTo(identity)

        rig.everyFileGoes()
        assertThat(rig.dao.getActiveSongByLocator(1)).isNull()

        // Dormant, no favourite, lyric, edit, playlist entry, bookmark or legacy counter: only the history names it.
        assertThat(rig.db.libraryItemDao().purgeableDormantIds()).containsExactly(identity)
        assertThat(cleanup.count()).isEqualTo(0)
        assertThat(cleanup.remove()).isEqualTo(0)
        assertThat(rig.db.libraryItemDao().getById(identity)).isNotNull()

        // The file comes back under another number: the same item, with the same plays and the same time.
        rig.fileReturnsAt(77, "Known")
        val returned = rig.dao.getActiveSongByLocator(77)!!
        assertThat(returned.libraryItemId).isEqualTo(identity)
        val ledger = ListeningLedger.build(rig.history.exportEventsForBackup(), Kit.catalog(listOf(Kit.song(77, identity = returned.libraryItemId, durationMs = 180_000L))))
        assertThat(ledger.stats(identity)!!.plays).isEqualTo(1)
        assertThat(ledger.stats(identity)!!.listenedMs).isEqualTo(120_000L)
        assertThat(ledger.coverage.identityMs).isEqualTo(120_000L)
    }

    @Test fun an_item_nobody_listened_to_and_nothing_was_saved_for_is_still_removed_and_the_count_says_so() = runBlocking {
        rig.libraryOf(1L to "Heard", 2L to "Never heard", 3L to "Saved")
        val heard = rig.identityOf(1)
        val never = rig.identityOf(2)
        val saved = rig.identityOf(3)
        rig.db.favoritesDao().insertAll(listOf(FavoritesEntity(songId = 3, libraryItemId = saved)))
        rig.listenTo(tracker, 1)

        rig.everyFileGoes()

        // Three dormant items: one named by the history, one by a favourite, one by nothing.
        assertThat(cleanup.count()).isEqualTo(1)
        assertThat(cleanup.remove()).isEqualTo(1)
        assertThat(rig.db.libraryItemDao().getById(never)).isNull()
        assertThat(rig.db.libraryItemDao().getById(heard)).isNotNull()
        assertThat(rig.db.libraryItemDao().getById(saved)).isNotNull()
        assertThat(cleanup.count()).isEqualTo(0)
    }

    @Test fun a_listen_in_progress_whose_identity_was_delivered_when_its_file_goes_keeps_its_item_and_the_event_written_after_finds_it() = runBlocking {
        rig.libraryOf(1L to "Playing")
        val identity = rig.identityOf(1)

        // Playing now, and the tracker has the identity (the usual case: a lookup is milliseconds). The library then loses the file.
        // (A lookup that has not delivered yet is DormantItemCleanupPendingLookupTest.)
        tracker.onTrackChanged("1", 0L, 180_000L, 0L, true)
        rig.waitFor { tracker.sessionSnapshot()?.libraryItemId == identity }
        rig.everyFileGoes()
        assertThat(rig.db.libraryItemDao().purgeableDormantIds()).containsExactly(identity)

        assertThat(cleanup.count()).isEqualTo(0)
        assertThat(cleanup.remove()).isEqualTo(0)

        rig.endTwoMinuteListen(tracker)
        rig.waitFor { runBlocking { rig.history.exportEventsForBackup() }.isNotEmpty() }
        assertThat(rig.history.exportEventsForBackup().single().libraryItemId).isEqualTo(identity)
        assertThat(rig.db.libraryItemDao().getById(identity)).isNotNull()
        // Written; the history names it from here on, and the tracker has nothing in flight.
        rig.waitFor { runBlocking { tracker.identitiesInFlight().identities }.isEmpty() }
        assertThat(cleanup.count()).isEqualTo(0)
    }

    @Test fun the_count_and_the_delete_agree_with_a_large_library_of_empty_items() {
        runBlocking { countAndDeleteWithMoreItemsThanOneStatementTakes() }
    }

    private suspend fun countAndDeleteWithMoreItemsThanOneStatementTakes() {
        val tracks = (1L..620L).map { it to "Track $it" }.toTypedArray()
        rig.libraryOf(*tracks)
        val heard = rig.identityOf(5)
        rig.listenTo(tracker, 5)
        rig.everyFileGoes()

        // More items than one delete statement takes: the count and what goes are the same, and the heard item stays.
        assertThat(cleanup.count()).isEqualTo(619)
        assertThat(cleanup.remove()).isEqualTo(619)
        assertThat(rig.db.libraryItemDao().getAll().map { it.libraryItemId }).containsExactly(heard)
    }

    // --- a lookup that never answers ---------------------------------------------------------------------------------------

    @Test fun a_lookup_that_does_not_answer_in_time_makes_the_clean_up_remove_nothing_instead_of_guessing() = runBlocking {
        rig.libraryOf(1L to "Playing", 2L to "Never listened")
        val real = LibraryIdentityLookup(rig.dao)
        val read = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        val stuck = mockk<LibraryIdentityLookup>()
        coEvery { stuck.identityOf(any()) } coAnswers {
            val identity = real.identityOf(firstArg())
            read.complete(Unit)
            answer.await()
            identity
        }
        val stuckTracker = rig.trackerWith(stuck)
        val stuckCleanup = rig.cleanupOver(stuckTracker)
        stuckCleanup.lookupWaitMs = 400L
        stuckTracker.onTrackChanged("1", 0L, 180_000L, 0L, true)
        read.await()
        rig.everyFileGoes()

        // Neither the count nor the delete says "removable" for what cannot be told apart from the item being looked up.
        assertThat(stuckCleanup.count()).isEqualTo(0)
        assertThat(stuckCleanup.remove()).isEqualTo(0)
        assertThat(rig.db.libraryItemDao().getAll()).hasSize(2)

        // Once the lookup answers, the same clean-up knows what to keep and removes the one nobody listened to.
        answer.complete(Unit)
        stuckCleanup.lookupWaitMs = 5_000L
        assertThat(stuckCleanup.remove()).isEqualTo(1)
        assertThat(rig.db.libraryItemDao().getAll()).hasSize(1)
    }
}
