package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.*
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
class B44DiscoveryMigrationTest {
    @Test fun `version thirteen upgrades without dropping saved artist images and queue pause persists on reopen`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "b44-migration"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase/13.json").readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(13) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val table = entities.getJSONObject(index)
                        db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
                        val indices = table.optJSONArray("indices") ?: org.json.JSONArray()
                        for (i in 0 until indices.length()) db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase.execSQL("INSERT INTO artists(id, name, track_count, image_url) VALUES (7, 'Artist', 2, '/saved/photo.jpg')")
        helper.close()
        val db = Room.databaseBuilder(context, PixelPlayerDatabase::class.java, name).addMigrations(MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17).build()
        val image = db.openHelper.writableDatabase.query("SELECT image_url FROM artists WHERE id = 7").use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }
        assertThat(image).isEqualTo("/saved/photo.jpg")
        db.musicInfoDiscoveryDao().initQueue(InfoQueue(paused = true))
        db.close()
        val reopened = Room.databaseBuilder(context, PixelPlayerDatabase::class.java, name).addMigrations(MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17).build()
        assertThat(reopened.musicInfoDiscoveryDao().queueOnce()?.paused).isTrue()
        reopened.close()
        context.deleteDatabase(name)
        Unit
    }
}
