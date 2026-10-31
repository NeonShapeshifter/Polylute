package com.lostf1sh.pixelplayeross.data.offline

import com.lostf1sh.pixelplayeross.data.musicinfo.AboutKind
import com.lostf1sh.pixelplayeross.data.musicinfo.DownloadKeys
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal fun OfflineTarget.sharedKey(): String = when (kind) {
    OfflineKind.LYRICS -> "lyrics|$songId"
    OfflineKind.ARTIST_PHOTOS -> DownloadKeys.photo(title)
    OfflineKind.COVERS -> DownloadKeys.cover(title, albumArtist.orEmpty())
    OfflineKind.DESCRIPTIONS -> DownloadKeys.about(
        if (album) AboutKind.ALBUM else AboutKind.ARTIST,
        title,
        albumArtist,
    )
}

/** Each job retains its own append-only journal. The manifest changes ordering, never overwrites a paused job. */
internal class OfflineJobStore(private val dir: File, private val claims: com.lostf1sh.pixelplayeross.data.network.DownloadAssetRegistry? = null) {
    private val slots = linkedMapOf<String, OfflineBatchStore>()
    private val order = mutableListOf<String>()
    private var loaded = false
    private val events = MutableStateFlow<OfflineBatch?>(null)
    val state = events.asStateFlow()
    private val allEvents = MutableStateFlow<List<OfflineBatch>>(emptyList())
    val jobs = allEvents.asStateFlow()
    private fun slot(plan: String) = slots.getOrPut(plan) { OfflineBatchStore(File(dir, "jobs/$plan")) }
    private fun load() {
        if (loaded) return
        order.clear()
        val manifest = File(dir, "queue.json")
        if (manifest.isFile) {
            val ids = JSONObject(manifest.readText()).getJSONArray("order")
            repeat(ids.length()) { order += ids.getString(it) }
        } else {
            // Upgrade the single 1.1.0 job intact, including its journal and pause state. Keep the old files as a recovery copy.
            val legacy = OfflineBatchStore(dir).read()
            if (legacy != null) {
                val destination = File(dir, "jobs/${legacy.planId}").apply { mkdirs() }
                listOf("job.json", "progress.json", "results.jsonl").forEach { name ->
                    File(dir, name).takeIf { it.isFile }?.let { source ->
                        // No manifest means migration has not committed yet. Rebuild any partial
                        // copy from the untouched legacy journal, then publish it atomically.
                        val temporary = File(destination, "$name.tmp")
                        source.copyTo(temporary, overwrite = true)
                        check(temporary.renameTo(File(destination, name)))
                    }
                }
                order += legacy.planId
            }
            persist()
        }
        loaded = true
        publish()
    }
    private fun persist() {
        dir.mkdirs()
        val tmp = File(dir, "queue.json.tmp")
        tmp.writeText(JSONObject().put("order", JSONArray(order)).toString())
        check(tmp.renameTo(File(dir, "queue.json")))
    }
    private fun publish() {
        val all = order.mapNotNull { slot(it).read() }
        allEvents.value = all
        events.value = all.firstOrNull { it.status != OfflineStatus.COMPLETE } ?: all.firstOrNull()
    }
    @Synchronized fun read(): OfflineBatch? { load(); return events.value }
    @Synchronized fun all(): List<OfflineBatch> { load(); return allEvents.value }
    @Synchronized fun enqueue(batch: OfflineBatch) {
        load()
        read()?.takeIf { it.active }?.let { running ->
            slot(running.planId).start(running.copy(id = UUID.randomUUID().toString(), status = OfflineStatus.QUEUED), clearResults = false)
        }
        slot(batch.planId).start(batch, clearResults = true)
        val existing = order.flatMap { plan ->
            val store = slot(plan)
            val other = store.read()
            store.completedResults()
                .filterValues { !it.deletedByUser }
                .mapNotNull { (index, result) ->
                    other?.targets?.getOrNull(index)?.sharedKey()?.let { it to result }
                }
        }.toMap()
        batch.targets.forEachIndexed { index, target -> existing[target.sharedKey()]?.let { result ->
            slot(batch.planId).result(batch.id, index, result, allowInactive = true)
            result.cacheKeys.forEach { asset -> claims?.adopt(asset, batch.planId); slot(batch.planId).claim(asset) }
        } }
        order.add(0, batch.planId)
        persist(); publish()
    }
    @Synchronized fun replaceCurrent(batch: OfflineBatch, clearResults: Boolean) {
        load(); slot(batch.planId).start(batch, clearResults); publish()
    }
    @Synchronized fun update(id: String, change: (OfflineBatch) -> OfflineBatch): OfflineBatch? {
        load()
        val old = read()?.takeIf { it.id == id } ?: return null
        return slot(old.planId).update(id, change).also { publish() }
    }
    @Synchronized fun result(id: String, index: Int, result: OfflineResult): OfflineBatch? {
        load()
        val old = read()?.takeIf { it.id == id } ?: return null
        val recorded = slot(old.planId).result(id, index, result) ?: return null
        if (result.failed == 0) {
            val key = old.targets[index].sharedKey()
            order.filter { it != old.planId }.forEach { plan ->
                val store = slot(plan); val other = store.read() ?: return@forEach
                other.targets.forEachIndexed { i, t -> if (t.sharedKey() == key) {
                    store.result(other.id, i, result, allowInactive = true)
                    result.cacheKeys.forEach { asset -> claims?.adopt(asset, other.planId); store.claim(asset) }
                } }
            }
        }
        publish(); return recorded
    }
    @Synchronized fun results(): List<OfflineResult> { load(); return read()?.let { slot(it.planId).results() }.orEmpty() }
    @Synchronized fun claim(id: String, key: String) {
        load()
        all().firstOrNull { (it.id == id || it.planId == id) }?.let { slot(it.planId).claim(key) }
    }
    @Synchronized fun remove(plan: String): OfflineBatch? {
        load()
        val batch = slot(plan).read()?.takeIf { !it.active } ?: return null
        order.remove(plan); persist(); publish()
        File(dir, "jobs/$plan").deleteRecursively(); slots.remove(plan)
        return batch
    }
    @Synchronized fun invalidate(keys: Set<String>) {
        load()
        all().forEach { batch -> slot(batch.planId).invalidateTargets(keys) }
        publish()
    }
}
