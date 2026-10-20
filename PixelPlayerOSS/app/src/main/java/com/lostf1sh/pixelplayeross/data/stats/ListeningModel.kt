package com.lostf1sh.pixelplayeross.data.stats

import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository.PlaybackEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * "The library changed": a sync added or removed files, tags were edited, a favourite was toggled.
 *
 * It has no dependencies on purpose. The repositories that raise it (`MusicRepositoryImpl`, `SyncWorker`) sit below what reads
 * the library for Stats, and a signal they could call without depending on the thing that reacts to it is what keeps those two
 * from forming a cycle. [ListeningModel] and the collections listen.
 */
@Singleton
class LibraryChangeSignal @Inject constructor() {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    /** [reason] is only for the log: the table of what raises this is in the callers. */
    fun changed(reason: String) {
        Timber.tag("ListeningModel").d("library changed: %s", reason)
        _version.update { it + 1 }
    }
}

/** What the screens and the collections read the listening from; a seam so they are tested over a ledger built by hand. */
interface LedgerProvider {
    /** Moves whenever what [ledger] would answer may have changed: a listen, or the library. */
    val changes: Flow<Long>

    suspend fun ledger(): ListeningLedger
}

/**
 * The one place the Stats screen, Home's card, the "Top N" and the automatic collections get their [ListeningLedger] from.
 *
 * It holds the history (read from the file once, then grown by what the store appends), the library catalog and the ledger built
 * from both, and rebuilds only what changed:
 *
 *  - a listen was written: the new events are added and the ledger is rebuilt from the events already in memory (no file read,
 *    no parse);
 *  - the library changed ([LibraryChangeSignal]): the catalog is read again, which is what re-attributes the history to the library as
 *    it is now (R-04), and a screen that was open shows it without a restart;
 *  - the history was replaced (a restore, an import, a prune): everything is read again.
 *
 * Everything runs off the main thread. Asking for the ledger while it is being rebuilt waits for that rebuild instead of starting
 * a second one.
 */
@Singleton
class ListeningModel @Inject constructor(
    private val history: PlaybackStatsRepository,
    private val catalogSource: CatalogSource,
    private val librarySignal: LibraryChangeSignal,
) : LedgerProvider {
    private val mutex = Mutex()
    private val events = ArrayList<PlaybackEvent>()
    private var epoch = 0L
    private var consumed = 0
    private var catalog: LibraryCatalog? = null
    private var catalogAtSignal = -1L
    private var ledger: ListeningLedger? = null

    override val changes: Flow<Long> = combine(history.refreshFlow, librarySignal.version) { h, l -> h * 1_000_003L + l }

    override suspend fun ledger(): ListeningLedger = mutex.withLock {
        withContext(Dispatchers.Default) {
            val signal = librarySignal.version.value
            if (catalog == null || catalogAtSignal != signal) {
                catalog = withContext(Dispatchers.IO) { catalogSource.load() }
                catalogAtSignal = signal
                ledger = null
            }
            val delta = history.readSince(epoch, consumed)
            if (delta.replaced) {
                events.clear()
                events.addAll(delta.events)
                epoch = delta.epoch
                ledger = null
            } else if (delta.events.isNotEmpty()) {
                events.addAll(delta.events)
                ledger = null
            }
            consumed = events.size
            ledger ?: ListeningLedger.build(events, catalog!!).also { ledger = it }
        }
    }
}
