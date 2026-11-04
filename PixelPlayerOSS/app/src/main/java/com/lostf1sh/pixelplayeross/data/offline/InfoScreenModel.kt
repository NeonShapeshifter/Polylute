package com.lostf1sh.pixelplayeross.data.offline

/** A screen owns one payload-free snapshot. Only touched keys contribute to aggregate changes. */
internal class InfoScreenModel(rows: List<InfoDiscovery>, members: List<InfoArtistMember>, visibleScope: String = "library") {
    private val base = rows.associateBy { it.key }.toMutableMap()
    private val shown = base.toMutableMap()
    private val selected = rows.filter { it.selected }.mapTo(linkedSetOf()) { it.key }
    private val kinds = rows.groupBy { it.kind }.mapValues { (_, rows) -> rows.map { it.key } }
    private val visibleMembers = members.filter { it.scope == visibleScope }
    private val artists = visibleMembers.groupBy { it.artistKey }.mapValues { (_, rows) -> rows.map { it.itemKey }.distinct() }
    private val credits = members.groupBy { it.itemKey }.mapValues { (_, rows) -> rows.map { it.artistKey }.distinct().size - 1 }
    private val rowArtists = visibleMembers.groupBy { it.itemKey }.mapValues { (_, rows) -> rows.map { it.artistKey }.distinct() }
    private val groups = mutableMapOf<Pair<String, String?>, IntArray>()
    private val totals = InfoKind.entries.associateWith { LongArray(24) }
    private val pending = linkedMapOf<Long, Intent>()
    private var next = 0L
    data class Intent(val id: Long, val keys: List<String>, val selected: Boolean, val clear: Boolean = false) { val keySet = keys.toHashSet() }

