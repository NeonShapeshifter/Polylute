package com.lostf1sh.pixelplayeross.data.offline

import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Availability is independent of download state. A failed check never becomes an absence. */
enum class InfoKind { PHOTO, BIO, COVER, DESCRIPTION, LYRICS }

@Entity(tableName = "info_discovery", indices = [Index("artistKey"), Index(value = ["findPending", "priority"]), Index(value = ["downloadState", "retryAt"])])
data class InfoDiscovery(
    @PrimaryKey val key: String,
    val kind: String,
    val title: String,
    val artistKey: String,
    val artistName: String,
    val albumKey: String = "",
    val albumName: String = "",
    val songId: String = "",
    val searchText: String = "",
    val availability: String = "UNCHECKED",
    val payload: String? = null,
    val sourceBytes: Long? = null,
    val checkedAt: Long = 0,
    val saved: Boolean = false,
    val selected: Boolean = false,
    val findPending: Boolean = false,
    val priority: Int = 1,
    val downloadState: String = "IDLE",
    val attempts: Int = 0,
    val retryAt: Long = 0,
    val albumArtist: String = "",
    @ColumnInfo(defaultValue = "0") val downloadEpoch: Long = 0,
    @ColumnInfo(defaultValue = "''") val retrySource: String = "",
    @ColumnInfo(defaultValue = "''") val retryReason: String = "",
    @ColumnInfo(defaultValue = "0") val deletedByUser: Boolean = false,
)

@Entity(tableName = "info_scopes", primaryKeys = ["scope", "itemKey"], indices = [Index("itemKey")])
data class InfoScope(val scope: String, val itemKey: String)

@Entity(tableName = "info_artist_members", primaryKeys = ["scope", "artistKey", "itemKey"], indices = [Index("itemKey")])
data class InfoArtistMember(val scope: String, val artistKey: String, val artistName: String, val itemKey: String, val searchText: String)

@Entity(tableName = "info_queue")
data class InfoQueue(
    @PrimaryKey val id: Int = 1,
    val paused: Boolean = false,
    val currentArtist: String = "",
    val findMeasuredMs: Long = 0,
    val findMeasuredCount: Int = 0,
    val legacyDropped: Boolean = false,
)

data class InfoArtist(val artistKey: String, val artistName: String)

/** Updating catalog labels never replaces discovery, selection or queue state. */
data class InfoCatalogMetadata(
    val key: String,
    val title: String,
    val artistKey: String,
    val artistName: String,
    val albumKey: String,
    val albumName: String,
    val songId: String,
    val searchText: String,
    val albumArtist: String,
)

internal fun InfoDiscovery.catalogMetadata() = InfoCatalogMetadata(
    key, title, artistKey, artistName, albumKey, albumName, songId, searchText, albumArtist,
)

/** UI projections deliberately omit the resolved text and URLs. */
data class InfoChoice(
    val key: String,
    val kind: String,
    val title: String,
    val artistKey: String,
    val albumKey: String,
    val albumName: String,
    val availability: String,
    val sourceBytes: Long?,
    val saved: Boolean,
    val selected: Boolean,
    val downloadState: String,
    val retryAt: Long = 0,
    val retrySource: String = "",
    val retryReason: String = "",
    val deletedByUser: Boolean = false,
    val sharedWithOthers: Int = 0,
)

data class InfoProgress(val total: Int, val done: Int, val retry: Int, val pending: Int = 0)
data class InfoSelection(val downloads: Int, val deletes: Int, val knownBytes: Long?, val unknownSizes: Int, val pending: Int = 0)

@Dao
abstract class MusicInfoDiscoveryDao {
    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<(List<String>) -> Unit>()
    fun listenChanges(listener: (List<String>) -> Unit): () -> Unit {
        changeListeners.add(listener)
        return { changeListeners.remove(listener); Unit }
    }
    private fun changed(keys: List<String>) { if (keys.isNotEmpty()) changeListeners.forEach { it(keys) } }

    @Query("SELECT * FROM info_artist_members WHERE scope IN (:scope, 'credits:' || :scope)")
    abstract suspend fun scopeMembers(scope: String): List<InfoArtistMember>

