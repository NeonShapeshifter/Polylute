package com.lostf1sh.pixelplayeross.data.database

import android.app.Application
import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * B56 phase 6 (Sherlock #36): the one Room change of the batch, v16 -> v17, an additive table `song_composers` (the reason: composers must be stored to be searched; nothing in
 * `songs` changes). Room validates the migrated schema against the entities on open, so opening the database is half the test; the rest is what must survive and how the table behaves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class B56ComposerMigrationTest {
    private fun version16(context: Context, name: String): SupportSQLiteDatabase {
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase/16.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(16) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (i in 0 until entities.length()) {
                        val table = entities.getJSONObject(i)
                        db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
                        val indices = table.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        val old = helper.writableDatabase
        fun insert(table: String, extra: ContentValues) {
            val definition = (0 until entities.length()).map { entities.getJSONObject(it) }.single { it.getString("tableName") == table }
            val fields = definition.getJSONArray("fields")
            val values = ContentValues()
            for (i in 0 until fields.length()) {
                val field = fields.getJSONObject(i)
                if (field.optBoolean("notNull")) {
                    if (field.getString("affinity") == "TEXT") values.put(field.getString("columnName"), "") else values.put(field.getString("columnName"), 0L)
                }
            }
            values.putAll(extra)
            old.insert(table, 5, values)
        }
        insert("artists", ContentValues().apply { put("id", 56); put("name", "Artist") })
        insert("albums", ContentValues().apply { put("id", 56); put("title", "Album") })
        insert("songs", ContentValues().apply {
            put("id", 56); put("album_id", 56); put("artist_id", 56); put("title", "Symphony 5")
            put("file_path", "/music/classical/5.flac"); put("library_item_id", "identity-56"); put("stable_key", "key-56")
        })
        return old
    }

    @Test
    fun `version 17 adds the composers table and keeps the songs, with every song waiting to be read`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "b56-composers"
        version16(context, name).close()
        val db = Room.databaseBuilder(context, PixelPlayerDatabase::class.java, name).addMigrations(MIGRATION_16_17).build()
        try {
            val sql = db.openHelper.writableDatabase
            assertThat(sql.version).isEqualTo(17)
            sql.query("SELECT title, library_item_id, stable_key FROM songs WHERE id = 56").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("Symphony 5")
                assertThat(it.getString(1)).isEqualTo("identity-56")
                assertThat(it.getString(2)).isEqualTo("key-56")
            }
            assertThat(db.musicDao().songsNeedingComposer(10)).containsExactly(ComposerBackfillRow(56, "/music/classical/5.flac"))
            assertThat(db.musicDao().countSongsNeedingComposer()).isEqualTo(1)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test
    fun `a composer row is replaced when the song is read again and goes with its song`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "b56-composers-rows"
        version16(context, name).close()
        val db = Room.databaseBuilder(context, PixelPlayerDatabase::class.java, name).addMigrations(MIGRATION_16_17).build()
        try {
            val dao = db.musicDao()
            dao.upsertComposers(listOf(SongComposerEntity(56, "Ludwig van Beethoven", 1L)))
            dao.upsertComposers(listOf(SongComposerEntity(56, "Beethoven; Mozart", 2L)))
            assertThat(dao.countSongsNeedingComposer()).isEqualTo(0)
            db.openHelper.writableDatabase.query("SELECT composer FROM song_composers").use {
                assertThat(it.count).isEqualTo(1)
                it.moveToFirst(); assertThat(it.getString(0)).isEqualTo("Beethoven; Mozart")
            }

            dao.forgetComposers(listOf(56))
            assertThat(dao.countSongsNeedingComposer()).isEqualTo(1)
            dao.upsertComposers(listOf(SongComposerEntity(56, "Beethoven", 3L)))
            db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
            db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE id = 56")
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM song_composers").use { it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(0) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
