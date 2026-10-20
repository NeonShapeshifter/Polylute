package com.lostf1sh.pixelplayeross.data.collections

import com.lostf1sh.pixelplayeross.data.metadata.DuplicateCandidate
import com.lostf1sh.pixelplayeross.data.metadata.DuplicateDetector
import com.lostf1sh.pixelplayeross.data.metadata.MetadataNormalizer
import com.lostf1sh.pixelplayeross.data.stats.ItemStats
import com.lostf1sh.pixelplayeross.data.stats.LedgerProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Smart collections as a product feature: stored definitions, executed on demand.
 *
 * ## Where the work is
 *
 * Reading the library once, then evaluating. Everything that decides membership lives in
 * [SmartCollectionEngine], which is pure and knows nothing about the database; everything that
 * decides *what the user saved* lives in the DAO and the model. This class is the join between
 * them, and it is deliberately thin.
 *
 * ## No caching
 *
 * The brief says not to add caching unless measurement asks for it, and measurement did not: a
 * rule set over a few thousand tracks is one indexed read and some arithmetic. A cache would also
 * be a correctness liability here — a collection is a *view*, and the whole point is that it is
 * right the moment something changes. A stale cache would make a play count, a favourite or a
 * dormancy take effect "eventually", which is exactly the behaviour a saved query should not have.
 *
 * ## Built-ins are ordinary collections
 *
 * They are seeded as normal rows with [SmartCollection.isBuiltIn] set, evaluated by the same engine
 * with no special-casing anywhere. They can be edited and re-sorted like any other; only deletion is
 * refused, because a built-in that vanished would have no way back.
 */
