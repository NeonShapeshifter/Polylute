package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import android.content.Context
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.PixelPlayerDatabase
import io.mockk.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B52CountsAndWaitsContractTest {
    private fun field(value: Any, name: String): Any = value.javaClass.getMethod("get$name").invoke(value)
    private fun typeCounts(rows: List<InfoDiscovery>): List<*> = Class.forName("com.lostf1sh.pixelplayeross.data.offline.MusicInfoStateKt")
        .getMethod("infoTypeCounts", List::class.java).invoke(null, rows) as List<*>
    private suspend fun group(dao: MusicInfoDiscoveryDao): Any {
        val method = dao.javaClass.methods.firstOrNull { it.name == "groupCounts" }
        org.junit.Assert.assertNotNull("Group state must be derived from persisted choices", method)
        @Suppress("UNCHECKED_CAST")
        val flow = method!!.invoke(dao, "library", "artist", null) as Flow<Any>
        return flow.first()
    }

    @Test fun `per type counts and group tri state follow persisted choices across a restart`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "b52-counts"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, PixelPlayerDatabase::class.java, name).build()
        try {
            val dao = db.musicInfoDiscoveryDao()
            val rows = (0..8).map { InfoDiscovery("photo|$it", "PHOTO", "Photo $it", "artist", "Artist", sourceBytes = 10, availability = "AVAILABLE") }.toMutableList()
            rows[0] = rows[0].copy(selected = true)
            rows[2] = rows[2].copy(saved = true, selected = true, downloadState = "DONE")
            rows[3] = rows[3].copy(downloadState = "QUEUED")
            rows[4] = rows[4].copy(downloadState = "RETRY")
            rows[5] = rows[5].copy(availability = "LOCAL")
            rows[6] = rows[6].copy(availability = "MISSING")
            rows[7] = rows[7].copy(availability = "FAILED")
            rows[8] = rows[8].copy(availability = "UNCHECKED")
            dao.insert(rows)
            dao.addScope(rows.map { InfoScope("library", it.key) })
            dao.addArtistMembers(rows.map { InfoArtistMember("library", "artist", "Artist", it.key, "artist") })
            val photo = typeCounts(dao.scopeRows("library")).first()!!
            listOf("Total" to 9, "Available" to 2, "Saved" to 1, "Queued" to 1, "Retrying" to 1, "Chosen" to 1,
                "ChosenSaved" to 1, "Local" to 1, "Missing" to 1, "Failed" to 1, "Unchecked" to 1).forEach { (name, count) ->
                assertThat(field(photo, name)).isEqualTo(count)
            }
            assertThat(field(photo, "KnownBytes")).isEqualTo(10L)
            assertThat(field(photo, "AvailableBytes")).isEqualTo(20L)
            assertThat(field(photo, "SavedBytes")).isEqualTo(10L)
            assertThat(field(group(dao), "CheckState").toString()).isEqualTo("PARTIAL")
            dao.select("photo|1", true)
            dao.select("photo|3", true)
            dao.select("photo|4", true)
            assertThat(field(group(dao), "CheckState").toString()).isEqualTo("CHECKED")
            db.close()
            db = Room.databaseBuilder(context, PixelPlayerDatabase::class.java, name).build()
            assertThat(field(group(db.musicInfoDiscoveryDao()), "CheckState").toString()).isEqualTo("CHECKED")
            db.musicInfoDiscoveryDao().selectKind("library", "PHOTO", false)
            assertThat(field(group(db.musicInfoDiscoveryDao()), "CheckState").toString()).isEqualTo("EMPTY")
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun `WiFi absence and unvalidated internet are distinct and unmetered cellular is insufficient`() {
        val method = Class.forName("com.lostf1sh.pixelplayeross.data.offline.InfoNetworkStateKt").methods.single { it.name.startsWith("classifyInfoNetworks") }
        fun state(capabilities: List<NetworkCapabilities>) = method.invoke(null, capabilities).toString()
        fun network(transport: Int, validated: Boolean) = mockk<NetworkCapabilities> {
            every { hasTransport(any()) } answers { firstArg<Int>() == transport }
            every { hasCapability(any()) } answers {
                firstArg<Int>() in listOf(NetworkCapabilities.NET_CAPABILITY_NOT_METERED, NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    (validated && firstArg<Int>() == NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        assertThat(state(emptyList())).isEqualTo("WIFI")
        assertThat(state(listOf(network(NetworkCapabilities.TRANSPORT_CELLULAR, true)))).isEqualTo("WIFI")
        assertThat(state(listOf(network(NetworkCapabilities.TRANSPORT_WIFI, false)))).isEqualTo("INTERNET")
        assertThat(state(listOf(network(NetworkCapabilities.TRANSPORT_WIFI, true)))).isEqualTo("READY")
    }
}