    @Query("SELECT key, kind, title, artistKey, artistName, albumKey, albumName, songId, searchText, availability, NULL AS payload, sourceBytes, checkedAt, saved, selected, findPending, priority, downloadState, attempts, retryAt, albumArtist, downloadEpoch, retrySource, retryReason, deletedByUser FROM info_discovery WHERE key IN (:keys)")
    abstract suspend fun changedRows(keys: List<String>): List<InfoDiscovery>

    @Query("SELECT retrySource AS source, retryReason AS reason, MAX(retryAt) AS retryAt FROM info_discovery WHERE downloadState = 'RETRY' AND retrySource != '' GROUP BY retrySource, retryReason")
    abstract fun sourceRetries(): Flow<List<InfoSourceRetry>>

    @Query("SELECT d.key, d.kind, d.title, d.artistKey, d.artistName, d.albumKey, d.albumName, d.songId, d.searchText, d.availability, NULL AS payload, d.sourceBytes, d.checkedAt, d.saved, d.selected, d.findPending, d.priority, d.downloadState, d.attempts, d.retryAt, d.albumArtist, d.downloadEpoch, d.retrySource, d.retryReason, d.deletedByUser FROM info_discovery d JOIN info_scopes s ON s.itemKey = d.key WHERE s.scope = :scope")
    abstract fun observeScope(scope: String): Flow<List<InfoDiscovery>>

    @Query("SELECT COUNT(*) AS eligible, COALESCE(SUM(CASE WHEN d.selected = 1 THEN 1 ELSE 0 END), 0) AS chosen FROM info_discovery d JOIN info_artist_members m ON m.itemKey = d.key WHERE m.scope = :scope AND m.artistKey = :artist AND (:album IS NULL OR d.albumKey = :album) AND (d.selected = 1 OR d.saved = 1 OR d.availability = 'AVAILABLE' OR d.downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY'))")
    abstract fun groupCounts(scope: String, artist: String, album: String?): Flow<InfoGroupCounts>

    @Query("SELECT * FROM info_discovery")
    abstract suspend fun allRows(): List<InfoDiscovery>

    @Query("SELECT * FROM info_discovery WHERE downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY')")
    abstract suspend fun pendingDownloads(): List<InfoDiscovery>

    @Query("UPDATE info_discovery SET downloadState = 'DOWNLOADING' WHERE key = :key AND downloadEpoch = :epoch AND downloadState IN ('QUEUED', 'RETRY')")
    protected abstract suspend fun startDownloadSql(key: String, epoch: Long): Int
    @Query("SELECT key FROM info_discovery WHERE key = :key AND downloadEpoch = :epoch AND downloadState IN ('QUEUED', 'RETRY')")
    protected abstract suspend fun startDownloadKeys(key: String, epoch: Long): List<String>
    @Transaction
    open suspend fun startDownload(key: String, epoch: Long): Int {
        val keys = startDownloadKeys(key, epoch)
        val result = startDownloadSql(key, epoch)
        changed(keys)
        return result
    }

    @Query("UPDATE info_discovery SET downloadState = 'QUEUED', downloadEpoch = downloadEpoch + 1 WHERE downloadState = 'DOWNLOADING'")
    protected abstract suspend fun recoverInterruptedDownloadsSql()
    @Query("SELECT key FROM info_discovery WHERE downloadState = 'DOWNLOADING'")
    protected abstract suspend fun recoverInterruptedDownloadsKeys(): List<String>
    @Transaction
    open suspend fun recoverInterruptedDownloads() {
        val keys = recoverInterruptedDownloadsKeys()
        recoverInterruptedDownloadsSql()
        changed(keys)
    }

    @Query("SELECT EXISTS(SELECT 1 FROM info_discovery WHERE downloadState = 'DOWNLOADING')")
    abstract suspend fun hasInterruptedDownloads(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM info_scope_state WHERE scope = :scope)")
    abstract suspend fun hasScopeState(scope: String): Boolean

    @Query("UPDATE info_discovery SET downloadState = 'QUEUED' WHERE key = :key AND downloadEpoch = :epoch AND downloadState = 'DOWNLOADING'")
    protected abstract suspend fun interruptedDownloadSql(key: String, epoch: Long)
    @Query("SELECT key FROM info_discovery WHERE key = :key AND downloadEpoch = :epoch AND downloadState = 'DOWNLOADING'")
    protected abstract suspend fun interruptedDownloadKeys(key: String, epoch: Long): List<String>
    @Transaction
    open suspend fun interruptedDownload(key: String, epoch: Long) {
        val keys = interruptedDownloadKeys(key, epoch)
        interruptedDownloadSql(key, epoch)
        changed(keys)
    }

