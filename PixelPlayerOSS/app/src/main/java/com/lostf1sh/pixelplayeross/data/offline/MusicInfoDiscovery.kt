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
)

data class InfoProgress(val total: Int, val done: Int, val retry: Int, val pending: Int = 0)
data class InfoSelection(val downloads: Int, val deletes: Int, val knownBytes: Long?, val unknownSizes: Int)

@Dao
abstract class MusicInfoDiscoveryDao {
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

    @Query("SELECT d.key, d.kind, d.title, m.artistKey, d.albumKey, d.albumName, d.availability, d.sourceBytes, d.saved, d.selected, d.downloadState FROM info_discovery d INNER JOIN info_artist_members m ON m.itemKey = d.key WHERE m.scope = :scope AND m.artistKey = :artist ORDER BY d.albumKey, d.kind, d.title")
    abstract fun choices(scope: String, artist: String): Flow<List<InfoChoice>>

    @Query("SELECT * FROM info_discovery WHERE key = :key")
    abstract suspend fun get(key: String): InfoDiscovery?

    @Query("SELECT * FROM info_discovery WHERE findPending = 1 ORDER BY priority, artistKey, albumKey LIMIT 1")
    abstract suspend fun nextCheck(): InfoDiscovery?

    @Query("UPDATE info_discovery SET findPending = 1, priority = :priority WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope) AND (checkedAt < :olderThan OR availability IN ('UNCHECKED', 'FAILED'))")
    abstract suspend fun find(scope: String, olderThan: Long, priority: Int)

    @Query("UPDATE info_discovery SET priority = 0 WHERE findPending = 1 AND key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    abstract suspend fun prioritize(scope: String)

    @Query("UPDATE info_discovery SET availability = :availability, payload = :payload, sourceBytes = :bytes, checkedAt = :at, findPending = 0 WHERE key = :key")
    abstract suspend fun checked(key: String, availability: String, payload: String?, bytes: Long?, at: Long)

    @Query("UPDATE info_discovery SET saved = :saved WHERE key = :key AND (:observedState IS NULL OR downloadState = :observedState)")
    abstract suspend fun saved(key: String, saved: Boolean, observedState: String? = null)

    @Query("SELECT key, kind, title, artistKey, artistName, albumKey, albumName, songId, searchText, availability, NULL AS payload, sourceBytes, checkedAt, saved, selected, findPending, priority, downloadState, attempts, retryAt, albumArtist FROM info_discovery WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    abstract suspend fun scopeRows(scope: String): List<InfoDiscovery>

    @Query("UPDATE info_discovery SET selected = :selected WHERE key = :key AND (saved = 1 OR availability = 'AVAILABLE')")
    abstract suspend fun select(key: String, selected: Boolean)

    @Query("UPDATE info_discovery SET selected = :selected WHERE kind = :kind AND key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope) AND (saved = 1 OR availability = 'AVAILABLE')")
    abstract suspend fun selectKind(scope: String, kind: String, selected: Boolean)

    @Query("UPDATE info_discovery SET selected = :selected WHERE (:album IS NULL OR albumKey = :album) AND key IN (SELECT itemKey FROM info_artist_members WHERE scope = :scope AND artistKey = :artist) AND (saved = 1 OR availability = 'AVAILABLE')")
    abstract suspend fun selectGroup(scope: String, artist: String, album: String?, selected: Boolean)

    @Query("SELECT SUM(CASE WHEN saved = 0 AND selected = 1 THEN 1 ELSE 0 END) AS downloads, SUM(CASE WHEN saved = 1 AND selected = 1 THEN 1 ELSE 0 END) AS deletes, SUM(CASE WHEN selected = 1 AND saved = 0 THEN sourceBytes END) AS knownBytes, SUM(CASE WHEN selected = 1 AND saved = 0 AND sourceBytes IS NULL THEN 1 ELSE 0 END) AS unknownSizes FROM info_discovery WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    abstract fun selection(scope: String): Flow<InfoSelection>

    @Query("SELECT COUNT(*) AS total, SUM(CASE WHEN findPending = 0 AND availability != 'UNCHECKED' THEN 1 ELSE 0 END) AS done, SUM(CASE WHEN availability = 'FAILED' THEN 1 ELSE 0 END) AS retry, SUM(CASE WHEN findPending = 1 THEN 1 ELSE 0 END) AS pending FROM info_discovery WHERE key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    abstract fun findProgress(scope: String): Flow<InfoProgress>

    @Query("UPDATE info_discovery SET downloadState = 'QUEUED', attempts = 0, retryAt = 0, selected = 0 WHERE selected = 1 AND saved = 0 AND availability = 'AVAILABLE' AND downloadState NOT IN ('QUEUED', 'RETRY') AND key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    abstract suspend fun appendSelected(scope: String)

    @Query("SELECT COUNT(*) AS total, SUM(CASE WHEN downloadState = 'DONE' THEN 1 ELSE 0 END) AS done, SUM(CASE WHEN downloadState = 'RETRY' THEN 1 ELSE 0 END) AS retry, 0 AS pending FROM info_discovery WHERE downloadState != 'IDLE'")
    abstract fun downloadProgress(): Flow<InfoProgress>

    @Query("SELECT * FROM info_discovery WHERE downloadState IN ('QUEUED', 'RETRY') AND retryAt <= :now ORDER BY artistKey, albumKey LIMIT 1")
    abstract suspend fun nextDownload(now: Long): InfoDiscovery?

    @Query("SELECT MIN(retryAt) FROM info_discovery WHERE downloadState IN ('QUEUED', 'RETRY')")
    abstract suspend fun nextRetry(): Long?

    @Query("UPDATE info_discovery SET downloadState = 'DONE', saved = 1, attempts = 0, retryAt = 0 WHERE key = :key AND downloadState IN ('QUEUED', 'RETRY')")
    abstract suspend fun downloaded(key: String)

    @Query("UPDATE info_discovery SET downloadState = 'RETRY', attempts = :attempts, retryAt = :at WHERE key = :key AND downloadState IN ('QUEUED', 'RETRY')")
    abstract suspend fun retry(key: String, attempts: Int, at: Long)

    @Query("UPDATE info_discovery SET saved = 0, selected = 0, downloadState = 'IDLE', retryAt = 0, availability = CASE WHEN availability = 'UNCHECKED' THEN 'AVAILABLE' ELSE availability END WHERE key = :key")
    abstract suspend fun deleted(key: String)

    @Query("SELECT * FROM info_discovery WHERE selected = 1 AND saved = 1 AND key IN (SELECT itemKey FROM info_scopes WHERE scope = :scope)")
    abstract suspend fun selectedSaved(scope: String): List<InfoDiscovery>
}
