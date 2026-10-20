package com.lostf1sh.pixelplayeross.data.collections.auto

import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.data.model.StorageFilter
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.CatalogItem
import com.lostf1sh.pixelplayeross.data.stats.LedgerProvider
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.di.AppScope
import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/** All the automatic collections, and which of them the listener has hidden. */
class AutoCollectionsState(
    /** False until the first time they were built in this process. */
    val isReady: Boolean,
    /** Every collection, in the order the list shows them, hidden or not. */
    val collections: List<AutoCollection>,
    val hidden: Set<AutoCollectionId>,
) {
    val visible: List<AutoCollection> get() = collections.filter { it.id !in hidden }
    val hiddenCollections: List<AutoCollection> get() = collections.filter { it.id in hidden }

    fun collection(id: AutoCollectionId): AutoCollection? = collections.firstOrNull { it.id == id }

    companion object {
        val Initial = AutoCollectionsState(false, emptyList(), emptySet())
    }
}

/** The two lists Home shows: the mix of the day, and "Tu mix de hoy" (the same blend, leaning on rediscovery, never repeating the first). */
class HomeMixes(val isReady: Boolean, val daily: List<CatalogItem>, val yours: List<CatalogItem>) {
    companion object {
        val Initial = HomeMixes(false, emptyList(), emptyList())
    }
}

/** The mix of the day as it was made: the identities of both lists and the local day they are for. */
@Serializable
internal data class AutoMixSnapshot(val epochDay: Long, val daily: List<String>, val yours: List<String>)

/**
 * The automatic collections, built from the ledger and kept for the screens (B28).
 *
 * ## When they are built
 *
 * Not for every listen: a track that ends while music plays does not recompute anything. They are rebuilt
 *
 *  - when the library changes (a sync, an edit, a favourite), a few seconds after the last change;
 *  - at the local day change (the mix of the day is another one);
 *  - when a screen that shows them opens and what it would show is older than [STALE_AFTER_MS] ([ensureFresh]);
 *  - when asked.
 *
 * Every build runs off the main thread, and two never run at once. A build reads the ledger the model already holds.
 *
 * ## The mix of the day is stable
 *
 * Its list is saved with the local day it was made for. A rebuild during the same day (a sync, a favourite) gives the saved list back,
 * without the tracks that have left the library and without new ones; the next day's is made from the seed of that day.
 *
 * The saved list may be empty, and that is a mix like any other: the listener swiped out every track, or the library holds none of
 * them for the moment. Having no snapshot for today is what makes a new mix, never an empty one (R28-05).
 *
 * ## Hidden is the only thing a collection can be
 *
 * The set of hidden keys is a preference, so it survives a backup and a restore with the other settings. Nothing is stored for a collection that is
 * not hidden, and a collection cannot go missing: they are defined in code.
 */
