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

/** Home owns Suggestions; only Daily Mix and the automatic collections belong in Playlists. */
class HomeMixes(
    val isReady: Boolean, val daily: List<CatalogItem>, val yours: List<CatalogItem>,
    val epochDay: Long = 0, val preview: List<CatalogItem> = yours.take(3),
    val suggestedAlbums: List<com.lostf1sh.pixelplayeross.data.model.Album> = emptyList(),
    val fallback: Boolean = false, val generation: Int = 0, val previewGeneration: Int = 0, val dailyHidden: Boolean = false,
) {
    companion object { val Initial = HomeMixes(false, emptyList(), emptyList()) }
}

/** Same preference key and durable library identities. Optional fields read old snapshots safely. */
@Serializable
internal data class AutoMixSnapshot(
    val epochDay: Long, val daily: List<String>, val yours: List<String>, val generation: Int = 0,
    val preview: List<String> = emptyList(), val previewGeneration: Int = 0,
    val albums: List<String> = emptyList(), val homeRevision: Int = 0, val fallback: Boolean = false,
)

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
 * The saved list may be empty, and that is a mix like any other: its files were deleted, or the library holds none of
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

    private data class Built(
        val collections: List<AutoCollection>,
        val yours: List<CatalogItem>,
        val atMs: Long,
        val epochDay: Long,
        val snapshot: AutoMixSnapshot,
        val albums: List<com.lostf1sh.pixelplayeross.data.model.Album>,
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

    val homeMixes: StateFlow<HomeMixes> = combine(built, preferences.hiddenAutoCollectionsFlow) { b, hidden ->
        if (b == null) HomeMixes.Initial
        else HomeMixes(true,
            if (AutoCollectionId.DAILY_MIX.key in hidden) emptyList() else b.collections.firstOrNull { it.id == AutoCollectionId.DAILY_MIX }?.items.orEmpty(),
            b.yours, b.epochDay, b.snapshot.preview.mapNotNull { id -> b.yours.firstOrNull { it.identity == id } },
            b.albums, b.snapshot.fallback, b.snapshot.generation, b.snapshot.previewGeneration, AutoCollectionId.DAILY_MIX.key in hidden)
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
     * A deleted file is taken out of today's mix. It is out for the rest of the day, in the list and in the saved mix,
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
        val snapshot = current.snapshot.copy(daily = kept.map { it.identity })
        built.value = current.copy(collections = updated, snapshot = snapshot)
        saveSnapshot(snapshot)
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
    suspend fun refresh(reason: String, regenerate: Boolean = false): Unit = buildMutex.withLock {
        val now = clock()
        val zone = zone()
        runCatching { withContext(dispatchers.default) { build(now, zone, regenerate) } }
            .onSuccess { built.value = it }
            .onFailure { Timber.tag("AutoCollections").e(it, "Failed to build the automatic collections (%s)", reason) }
    }

    /** Refresh the three covers only. The day's Suggestions and the Daily Mix are not rebuilt. */
    suspend fun refreshSuggestionsPreview(): Unit = buildMutex.withLock {
        val current = built.value ?: return@withLock
        val count = current.snapshot.previewGeneration + 1
        val ids = current.yours.map { it.identity }
        val preview = suggestionPreview(ids, suggestionSeed("preview:${current.epochDay}:$count"), current.snapshot.preview)
        val snapshot = current.snapshot.copy(preview = preview, previewGeneration = count)
        built.value = current.copy(snapshot = snapshot)
        saveSnapshot(snapshot)
    }

    private suspend fun build(now: Long, zone: ZoneId, regenerate: Boolean): Built {
        val ledger = ledgers.ledger()
        val order = withContext(dispatchers.io) { libraryOrder() }
        val others = AutoCollectionEngine.compute(ledger, now, zone, order)
        val epochDay = localEpochDay(now, zone)
        val previous = readSnapshot()
        val saved = previous?.takeIf { it.epochDay == epochDay }
        val generation = (saved?.generation ?: 0) + if (regenerate) 1 else 0
        val daily = if (saved != null && !regenerate) {
            val items = saved.daily.mapNotNull { ledger.catalog.item(it) }
            AutoCollection(AutoCollectionId.DAILY_MIX, items, emptyReason = if(items.isEmpty()) EmptyReason.NOTHING_MATCHES else null)
        } else AutoCollectionEngine.dailyMix(ledger, others, epochDay, generation = generation)
        val reuseSuggestions = saved?.homeRevision == HOME_REVISION && !regenerate
        val fallback = if (reuseSuggestions) saved!!.fallback else ledger.isEmpty
        val excluded = daily.items.mapTo(hashSetOf()) { it.identity }
        val yours = when {
            reuseSuggestions -> saved!!.yours.mapNotNull { ledger.catalog.item(it) }.filter { it.identity !in excluded }
            // No history has an honest newest-added list, capped at 48, under Suggestions.
            fallback -> ledger.catalog.items.filter { it.identity !in excluded }
                .sortedWith(compareByDescending<CatalogItem> { it.song.dateAdded }.thenBy { it.identity }).take(48)
            else -> AutoCollectionEngine.dailyMix(ledger, others, epochDay, yours = true, excluding = excluded).items
        }
        // Choose once for the day, including across library scans and explicit Daily regeneration.
        // An empty first catalog can fill after import; unavailable albums keep their saved ids.
        val retainedAlbumIds = saved?.takeIf { it.homeRevision == HOME_REVISION }?.albums?.takeIf { it.isNotEmpty() }
        val albums = if (retainedAlbumIds != null) {
            retainedAlbumIds.mapNotNull { key -> ledger.catalog.tracksByAlbum[key]?.let(::suggestedAlbum) }
        } else SuggestedAlbums.build(ledger, now, epochDay, others[AutoCollectionId.ON_REPEAT]?.items.orEmpty().mapTo(hashSetOf()) { it.identity }).albums
        val ids = yours.map { it.identity }
        val keptPreview = if(reuseSuggestions) saved!!.preview else emptyList()
        val previewGeneration = saved?.previewGeneration ?: 0
        val preview = if (reuseSuggestions && (keptPreview.isNotEmpty() || saved!!.yours.isEmpty())) keptPreview else suggestionPreview(
            ids, suggestionSeed("preview:$epochDay:$generation:$previewGeneration"), previous?.preview.orEmpty())
        // Availability is a projection. A removed SD card must not erase its identities from today's saved lists.
        val dailyIds = if(saved != null && !regenerate) saved.daily else daily.items.map { it.identity }
        val yoursIds = if(reuseSuggestions) saved!!.yours else ids
        val albumIds = retainedAlbumIds ?: albums.map { it.id.toString() }
        val snapshot = AutoMixSnapshot(epochDay,dailyIds,yoursIds,generation,preview,previewGeneration,albumIds,HOME_REVISION,fallback)
        saveSnapshot(snapshot)
        val ordered = buildList {
            add(daily)
            AutoCollectionId.entries.filter { it != AutoCollectionId.DAILY_MIX }.forEach { id -> others[id]?.let(::add) }
        }
        return Built(ordered,yours,now,epochDay,snapshot,albums)
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
        // A clock or zone moving backwards must not replace the snapshot of a later day.
        if ((readSnapshot()?.epochDay ?: Long.MIN_VALUE) > snapshot.epochDay) return
        runCatching { preferences.saveAutoMixSnapshotJson(json.encodeToString(AutoMixSnapshot.serializer(), snapshot)) }
            .onFailure { Timber.tag("AutoCollections").w(it, "Could not save the mix of the day") }
    }

    companion object {
        /** A screen that opens and finds the collections older than this builds them again. */
        val STALE_AFTER_MS: Long = TimeUnit.MINUTES.toMillis(15)

        private const val HOME_REVISION = 1
        private const val LIBRARY_DEBOUNCE_MS = 5_000L
        private val json = Json { ignoreUnknownKeys = true }

        fun localEpochDay(nowMs: Long, zone: ZoneId): Long =
            Math.floorDiv(nowMs + zone.rules.getOffset(Instant.ofEpochMilli(nowMs)).totalSeconds * 1000L, 24L * 60 * 60 * 1000)
    }
}
