package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class)
class B55SelectionTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),PixelPlayerDatabase::class.java).build()
    private suspend fun seed(db: PixelPlayerDatabase, rows: List<InfoDiscovery>) {
        val dao=db.musicInfoDiscoveryDao()
        dao.insert(rows); dao.addScope(rows.map { InfoScope("library",it.key) })
        dao.addArtistMembers(rows.mapIndexed { n,r -> InfoArtistMember("library",if(n < 50) "fifty" else r.artistKey,r.artistName,r.key,"") })
    }
    // Verbatim B52-fix1 loop semantics, retained as an independent differential oracle.
    private suspend fun oldSelect(dao: MusicInfoDiscoveryDao, key: String, selected: Boolean) {
        val row=dao.get(key) ?: return
        if(!selected) {
            if(row.downloadState in listOf("QUEUED","RETRY")) dao.cancelDownload(key)
            dao.setSelection(key,false)
        } else if(row.saved || row.availability == "AVAILABLE" || row.downloadState in listOf("QUEUED","DOWNLOADING","RETRY")) dao.setSelection(key,true)
    }
    @Test fun `set selection matches the old loop for every field and operation`(): Unit = runBlocking {
        val random=Random(55)
        repeat(12) { operation ->
            val rows=b55Fixture().filterIndexed { n,_ -> n % 40 == 0 }.shuffled(random)
            val old=database();val new=database()
            try {
                seed(old,rows);seed(new,rows)
                val a=old.musicInfoDiscoveryDao();val b=new.musicInfoDiscoveryDao()
                val selected=operation % 2 == 0
                old.withTransaction {
                    when(operation / 2) {
                        0 -> oldSelect(a, rows[0].key,selected)
                        1 -> a.scopeRows("library").filter { it.kind=="LYRICS" }.forEach { oldSelect(a,it.key,selected) }
                        2 -> a.scopeRows("library").filter { it.kind=="PHOTO" && !it.saved }.forEach { oldSelect(a,it.key,selected) }
                        3 -> a.groupKeys("library","fifty",null).forEach { oldSelect(a,it,selected) }
                        4 -> a.groupKeys("library","fifty",rows[0].albumKey).forEach { oldSelect(a,it,selected) }
                        else -> a.scopeRows("library").filter { it.selected }.forEach { a.setSelection(it.key,false) }
                    }
                }
                when(operation / 2) {
                    0 -> b.select(rows[0].key,selected)
                    1 -> b.selectKind("library","LYRICS",selected)
                    2 -> b.selectAvailableKind("library","PHOTO",selected)
                    3 -> b.selectGroup("library","fifty",null,selected)
                    4 -> b.selectGroup("library","fifty",rows[0].albumKey,selected)
                    else -> b.clearSelection("library")
                }
                assertThat(b.allRows().sortedBy { it.key }).isEqualTo(a.allRows().sortedBy { it.key })
            } finally { old.close();new.close() }
        }
    }
    @Test fun `memory taps deltas and acknowledgements equal a fresh load after random sequences`(): Unit = runBlocking {
        val db=database()
        try {
            seed(db,b55Fixture().take(500))
            val dao=db.musicInfoDiscoveryDao()
            val model=InfoScreenModel(dao.scopeRows("library"),dao.scopeMembers("library"))
            val random=Random(55)
            repeat(200) {
                val key="LYRICS-${random.nextInt(500)}"
                when(random.nextInt(4)) {
                    0 -> { dao.checked(key,"AVAILABLE","words",1024,1234);model.merge(dao.changedRows(listOf(key))) }
                    1 -> { dao.downloaded(key);model.merge(dao.changedRows(listOf(key))) }
                    else -> {
                        val selected=random.nextBoolean()
                        val intent=model.choose(listOf(key),selected)
                        // Before any DAO write, the checkbox already reflects this tap.
                        if(!selected || model.row(key)!!.availability=="AVAILABLE") assertThat(model.row(key)!!.selected).isEqualTo(selected)
                        dao.select(key,selected);model.merge(dao.changedRows(listOf(key)),intent.id)
                    }
                }
                val fresh=dao.scopeRows("library")
                assertThat(model.rows().sortedBy { it.key }).isEqualTo(fresh.sortedBy { it.key })
                assertThat(model.types()).isEqualTo(infoTypeCounts(fresh))
                assertThat(model.selection()).isEqualTo(dao.selection("library").first())
            }
        } finally { db.close() }
    }
    @Test fun `owner sized timing medians stay inside the SQL and tap budgets`(): Unit = runBlocking {
        val db=database()
        try {
            val fixture=b55Fixture();assertThat(fixture).hasSize(11640);assertThat(b55Songs()).hasSize(4948);seed(db,fixture)
            val dao=db.musicInfoDiscoveryDao()
            suspend fun measure(label: String, budget: Double? = null, prepare: suspend () -> Unit = {}, block: suspend () -> Unit): Double {
                repeat(3) { prepare();block() }
                val samples=(0 until 20).map { prepare();val start=System.nanoTime();block();(System.nanoTime()-start)/1e6 }.sorted()
                val median=(samples[9]+samples[10])/2
                println("B55 timing $label median20_ms=$median budget=$budget")
                if(budget != null) assertThat(median).isAtMost(budget)
                return median
            }
            for(selected in listOf(true,false)) {
                measure("legacy oracle lyrics $selected") { db.withTransaction { dao.scopeRows("library").filter { it.kind=="LYRICS" }.forEach { oldSelect(dao,it.key,selected) } } }
                measure("after lyrics $selected",30.0) { dao.selectKind("library","LYRICS",selected) }
            }
            measure("legacy oracle fifty") { db.withTransaction { dao.groupKeys("library","fifty",null).forEach { oldSelect(dao,it,true) } } }
            measure("after fifty",30.0) { dao.selectGroup("library","fifty",null,true) }
            measure("legacy oracle clear") { db.withTransaction { dao.scopeRows("library").filter { it.selected }.forEach { dao.setSelection(it.key,false) } } }
            measure("after clear with Undo",30.0,{ dao.selectKind("library","PHOTO",true) }) {
                db.withTransaction {
                    val token=java.util.UUID.randomUUID().toString()
                    val totals=dao.clearTotals("library")
                    dao.insertUndo(InfoUndo(token,"CLEAR",1234,totals.downloads,totals.knownBytes ?: 0))
                    dao.captureClear("library",token);dao.clearSelection("library")
                }
            }
            measure("first load") { InfoScreenModel(dao.scopeRows("library"),dao.scopeMembers("library")) }
            val model=InfoScreenModel(dao.scopeRows("library"),dao.scopeMembers("library"))
            var choose=false
            measure("tap lyrics",16.0) { choose=!choose;model.choose(model.keys(InfoKind.LYRICS),choose);model.types();model.selection() }
            var deltaNumber=0L
            measure("checked delta",5.0) {
                deltaNumber++
                model.merge(listOf(fixture[0].copy(availability=if(deltaNumber % 2 == 0L) "AVAILABLE" else "FAILED",checkedAt=deltaNumber)))
                model.types();model.selection()
            }
        } finally { db.close() }
    }
}