@Singleton
class AutoCollectionRepository @Inject constructor(
    private val ledgers: LedgerProvider,
    private val preferences: UserPreferencesRepository,
    private val musicRepository: MusicRepository,
    private val librarySignal: LibraryChangeSignal,
    private val dispatchers: DispatcherProvider,
    @AppScope private val scope: CoroutineScope,
) {
    /** The clock and the zone, replaceable by a test. */
    internal var clock: () -> Long = System::currentTimeMillis
    internal var zone: () -> ZoneId = ZoneId::systemDefault

    private class Built(
        val collections: List<AutoCollection>,
        val yours: List<CatalogItem>,
        val atMs: Long,
        val epochDay: Long,
    )

    private val built = MutableStateFlow<Built?>(null)
    private val buildMutex = Mutex()

    val state: StateFlow<AutoCollectionsState> = combine(built, preferences.hiddenAutoCollectionsFlow) { built, hidden ->
        AutoCollectionsState(
            isReady = built != null,
            collections = built?.collections.orEmpty(),
            hidden = hidden.mapNotNullTo(HashSet()) { AutoCollectionId.fromKey(it) },
        )
    }.stateIn(scope, SharingStarted.Eagerly, AutoCollectionsState.Initial)

    val homeMixes: StateFlow<HomeMixes> = built.map { b ->
        if (b == null) HomeMixes.Initial
        else HomeMixes(true, b.collections.firstOrNull { it.id == AutoCollectionId.DAILY_MIX }?.items.orEmpty(), b.yours)
    }.stateIn(scope, SharingStarted.Eagerly, HomeMixes.Initial)

    init {
        observeLibrary()
    }

    @OptIn(FlowPreview::class)
    private fun observeLibrary() {
        scope.launch {
            librarySignal.version.drop(1).debounce(LIBRARY_DEBOUNCE_MS).collect { refresh("library changed") }
        }
    }

    /** When they were last built, 0 if never: what the day-change check compares with today. */
    val builtAtMs: Long get() = built.value?.atMs ?: 0L

    /**
     * The listener took a track out of today's mix (a swipe on Home). It is out for the rest of the day, in the list and in the saved mix,
     * and the next day's is made without it having been taken out.
     */
    suspend fun removeFromDailyMix(locator: String): Unit = buildMutex.withLock {
        val current = built.value ?: return@withLock
        val daily = current.collections.firstOrNull { it.id == AutoCollectionId.DAILY_MIX } ?: return@withLock
        val kept = daily.items.filter { it.locator != locator }
        if (kept.size == daily.items.size) return@withLock
        val updated = current.collections.map {
            if (it.id == AutoCollectionId.DAILY_MIX) AutoCollection(AutoCollectionId.DAILY_MIX, kept, emptyReason = if (kept.isEmpty()) EmptyReason.NOTHING_MATCHES else null) else it
        }
        built.value = Built(updated, current.yours, current.atMs, current.epochDay)
        saveSnapshot(AutoMixSnapshot(current.epochDay, kept.map { it.identity }, current.yours.map { it.identity }))
    }

    fun hide(id: AutoCollectionId) {
        scope.launch { preferences.setAutoCollectionHidden(id.key, true) }
    }

    fun show(id: AutoCollectionId) {
        scope.launch { preferences.setAutoCollectionHidden(id.key, false) }
    }

    fun showAllHidden() {
        scope.launch { preferences.showAllAutoCollections() }
    }

    /** Builds them if they were never built in this process, belong to another day, or are older than [maxAgeMs]. */
    suspend fun ensureFresh(maxAgeMs: Long = STALE_AFTER_MS) {
        val now = clock()
        val current = built.value
        val today = localEpochDay(now, zone())
        if (current == null || current.epochDay != today || now - current.atMs > maxAgeMs) refresh("opened, stale")
    }

    /** The day changed under a running app: build today's, once. */
    suspend fun refreshIfDayChanged() {
        val current = built.value
        if (current == null || current.epochDay != localEpochDay(clock(), zone())) refresh("day changed")
    }

    /** Rebuilds them now. A build already running finishes first. */
    suspend fun refresh(reason: String): Unit = buildMutex.withLock {
        val now = clock()
        val zone = zone()
        runCatching { withContext(dispatchers.default) { build(now, zone) } }
            .onSuccess { built.value = it }
            .onFailure { Timber.tag("AutoCollections").e(it, "Failed to build the automatic collections (%s)", reason) }
    }

    private suspend fun build(now: Long, zone: ZoneId): Built {
        val ledger = ledgers.ledger()
        val order = withContext(dispatchers.io) { libraryOrder() }
        val others = AutoCollectionEngine.compute(ledger, now, zone, order)
        val epochDay = localEpochDay(now, zone)

        // A snapshot for today is the mix of today even when nothing in it is left to show; only the lack of one makes a new mix.
        val saved = readSnapshot()?.takeIf { it.epochDay == epochDay }
        val dailyCollection: AutoCollection
        val yours: List<CatalogItem>
        if (saved != null) {
            val savedDaily = saved.daily.mapNotNull { ledger.catalog.item(it) }
            dailyCollection = AutoCollection(
                AutoCollectionId.DAILY_MIX, savedDaily,
                emptyReason = if (savedDaily.isEmpty()) EmptyReason.NOTHING_MATCHES else null,
            )
            yours = saved.yours.mapNotNull { ledger.catalog.item(it) }
        } else {
            val dailyMix = AutoCollectionEngine.dailyMix(ledger, others, epochDay)
            val yoursMix = AutoCollectionEngine.dailyMix(
                ledger, others, epochDay, yours = true, excluding = dailyMix.items.mapTo(HashSet()) { it.identity },
            )
            dailyCollection = dailyMix
            yours = yoursMix.items
            if (dailyMix.items.isNotEmpty()) {
                saveSnapshot(AutoMixSnapshot(epochDay, dailyMix.items.map { it.identity }, yours.map { it.identity }))
            }
        }
        val ordered = buildList {
            add(dailyCollection)
            AutoCollectionId.entries.filter { it != AutoCollectionId.DAILY_MIX }.forEach { id -> others[id]?.let(::add) }
        }
        return Built(ordered, yours, now, epochDay)
    }

    /** The locators of the library in the order the Songs list shows them (its saved sort), for the collections that follow it. */
    private suspend fun libraryOrder(): List<String>? = runCatching {
        val sort = SortOption.fromStorageKey(
            preferences.songsSortOptionFlow.first(),
            SortOption.SONGS,
            SortOption.SongDefaultOrder,
        )
        musicRepository.getSongIdsSorted(sort, StorageFilter.ALL).map { it.toString() }
    }.onFailure { Timber.tag("AutoCollections").w(it, "Library order unavailable; using the title") }.getOrNull()

    private suspend fun readSnapshot(): AutoMixSnapshot? = runCatching {
        preferences.autoMixSnapshotJson()?.let { json.decodeFromString(AutoMixSnapshot.serializer(), it) }
    }.getOrNull()

    private suspend fun saveSnapshot(snapshot: AutoMixSnapshot) {
        runCatching { preferences.saveAutoMixSnapshotJson(json.encodeToString(AutoMixSnapshot.serializer(), snapshot)) }
            .onFailure { Timber.tag("AutoCollections").w(it, "Could not save the mix of the day") }
    }

    companion object {
        /** A screen that opens and finds the collections older than this builds them again. */
        val STALE_AFTER_MS: Long = TimeUnit.MINUTES.toMillis(15)

        private const val LIBRARY_DEBOUNCE_MS = 5_000L
        private val json = Json { ignoreUnknownKeys = true }

        fun localEpochDay(nowMs: Long, zone: ZoneId): Long =
            Math.floorDiv(nowMs + zone.rules.getOffset(Instant.ofEpochMilli(nowMs)).totalSeconds * 1000L, 24L * 60 * 60 * 1000)
    }
}
