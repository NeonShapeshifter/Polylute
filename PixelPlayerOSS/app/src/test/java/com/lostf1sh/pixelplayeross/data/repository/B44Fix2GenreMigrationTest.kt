package com.lostf1sh.pixelplayeross.data.repository

import android.app.Application
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
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class B44Fix2GenreMigrationTest {
    private fun rows(db: SupportSQLiteDatabase, table: String): List<List<String?>> = db.query("SELECT * FROM `$table`").use { cursor ->
        buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
    }
    @Test fun `migration from fourteen preserves every preexisting row and adds only genre images`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "b44-fix2-migration.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase/14.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (index in 0 until entities.length()) {
                        val table = entities.getJSONObject(index)
                        db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
                        val indices = table.optJSONArray("indices") ?: org.json.JSONArray()
                        for (i in 0 until indices.length()) db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
                    }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        val old = helper.writableDatabase
        val tables = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        old.execSQL("PRAGMA foreign_keys = OFF")
        for (table in tables) {
            val columns = old.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(1) to if (cursor.getString(2).contains("INT")) "1" else "'seed'") }
            }
            old.execSQL("INSERT INTO `$table` (${columns.joinToString { "`${it.first}`" }}) VALUES (${columns.joinToString { it.second }})")
        }
        val previous = tables.associateWith { rows(old, it) }
        helper.close()
        val migration = Class.forName("com.lostf1sh.pixelplayeross.data.database.MigrationsKt")
            .getMethod("getMIGRATION_14_15").invoke(null) as Migration
        val upgraded = Room.databaseBuilder(context, PixelPlayerDatabase::class.java, name).addMigrations(migration).build()
        try {
            val sqlite = upgraded.openHelper.writableDatabase
            assertThat(sqlite.version).isEqualTo(15)
            for ((table, data) in previous) assertThat(rows(sqlite, table)).isEqualTo(data)
            assertThat(rows(sqlite, "genre_images")).isEmpty()
            println("B44-fix2 HOST migration 14 -> 15 preserved ${previous.size} tables with one row each")
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
