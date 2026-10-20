package com.lostf1sh.pixelplayeross.data.identity

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lostf1sh.pixelplayeross.data.database.MIGRATION_10_11
import com.lostf1sh.pixelplayeross.data.database.MIGRATION_11_12
import com.lostf1sh.pixelplayeross.data.database.MIGRATION_12_13
import com.lostf1sh.pixelplayeross.data.database.MIGRATION_6_7
import com.lostf1sh.pixelplayeross.data.database.MIGRATION_7_8
import com.lostf1sh.pixelplayeross.data.database.MIGRATION_8_9
import com.lostf1sh.pixelplayeross.data.database.MIGRATION_9_10
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migration an existing user actually goes through, against a real database.
 *
 * This is the proof that was deferred six times, and the reason it matters is simple: `MIGRATION_9_10`
 * is the step that gives every existing user a durable identity and moves their favourites, lyrics,
 * playlist membership and bookmarks onto it. If it is wrong, it is wrong **on upgrade** — on data
 * that no fresh install and no in-memory test ever contains. Nothing else in this phase can be
 * validated on a database that has never been through it.
 *
 * So this test builds a genuine pre-upgrade database: artist and album parents, three songs with
 * identities and fingerprints, a favourite, lyrics, a playlist with membership, an audio bookmark,
 * engagement history (one row identity-keyed, one still locator-only), and a user-edited title.
 * Then it runs the real migration chain to the current schema, with validation enabled, and reads
 * every piece of that state back.
 *
 * Nothing here asserts against the schema JSON. It reads the columns, because "the migration runs"
 * and "the user's data is still there and correctly attributed" are different claims, and only the
 * second one is worth anything.
 */