@Singleton
class SmartCollectionRepository @Inject constructor(
    private val dao: SmartCollectionDao,
    private val candidateDao: CollectionCandidateDao,
    private val engine: SmartCollectionEngine,
    private val duplicateDetector: DuplicateDetector,
    private val ledgers: LedgerProvider,
) {

    fun observeAll(): Flow<List<SmartCollection>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun getAll(): List<SmartCollection> = dao.getAll().map { it.toModel() }

    suspend fun getById(id: Long): SmartCollection? = dao.getById(id)?.toModel()

    /**
     * Creates a collection and returns it with its assigned id.
     *
     * [SmartCollection.id] is ignored on the way in — a create is a create, and letting a caller
     * pass an id would make "create" able to overwrite.
     */
    suspend fun create(
        name: String,
        rules: List<SmartCollectionRule>,
        matchMode: SmartCollectionMatchMode = SmartCollectionMatchMode.ALL,
        sortField: SmartCollectionSortField = SmartCollectionSortField.TITLE,
        sortDirection: SmartCollectionSortDirection = SmartCollectionSortDirection.ASCENDING,
        limit: Int? = null,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val id = dao.insertCollection(
            SmartCollectionEntity(
                name = name.trim(),
                matchMode = matchMode.storageKey,
                sortField = sortField.storageKey,
                sortDirection = sortDirection.storageKey,
                resultLimit = limit?.takeIf { it > 0 },
                isBuiltIn = false,
                createdAt = now,
                updatedAt = now,
            )
        )
        dao.replaceRules(id, rules.toEntities(id))
        return id
    }

    /**
     * Saves an edited definition.
     *
     * The rules are replaced wholesale in one transaction, so a collection is never observable with
     * half of an edit applied — which would silently mean something different from both the old and
     * the new definition.
     */
    suspend fun update(collection: SmartCollection, now: Long = System.currentTimeMillis()) {
        val existing = dao.getById(collection.id) ?: return
        dao.updateCollection(
            existing.collection.copy(
                name = collection.name.trim(),
                matchMode = collection.matchMode.storageKey,
                sortField = collection.sortField.storageKey,
                sortDirection = collection.sortDirection.storageKey,
                resultLimit = collection.limit?.takeIf { it > 0 },
                updatedAt = now,
            )
        )
        dao.replaceRules(collection.id, collection.rules.toEntities(collection.id))
    }

    /** Deletes a user collection. A built-in is left alone — see the class note. */
    suspend fun delete(id: Long) = dao.deleteCollection(id)

    /** Copies a collection under a new name. Cheap, and the usual way to start a variation. */
    suspend fun duplicate(id: Long, now: Long = System.currentTimeMillis()): Long? {
        val source = getById(id) ?: return null
        return create(
            name = "${source.name} copy",
            rules = source.rules,
            matchMode = source.matchMode,
            sortField = source.sortField,
            sortDirection = source.sortDirection,
            limit = source.limit,
            now = now,
        )
    }

    /**
     * Seeds the built-ins, once.
     *
     * Idempotent by key rather than by count: a built-in the user deleted and one that was never
     * created are different states, and re-seeding on every launch would resurrect a deletion. The
     * key is stored on the row so the check is a lookup, not a guess about names.
     */
    suspend fun seedBuiltInsIfNeeded(now: Long = System.currentTimeMillis()) {
        BuiltInCollections.all.forEach { builtIn ->
            if (dao.getByBuiltInKey(builtIn.key) != null) return@forEach
            val id = dao.insertCollection(
                SmartCollectionEntity(
                    name = builtIn.name,
                    matchMode = builtIn.collection.matchMode.storageKey,
                    sortField = builtIn.collection.sortField.storageKey,
                    sortDirection = builtIn.collection.sortDirection.storageKey,
                    resultLimit = builtIn.collection.limit,
                    isBuiltIn = true,
                    builtInKey = builtIn.key,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            dao.replaceRules(id, builtIn.collection.rules.toEntities(id))
        }
    }

    // --- execution ---------------------------------------------------------------------------------------

    suspend fun execute(collectionId: Long, now: Long = System.currentTimeMillis()): SmartCollectionResult? {
        val collection = getById(collectionId) ?: return null
        return execute(collection, now)
    }

    suspend fun execute(collection: SmartCollection, now: Long = System.currentTimeMillis()): SmartCollectionResult =
        engine.evaluate(collection, candidates(), now)

    /**
     * Every playable track, with the fields a rule can address.
     *
     * One query. The probable-duplicate flag is the only derived field, and it is derived here from
     * the same [DuplicateDetector] the Maintenance diagnostic uses, so a rule against it and the
     * diagnostic's answer cannot disagree.
     */
    /**
     * Why every collection does or does not contain [libraryItemId]. Maintenance-only.
     *
     * Read-only, like the rest of the diagnostics: it runs `SELECT`s and evaluates them in memory,
     * and writes nothing.
     *
     * Evaluates the whole library to answer for one track, because that is what a collection *is* —
     * a query over the library, not a property of a track. The alternative, deciding membership per
     * track by some cheaper route, would be a second implementation of the rule engine and would
     * eventually answer differently from the real one. It runs on demand from Maintenance, never on
     * a playback or browsing path.
     */
    suspend fun diagnose(
        libraryItemId: String,
        now: Long = System.currentTimeMillis(),
    ): CollectionDiagnostic {
        val all = getAll()
        val candidate = candidates().firstOrNull { it.libraryItemId == libraryItemId }
            ?: return CollectionDiagnostic(isActiveCandidate = false, explanations = emptyList())

        val explanations = all.map { collection ->
            // Membership is the engine's own verdict for a one-candidate library, not a reading of
            // the per-rule results below. Under ALL an UNKNOWN is not a pass, and under ANY it is
            // not a failure, and restating that rule here is how the two would drift apart.
            val decision = engine.evaluate(collection, listOf(candidate), now)
            CollectionExplanation(
                collectionId = collection.id,
                name = collection.name,
                isBuiltIn = collection.isBuiltIn,
                matchMode = collection.matchMode,
                ruleCount = collection.rules.size,
                isMember = decision.matchedCount > 0,
                needsAttention = decision.needsAttention,
                evaluations = engine.explain(collection, candidate, now),
            )
        }
        return CollectionDiagnostic(isActiveCandidate = true, explanations = explanations)
    }

    suspend fun candidates(): List<CollectionCandidate> {
        val rows = candidateDao.allCandidates()
        // What was listened to is the ledger's: plays are the per-occurrence plays Stats shows, and unattributed history credits nobody.
        val ledger = ledgers.ledger()

        // Grouped over the already-read rows rather than re-querying: duplicate detection needs
        // title, artist and duration, all of which are in hand.
        val duplicateIdentities = duplicateDetector
            .findGroups(rows.map { it.toDuplicateSignals() })
            .flatMap { group -> group.tracks.map { it.libraryItemId } }
            .toSet()

        return rows.map { row ->
            row.toCandidate(row.libraryItemId in duplicateIdentities, ledger.stats(row.libraryItemId))
        }
    }
}

/**
 * The duplicate detector's input, built from a row already in hand.
 *
 * Only title, artist and duration are consulted by the detector; the rest are filled with the
 * values the row carries so nothing has to be re-read. Using the shared detector rather than a
 * second comparison here is what keeps a `probable duplicate` rule and the Maintenance diagnostic
 * from ever disagreeing about the same track.
 */
private fun CollectionCandidateRow.toDuplicateSignals() = DuplicateCandidate(
    libraryItemId = libraryItemId,
    title = title.orEmpty(),
    artist = trackArtist.orEmpty(),
    albumId = 0L,
    durationMs = durationMs,
)

/**
 * Row to candidate, resolving every text field.
 *
 * `genres_json` is preferred over the raw tag because it is the parsed form; the raw tag is the
 * fallback for a row written before Phase 12.3 or one whose scan has not yet filled it, so a rule
 * about genre works on an install that has not been rescanned.
 */
private fun CollectionCandidateRow.toCandidate(isProbableDuplicate: Boolean, stats: ItemStats?) = CollectionCandidate(
    libraryItemId = libraryItemId,
    locator = locator,
    title = MetadataNormalizer.normalizeForDisplay(title),
    trackArtist = MetadataNormalizer.normalizeForDisplay(trackArtist),
    albumArtist = MetadataNormalizer.normalizeForDisplay(albumArtist),
    album = MetadataNormalizer.normalizeForDisplay(album),
    genres = genresJson?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
        .ifEmpty { MetadataNormalizer.parseGenres(legacyGenre) },
    playCount = stats?.plays ?: 0,
    completionCount = stats?.completions ?: 0,
    skipCount = stats?.skips ?: 0,
    durationMs = durationMs,
    trackNumber = trackNumber.takeIf { it > 0 },
    discNumber = discNumber?.takeIf { it > 0 },
    releaseYear = MetadataNormalizer.parseYear(releaseYear),
    bitrate = bitrate,
    sampleRate = sampleRate,
    firstSeenAt = firstSeenAt,
    lastPlayedAt = stats?.lastPlayMs ?: 0L,
    lastCompletedAt = stats?.lastCompletionMs ?: 0L,
    lastSkippedAt = stats?.lastSkipMs ?: 0L,
    isFavourite = isFavourite,
    isCompilation = isCompilation,
    isProbableDuplicate = isProbableDuplicate,
    albumGroupingKey = albumGroupingKey,
)

private fun List<SmartCollectionRule>.toEntities(collectionId: Long): List<SmartCollectionRuleEntity> =
    mapIndexed { index, rule ->
        SmartCollectionRuleEntity(
            collectionId = collectionId,
            position = index,
            field = rule.target.storageKey,
            operator = rule.operation.storageKey,
            valueText = rule.textValue,
            valueNumber = rule.numberValue,
            valueSecondNumber = rule.secondNumberValue,
        )
    }
