package com.lostf1sh.pixelplayeross.data.metadata

import android.content.SharedPreferences

/** Only negative tag-reader results are versioned; valid database years retain their priority. */
// KTX edit discards the commit result; durable invalidation must detect write failures.
@android.annotation.SuppressLint("UseKtx")
internal class YearRepairCache(private val prefs: SharedPreferences) {
    data class State(val noYear: MutableSet<String>, val failures: MutableMap<String, Int>)

    fun load(): State {
        if (prefs.getInt(KEY_VERSION, 0) < READER_VERSION) {
            // One atomic durable edit, before any reads. If interrupted before commit, retry the
            // migration; after commit, page checkpoints survive and startup does not reread all tags.
            check(prefs.edit().remove(KEY_NO_YEAR).remove(KEY_FAILURES)
                .putInt(KEY_VERSION, READER_VERSION).commit())
        }
        val failures = mutableMapOf<String, Int>()
        for (entry in prefs.getStringSet(KEY_FAILURES, emptySet()).orEmpty()) {
            val at = entry.lastIndexOf('|')
            val count = entry.substring(at + 1).toIntOrNull()
            if (at > 0 && count != null && count > 0) failures[entry.substring(0, at)] = count
        }
        return State(prefs.getStringSet(KEY_NO_YEAR, emptySet()).orEmpty().toMutableSet(), failures)
    }

    fun save(state: State) {
        check(prefs.edit()
            .putStringSet(KEY_NO_YEAR, HashSet(state.noYear))
            .putStringSet(KEY_FAILURES, state.failures.mapTo(HashSet()) { (key, count) -> "$key|$count" })
            .commit())
    }

    internal companion object {
        // v1 introduces the current DATE/YEAR/ORIGINALDATE/ORIGINALYEAR/RELEASEDATE reader and
        // separates confirmed absence from exhausted read failures. Unversioned negatives are ambiguous.
        // v2 (B15-F6) changes no reader: it only runs the repair once more on every install, so negatives written
        // under a marker of 1 by a build that is not in the history (a working-tree install between B12 and the
        // commit that introduced the marker, say) cannot stand. Only songs still without a year are read again, and
        // an install whose songs all have one reads nothing.
        const val READER_VERSION = 2
        const val KEY_VERSION = "reader_version"
        const val KEY_NO_YEAR = "no_year_tag"
        const val KEY_FAILURES = "read_failures"
    }
}
