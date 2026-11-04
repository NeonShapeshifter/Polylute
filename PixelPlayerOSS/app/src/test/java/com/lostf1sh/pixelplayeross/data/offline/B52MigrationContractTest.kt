package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class B52MigrationContractTest {
    @Test fun `version fifteen preserves selection saved queue identity and old IDs while adding Undo and new ID columns`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "b52-migration"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase/15.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(15) {
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
                    if (field.getString("affinity") == "TEXT") values.put(field.getString("columnName"), "")
                    else values.put(field.getString("columnName"), 0L)
                }
            }
            values.putAll(extra)
            old.insert(table, 5, values)
        }
        insert("artists", ContentValues().apply { put("id", 52); put("name", "Artist") })
        insert("albums", ContentValues().apply { put("id", 52); put("title", "Album") })
        insert("songs", ContentValues().apply {
            put("id", 52); put("album_id", 52); put("artist_id", 52); put("title", "Song")
            put("library_item_id", "identity-52"); put("stable_key", "untouched-key")
            put("mb_recording_id", "00000001-0000-4000-8000-000000000001")
        })
        insert("info_discovery", ContentValues().apply {
            put("key", "photo|artist"); put("kind", "PHOTO"); put("availability", "AVAILABLE")
            put("selected", 1); put("saved", 1); put("downloadState", "DONE")
        })
        insert("info_queue", ContentValues().apply { put("id", 1); put("paused", 1) })
        helper.close()
        val builder = Room.databaseBuilder(context, PixelPlayerDatabase::class.java, name)
        // B56: the database is at version 17 now (composers); the chain from 15 runs both steps
        for (step in listOf("getMIGRATION_15_16", "getMIGRATION_16_17")) {
            val migration = Class.forName("com.lostf1sh.pixelplayeross.data.database.MigrationsKt").methods.firstOrNull { it.name == step }
            migration?.let { builder.addMigrations(it.invoke(null) as Migration) }
        }
        val db = builder.build()
        try {
            val sql = db.openHelper.writableDatabase
            assertThat(sql.version).isEqualTo(17)
            assertThat(db.musicInfoDiscoveryDao().get("photo|artist")?.selected).isTrue()
            assertThat(db.musicInfoDiscoveryDao().get("photo|artist")?.saved).isTrue()
            assertThat(db.musicInfoDiscoveryDao().queueOnce()?.paused).isTrue()
            sql.query("SELECT library_item_id, stable_key, mb_recording_id, mb_release_group_id, mb_album_artist_id FROM songs WHERE id=52").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("identity-52")
                assertThat(it.getString(1)).isEqualTo("untouched-key")
                assertThat(it.getString(2)).isEqualTo("00000001-0000-4000-8000-000000000001")
                assertThat(it.isNull(3)).isTrue(); assertThat(it.isNull(4)).isTrue()
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
