package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.model.LibraryTabId
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.utils.TextFold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Applied queries only. The field debounces edits; queue builders read the same applied query. */
class LibraryTabSearch {
    private val mutableQueries = MutableStateFlow<Map<LibraryTabId, String>>(emptyMap())
    val queries = mutableQueries.asStateFlow()
    fun query(tab: LibraryTabId): String = queries.value[tab].orEmpty()
    fun set(tab: LibraryTabId, query: String) { mutableQueries.update { it + (tab to query) } }
    fun clear() { mutableQueries.value = emptyMap() }
    fun matches(tab: LibraryTabId, vararg text: String): Boolean = matchesQuery(query(tab), *text)
    companion object {
        fun matchesQuery(query: String, vararg text: String): Boolean {
            val needle = TextFold.fold(query)
            return needle.isEmpty() || text.any { needle in TextFold.fold(it) }
        }
        fun songMatches(query: String, song: Song): Boolean = matchesQuery(query, song.title, song.displayArtist, song.album, song.path)
        fun <T> filter(rows: List<T>, query: String, text: (T) -> String): List<T> {
            val needle = TextFold.fold(query)
            return if (needle.isEmpty()) rows else rows.filter { needle in TextFold.fold(text(it)) }
        }
    }
}
