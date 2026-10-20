package com.lostf1sh.pixelplayeross.data.identity

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.LibraryIdentityLookup
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The clean-up of items without a file against a listen whose identity is still being looked up (R28-01-F1).
 *
 * The tracker opens a listen with the locator and asks the library, off the main thread, which item that is; the answer reaches the
 * session some time later. Maintenance can run in that gap, with the file already gone, and the history does not name the item yet.
 * Here the lookup reads the real library (so it has the identity) and holds only the **delivery** behind a barrier, which is the order
 * the finding needs; nothing in a test waits for the tracker to know the identity before the clean-up starts. Real Room, real sync,
 * real history file, real tracker and clean-up. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DormantItemCleanupPendingLookupTest {
    private lateinit var rig: DormantCleanupRig

    @Before fun setup() { rig = DormantCleanupRig() }

    @After fun close() = rig.close()

    /** A lookup that reads the library for real, says it has read it, and hands the answer over only when [deliver] is completed. */
    private class HeldLookup(real: LibraryIdentityLookup) {
        val read = CompletableDeferred<Unit>()
        val deliver = CompletableDeferred<Unit>()
        val lookup: LibraryIdentityLookup = mockk<LibraryIdentityLookup>().also { held ->
            coEvery { held.identityOf(any()) } coAnswers {
                val identity = real.identityOf(firstArg())
                read.complete(Unit)
                deliver.await()
                identity
            }
        }
    }

    private fun waitForEvents(count: Int) = rig.waitFor { runBlocking { rig.history.exportEventsForBackup() }.size >= count }

    private fun playsOf(identity: String, locator: Long, itemMs: Long = 180_000L): Pair<Int, Long> {
        val events = runBlocking { rig.history.exportEventsForBackup() }
        val returned = runBlocking { rig.dao.getActiveSongByLocator(locator)!! }
        val ledger = ListeningLedger.build(events, Kit.catalog(listOf(Kit.song(locator, identity = returned.libraryItemId, durationMs = itemMs))))
        return (ledger.stats(identity)?.plays ?: 0) to (ledger.stats(identity)?.listenedMs ?: 0L)
    }

    // --- the auditor's probe -------------------------------------------------------------------------------------------

    @Test fun a_listen_whose_lookup_has_read_the_library_but_not_delivered_keeps_its_item_and_gets_its_play_back() = runBlocking {
        rig.libraryOf(1L to "Playing")
        val identity = rig.identityOf(1)
        val held = HeldLookup(LibraryIdentityLookup(rig.dao))
        val tracker = rig.trackerWith(held.lookup)
        val cleanup = rig.cleanupOver(tracker)

        // Playing now. The lookup has the identity in hand and has not handed it over; the tracker does not know it yet.
        tracker.onTrackChanged("1", 0L, 180_000L, 0L, true)
        held.read.await()
        rig.everyFileGoes()
        assertThat(rig.db.libraryItemDao().purgeableDormantIds()).containsExactly(identity)

        // The listener runs the clean-up now. It waits for the lookup; it does not remove the item the lookup is about to name.
        val removal = async(Dispatchers.IO) { cleanup.remove() }
        delay(300)
        assertThat(removal.isCompleted).isFalse()
        held.deliver.complete(Unit)
        assertThat(removal.await()).isEqualTo(0)
        assertThat(rig.db.libraryItemDao().getById(identity)).isNotNull()

        // The listen goes on and is written under that identity; when the file comes back it is the same item with its play.
        rig.endTwoMinuteListen(tracker)
        waitForEvents(1)
        assertThat(rig.history.exportEventsForBackup().single().libraryItemId).isEqualTo(identity)
        rig.fileReturnsAt(77, "Playing")
        assertThat(rig.identityOf(77)).isEqualTo(identity)
        assertThat(playsOf(identity, 77)).isEqualTo(1 to 120_000L)
    }

    @Test fun the_count_waits_too_and_does_not_offer_the_item_the_lookup_is_about_to_name() = runBlocking {
        rig.libraryOf(1L to "Playing")
        val held = HeldLookup(LibraryIdentityLookup(rig.dao))
        val tracker = rig.trackerWith(held.lookup)
        val cleanup = rig.cleanupOver(tracker)
        tracker.onTrackChanged("1", 0L, 180_000L, 0L, true)
        held.read.await()
        rig.everyFileGoes()

        val counted = async(Dispatchers.IO) { cleanup.count() }
        delay(300)
        held.deliver.complete(Unit)

        assertThat(counted.await()).isEqualTo(0)
    }

    // --- a listen that has already ended ---------------------------------------------------------------------------------

    @Test fun a_finished_listen_whose_lookup_is_still_pending_is_still_protected_and_written_under_the_identity() = runBlocking {
        rig.libraryOf(1L to "Playing")
        val identity = rig.identityOf(1)
        val held = HeldLookup(LibraryIdentityLookup(rig.dao))
        val tracker = rig.trackerWith(held.lookup)
        val cleanup = rig.cleanupOver(tracker)

        tracker.onTrackChanged("1", 0L, 180_000L, 0L, true)
        held.read.await()
        // Two minutes of it are heard and the listener moves on: the session is closed, its event waits for the lookup.
        rig.endTwoMinuteListen(tracker)
        rig.everyFileGoes()
        assertThat(rig.history.exportEventsForBackup()).isEmpty()

        val removal = async(Dispatchers.IO) { cleanup.remove() }
        delay(300)
        assertThat(removal.isCompleted).isFalse()
        held.deliver.complete(Unit)
        assertThat(removal.await()).isEqualTo(0)

        waitForEvents(1)
        assertThat(rig.history.exportEventsForBackup().single().libraryItemId).isEqualTo(identity)
        rig.fileReturnsAt(77, "Playing")
        assertThat(rig.identityOf(77)).isEqualTo(identity)
        assertThat(playsOf(identity, 77)).isEqualTo(1 to 120_000L)
    }

    // --- a lookup that does not deliver ----------------------------------------------------------------------------------

    @Test fun a_failed_lookup_leaves_no_protection_behind_and_does_not_block_the_clean_up() = runBlocking {
        rig.libraryOf(1L to "Lookup fails", 2L to "Never listened")
        val failing = mockk<LibraryIdentityLookup>()
        coEvery { failing.identityOf(any()) } throws IllegalStateException("the library could not be read")
        val tracker = rig.trackerWith(failing)
        val cleanup = rig.cleanupOver(tracker)

        tracker.onTrackChanged("1", 0L, 180_000L, 0L, true)
        rig.everyFileGoes()

        // That listen records no identity (B28a), so nothing points at either item: both are removable, and nothing waited for long.
        val started = System.nanoTime()
        assertThat(cleanup.count()).isEqualTo(2)
        assertThat(cleanup.remove()).isEqualTo(2)
        assertThat((System.nanoTime() - started) / 1_000_000L).isLessThan(2_500L)
        assertThat(rig.db.libraryItemDao().getAll()).isEmpty()

        rig.endTwoMinuteListen(tracker)
        waitForEvents(1)
        assertThat(rig.history.exportEventsForBackup().single().libraryItemId).isNull()
    }

    @Test fun a_cancelled_lookup_leaves_no_protection_behind_and_does_not_block_the_clean_up() = runBlocking {
        rig.libraryOf(1L to "Lookup cancelled", 2L to "Never listened")
        val cancelled = mockk<LibraryIdentityLookup>()
        coEvery { cancelled.identityOf(any()) } coAnswers {
            currentCoroutineContext().cancel()
            awaitCancellation()
        }
        val tracker = rig.trackerWith(cancelled)
        val cleanup = rig.cleanupOver(tracker)

        tracker.onTrackChanged("1", 0L, 180_000L, 0L, true)
        rig.everyFileGoes()

        val started = System.nanoTime()
        assertThat(cleanup.count()).isEqualTo(2)
        assertThat(cleanup.remove()).isEqualTo(2)
        assertThat((System.nanoTime() - started) / 1_000_000L).isLessThan(2_500L)
        assertThat(rig.db.libraryItemDao().getAll()).isEmpty()
    }

    @Test fun an_item_with_no_listen_in_flight_is_removed_at_once_while_another_listen_waits_for_its_lookup() = runBlocking {
        rig.libraryOf(1L to "Playing", 2L to "Never listened")
        val identity = rig.identityOf(1)
        val empty = rig.identityOf(2)
        val held = HeldLookup(LibraryIdentityLookup(rig.dao))
        val tracker = rig.trackerWith(held.lookup)
        val cleanup = rig.cleanupOver(tracker)
        tracker.onTrackChanged("1", 0L, 180_000L, 0L, true)
        held.read.await()
        rig.everyFileGoes()

        val removal = async(Dispatchers.IO) { cleanup.remove() }
        delay(300)
        held.deliver.complete(Unit)

        // The item that was playing stays; the one nobody listened to goes.
        assertThat(removal.await()).isEqualTo(1)
        assertThat(rig.db.libraryItemDao().getById(empty)).isNull()
        assertThat(rig.db.libraryItemDao().getById(identity)).isNotNull()
    }
}