    @Query("UPDATE info_discovery SET saved = 0, selected = 0, downloadState = 'IDLE', attempts = 0, retryAt = 0, availability = 'AVAILABLE', downloadEpoch = downloadEpoch + 1 WHERE key = :key AND saved = 0 AND downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY')")
    protected abstract suspend fun cancelDownloadSql(key: String): Int
    @Query("SELECT key FROM info_discovery WHERE key = :key AND saved = 0 AND downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY')")
    protected abstract suspend fun cancelDownloadKeys(key: String): List<String>
    @Transaction
    open suspend fun cancelDownload(key: String): Int {
        val keys = cancelDownloadKeys(key)
        val result = cancelDownloadSql(key)
        changed(keys)
        return result
    }

    @Query("UPDATE info_discovery SET saved = 0, selected = 0, downloadState = 'IDLE', retryAt = 0, deletedByUser = 1, downloadEpoch = downloadEpoch + 1 WHERE key = :key")
    protected abstract suspend fun pendingDeleteSql(key: String)
    @Query("SELECT key FROM info_discovery WHERE key = :key")
    protected abstract suspend fun pendingDeleteKeys(key: String): List<String>
    @Transaction
    open suspend fun pendingDelete(key: String) {
        val keys = pendingDeleteKeys(key)
        pendingDeleteSql(key)
        changed(keys)
    }

    @Query("UPDATE info_discovery SET saved = :saved, selected = :selected, downloadState = :state, attempts = :attempts, retryAt = :retryAt, deletedByUser = :deleted WHERE key = :key AND downloadEpoch = :epoch AND downloadState = 'IDLE' AND saved = 0")
    protected abstract suspend fun restoreUndoItemSql(key: String, epoch: Long, saved: Boolean, selected: Boolean, state: String, attempts: Int, retryAt: Long, deleted: Boolean)
    @Query("SELECT key FROM info_discovery WHERE key = :key AND downloadEpoch = :epoch AND downloadState = 'IDLE' AND saved = 0")
    protected abstract suspend fun restoreUndoItemKeys(key: String, epoch: Long): List<String>
    @Transaction
    open suspend fun restoreUndoItem(key: String, epoch: Long, saved: Boolean, selected: Boolean, state: String, attempts: Int, retryAt: Long, deleted: Boolean) {
        val keys = restoreUndoItemKeys(key, epoch)
        restoreUndoItemSql(key, epoch, saved, selected, state, attempts, retryAt, deleted)
        changed(keys)
    }

    @Query("UPDATE info_discovery SET deletedByUser = :deleted WHERE key = :key AND (:epoch IS NULL OR downloadEpoch = :epoch)")
    protected abstract suspend fun deletedMarkSql(key: String, deleted: Boolean, epoch: Long?)
    @Query("SELECT key FROM info_discovery WHERE key = :key AND (:epoch IS NULL OR downloadEpoch = :epoch)")
    protected abstract suspend fun deletedMarkKeys(key: String, epoch: Long?): List<String>
    @Transaction
    open suspend fun deletedMark(key: String, deleted: Boolean, epoch: Long? = null) {
        val keys = deletedMarkKeys(key, epoch)
        deletedMarkSql(key, deleted, epoch)
        changed(keys)
    }

    @Query("UPDATE info_discovery SET deletedByUser = 0, downloadEpoch = downloadEpoch + 1 WHERE key = :key AND deletedByUser = 1")
    protected abstract suspend fun allowRefreshSql(key: String)
    @Query("SELECT key FROM info_discovery WHERE key = :key AND deletedByUser = 1")
    protected abstract suspend fun allowRefreshKeys(key: String): List<String>
    @Transaction
    open suspend fun allowRefresh(key: String) {
        val keys = allowRefreshKeys(key)
        allowRefreshSql(key)
        changed(keys)
    }

