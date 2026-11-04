package com.lostf1sh.pixelplayeross.presentation.model

import com.lostf1sh.pixelplayeross.data.collections.auto.suggestionPreview

/** Never borrow the Daily Mix for Suggestions. Only the no-mix library preview can be a fallback. */
internal fun <T> homeMixSource(curated: List<T>, daily: List<T>, preview: List<T>): List<T> = when {
    curated.isNotEmpty() -> curated
    daily.isNotEmpty() -> emptyList()
    else -> preview
}

internal fun <T> homeSuggestionPreview(source: List<T>, saved: List<T>, dap: Boolean, seed: Long, previous: List<T> = emptyList()): List<T> =
    if(dap) saved.filter { it in source } else suggestionPreview(source,seed,previous)

/** Kept by the light Home ViewModel, so rotation is not an app open. No database work. */
internal class SongSuggestionPicker {
    private var lastKey: Pair<List<String>,Long>? = null
    private var chosen=emptyList<String>()
    fun pick(songs: List<com.lostf1sh.pixelplayeross.data.model.Song>, seed: Long): List<com.lostf1sh.pixelplayeross.data.model.Song> {
        val ids=songs.map { it.id }
        val key=ids to seed
        if(lastKey != key) {
            chosen=suggestionPreview(ids,seed,if(lastKey?.second != seed) chosen else emptyList())
            lastKey=key
        }
        val byId=songs.associateBy { it.id }
        return chosen.mapNotNull(byId::get)
    }
}

/** The skeleton follows data readiness, never a presentation timer. */
internal fun homeContentLoaded(hasGenerated:Boolean,historyLoaded:Boolean,fallbackNeeded:Boolean,fallbackLoaded:Boolean):Boolean =
    hasGenerated && historyLoaded && (!fallbackNeeded || fallbackLoaded)
