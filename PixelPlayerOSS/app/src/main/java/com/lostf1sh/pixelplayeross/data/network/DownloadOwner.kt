package com.lostf1sh.pixelplayeross.data.network

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject

class DownloadOwner(val id: String, val journal: (String) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DownloadOwner>
}

data class DownloadAsset(val id: String, val file: String? = null, val songId: String? = null, val libraryItemId: String? = null)

/** Append-only ownership events, shared by page visits and jobs. Legacy files have a conservative claim until explicitly deleted. */
class DownloadAssetRegistry(private val file: File) {
    private class Entry(var asset: DownloadAsset, val owners: MutableSet<String>)
    private class State { val entries = linkedMapOf<String, Entry>(); var loaded = false }
    private val state = states.computeIfAbsent(file.absolutePath) { State() }
    private fun load() {
        if (state.loaded && file.exists()) return
        state.loaded = true; state.entries.clear()
        file.takeIf { it.isFile }?.useLines { lines -> lines.forEach { line ->
            runCatching {
                val json = JSONObject(line); val id = json.getString("id")
                if (json.optBoolean("removed")) state.entries.remove(id)
                else {
                    val owners = json.getJSONArray("owners")
                    state.entries[id] = Entry(DownloadAsset(id, json.optString("file").takeIf { it.isNotBlank() },
                        json.optString("song").takeIf { it.isNotBlank() }, json.optString("identity").takeIf { it.isNotBlank() }),
                        (0 until owners.length()).map { owners.getString(it) }.toMutableSet())
                }
            }
        } }
    }
    private fun append(asset: DownloadAsset, owners: Set<String>) {
        file.parentFile?.mkdirs()
        file.appendText(JSONObject().put("id", asset.id).put("file", asset.file).put("song", asset.songId)
            .put("identity", asset.libraryItemId).put("owners", JSONArray(owners)).toString() + "\n")
    }
    fun claim(asset: DownloadAsset, owner: String, newlyWritten: Boolean) = synchronized(state) {
        load()
        val previous = state.entries[asset.id]
        val entry = previous ?: Entry(asset, mutableSetOf<String>().apply { if (!newlyWritten) add("legacy") }).also { state.entries[asset.id] = it }
        val changed = entry.owners.add(owner) || entry.asset != asset
        entry.asset = asset
        if (previous == null || changed) append(asset, entry.owners)
    }
    suspend fun note(asset: DownloadAsset, newlyWritten: Boolean) {
        val owner = currentCoroutineContext()[DownloadOwner]
        claim(asset, owner?.id ?: "page", newlyWritten)
        owner?.journal?.invoke(asset.id)
    }
    fun adopt(id: String, owner: String) = synchronized(state) {
        load()
        state.entries[id]?.let { entry -> if (entry.owners.add(owner)) append(entry.asset, entry.owners) }
    }
    fun release(owner: String, prefix: String): List<DownloadAsset> = synchronized(state) {
        load()
        val removed = mutableListOf<DownloadAsset>()
        state.entries.values.filter { it.asset.id.startsWith(prefix) && owner in it.owners }.toList().forEach { entry ->
            entry.owners.remove(owner)
            if (entry.owners.isEmpty()) { removed += entry.asset; forget(entry.asset.id) }
            else append(entry.asset, entry.owners)
        }
        removed
    }
    fun forgetPrefix(prefix: String) = synchronized(state) {
        load()
        state.entries.keys.filter { it.startsWith(prefix) }.toList().forEach(::forget)
    }
    fun forget(id: String) = synchronized(state) {
        load(); state.entries.remove(id)
        file.parentFile?.mkdirs(); file.appendText(JSONObject().put("id", id).put("removed", true).toString() + "\n")
    }
    companion object { private val states = ConcurrentHashMap<String, State>() }
}
