package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class B55StatementBudgetTest {
    @Test fun `one type tap uses a bounded set of statements instead of one query per song`(): Unit = runBlocking {
        val statements=java.util.concurrent.CopyOnWriteArrayList<String>()
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),PixelPlayerDatabase::class.java)
            .setQueryCallback({ sql,_ -> statements.add(sql) },java.util.concurrent.Executor { it.run() }).build()
        try {
            val dao=db.musicInfoDiscoveryDao()
            val rows=(0 until 4938).map { InfoDiscovery("lyrics-$it","LYRICS","Synthetic $it","artist","Synthetic",availability="AVAILABLE") }
            dao.insert(rows);dao.addScope(rows.map { InfoScope("library",it.key) })
            statements.clear()
            val start=System.nanoTime();dao.selectKind("library","LYRICS",true)
            println("B55 actual tree lyrics commit_ms=${(System.nanoTime()-start)/1e6}")
            val reads=statements.count { it.startsWith("SELECT",true) && it.contains("info_discovery") }
            val updates=statements.count { it.startsWith("UPDATE info_discovery",true) }
            println("B55 actual tree selection reads=$reads updates=$updates")
            assertThat(reads).isAtMost(1);assertThat(updates).isEqualTo(1)
        } finally { db.close() }
    }
}
