package com.lostf1sh.pixelplayeross.data.offline

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "info_scope_state")
data class InfoScopeState(@PrimaryKey val scope: String, val findRequested: Boolean = false, val defaultChosen: Boolean = false)

@Entity(tableName = "info_undo")
data class InfoUndo(@PrimaryKey val token: String, val action: String, val untilAt: Long, val count: Int, val knownBytes: Long, val failed: Boolean = false)

@Entity(tableName = "info_undo_items", primaryKeys = ["token", "key"])
data class InfoUndoItem(
    val token: String, val key: String, val kind: String, val title: String, val artist: String, val songId: String,
    val state: String, val selected: Boolean, val saved: Boolean, val attempts: Int, val retryAt: Long,
    val epoch: Long, val deleted: Boolean,
)

enum class InfoCheckState { EMPTY, PARTIAL, CHECKED }

data class InfoSourceRetry(val source: String, val reason: String, val retryAt: Long)
data class InfoWaitState(
    val network: InfoNetworkState = InfoNetworkState.READY,
    val sources: List<com.lostf1sh.pixelplayeross.data.network.SourceWait> = emptyList(),
    val nowMillis: Long = System.currentTimeMillis(),
)

data class InfoGroupCounts(val eligible: Int = 0, val chosen: Int = 0) {
    val checkState: InfoCheckState get() = when {
        chosen == 0 -> InfoCheckState.EMPTY
        chosen == eligible -> InfoCheckState.CHECKED
        else -> InfoCheckState.PARTIAL
    }
}

data class InfoTypeCounts(
    val kind: InfoKind, val total: Int, val available: Int, val saved: Int, val queued: Int, val retrying: Int,
    val chosen: Int, val chosenSaved: Int, val knownBytes: Long, val unknownSizes: Int, val group: InfoGroupCounts,
    val local: Int, val missing: Int, val failed: Int, val unchecked: Int,
    val availableBytes: Long = 0, val availableUnknownSizes: Int = 0, val savedBytes: Long = 0,
    val newItems: Int = available + queued + retrying, val chosenNew: Int = chosen,
    /** B56-3.1: lyrics on the phone with no timing and no timed version at LRCLIB (those with one count in [available]). */
    val localPlain: Int = 0,
)

/** Every checkbox comes from the same persisted rows, including choices hidden by search or collapse. */
fun infoGroupCounts(rows: List<InfoChoice>): InfoGroupCounts {
    val eligible = rows.filter { it.selected || it.saved || it.availability == "AVAILABLE" || it.downloadState in listOf("QUEUED", "DOWNLOADING", "RETRY") }
    return InfoGroupCounts(eligible.size, eligible.count { it.selected })
}

fun infoTypeCounts(rows: List<InfoDiscovery>): List<InfoTypeCounts> = InfoKind.entries.map { kind ->
    val members = rows.filter { it.kind == kind.name }
    val available = members.filter { !it.saved && it.availability == "AVAILABLE" && it.downloadState == "IDLE" }
    val chosen = members.filter { it.selected && !it.saved }
    InfoTypeCounts(kind, members.size, available.size, members.count { it.saved },
        members.count { it.downloadState in listOf("QUEUED", "DOWNLOADING") }, members.count { it.downloadState == "RETRY" },
        chosen.size, members.count { it.selected && it.saved }, chosen.sumOf { it.sourceBytes ?: 0 }, chosen.count { it.sourceBytes == null },
        InfoGroupCounts(members.count { it.selected || it.saved || it.availability == "AVAILABLE" || it.downloadState in listOf("QUEUED", "DOWNLOADING", "RETRY") },
            members.count { it.selected }), members.count { it.availability == "LOCAL" },
        members.count { it.availability == "MISSING" }, members.count { it.availability == "FAILED" }, members.count { it.availability == "UNCHECKED" },
        available.sumOf { it.sourceBytes ?: 0 }, available.count { it.sourceBytes == null }, members.filter { it.saved }.sumOf { it.sourceBytes ?: 0 },
        localPlain = members.count { it.availability == MusicInfoDownloads.LOCAL_PLAIN })
}
