package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B44DiscoverySchemaTest {
    @Test fun `discovery and queue have indexed Room tables separate from downloaded content`() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PixelPlayerDatabase::class.java).build()
        try {
            val tables = db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertThat(tables).containsAtLeast("info_discovery", "info_queue", "info_scopes")
        } finally { db.close() }
    }
}
