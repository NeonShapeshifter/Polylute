package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-073: `SONG_LIST_PROJECTION` had drifted from `SongEntity` — `genres_json`, `raw_track_number`
 * and `raw_disc_number` were missing (KSP warned on nine queries). Those `SongEntity`s came back with
 * defaults, so list consumers lost multi-genre, and any write-back of such an entity would have
 * erased the stored values.
 *
 * Every entity column must be in the list projection. `lyrics` is the one deliberate exception: it
 * is projected as `NULL AS lyrics` because lists never need the text.
 */
class SongListProjectionDriftTest {

    private val entity = File("src/main/java/com/lostf1sh/pixelplayeross/data/database/SongEntity.kt").readText()
    private val dao = File("src/main/java/com/lostf1sh/pixelplayeross/data/database/MusicDao.kt").readText()

    @Test
    fun `the list projection carries every SongEntity column`() {
        val columns = Regex("""@ColumnInfo\(name = "([a-z0-9_]+)"""").findAll(entity).map { it.groupValues[1] }.toSet()
        assertThat(columns).isNotEmpty()
        val projection = dao.substringAfter("private const val SONG_LIST_PROJECTION = \"\"\"").substringBefore("\"\"\"")
        val projected = Regex("""\b[a-z0-9_]+\b""").findAll(projection).map { it.value }.toSet()

        val missing = columns - projected
        assertThat(missing).isEmpty()
    }
}
