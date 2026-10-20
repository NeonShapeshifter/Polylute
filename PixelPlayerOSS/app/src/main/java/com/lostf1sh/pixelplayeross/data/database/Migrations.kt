package com.lostf1sh.pixelplayeross.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Returns true if [table] already has a column named [column].
 *
 * Platform Auto Backup can restore a database that reports the right schema version but whose
 * columns have drifted, so migrations guard each `ALTER` with this check instead of assuming a
 * bare `ADD COLUMN` is safe (see the migration notes in CLAUDE.md / CONTRIBUTING).
 */
private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}

private fun SupportSQLiteDatabase.addColumnIfMissing(table: String, column: String, ddl: String) {
    if (!hasColumn(table, column)) {
        execSQL("ALTER TABLE `$table` ADD COLUMN $ddl")
    }
}

/**
 * v1 -> v2: album-artist support for the unified library (issue #8).
 *
 * - `songs.album_artist_id`: id of the *effective* album artist (the song's `album_artist` when
 *   present, otherwise its primary track artist). Source-independent, so the "Group by Album
 *   Artist" Artists tab can collapse on it at runtime without forcing a re-sync.
 * - `navidrome_songs.album_artist` / `jellyfin_songs.album_artist`: carry the server's
 *   album-artist tag through the cloud cache so the unified projection can populate the above.
 *
 * Additive and idempotent — each column is added only when missing. `album_artist_id` is then
 * backfilled to the existing primary artist so the collapsed Artists tab is populated before the
 * next library sync recomputes precise values (e.g. collapsing compilations under one artist).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing("songs", "album_artist_id", "`album_artist_id` INTEGER NOT NULL DEFAULT 0")
        db.addColumnIfMissing("navidrome_songs", "album_artist", "`album_artist` TEXT")
        db.addColumnIfMissing("jellyfin_songs", "album_artist", "`album_artist` TEXT")

        db.execSQL("UPDATE songs SET album_artist_id = artist_id WHERE album_artist_id = 0")

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_album_artist_id` ON `songs` (`album_artist_id`)")
    }
}

/**
 * v2 -> v3: opt-in ListenBrainz scrobbling + MusicBrainz identifier storage.
 *
 * - `listenbrainz_pending_listens`: offline scrobble queue. Rows snapshot track metadata at
 *   enqueue time and are deleted on successful submission or permanent rejection.
 * - `songs.mb_recording_id` / `mb_release_id` / `mb_artist_id`: MusicBrainz identifiers, read
 *   from embedded file tags or applied via tag lookup. Scrobbles carry the recording MBID when
 *   known for better server-side matching.
 *
 * Additive and idempotent, per the Auto Backup drift guard above.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS `listenbrainz_pending_listens` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `listened_at_ms` INTEGER NOT NULL,
                    `track_name` TEXT NOT NULL,
                    `artist_name` TEXT NOT NULL,
                    `release_name` TEXT,
                    `duration_ms` INTEGER,
                    `recording_mbid` TEXT,
                    `source` TEXT NOT NULL,
                    `attempts` INTEGER NOT NULL DEFAULT 0,
                    `created_at_ms` INTEGER NOT NULL
                )
            """.trimIndent()
        )

        db.addColumnIfMissing("songs", "mb_recording_id", "`mb_recording_id` TEXT")
        db.addColumnIfMissing("songs", "mb_release_id", "`mb_release_id` TEXT")
        db.addColumnIfMissing("songs", "mb_artist_id", "`mb_artist_id` TEXT")
    }
}

/**
 * v3 -> v4: audio bookmarks — named position markers inside a track (audiobooks, DJ sets,
 * podcasts), grouped per song on the Bookmarks screen.
 *
 * `audio_bookmarks` snapshots song metadata at save time so a bookmark stays presentable even
 * if the underlying song leaves the library. Additive and idempotent, per the Auto Backup
 * drift guard above.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS `audio_bookmarks` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `song_id` TEXT NOT NULL,
                    `song_title` TEXT NOT NULL,
                    `artist_name` TEXT NOT NULL,
                    `album_art_uri` TEXT,
                    `title` TEXT NOT NULL,
                    `timestamp_ms` INTEGER NOT NULL,
                    `created_time` INTEGER NOT NULL
                )
            """.trimIndent()
        )
    }
}

/** v4 -> v5: app-private offline copies of Navidrome and Jellyfin tracks. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS `offline_tracks` (
                    `download_id` TEXT NOT NULL,
                    `attempt_id` TEXT NOT NULL,
                    `song_id` TEXT NOT NULL,
                    `source_uri` TEXT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `mime_type` TEXT,
                    `local_path` TEXT,
                    `state` TEXT NOT NULL,
                    `bytes_downloaded` INTEGER NOT NULL,
                    `total_bytes` INTEGER,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `error_message` TEXT,
                    PRIMARY KEY(`download_id`)
                )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_offline_tracks_source_uri` " +
                "ON `offline_tracks` (`source_uri`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_offline_tracks_song_id` " +
                "ON `offline_tracks` (`song_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_offline_tracks_state` " +
                "ON `offline_tracks` (`state`)"
        )
    }
}

/**
 * v5 -> v6: removes the remote-server-music architecture.
 *
 * The dedicated Navidrome, Jellyfin, ListenBrainz and cloud-download tables are dropped
 * outright. Cloud songs also lived as ordinary rows in the shared `songs` table
 * (source_type 5 = Navidrome, 6 = Jellyfin), and the tables that point at them —
 * favorites, lyrics, playlist entries, bookmarks, engagements — declare no foreign key to
 * `songs`, so those rows have to be cleared explicitly or they would dangle forever.
 *
 * The delete predicate only matches rows that are unambiguously cloud-origin: no local
 * file ever has a `navidrome://` / `jellyfin://` content URI, and no local row is ever
 * written with a non-zero source_type. Albums, artists and engagements are then pruned
 * with the exact statements the app's own rescan uses, so the library left behind is
 * what a full rescan would have produced — no empty albums, no artist rows for acts that
 * only existed on a server.
 *
 * SQLite drops the indexes associated with a dropped table automatically.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    /** Matches cloud-origin rows in the shared `songs` table; never matches local audio. */
    private val CLOUD_SONG = "source_type <> 0" +
        " OR content_uri_string LIKE 'navidrome://%'" +
        " OR content_uri_string LIKE 'jellyfin://%'"

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `navidrome_songs`")
        db.execSQL("DROP TABLE IF EXISTS `navidrome_playlists`")
        db.execSQL("DROP TABLE IF EXISTS `jellyfin_songs`")
        db.execSQL("DROP TABLE IF EXISTS `jellyfin_playlists`")
        db.execSQL("DROP TABLE IF EXISTS `listenbrainz_pending_listens`")
        db.execSQL("DROP TABLE IF EXISTS `offline_tracks`")

        // Dependents first: these tables carry no FK to `songs`, so nothing cascades.
        db.execSQL("DELETE FROM favorites WHERE songId IN (SELECT id FROM songs WHERE $CLOUD_SONG)")
        db.execSQL("DELETE FROM lyrics WHERE songId IN (SELECT id FROM songs WHERE $CLOUD_SONG)")
        db.execSQL(
            "DELETE FROM playlist_songs WHERE song_id IN " +
                "(SELECT CAST(id AS TEXT) FROM songs WHERE $CLOUD_SONG)"
        )
        db.execSQL(
            "DELETE FROM audio_bookmarks WHERE song_id IN " +
                "(SELECT CAST(id AS TEXT) FROM songs WHERE $CLOUD_SONG)"
        )

        // song_artist_cross_ref cascades; songs_fts is kept in step by its delete trigger.
        db.execSQL("DELETE FROM songs WHERE $CLOUD_SONG")
        db.execSQL(
            "DELETE FROM song_engagements WHERE song_id NOT IN (SELECT CAST(id AS TEXT) FROM songs)"
        )
        // Safety net: correct even if the FTS sync triggers were missing on this install.
        db.execSQL("DELETE FROM songs_fts WHERE rowid NOT IN (SELECT id FROM songs)")

        db.execSQL("DELETE FROM albums WHERE NOT EXISTS (SELECT 1 FROM songs WHERE songs.album_id = albums.id)")
        db.execSQL(
            "DELETE FROM artists " +
                "WHERE NOT EXISTS (SELECT 1 FROM song_artist_cross_ref WHERE song_artist_cross_ref.artist_id = artists.id) " +
                "AND NOT EXISTS (SELECT 1 FROM songs WHERE songs.artist_id = artists.id) " +
                "AND NOT EXISTS (SELECT 1 FROM songs WHERE songs.album_artist_id = artists.id)"
        )
    }
}