@RunWith(AndroidJUnit4::class)
class MigrationProofRoomTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PixelPlayerDatabase::class.java,
    )

    private companion object {
        const val DB = "migration-proof.db"

        /**
         * The pre-upgrade schema. Deliberately a *real* earlier version rather than the current one
         * with columns removed, so the migration runs against the shape it was written for.
         */
        const val START_VERSION = 9

        /**
         * The version Room is compiled against. Bump this with `PixelPlayerDatabase.version`; the
         * migration chain passed to [MigrationTestHelper.runMigrationsAndValidate] is checked against
         * it, so a new migration that is not added here fails loudly instead of being skipped.
         */
        const val CURRENT_SCHEMA_VERSION = 13

        /** The identity `MIGRATION_8_9` would have minted for each locator. Opaque, not a locator. */
        const val L1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val L2 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val L3 = "cccccccccccccccccccccccccccccccc"

        const val FAVOURITE_TIMESTAMP = 1_700_000_000_000L
        const val LYRICS_L1 = "[00:01.00] first line"
        const val LYRICS_L2 = "[00:02.00] second line"
        const val PLAYLIST_ID = "playlist-1"
        const val BOOKMARK_TITLE = "Chapter One"
        const val EDITED_TITLE = "The Title I Chose"
        const val FILE_TITLE = "original file title"
    }

    // --- seeding -----------------------------------------------------------------------------------

    /** Seeds the database exactly as a pre-upgrade install would have it. */
    private fun seedPreMigrationDatabase(db: SupportSQLiteDatabase) {
        // Parents first: `songs` declares foreign keys to both.
        db.execSQL("INSERT INTO `artists` (`id`, `name`, `track_count`) VALUES (1, 'Artist One', 3)")
        db.execSQL(
            "INSERT INTO `albums` (`id`, `title`, `artist_name`, `artist_id`, `album_art_uri_string`, " +
                "`song_count`, `date_added`, `year`, `album_artist`) " +
                "VALUES (1, 'Album One', 'Artist One', 1, NULL, 3, 1000, 2024, 'Artist One')"
        )

        // Three library items. L1 carries a user-edited title; L2 and L3 are untouched.
        insertSong(db, locator = 11L, identity = L1, title = EDITED_TITLE, titleUserEdited = 1)
        insertSong(db, locator = 22L, identity = L2, title = "Second Song", titleUserEdited = 0)
        insertSong(db, locator = 33L, identity = L3, title = "Third Song", titleUserEdited = 0)

        db.execSQL(
            "INSERT INTO `favorites` (`songId`, `isFavorite`, `timestamp`) VALUES (11, 1, $FAVOURITE_TIMESTAMP)"
        )
        db.execSQL("INSERT INTO `lyrics` (`songId`, `content`, `isSynced`, `source`) VALUES (11, '$LYRICS_L1', 1, 'lrc')")
        db.execSQL("INSERT INTO `lyrics` (`songId`, `content`, `isSynced`, `source`) VALUES (22, '$LYRICS_L2', 0, NULL)")

        db.execSQL(
            "INSERT INTO `playlists` (`id`, `name`, `created_at`, `last_modified`, `is_queue_generated`, " +
                "`cover_image_uri`, `cover_color_argb`, `cover_icon_name`, `cover_shape_type`, " +
                "`cover_shape_detail_1`, `cover_shape_detail_2`, `cover_shape_detail_3`, " +
                "`cover_shape_detail_4`, `source`) " +
                "VALUES ('$PLAYLIST_ID', 'My Playlist', 1000, 2000, 0, NULL, NULL, NULL, NULL, 0, 0, 0, 0, 'LOCAL')"
        )
        db.execSQL("INSERT INTO `playlist_songs` (`playlist_id`, `song_id`, `sort_order`) VALUES ('$PLAYLIST_ID', '11', 0)")
        db.execSQL("INSERT INTO `playlist_songs` (`playlist_id`, `song_id`, `sort_order`) VALUES ('$PLAYLIST_ID', '22', 1)")

        db.execSQL(
            "INSERT INTO `audio_bookmarks` (`song_id`, `song_title`, `artist_name`, `album_art_uri`, " +
                "`title`, `timestamp_ms`, `created_time`) " +
                "VALUES ('11', 'Track One', 'Artist One', NULL, '$BOOKMARK_TITLE', 42000, 3000)"
        )

        // Engagement: one row already identity-keyed (the state after the previous phase's rekey),
        // one still locator-only — the shape a library is in if a rekey has not yet run.
        insertEngagement(db, songId = "11", stableKey = "fp-11", identity = L1, playCount = 7, completions = 5, skips = 2)
        insertEngagement(db, songId = "33", stableKey = "", identity = "", playCount = 3, completions = 1, skips = 1)
    }

    private fun insertSong(
        db: SupportSQLiteDatabase,
        locator: Long,
        identity: String,
        title: String,
        titleUserEdited: Int,
    ) {
        db.execSQL(
            "INSERT INTO `songs` (`id`, `title`, `artist_name`, `artist_id`, `album_artist`, " +
                "`album_artist_id`, `album_name`, `album_id`, `content_uri_string`, " +
                "`album_art_uri_string`, `duration`, `genre`, `file_path`, `parent_directory_path`, " +
                "`is_favorite`, `track_number`, `disc_number`, `year`, `date_added`, `source_type`, " +
                "`media_store_date_added`, `media_store_date_modified`, `title_user_edited`, " +
                "`artist_user_edited`, `album_user_edited`, `genre_user_edited`, `library_item_id`, " +
                "`stable_key`, `file_size`) " +
                "VALUES ($locator, '$title', 'Artist One', 1, 'Artist One', 1, 'Album One', 1, " +
                "'content://media/audio/$locator', NULL, 240000, 'Genre', '/music/$locator.flac', " +
                "'/music', 0, 1, 1, 2024, 1000, 0, 1000, 1000, $titleUserEdited, 0, 0, 0, " +
                "'$identity', 'fp-$locator', 8000000)"
        )
    }

    private fun insertEngagement(
        db: SupportSQLiteDatabase,
        songId: String,
        stableKey: String,
        identity: String,
        playCount: Long,
        completions: Long,
        skips: Long,
    ) {
        db.execSQL(
            "INSERT INTO `song_engagements` (`song_id`, `stable_key`, `library_item_id`, `play_count`, " +
                "`total_play_duration_ms`, `completion_count`, `skip_count`, `first_played_at`, " +
                "`last_completed_at`, `last_skipped_at`, `last_played_timestamp`) " +
                "VALUES ('$songId', '$stableKey', '$identity', $playCount, 90000, $completions, $skips, " +
                "100, 200, 150, 250)"
        )
    }

    // --- readback helpers --------------------------------------------------------------------------

    private fun count(db: SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT COUNT(*) FROM `$table`").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun number(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }

    // --- §1: the real migration --------------------------------------------------------------------

    @Test
    fun migration_9_to_current_preserves_every_piece_of_user_state() {
        helper.createDatabase(DB, START_VERSION).use { db -> seedPreMigrationDatabase(db) }

        // Validation is on: the resulting schema must equal what Room expects at the current version
        // exactly, so a mismatch between this migration and the entities fails here rather than on a
        // user's device as "Room cannot verify the data integrity".
        val db = helper.runMigrationsAndValidate(
            DB,
            CURRENT_SCHEMA_VERSION,
            true,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
        )

        // --- durable identities are preserved, and none is replaced --------------------------------
        assertEquals(3L, count(db, "songs"))
        assertEquals(3L, count(db, "library_items"))
        assertEquals(L1, scalar(db, "SELECT library_item_id FROM songs WHERE id = 11"))
        assertEquals(L2, scalar(db, "SELECT library_item_id FROM songs WHERE id = 22"))
        assertEquals(L3, scalar(db, "SELECT library_item_id FROM songs WHERE id = 33"))
        assertEquals(3L, number(db, "SELECT COUNT(*) FROM library_items WHERE state = 'ACTIVE'"))
        assertEquals(11L, number(db, "SELECT current_locator FROM library_items WHERE library_item_id = '$L1'"))
        assertEquals(22L, number(db, "SELECT current_locator FROM library_items WHERE library_item_id = '$L2'"))
        // No identity is invented: every stored identity matches the song it was already on.
        assertEquals(0L, number(db, "SELECT COUNT(*) FROM library_items WHERE stable_key = ''"))

        // --- favourites are associated with the correct identity -----------------------------------
        assertEquals(1L, count(db, "favorites"))
        assertEquals(L1, scalar(db, "SELECT library_item_id FROM favorites WHERE songId = 11"))
        assertEquals(1L, number(db, "SELECT isFavorite FROM favorites WHERE songId = 11"))
        assertEquals(FAVOURITE_TIMESTAMP, number(db, "SELECT timestamp FROM favorites WHERE songId = 11"))

        // --- lyrics survive, on the right identity --------------------------------------------------
        assertEquals(2L, count(db, "lyrics"))
        assertEquals(L1, scalar(db, "SELECT library_item_id FROM lyrics WHERE songId = 11"))
        assertEquals(L2, scalar(db, "SELECT library_item_id FROM lyrics WHERE songId = 22"))
        assertEquals(LYRICS_L1, scalar(db, "SELECT content FROM lyrics WHERE songId = 11"))
        assertEquals(LYRICS_L2, scalar(db, "SELECT content FROM lyrics WHERE songId = 22"))

        // --- playlists survive, membership included --------------------------------------------------
        assertEquals(1L, count(db, "playlists"))
        assertEquals(2L, count(db, "playlist_songs"))
        assertEquals(
            L1,
            scalar(db, "SELECT library_item_id FROM playlist_songs WHERE playlist_id = '$PLAYLIST_ID' AND song_id = '11'"),
        )
        assertEquals(
            L2,
            scalar(db, "SELECT library_item_id FROM playlist_songs WHERE playlist_id = '$PLAYLIST_ID' AND song_id = '22'"),
        )

        // --- bookmarks survive ----------------------------------------------------------------------
        assertEquals(1L, count(db, "audio_bookmarks"))
        assertEquals(L1, scalar(db, "SELECT library_item_id FROM audio_bookmarks WHERE song_id = '11'"))
        assertEquals(BOOKMARK_TITLE, scalar(db, "SELECT title FROM audio_bookmarks WHERE song_id = '11'"))

        // --- engagement survives --------------------------------------------------------------------
        assertEquals(2L, count(db, "song_engagements"))
        assertEquals(7L, number(db, "SELECT play_count FROM song_engagements WHERE song_id = '11'"))
        assertEquals(5L, number(db, "SELECT completion_count FROM song_engagements WHERE song_id = '11'"))
        assertEquals(2L, number(db, "SELECT skip_count FROM song_engagements WHERE song_id = '11'"))
        assertEquals(L1, scalar(db, "SELECT library_item_id FROM song_engagements WHERE song_id = '11'"))
        assertEquals(3L, number(db, "SELECT play_count FROM song_engagements WHERE song_id = '33'"))

        // --- the user's edited metadata survives, on its durable owner -------------------------------
        assertEquals(1L, count(db, "library_item_overrides"))
        assertEquals(
            EDITED_TITLE,
            scalar(db, "SELECT title FROM library_item_overrides WHERE library_item_id = '$L1'"),
        )
        assertEquals(
            1L,
            number(db, "SELECT title_user_edited FROM library_item_overrides WHERE library_item_id = '$L1'"),
        )
        // "Not edited" has to read as *no opinion*: carrying the text across with the flag off would
        // pin a title the user never chose and freeze it against future tag changes.
        assertEquals(
            0L,
            number(db, "SELECT COUNT(*) FROM library_item_overrides WHERE title_user_edited = 0 AND title IS NOT NULL"),
        )
        // Items the user never edited get no row at all — an empty row claims an edit that did not happen.
        assertEquals(0L, number(db, "SELECT COUNT(*) FROM library_item_overrides WHERE library_item_id = '$L2'"))

        // --- Phase 12.3: the metadata columns exist and nothing was backfilled ---------------------
        // A grouping key is the output of a real algorithm, so it is computed by the next scan
        // rather than approximated in SQL. The columns must therefore be present, defaulted, and
        // empty — a non-empty value here would mean a key the next scan disagrees with, and the
        // library would silently re-group itself a second time.
        assertEquals(0L, number(db, "SELECT COUNT(*) FROM songs WHERE album_grouping_key <> ''"))
        assertEquals(0L, number(db, "SELECT COUNT(*) FROM songs WHERE artist_grouping_key <> ''"))
        assertEquals(0L, number(db, "SELECT COUNT(*) FROM songs WHERE is_compilation <> 0"))
        assertEquals(0L, number(db, "SELECT COUNT(*) FROM albums WHERE grouping_key <> ''"))

        // --- Phase 12.4: the collection tables exist and start empty ---------------------------------
        // New tables, so there is nothing to backfill and nothing to lose. Asserted present rather
        // than assumed: a migration that forgot to create them would fail only when a user first
        // opened the Smart Collections screen.
        assertEquals(0L, number(db, "SELECT COUNT(*) FROM smart_collections"))
        assertEquals(0L, number(db, "SELECT COUNT(*) FROM smart_collection_rules"))
        // And the user's state is still exactly where it was.
        assertEquals(1L, count(db, "favorites"))
        assertEquals(2L, count(db, "lyrics"))
        assertEquals(2L, count(db, "playlist_songs"))

        db.close()
    }

    @Test
    fun many_dormant_items_with_a_null_locator_are_valid_after_migration() {
        helper.createDatabase(DB, START_VERSION).use { db -> seedPreMigrationDatabase(db) }

        val db = helper.runMigrationsAndValidate(
            DB,
            CURRENT_SCHEMA_VERSION,
            true,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
        )

        // An ejected card makes every item dormant at once. A `-1` sentinel under the unique index
        // would have permitted exactly one — this is the shape that exposes it.
        db.execSQL("UPDATE `library_items` SET state = 'DORMANT', current_locator = NULL")

        assertEquals(3L, number(db, "SELECT COUNT(*) FROM library_items WHERE state = 'DORMANT'"))
        assertEquals(3L, number(db, "SELECT COUNT(*) FROM library_items WHERE current_locator IS NULL"))
        // Matching evidence is retained: discarding it would turn a temporary loss into a permanent one.
        assertEquals(0L, number(db, "SELECT COUNT(*) FROM library_items WHERE stable_key = ''"))
        // And user state is untouched by the transition itself.
        assertEquals(1L, count(db, "favorites"))
        assertEquals(2L, count(db, "lyrics"))
        assertEquals(2L, count(db, "playlist_songs"))
        assertEquals(1L, count(db, "audio_bookmarks"))
        assertEquals(2L, count(db, "song_engagements"))

        db.close()
    }

    @Test
    fun the_full_chain_from_an_old_schema_reaches_the_current_one_without_loss() {
        // A user who has not updated in a long time runs every migration in sequence. This is the
        // only test that exercises the *chain* rather than one step from a hand-built starting point,
        // and it is what catches a migration assuming a column an older schema does not have.
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL("INSERT INTO `artists` (`id`, `name`, `track_count`) VALUES (1, 'Artist One', 1)")
            db.execSQL(
                "INSERT INTO `albums` (`id`, `title`, `artist_name`, `artist_id`, `album_art_uri_string`, " +
                    "`song_count`, `date_added`, `year`) VALUES (1, 'Album One', 'Artist One', 1, NULL, 1, 1000, 2024)"
            )
            db.execSQL(
                "INSERT INTO `songs` (`id`, `title`, `artist_name`, `artist_id`, `album_name`, `album_id`, " +
                    "`content_uri_string`, `album_art_uri_string`, `duration`, `genre`, `file_path`, " +
                    "`parent_directory_path`, `is_favorite`, `track_number`, `disc_number`, `year`, " +
                    "`date_added`, `source_type`, `media_store_date_added`, `media_store_date_modified`) " +
                    "VALUES (11, 'Ancient Song', 'Artist One', 1, 'Album One', 1, " +
                    "'content://media/audio/11', NULL, 240000, 'Genre', '/music/11.flac', '/music', 1, 1, 1, " +
                    "2024, 1000, 0, 1000, 1000)"
            )
            db.execSQL("INSERT INTO `favorites` (`songId`, `isFavorite`, `timestamp`) VALUES (11, 1, 500)")
            db.execSQL("INSERT INTO `lyrics` (`songId`, `content`, `isSynced`, `source`) VALUES (11, 'old lyrics', 0, NULL)")
        }

        val migrated = helper.runMigrationsAndValidate(
            DB,
            CURRENT_SCHEMA_VERSION,
            true,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
        )

        assertEquals(1L, count(migrated, "songs"))
        assertEquals(1L, count(migrated, "library_items"))

        val identity = scalar(migrated, "SELECT library_item_id FROM songs WHERE id = 11")
        assertTrue("MIGRATION_8_9 must mint an identity", !identity.isNullOrEmpty())

        // `stable_key` is deliberately **empty** here, and that is the documented contract rather
        // than a gap: a key computed in SQL would have no file size to hash and would therefore not
        // match the key the next scan derives from the same file, leaving history attached to a key
        // that can never be reproduced. The column is filled by the scan.
        val fingerprint = scalar(migrated, "SELECT stable_key FROM songs WHERE id = 11")
        assertTrue("MIGRATION_7_8 must not invent an unreproducible fingerprint", fingerprint.isNullOrEmpty())

        // Every piece of user state ends up on that same identity.
        assertEquals(identity, scalar(migrated, "SELECT library_item_id FROM favorites WHERE songId = 11"))
        assertEquals(identity, scalar(migrated, "SELECT library_item_id FROM lyrics WHERE songId = 11"))
        assertEquals(identity, scalar(migrated, "SELECT library_item_id FROM library_items LIMIT 1"))
        assertEquals(1L, number(migrated, "SELECT isFavorite FROM favorites WHERE songId = 11"))
        assertEquals("old lyrics", scalar(migrated, "SELECT content FROM lyrics WHERE songId = 11"))

        migrated.close()
    }
}