    @Query("UPDATE info_discovery SET downloadState = 'IDLE' WHERE downloadState = 'DONE' AND NOT EXISTS (SELECT 1 FROM info_discovery WHERE downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY'))")
    protected abstract suspend fun acknowledgeFinishedSql()
    @Query("SELECT key FROM info_discovery WHERE downloadState = 'DONE' AND NOT EXISTS (SELECT 1 FROM info_discovery WHERE downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY'))")
    protected abstract suspend fun acknowledgeFinishedKeys(): List<String>
    @Transaction
    open suspend fun acknowledgeFinished() {
        val keys = acknowledgeFinishedKeys()
        acknowledgeFinishedSql()
        changed(keys)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun initScopeState(state: InfoScopeState)

    @Query("UPDATE info_scope_state SET findRequested = 1 WHERE scope = :scope")
    abstract suspend fun requestedFind(scope: String)

    @Query("SELECT scope FROM info_scope_state WHERE findRequested = 1 AND defaultChosen = 0 AND NOT EXISTS (SELECT 1 FROM info_discovery d JOIN info_scopes s ON s.itemKey = d.key WHERE s.scope = info_scope_state.scope AND d.findPending = 1)")
    abstract suspend fun finishedFirstFinds(): List<String>

    @Query("UPDATE info_scope_state SET defaultChosen = 1 WHERE scope = :scope")
    abstract suspend fun defaultChosen(scope: String)

    @Query("UPDATE info_discovery SET selected = 1 WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope) AND availability = 'AVAILABLE' AND saved = 0 AND downloadState = 'IDLE' AND deletedByUser = 0")
    protected abstract suspend fun chooseAvailableByDefaultSql(scope: String)
    @Query("SELECT key FROM info_discovery WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope) AND availability = 'AVAILABLE' AND saved = 0 AND downloadState = 'IDLE' AND deletedByUser = 0")
    protected abstract suspend fun chooseAvailableByDefaultKeys(scope: String): List<String>
    @Transaction
    open suspend fun chooseAvailableByDefault(scope: String) {
        val keys = chooseAvailableByDefaultKeys(scope)
        chooseAvailableByDefaultSql(scope)
        changed(keys)
    }

    @Insert
    abstract suspend fun insertUndo(action: InfoUndo)

    @Insert
    abstract suspend fun insertUndoItems(items: List<InfoUndoItem>)

    /** Oldest first; the screen offers the last active action, including a new clear selection. */
    @Query("SELECT * FROM info_undo ORDER BY untilAt, rowid")
    abstract fun undoActions(): Flow<List<InfoUndo>>

    @Query("SELECT * FROM info_undo ORDER BY untilAt")
    abstract suspend fun pendingUndoActions(): List<InfoUndo>

    @Query("SELECT * FROM info_undo WHERE token = :token")
    abstract suspend fun undoAction(token: String): InfoUndo?

    @Query("UPDATE info_undo SET failed = 1 WHERE token = :token")
    abstract suspend fun undoFailed(token: String)

    @Query("SELECT * FROM info_undo_items WHERE token = :token")
    abstract suspend fun undoItems(token: String): List<InfoUndoItem>

    @Query("DELETE FROM info_undo WHERE token = :token")
    abstract suspend fun removeUndo(token: String)

    @Query("DELETE FROM info_undo_items WHERE token = :token")
    abstract suspend fun removeUndoItems(token: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(rows: List<InfoDiscovery>)

    @Update(entity = InfoDiscovery::class)
    abstract suspend fun updateCatalog(rows: List<InfoCatalogMetadata>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addScope(rows: List<InfoScope>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addArtistMembers(rows: List<InfoArtistMember>)

    @Query("DELETE FROM info_scopes WHERE scope = :scope")
    abstract suspend fun clearScopeAssets(scope: String)

    @Query("DELETE FROM info_artist_members WHERE scope = :scope")
    abstract suspend fun clearScopeArtists(scope: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun initQueue(queue: InfoQueue)

    @Query("SELECT EXISTS(SELECT 1 FROM info_scopes WHERE scope = :scope LIMIT 1)")
    abstract suspend fun hasScope(scope: String): Boolean

    @Query("SELECT * FROM info_queue WHERE id = 1")
    abstract fun queue(): Flow<InfoQueue?>

    @Query("SELECT * FROM info_queue WHERE id = 1")
    abstract suspend fun queueOnce(): InfoQueue?

    @Query("UPDATE info_queue SET legacyDropped = 1 WHERE id = 1")
    abstract suspend fun legacyDropped()

    @Query("UPDATE info_queue SET paused = :paused WHERE id = 1")
    abstract suspend fun pause(paused: Boolean)

    @Query("UPDATE info_queue SET currentArtist = :artist WHERE id = 1")
    abstract suspend fun currentArtist(artist: String)

    @Query("UPDATE info_queue SET findMeasuredMs = findMeasuredMs + :ms, findMeasuredCount = findMeasuredCount + 1 WHERE id = 1")
    abstract suspend fun measured(ms: Long)

    @Query("SELECT m.artistKey, MIN(m.artistName) AS artistName FROM info_discovery d INNER JOIN info_artist_members m ON m.itemKey = d.key WHERE m.scope = :scope AND m.searchText LIKE '%' || :search || '%' GROUP BY m.artistKey ORDER BY artistName COLLATE NOCASE")
    abstract fun artists(scope: String, search: String): PagingSource<Int, InfoArtist>

    @Query("SELECT d.key, d.kind, d.title, m.artistKey, d.albumKey, d.albumName, d.availability, d.sourceBytes, d.saved, d.selected, d.downloadState, d.retryAt, d.retrySource, d.retryReason, d.deletedByUser, (SELECT COUNT(DISTINCT other.artistKey) FROM info_artist_members other WHERE other.itemKey = d.key AND other.artistKey != m.artistKey AND other.scope IN (:scope, 'credits:' || :scope)) AS sharedWithOthers FROM info_discovery d INNER JOIN info_artist_members m ON m.itemKey = d.key WHERE m.scope = :scope AND m.artistKey = :artist ORDER BY d.albumKey, d.kind, d.title")
    abstract fun choices(scope: String, artist: String): Flow<List<InfoChoice>>

    @Query("SELECT * FROM info_discovery WHERE key = :key")
    abstract suspend fun get(key: String): InfoDiscovery?

    @Query("SELECT * FROM info_discovery WHERE findPending = 1 ORDER BY priority, artistKey, albumKey LIMIT 1")
    abstract suspend fun nextCheck(): InfoDiscovery?

    @Query("UPDATE info_discovery SET findPending = 1, priority = :priority WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope) AND (checkedAt < :olderThan OR availability IN ('UNCHECKED', 'FAILED'))")
    protected abstract suspend fun findSql(scope: String, olderThan: Long, priority: Int)
    @Query("SELECT key FROM info_discovery WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope) AND (checkedAt < :olderThan OR availability IN ('UNCHECKED', 'FAILED'))")
    protected abstract suspend fun findKeys(scope: String, olderThan: Long): List<String>
    @Transaction
    open suspend fun find(scope: String, olderThan: Long, priority: Int) {
        val keys = findKeys(scope, olderThan)
        findSql(scope, olderThan, priority)
        changed(keys)
    }

    @Query("UPDATE info_discovery SET priority = 0 WHERE findPending = 1 AND key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    protected abstract suspend fun prioritizeSql(scope: String)
    @Query("SELECT key FROM info_discovery WHERE findPending = 1 AND key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    protected abstract suspend fun prioritizeKeys(scope: String): List<String>
    @Transaction
    open suspend fun prioritize(scope: String) {
        val keys = prioritizeKeys(scope)
        prioritizeSql(scope)
        changed(keys)
    }

    @Query("UPDATE info_discovery SET availability = :availability, payload = :payload, sourceBytes = :bytes, checkedAt = :at, findPending = 0 WHERE key = :key")
    protected abstract suspend fun checkedSql(key: String, availability: String, payload: String?, bytes: Long?, at: Long)
    @Query("SELECT key FROM info_discovery WHERE key = :key")
    protected abstract suspend fun checkedKeys(key: String): List<String>
    @Transaction
    open suspend fun checked(key: String, availability: String, payload: String?, bytes: Long?, at: Long) {
        val keys = checkedKeys(key)
        checkedSql(key, availability, payload, bytes, at)
        changed(keys)
    }

    @Query("UPDATE info_discovery SET saved = :saved WHERE key = :key AND (:observedState IS NULL OR downloadState = :observedState)")
    protected abstract suspend fun savedSql(key: String, saved: Boolean, observedState: String?)
    @Query("SELECT key FROM info_discovery WHERE key = :key AND (:observedState IS NULL OR downloadState = :observedState)")
    protected abstract suspend fun savedKeys(key: String, observedState: String?): List<String>
    @Transaction
    open suspend fun saved(key: String, saved: Boolean, observedState: String? = null) {
        val keys = savedKeys(key, observedState)
        savedSql(key, saved, observedState)
        changed(keys)
    }

    @Query("SELECT key, kind, title, artistKey, artistName, albumKey, albumName, songId, searchText, availability, NULL AS payload, sourceBytes, checkedAt, saved, selected, findPending, priority, downloadState, attempts, retryAt, albumArtist, downloadEpoch, retrySource, retryReason, deletedByUser FROM info_discovery WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    abstract suspend fun scopeRows(scope: String): List<InfoDiscovery>

    @Query("UPDATE info_discovery SET selected = :selected WHERE key = :key")
    protected abstract suspend fun setSelectionSql(key: String, selected: Boolean)
    @Query("SELECT key FROM info_discovery WHERE key = :key")
    protected abstract suspend fun setSelectionKeys(key: String): List<String>
    @Transaction
    open suspend fun setSelection(key: String, selected: Boolean) {
        val keys = setSelectionKeys(key)
        setSelectionSql(key, selected)
        changed(keys)
    }

    /** One set update; pending generations are cancelled, running downloads can finish. */
    @Query("UPDATE info_discovery SET selected = CASE WHEN :selected = 0 THEN 0 WHEN saved = 1 OR availability = 'AVAILABLE' OR downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY') THEN 1 ELSE selected END, availability = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 'AVAILABLE' ELSE availability END, attempts = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 0 ELSE attempts END, retryAt = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 0 ELSE retryAt END, downloadEpoch = downloadEpoch + CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 1 ELSE 0 END, downloadState = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 'IDLE' ELSE downloadState END WHERE key = :key")
    protected abstract suspend fun selectSql(key: String, selected: Boolean)
    @Query("SELECT key FROM info_discovery WHERE key = :key")
    protected abstract suspend fun selectKeys(key: String): List<String>
    @Transaction
    open suspend fun select(key: String, selected: Boolean) {
        val keys = selectKeys(key)
        selectSql(key, selected)
        changed(keys)
    }

    @Query("UPDATE info_discovery SET selected = CASE WHEN :selected = 0 THEN 0 WHEN saved = 1 OR availability = 'AVAILABLE' OR downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY') THEN 1 ELSE selected END, availability = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 'AVAILABLE' ELSE availability END, attempts = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 0 ELSE attempts END, retryAt = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 0 ELSE retryAt END, downloadEpoch = downloadEpoch + CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 1 ELSE 0 END, downloadState = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 'IDLE' ELSE downloadState END WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope) AND kind = :kind")
    protected abstract suspend fun selectKindSql(scope: String, kind: String, selected: Boolean)
    @Query("SELECT key FROM info_discovery WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope) AND kind = :kind")
    protected abstract suspend fun selectKindKeys(scope: String, kind: String): List<String>
    @Transaction
    open suspend fun selectKind(scope: String, kind: String, selected: Boolean) {
        val keys = selectKindKeys(scope, kind)
        selectKindSql(scope, kind, selected)
        changed(keys)
    }

    @Query("UPDATE info_discovery SET selected = CASE WHEN :selected = 0 THEN 0 WHEN saved = 1 OR availability = 'AVAILABLE' OR downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY') THEN 1 ELSE selected END, availability = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 'AVAILABLE' ELSE availability END, attempts = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 0 ELSE attempts END, retryAt = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 0 ELSE retryAt END, downloadEpoch = downloadEpoch + CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 1 ELSE 0 END, downloadState = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 'IDLE' ELSE downloadState END WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope) AND kind = :kind AND saved = 0")
    protected abstract suspend fun selectAvailableKindSql(scope: String, kind: String, selected: Boolean)
    @Query("SELECT key FROM info_discovery WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope) AND kind = :kind AND saved = 0")
    protected abstract suspend fun selectAvailableKindKeys(scope: String, kind: String): List<String>
    @Transaction
    open suspend fun selectAvailableKind(scope: String, kind: String, selected: Boolean) {
        val keys = selectAvailableKindKeys(scope, kind)
        selectAvailableKindSql(scope, kind, selected)
        changed(keys)
    }

    @Query("UPDATE info_discovery SET selected = CASE WHEN :selected = 0 THEN 0 WHEN saved = 1 OR availability = 'AVAILABLE' OR downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY') THEN 1 ELSE selected END, availability = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 'AVAILABLE' ELSE availability END, attempts = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 0 ELSE attempts END, retryAt = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 0 ELSE retryAt END, downloadEpoch = downloadEpoch + CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 1 ELSE 0 END, downloadState = CASE WHEN :selected = 0 AND saved = 0 AND downloadState IN ('QUEUED', 'RETRY') THEN 'IDLE' ELSE downloadState END WHERE key IN (SELECT itemKey FROM info_artist_members WHERE scope = :scope AND artistKey = :artist) AND (:album IS NULL OR albumKey = :album)")
    protected abstract suspend fun selectGroupSql(scope: String, artist: String, album: String?, selected: Boolean)
    @Query("SELECT key FROM info_discovery WHERE key IN (SELECT itemKey FROM info_artist_members WHERE scope = :scope AND artistKey = :artist) AND (:album IS NULL OR albumKey = :album)")
    protected abstract suspend fun selectGroupKeys(scope: String, artist: String, album: String?): List<String>
    @Transaction
    open suspend fun selectGroup(scope: String, artist: String, album: String?, selected: Boolean) {
        val keys = selectGroupKeys(scope, artist, album)
        selectGroupSql(scope, artist, album, selected)
        changed(keys)
    }

    @Query("SELECT itemKey FROM info_artist_members WHERE scope = :scope AND artistKey = :artist AND (:album IS NULL OR itemKey IN (SELECT key FROM info_discovery WHERE albumKey = :album))")
    abstract suspend fun groupKeys(scope: String, artist: String, album: String?): List<String>

    /** Clear deliberately preserves the queue, as in B52-fix1. Undo is captured in SQL. */
    @Query("INSERT INTO info_undo_items (token, `key`, kind, title, artist, songId, state, selected, saved, attempts, retryAt, epoch, deleted) SELECT :token, `key`, kind, title, CASE WHEN albumArtist = '' THEN artistName ELSE albumArtist END, songId, downloadState, selected, saved, attempts, retryAt, downloadEpoch + 1, deletedByUser FROM info_discovery WHERE selected = 1 AND key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    abstract suspend fun captureClear(scope: String, token: String)

    @Query("UPDATE info_discovery SET selected = 0 WHERE selected = 1 AND key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    protected abstract suspend fun clearSelectionSql(scope: String)
    @Query("SELECT key FROM info_discovery WHERE selected = 1 AND key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    protected abstract suspend fun clearSelectionKeys(scope: String): List<String>
    @Transaction
    open suspend fun clearSelection(scope: String) {
        val keys = clearSelectionKeys(scope)
        clearSelectionSql(scope)
        changed(keys)
    }

    @Query("SELECT COUNT(*) AS downloads, 0 AS deletes, SUM(sourceBytes) AS knownBytes, 0 AS unknownSizes, 0 AS pending FROM info_discovery WHERE selected = 1 AND key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    abstract suspend fun clearTotals(scope: String): InfoSelection

    @Query("SELECT SUM(CASE WHEN saved = 0 AND selected = 1 THEN 1 ELSE 0 END) AS downloads, SUM(CASE WHEN saved = 1 AND selected = 1 THEN 1 ELSE 0 END) AS deletes, SUM(CASE WHEN selected = 1 AND saved = 0 THEN sourceBytes END) AS knownBytes, SUM(CASE WHEN selected = 1 AND saved = 0 AND sourceBytes IS NULL THEN 1 ELSE 0 END) AS unknownSizes, SUM(CASE WHEN selected = 1 AND saved = 0 AND downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY') THEN 1 ELSE 0 END) AS pending FROM info_discovery WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    abstract fun selection(scope: String): Flow<InfoSelection>

    @Query("SELECT COUNT(*) AS total, SUM(CASE WHEN findPending = 0 AND availability != 'UNCHECKED' THEN 1 ELSE 0 END) AS done, SUM(CASE WHEN availability = 'FAILED' THEN 1 ELSE 0 END) AS retry, SUM(CASE WHEN findPending = 1 THEN 1 ELSE 0 END) AS pending FROM info_discovery WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    abstract fun findProgress(scope: String): Flow<InfoProgress>

    @Query("UPDATE info_discovery SET downloadState = 'QUEUED', attempts = 0, retryAt = 0, deletedByUser = 0, downloadEpoch = downloadEpoch + 1 WHERE selected = 1 AND saved = 0 AND availability = 'AVAILABLE' AND downloadState NOT IN ('QUEUED', 'DOWNLOADING', 'RETRY') AND key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    protected abstract suspend fun appendSelectedSql(scope: String)
    @Query("SELECT key FROM info_discovery WHERE selected = 1 AND saved = 0 AND availability = 'AVAILABLE' AND downloadState NOT IN ('QUEUED', 'DOWNLOADING', 'RETRY') AND key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    protected abstract suspend fun appendSelectedKeys(scope: String): List<String>
    @Transaction
    open suspend fun appendSelected(scope: String) {
        val keys = appendSelectedKeys(scope)
        appendSelectedSql(scope)
        changed(keys)
    }

    @Query("SELECT COUNT(*) AS total, SUM(CASE WHEN downloadState = 'DONE' THEN 1 ELSE 0 END) AS done, SUM(CASE WHEN downloadState = 'RETRY' THEN 1 ELSE 0 END) AS retry, 0 AS pending FROM info_discovery WHERE downloadState != 'IDLE'")
    abstract fun downloadProgress(): Flow<InfoProgress>

    @Query("SELECT * FROM info_discovery WHERE downloadState IN ('QUEUED', 'RETRY') AND retryAt <= :now ORDER BY artistKey, albumKey LIMIT 1")
    abstract suspend fun nextDownload(now: Long): InfoDiscovery?

    @Query("SELECT MIN(retryAt) FROM info_discovery WHERE downloadState IN ('QUEUED', 'RETRY')")
    abstract suspend fun nextRetry(): Long?

    @Query("UPDATE info_discovery SET downloadState = 'DONE', saved = 1, selected = 0, attempts = 0, retryAt = 0 WHERE key = :key AND downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY') AND (:epoch IS NULL OR downloadEpoch = :epoch)")
    protected abstract suspend fun downloadedSql(key: String, epoch: Long?)
    @Query("SELECT key FROM info_discovery WHERE key = :key AND downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY') AND (:epoch IS NULL OR downloadEpoch = :epoch)")
    protected abstract suspend fun downloadedKeys(key: String, epoch: Long?): List<String>
    @Transaction
    open suspend fun downloaded(key: String, epoch: Long? = null) {
        val keys = downloadedKeys(key, epoch)
        downloadedSql(key, epoch)
        changed(keys)
    }

    @Query("UPDATE info_discovery SET downloadState = 'RETRY', attempts = :attempts, retryAt = :at, retrySource = :source, retryReason = :reason WHERE key = :key AND downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY') AND (:epoch IS NULL OR downloadEpoch = :epoch)")
    protected abstract suspend fun retrySql(key: String, attempts: Int, at: Long, epoch: Long?, source: String, reason: String)
    @Query("SELECT key FROM info_discovery WHERE key = :key AND downloadState IN ('QUEUED', 'DOWNLOADING', 'RETRY') AND (:epoch IS NULL OR downloadEpoch = :epoch)")
    protected abstract suspend fun retryKeys(key: String, epoch: Long?): List<String>
    @Transaction
    open suspend fun retry(key: String, attempts: Int, at: Long, epoch: Long? = null, source: String = "", reason: String = "") {
        val keys = retryKeys(key, epoch)
        retrySql(key, attempts, at, epoch, source, reason)
        changed(keys)
    }

    @Query("UPDATE info_discovery SET saved = 0, selected = 0, downloadState = 'IDLE', retryAt = 0, deletedByUser = 1, downloadEpoch = downloadEpoch + 1, availability = CASE WHEN availability = 'UNCHECKED' THEN 'AVAILABLE' ELSE availability END WHERE key = :key")
    protected abstract suspend fun deletedSql(key: String)
    @Query("SELECT key FROM info_discovery WHERE key = :key")
    protected abstract suspend fun deletedKeys(key: String): List<String>
    @Transaction
    open suspend fun deleted(key: String) {
        val keys = deletedKeys(key)
        deletedSql(key)
        changed(keys)
    }

    @Query("SELECT * FROM info_discovery WHERE selected = 1 AND saved = 1 AND key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    abstract suspend fun selectedSaved(scope: String): List<InfoDiscovery>
}
