package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-010: the debug build is the daily driver, and it opened the database with
 * `fallbackToDestructiveMigration(dropAllTables = true)`. Installing an older debug APK (the workspace
 * keeps several, with rollback scripts) or shipping an entity without a migration silently dropped
 * every table — playlists, favourites, statistics, lyrics. The database must fail loudly instead, in
 * every build, exactly as release does.
 *
 * Source-level: the builder lives in a Hilt module that needs a Context to run.
 */
class NoDestructiveMigrationBoundaryTest {

    private val module = File("src/main/java/com/lostf1sh/pixelplayeross/di/AppModule.kt").readText()

    @Test
    fun `no build opens the database with a destructive migration fallback`() {
        assertThat(module).doesNotContain("fallbackToDestructiveMigration")
    }
}