    private val checkTimes = java.util.TreeMap<Long, Int>()
    init { rows.forEach {
        contribute(it, 1)
        checkTimes[it.checkedAt] = (checkTimes[it.checkedAt] ?: 0) + 1
    } }
    val size get() = shown.size
    val checkedAt: Long get() = checkTimes.lastEntry()?.key ?: 0L
    fun keys(kind: InfoKind): List<String> = kinds[kind.name].orEmpty().filter { shown[it]?.saved == false }
    fun keys(artist: String, album: String?): List<String> = artists[artist].orEmpty().filter { album == null || shown[it]?.albumKey == album }
    fun selectedKeys(): List<String> = selected.toList()
    fun groupCounts(artist: String, album: String?) = groups[artist to album]?.let { InfoGroupCounts(it[0], it[1]) } ?: InfoGroupCounts()
    fun row(key: String) = shown[key]
    fun rows(): List<InfoDiscovery> = shown.values.toList()
    fun artists(query: String): List<InfoArtist> {
        val search = infoSearch(query)
        return visibleMembers.asSequence().filter { search.isEmpty() || search in it.searchText }
            .distinctBy { it.artistKey }.map { InfoArtist(it.artistKey, it.artistName) }.toList()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artistName })
    }
    fun choices(artist: String): List<InfoChoice> = artists[artist].orEmpty().mapNotNull { shown[it] }.sortedWith(
        compareBy<InfoDiscovery> { it.albumKey }.thenBy { it.kind }.thenBy { it.title }
    ).map { r -> InfoChoice(r.key, r.kind, r.title, artist, r.albumKey, r.albumName, r.availability,
        r.sourceBytes, r.saved, r.selected, r.downloadState, r.retryAt, r.retrySource, r.retryReason,
        r.deletedByUser, credits[r.key] ?: 0) }

    fun choose(keys: List<String>, selected: Boolean, clear: Boolean = false): Intent {
        val intent = Intent(++next, keys, selected, clear)
        pending[intent.id] = intent
        keys.forEach { key -> shown[key]?.let { replace(key, apply(it, intent)) } }
        return intent
    }

    /** New worker state is rebased under all unacknowledged taps, in tap order. */
    fun merge(rows: List<InfoDiscovery>, finished: Long? = null) {
        rows.forEach { if (it.key in base) base[it.key] = it.copy(payload = null) }
        val affected = rows.mapTo(linkedSetOf()) { it.key }
        finished?.let { pending.remove(it)?.keys?.let(affected::addAll) }
        affected.forEach { key ->
            var value = base[key] ?: return@forEach
            pending.values.forEach { if (key in it.keySet) value = apply(value, it) }
            replace(key, value)
        }
    }
    private fun apply(row: InfoDiscovery, intent: Intent): InfoDiscovery {
        if (intent.clear) return row.copy(selected = false)
        if (intent.selected) return if (eligible(row)) row.copy(selected = true) else row
        return if (!row.saved && row.downloadState in listOf("QUEUED", "RETRY")) row.copy(
            selected = false, availability = "AVAILABLE", downloadState = "IDLE", attempts = 0,
            retryAt = 0, downloadEpoch = row.downloadEpoch + 1) else row.copy(selected = false)
    }
    private fun replace(key: String, value: InfoDiscovery) {
        val old = shown[key] ?: return
        if (old == value) return
        contribute(old, -1); contribute(value, 1); shown[key] = value
        if(value.selected) selected.add(key) else selected.remove(key)
        if (old.checkedAt != value.checkedAt) {
            val remaining = (checkTimes[old.checkedAt] ?: 1) - 1
            if (remaining == 0) checkTimes.remove(old.checkedAt) else checkTimes[old.checkedAt] = remaining
            checkTimes[value.checkedAt] = (checkTimes[value.checkedAt] ?: 0) + 1
        }
    }
    private fun eligible(r: InfoDiscovery) = r.selected || r.saved || r.availability == "AVAILABLE" || r.downloadState in listOf("QUEUED", "DOWNLOADING", "RETRY")
    private fun contribute(r: InfoDiscovery, sign: Int) {
        rowArtists[r.key].orEmpty().forEach { artist ->
            listOf(null, r.albumKey).forEach { album ->
                val group = groups.getOrPut(artist to album) { IntArray(2) }
                if (eligible(r)) group[0] += sign
                if (r.selected) group[1] += sign
            }
        }
        val c = totals[InfoKind.valueOf(r.kind)]!!
        fun add(i: Int, value: Long = 1) { c[i] += sign * value }
        val available = !r.saved && r.availability == "AVAILABLE" && r.downloadState == "IDLE"
        val chosen = r.selected && !r.saved
        val queued = r.downloadState in listOf("QUEUED", "DOWNLOADING")
        val retry = r.downloadState == "RETRY"
        add(0)
        if (available) { add(1); add(16, r.sourceBytes ?: 0); if(r.sourceBytes == null) add(17) }
        if (r.saved) { add(2); add(18, r.sourceBytes ?: 0) }
        if (queued) add(3)
        if (retry) add(4)
        if (chosen) { add(5); add(7, r.sourceBytes ?: 0); if(r.sourceBytes == null) add(8); if(queued || retry) add(19) }
        if (r.selected && r.saved) add(6)
        if (eligible(r)) add(9)
        if (r.selected) add(10)
        when(r.availability) { "LOCAL" -> add(11); "MISSING" -> add(12); "FAILED" -> add(13); "UNCHECKED" -> add(14); MusicInfoDownloads.LOCAL_PLAIN -> add(22) }
        if(r.findPending) add(20)
        if(!r.findPending && r.availability != "UNCHECKED") add(21)
    }
    fun types() = InfoKind.entries.map { kind ->
        val c = totals[kind]!!
        InfoTypeCounts(kind, c[0].toInt(), c[1].toInt(), c[2].toInt(), c[3].toInt(), c[4].toInt(),
            c[5].toInt(), c[6].toInt(), c[7], c[8].toInt(), InfoGroupCounts(c[9].toInt(),c[10].toInt()),
            c[11].toInt(),c[12].toInt(),c[13].toInt(),c[14].toInt(),c[16],c[17].toInt(),c[18], localPlain = c[22].toInt())
    }
    fun selection(): InfoSelection {
        fun sum(i: Int) = totals.values.sumOf { it[i] }
        return InfoSelection(sum(5).toInt(),sum(6).toInt(),sum(7).takeIf { sum(5) > sum(8) },sum(8).toInt(),sum(19).toInt())
    }
    fun progress() = InfoProgress(size, totals.values.sumOf { it[21] }.toInt(), totals.values.sumOf { it[13] }.toInt(), totals.values.sumOf { it[20] }.toInt())
}