/**
 * Phase 12.1 — the playback intelligence foundation.
 *
 * Adds the outcome aggregates that a single `play_count` cannot express. Every column is additive
 * with a default, so existing rows keep their play counts, durations and last-played timestamps
 * untouched: this migration backfills nothing and destroys nothing.
 *
 * `first_played_at` starts at 0 for existing rows rather than being guessed from
 * `last_played_timestamp`. A wrong "first played" is worse than an absent one — it would silently
 * misdate every track the user has owned for years — so unknown stays unknown and is filled in the
 * next time the track is actually played.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `song_engagements` ADD COLUMN `completion_count` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `song_engagements` ADD COLUMN `skip_count` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `song_engagements` ADD COLUMN `first_played_at` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `song_engagements` ADD COLUMN `last_completed_at` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `song_engagements` ADD COLUMN `last_skipped_at` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_engagements_skip_count` ON `song_engagements` (`skip_count`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_engagements_last_played_timestamp` ON `song_engagements` (`last_played_timestamp`)")
    }
}

/**
 * Phase 12.1B — a durable, PixelPlayer-owned identity.
 *
 * Adds `songs.stable_key` / `songs.file_size` and `song_engagements.stable_key`. Purely additive:
 * every existing row keeps its id, its play counts, its favourites and its lyrics.
 *
 * **Nothing is backfilled here.** A stable key is a hash of duration, file size and normalised tags,
 * and `file_size` was never stored before this migration — so any key invented in SQL now would be
 * computed without the size input and would therefore *not* match the key a later scan derives from
 * the same file. A key that cannot be reproduced is worse than no key, because it looks like
 * identity and silently fails to reattach. Instead the columns start empty, the next scan fills
 * `songs.stable_key` from real cursor data, and `rekeyEngagements` moves existing history across
 * once those keys exist. Until then engagement continues to be found by `song_id`, exactly as
 * before, so no user state is lost or guessed at in the meantime.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `stable_key` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `file_size` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `song_engagements` ADD COLUMN `stable_key` TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_stable_key` ON `songs` (`stable_key`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_song_engagements_stable_key` " +
                "ON `song_engagements` (`stable_key`)"
        )
    }
}

/**
 * Phase 12.1B — the durable identity, and the bootstrap that gives every existing item one.
 *
 * The previous migration added a *fingerprint* and called it `stable_key`. That was matching
 * evidence, not identity: two byte-identical files share a fingerprint, and a re-tagged file
 * changes one. This migration adds `library_item_id` — opaque, generated once, never recomputed
 * from metadata — and backfills it for every row that already exists.
 *
 * ## Bootstrapping existing libraries
 *
 * `randomblob(16)` mints an identifier that is opaque and, critically, **not derived from the
 * MediaStore id**. The MediaStore id is used only to *locate* the row while assigning, which is the
 * one thing it is good for. Existing engagement is then re-anchored by looking up the identity of
 * the song its locator currently points at, so a user's history follows their music across the
 * upgrade rather than being abandoned or guessed at.
 *
 * The whole migration is one transaction — Room runs `migrate` inside one — so a device that dies
 * mid-upgrade does not end up with identities assigned to some rows and not others.
 *
 * Nothing is deleted. Favourites, lyrics, playlists and history are untouched.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `library_item_id` TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "ALTER TABLE `song_engagements` ADD COLUMN `library_item_id` TEXT NOT NULL DEFAULT ''"
        )

        // One identity per existing song. Not MediaStore-derived, and unique per row.
        db.execSQL(
            "UPDATE `songs` SET `library_item_id` = lower(hex(randomblob(16))) " +
                "WHERE `library_item_id` = '' OR `library_item_id` IS NULL"
        )

        // Re-anchor existing history onto those identities by locator — the only link available at
        // upgrade time, and exactly what a locator is for.
        db.execSQL(
            "UPDATE `song_engagements` SET `library_item_id` = COALESCE((" +
                "SELECT s.`library_item_id` FROM `songs` s " +
                "WHERE CAST(s.`id` AS TEXT) = `song_engagements`.`song_id`" +
                "), '') WHERE `library_item_id` = '' OR `library_item_id` IS NULL"
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_songs_library_item_id` ON `songs` (`library_item_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_song_engagements_library_item_id` " +
                "ON `song_engagements` (`library_item_id`)"
        )
    }
}

/**
 * Phase 12.1B — logical user state moves from the locator to the durable identity.
 *
 * `library_item_id` was declared the durable identity, but the state a user actually owns was still
 * keyed by the MediaStore locator. That made the model unable to represent a song that is
 * temporarily absent: deleting the projection (a removed card, a revoked permission, a scanner
 * rebuild) would delete favourites, lyrics, playlist membership and bookmarks along with it, because
 * they were owned by the thing that disappeared.
 *
 * This migration adds a `library_item_id` to each of those tables and backfills it from the song
 * each row currently points at. **The locator is used only as a bridge during migration** — it is
 * the only link that exists at upgrade time, which is exactly what a locator is for. No new
 * identities are minted and no existing `library_item_id` is replaced.
 *
 * The old locator columns are deliberately **kept**, so nothing that still reads them breaks and the
 * migration is reversible in principle. They stop being the ownership key.
 *
 * Nothing is deleted.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("favorites", "lyrics").forEach { table ->
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `library_item_id` TEXT NOT NULL DEFAULT ''")
            // Bridged through the locator, which still exists and is still correct at this moment.
            db.execSQL(
                "UPDATE `$table` SET `library_item_id` = COALESCE((" +
                    "SELECT s.`library_item_id` FROM `songs` s WHERE s.`id` = `$table`.`songId`" +
                    "), '')"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_${table}_library_item_id` " +
                    "ON `$table` (`library_item_id`)"
            )
        }

        listOf("playlist_songs", "audio_bookmarks").forEach { table ->
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `library_item_id` TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "UPDATE `$table` SET `library_item_id` = COALESCE((" +
                    "SELECT s.`library_item_id` FROM `songs` s " +
                    "WHERE CAST(s.`id` AS TEXT) = `$table`.`song_id`" +
                    "), '')"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_${table}_library_item_id` " +
                    "ON `$table` (`library_item_id`)"
            )
        }

        // The durable lifecycle. An item exists here whether or not it currently has a projection,
        // which is the invariant the old model could not express.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `library_items` (" +
                "`library_item_id` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                "`stable_key` TEXT NOT NULL DEFAULT '', " +
                "`file_signature` TEXT NOT NULL DEFAULT '', " +
                "`current_locator` INTEGER, " +
                "`first_seen_at` INTEGER NOT NULL DEFAULT 0, " +
                "`last_seen_at` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`library_item_id`))"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_library_items_current_locator` " +
                "ON `library_items` (`current_locator`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_library_items_stable_key` " +
                "ON `library_items` (`stable_key`)"
        )
        // Seed from the songs that exist right now. Every one of them is, by definition, ACTIVE.
        db.execSQL(
            "INSERT OR IGNORE INTO `library_items` " +
                "(`library_item_id`, `state`, `stable_key`, `file_signature`, `current_locator`, " +
                "`first_seen_at`, `last_seen_at`) " +
                "SELECT `library_item_id`, 'ACTIVE', `stable_key`, '', `id`, " +
                "`date_added`, `date_added` FROM `songs` WHERE `library_item_id` <> ''"
        )
    }
}

/**
 * Phase 12.1 — user metadata edits move from the disposable projection to the durable identity.
 *
 * The four `*_user_edited` flags and the values they protect lived on `songs`. That is the MediaStore
 * *projection* — a row for a file Android can currently see — which this phase made disposable on
 * purpose. So a user who renamed a track and then ejected their card would lose the rename, because
 * the thing holding it was deleted along with the projection.
 *
 * `library_item_overrides` is keyed by `library_item_id` and has no relationship to `songs` at all,
 * so an item with **no projection whatsoever** still owns its edits. That is the same invariant the
 * rest of this phase is built on, applied to the last piece of user state that had not been moved.
 *
 * Backfill is a straight copy for rows that actually carry an edit, bridged through `library_item_id`
 * — which `MIGRATION_8_9` already gave every song, so nothing has to be invented here. A flag that
 * is 0 stores NULL rather than the current text, because "not edited" has to read as *no opinion*:
 * copying the text across with the flag off would leave a value that looks like an edit the user
 * never made, and pin the track's title forever.
 *
 * Nothing is deleted, and `songs` keeps its columns — they become the projection's display values,
 * recomputed from this table whenever a projection is written.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `library_item_overrides` (" +
                "`library_item_id` TEXT NOT NULL, " +
                "`title` TEXT, " +
                "`artist` TEXT, " +
                "`album` TEXT, " +
                "`genre` TEXT, " +
                "`title_user_edited` INTEGER NOT NULL DEFAULT 0, " +
                "`artist_user_edited` INTEGER NOT NULL DEFAULT 0, " +
                "`album_user_edited` INTEGER NOT NULL DEFAULT 0, " +
                "`genre_user_edited` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`library_item_id`))"
        )

        db.execSQL(
            "INSERT OR IGNORE INTO `library_item_overrides` " +
                "(`library_item_id`, `title`, `artist`, `album`, `genre`, " +
                "`title_user_edited`, `artist_user_edited`, `album_user_edited`, `genre_user_edited`) " +
                "SELECT `library_item_id`, " +
                "CASE WHEN `title_user_edited` = 1 THEN `title` END, " +
                "CASE WHEN `artist_user_edited` = 1 THEN `artist_name` END, " +
                "CASE WHEN `album_user_edited` = 1 THEN `album_name` END, " +
                "CASE WHEN `genre_user_edited` = 1 THEN `genre` END, " +
                "`title_user_edited`, `artist_user_edited`, `album_user_edited`, `genre_user_edited` " +
                "FROM `songs` WHERE `library_item_id` <> '' AND (" +
                "`title_user_edited` = 1 OR `artist_user_edited` = 1 " +
                "OR `album_user_edited` = 1 OR `genre_user_edited` = 1)"
        )

        db.repairIndexDrift()
    }
}

/**
 * Repairs the schema drift the earlier migrations left behind.
 *
 * Running the migration chain against a real database for the first time found that it does not
 * produce the schema Room expects, in five places — and Room does not warn about this, it **refuses
 * to open the database**, with `Migration didn't properly handle`. On a device that means the app
 * cannot start after an upgrade.
 *
 * * `library_items.state` is declared by the entity but was never created by `MIGRATION_9_10`. Room
 *   compares the index sets in both directions, so a *missing* index fails exactly like an extra one.
 * * `songs.stable_key`, `songs.library_item_id`, `lyrics.library_item_id`,
 *   `playlist_songs.library_item_id` and `audio_bookmarks.library_item_id` were created by earlier
 *   migrations without being declared on their entities.
 *
 * The extra indices are **declared rather than dropped**: they are real lookups the app now performs
 * by identity, and dropping an index from a large table is pure cost for no benefit. So this only
 * has to create the one that is missing. `IF NOT EXISTS` keeps it correct on a database that already
 * has it, which is every database that reached version 10 through the normal path.
 */
