package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class B52KindSelectionContractTest {
    @Test fun `summary type chooses only new available items while advanced groups can choose saved items`() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),PixelPlayerDatabase::class.java).build()
        try {
            val dao=db.musicInfoDiscoveryDao()
            dao.insert(listOf(InfoDiscovery("new","COVER","Cover","artist","Artist",availability="AVAILABLE"),InfoDiscovery("saved","COVER","Cover","artist","Artist",availability="AVAILABLE",saved=true),InfoDiscovery("queue","COVER","Cover","artist","Artist",availability="AVAILABLE",downloadState="QUEUED")))
            dao.addScope(listOf("new","saved","queue").map { InfoScope("library",it) })
            dao.addArtistMembers(listOf("new","saved","queue").map { InfoArtistMember("library","artist","Artist",it,"artist") })
            val method=dao.javaClass.methods.firstOrNull { it.name=="selectAvailableKind" }
            if(method==null) dao.selectKind("library","COVER",true)
            else suspendCoroutineUninterceptedOrReturn<Unit> { method.invoke(dao,"library","COVER",true,it) }
            assertThat(dao.get("new")!!.selected).isTrue()
            assertThat(dao.get("saved")!!.selected).isFalse()
            assertThat(dao.get("queue")!!.selected).isTrue()
            dao.selectGroup("library","artist",null,true)
            assertThat(dao.get("saved")!!.selected).isTrue()
            dao.select("saved",false)
            dao.select("new",true)
            dao.appendSelected("library")
            if(method!=null) suspendCoroutineUninterceptedOrReturn<Unit> { method.invoke(dao,"library","COVER",true,it) }
            assertThat(dao.get("saved")!!.selected).isFalse()
        } finally { db.close() }
    }
}
