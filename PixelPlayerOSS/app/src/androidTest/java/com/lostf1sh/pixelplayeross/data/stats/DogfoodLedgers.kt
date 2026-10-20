package com.lostf1sh.pixelplayeross.data.stats

import android.content.Context
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import com.lostf1sh.pixelplayeross.data.database.toSong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

private fun catalogSourceOf(db: PixelPlayerDatabase) = object : CatalogSource {
    override suspend fun load(): LibraryCatalog {
        val songs = db.musicDao().getAllSongsList().map { it.toSong() }
        val rows = db.libraryItemDao().getAll()
        val present = songs.mapTo(HashSet()) { it.libraryItemId }
        return LibraryCatalog.of(
            songs,
            rows.associate { it.libraryItemId to it.firstSeenAt },
            rows.filter { it.libraryItemId !in present }.mapTo(HashSet()) { it.libraryItemId },
        )
    }
}

/**
 * The listening ledger of the app on this device, for the dogfood tests: the library read from [db] and the history read from the
 * app's own file. Read-only, like the tests that use it.
 */
internal fun dogfoodLedgers(context: Context, db: PixelPlayerDatabase): LedgerProvider = ListeningModel(
    history = PlaybackStatsRepository(context),
    catalogSource = catalogSourceOf(db),
    librarySignal = LibraryChangeSignal(),
)

/**
 * The library of [db] with only the listening a test gives it (none by default), for the tests that seed their own database: they
 * must not read the history of the app the test happens to run in. [events] is asked each time the ledger is, so a test can add to
 * it after the repository under test was built.
 */
internal fun libraryOnlyLedgers(
    db: PixelPlayerDatabase,
    events: () -> List<PlaybackStatsRepository.PlaybackEvent> = { emptyList() },
): LedgerProvider = object : LedgerProvider {
    override val changes: Flow<Long> = emptyFlow()
    override suspend fun ledger(): ListeningLedger = ListeningLedger.build(events(), catalogSourceOf(db).load())
}