/**
 * Phase 12.3 — metadata intelligence.
 *
 * Adds the derived columns that make album and artist grouping explicit, queryable and inspectable:
 * a grouping key on `songs` and on `albums`, the artist key, the parsed genre list, the raw
 * track/disc values the interpretations came from, and the compilation flag.
 *
 * **Nothing is backfilled here, and that is deliberate.** A grouping key is the output of
 * `MetadataNormalizer`, which is a real algorithm over several inputs; computing an approximation of
 * it in SQL would produce a key that the next scan disagrees with, so a library would silently
 * re-group itself twice. `MIGRATION_7_8` faced exactly this with `stable_key` and resolved it the
 * same way: the columns start empty, the next scan fills them from the real normalizer, and until
 * then grouping falls back to the locator-based behaviour that was already in place.
 *
 * Every column is additive with a default, so no existing row, counter, favourite or identity is
 * touched. `library_items.first_seen_at` already exists — it was added in `MIGRATION_9_10` and has
 * been durable since.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `album_grouping_key` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `artist_grouping_key` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `genres_json` TEXT")
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `raw_track_number` TEXT")
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `raw_disc_number` TEXT")
        db.execSQL("ALTER TABLE `songs` ADD COLUMN `is_compilation` INTEGER NOT NULL DEFAULT 0")

        db.execSQL("ALTER TABLE `albums` ADD COLUMN `grouping_key` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `albums` ADD COLUMN `is_compilation` INTEGER NOT NULL DEFAULT 0")

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_album_grouping_key` ON `songs` (`album_grouping_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_artist_grouping_key` ON `songs` (`artist_grouping_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_albums_grouping_key` ON `albums` (`grouping_key`)")
    }
}

/**
 * Phase 12.4 — smart collections.
 *
 * Two tables: a saved **definition** and its ordered rules. Nothing here stores which tracks matched.
 *
 * Membership is a view over current library state, so persisting results would mean keeping a second
 * copy of the library in step with every play, favourite, sync and dormancy — and a collection would
 * then be able to disagree with the library it was generated from. Rules are cheap to store and
 * cheap to evaluate; results are not worth storing at all.
 *
 * Both tables are new, so there is nothing to backfill and no existing row is touched. `library_item_id`
 * appears only as the *output* identity of an evaluation and is deliberately not a column here: a
 * collection is defined over predicates, not over rows, which is what lets it survive a renumber,
 * a temporary loss and a return without any migration at all.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `smart_collections` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`match_mode` TEXT NOT NULL DEFAULT 'all', " +
                "`sort_field` TEXT NOT NULL DEFAULT 'title', " +
                "`sort_direction` TEXT NOT NULL DEFAULT 'asc', " +
                "`result_limit` INTEGER, " +
                "`is_built_in` INTEGER NOT NULL DEFAULT 0, " +
                "`built_in_key` TEXT, " +
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_smart_collections_name` " +
                "ON `smart_collections` (`name`)"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `smart_collection_rules` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`collection_id` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL, " +
                "`field` TEXT NOT NULL, " +
                "`operator` TEXT NOT NULL, " +
                "`value_text` TEXT, " +
                "`value_number` REAL, " +
                "`value_second_number` REAL, " +
                "FOREIGN KEY(`collection_id`) REFERENCES `smart_collections`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_smart_collection_rules_collection_id` " +
                "ON `smart_collection_rules` (`collection_id`)"
        )
    }
}

private fun SupportSQLiteDatabase.repairIndexDrift() {
    execSQL("CREATE INDEX IF NOT EXISTS `index_library_items_state` ON `library_items` (`state`)")

    // The two identity indices on `songs` were created by `MIGRATION_7_8` and `MIGRATION_8_9`. A
    // database that ran those already has them, so these are no-ops there — but a database that
    // reaches this version by any other path (a restored backup, a schema that had drifted) does
    // not, and without them the schema Room validates cannot be produced *by the migrations
    // themselves*. A migration that only works because an earlier one happened to leave the right
    // artefact behind is not a migration.
    execSQL("CREATE INDEX IF NOT EXISTS `index_songs_stable_key` ON `songs` (`stable_key`)")
    execSQL("CREATE INDEX IF NOT EXISTS `index_songs_library_item_id` ON `songs` (`library_item_id`)")
}
