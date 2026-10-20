package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import timber.log.Timber

@Database(
    entities = [
        AlbumArtThemeEntity::class,
        SearchHistoryEntity::class,
        SongEntity::class,
        SongSearchFtsEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        TransitionRuleEntity::class,
        SongArtistCrossRef::class,
        SongEngagementEntity::class,
        LibraryItemEntity::class,
        LibraryItemOverrideEntity::class,
        FavoritesEntity::class,
        LyricsEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        AudioBookmarkEntity::class,
        com.lostf1sh.pixelplayeross.data.collections.SmartCollectionEntity::class,
        com.lostf1sh.pixelplayeross.data.collections.SmartCollectionRuleEntity::class
    ],
    version = 13,
    exportSchema = true
)
abstract class PixelPlayerDatabase : RoomDatabase() {
    abstract fun albumArtThemeDao(): AlbumArtThemeDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun musicDao(): MusicDao
    abstract fun transitionDao(): TransitionDao
    abstract fun engagementDao(): EngagementDao
    abstract fun libraryItemDao(): LibraryItemDao
    abstract fun smartCollectionDao(): com.lostf1sh.pixelplayeross.data.collections.SmartCollectionDao
    abstract fun collectionCandidateDao(): com.lostf1sh.pixelplayeross.data.collections.CollectionCandidateDao
    abstract fun libraryItemOverrideDao(): LibraryItemOverrideDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun localPlaylistDao(): LocalPlaylistDao
    abstract fun audioBookmarkDao(): AudioBookmarkDao

    companion object {
        fun installFavoriteSyncTriggers(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TRIGGER IF EXISTS trg_favorites_insert_sync_song")
            db.execSQL("DROP TRIGGER IF EXISTS trg_favorites_update_sync_song")
            db.execSQL("DROP TRIGGER IF EXISTS trg_favorites_delete_sync_song")

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_favorites_insert_sync_song
                    AFTER INSERT ON favorites
                    BEGIN
                        UPDATE songs SET is_favorite = NEW.isFavorite WHERE id = NEW.songId;
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_favorites_update_sync_song
                    AFTER UPDATE ON favorites
                    BEGIN
                        UPDATE songs SET is_favorite = NEW.isFavorite WHERE id = NEW.songId;
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_favorites_delete_sync_song
                    AFTER DELETE ON favorites
                    BEGIN
                        UPDATE songs SET is_favorite = 0 WHERE id = OLD.songId;
                    END
                """.trimIndent()
            )
        }

        private fun createSongsSearchVirtualTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                    CREATE VIRTUAL TABLE IF NOT EXISTS songs_fts
                    USING fts4(
                        title,
                        artist_name,
                        tokenize=unicode61
                    )
                """.trimIndent()
            )
        }

        fun installSongsSearchSyncTriggers(db: SupportSQLiteDatabase) {
            createSongsSearchVirtualTable(db)

            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_insert")
            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_update")
            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_delete")

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_insert
                    AFTER INSERT ON songs
                    BEGIN
                        INSERT INTO songs_fts(rowid, title, artist_name)
                        VALUES (NEW.id, NEW.title, NEW.artist_name);
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_update
                    AFTER UPDATE ON songs
                    BEGIN
                        DELETE FROM songs_fts WHERE rowid = OLD.id;
                        INSERT INTO songs_fts(rowid, title, artist_name)
                        VALUES (NEW.id, NEW.title, NEW.artist_name);
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_delete
                    AFTER DELETE ON songs
                    BEGIN
                        DELETE FROM songs_fts WHERE rowid = OLD.id;
                    END
                """.trimIndent()
            )
        }

        /**
         * Rebuilds the FTS index when it has fallen out of step with the songs table.
         *
         * `songs_fts` is a runtime artifact, not a Room entity, so no generated migration creates
         * it and `MIGRATION_5_6` merely deletes from it. An install whose database predates the
         * index — or one restored from a backup taken before it — therefore had no `songs_fts` at
         * all, and every FTS search threw "no such table". Rebuilding only when the index is empty
         * keeps this off the common path: a healthy database does one cheap count.
         */
        private fun backfillSongsSearchIndexIfNeeded(db: SupportSQLiteDatabase) {
            val indexed = db.query("SELECT count(*) FROM songs_fts").use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
            if (indexed > 0L) return

            val songs = db.query("SELECT count(*) FROM songs").use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
            if (songs == 0L) return

            Timber.i("songs_fts was empty with %d songs present; rebuilding the index", songs)
            db.execSQL("INSERT INTO songs_fts(rowid, title, artist_name) SELECT id, title, artist_name FROM songs")
        }

        fun createRuntimeArtifactsCallback(): RoomDatabase.Callback {
            return object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    installFavoriteSyncTriggers(db)
                    installSongsSearchSyncTriggers(db)
                }

                /**
                 * The same artifacts are installed on every open.
                 *
                 * `CREATE ... IF NOT EXISTS` makes this idempotent, and it is the only thing that
                 * repairs a database which was created before the search index existed. Without
                 * it, `onCreate` was the sole install path and an upgraded install searched
                 * against a table that was never there.
                 */
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    installFavoriteSyncTriggers(db)
                    installSongsSearchSyncTriggers(db)
                    backfillSongsSearchIndexIfNeeded(db)
                }
            }
        }
    }
}
