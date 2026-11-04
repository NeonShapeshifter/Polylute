package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** This unchanged probe also runs against B55-before, with the legacy public API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class B55SqlTimingTest {
    @Test fun `owner sized call to commit median stays inside thirty milliseconds`(): Unit = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),PixelPlayerDatabase::class.java).build()
        try {
            val dao=db.musicInfoDiscoveryDao();val rows=b55Fixture()
            dao.insert(rows);dao.addScope(rows.map { InfoScope("library",it.key) })
            dao.addArtistMembers(rows.take(50).map { InfoArtistMember("library","fifty","Synthetic artist",it.key,"") })
            val medians=linkedMapOf<String,Double>()
            suspend fun measure(name:String, prepare:suspend () -> Unit = {}, block:suspend () -> Unit) {
                repeat(3) { prepare();block() }
                val times=(0 until 20).map { prepare();val start=System.nanoTime();block();(System.nanoTime()-start)/1e6 }.sorted()
                medians[name]=(times[9]+times[10])/2
                println("B55 SQL actual tree $name median20_ms=${medians[name]}")
            }
            measure("choose lyrics") { dao.selectKind("library","LYRICS",true) }
            measure("unchoose lyrics") { dao.selectKind("library","LYRICS",false) }
            measure("fifty artist") { dao.selectGroup("library","fifty",null,true) }
            // Clear + Undo uses the real controller in the after tests. This legacy API probe measures its old loop.
            measure("clear legacy loop",{ dao.selectKind("library","PHOTO",true) }) {
                db.withTransaction { dao.scopeRows("library").filter { it.selected }.forEach { dao.setSelection(it.key,false) } }
            }
            medians.filterKeys { it != "clear legacy loop" }.values.forEach { assertThat(it).isAtMost(30.0) }
        } finally { db.close() }
    }
}
