package com.lostf1sh.pixelplayeross.data.offline

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.lostf1sh.pixelplayeross.data.database.*
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.musicinfo.*
import com.lostf1sh.pixelplayeross.data.repository.*
import io.mockk.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class B52Fix1SelectionTest {
    private val dir = Files.createTempDirectory("b52-backend").toFile()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = dir
    }
    private val db = Room.inMemoryDatabaseBuilder(app, PixelPlayerDatabase::class.java).build()
    private val dao get() = db.musicInfoDiscoveryDao()
    private val service = mockk<MusicInfoService>(relaxed = true)
    private val info = mockk<MusicInfoRepository>(relaxed = true)
    private val lyrics = mockk<LyricsRepository>(relaxed = true)
    private val music = mockk<MusicRepository>(relaxed = true)
    private val manager = mockk<WorkManager>(relaxed = true)
    private val deleted = DeletedDownloads(File(dir, "deleted.txt"))
    private val song = Song.emptySong().copy(id = "52", libraryItemId = "identity-52", title = "Song", artist = "Artist", album = "Album")
    private lateinit var downloads: MusicInfoDownloads

    @Before fun setup() {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any<Context>()) } returns manager
        val operation = mockk<Operation> { every { result } returns Futures.immediateFuture(Operation.SUCCESS) }
        every { manager.cancelUniqueWork(any()) } returns operation
        every { info.service } returns service
        every { info.deletedDownloads } returns deleted
        coEvery { music.getAllSongsOnce() } returns listOf(song)
        every { music.getSongsForAlbum(any()) } returns flowOf(listOf(song))
        every { music.getSongsByIds(any()) } returns flowOf(listOf(song))
        coEvery { service.discover(any(), any(), any()) } returns ResolvedInfo("payload", 52)
        coEvery { lyrics.discoverLyrics(any()) } returns "lyrics"
        downloads = MusicInfoDownloads(context, db, music, lyrics, info)
    }

    @After fun close() { db.close(); unmockkAll(); dir.deleteRecursively() }

    private suspend fun action(name: String, vararg args: Any): Any? = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = downloads.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size + 1 }
        org.junit.Assert.assertNotNull("Missing required downloader action $name", method)
        try { method!!.invoke(downloads, *args, continuation) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun token(result: Any): String = result.javaClass.getMethod("getToken").invoke(result) as String
    private suspend fun ready(): String {
        val scope = downloads.open(null)
        dao.scopeRows(scope).forEach { dao.checked(it.key, "AVAILABLE", "payload", 52, System.currentTimeMillis()) }
        return scope
    }


    private suspend fun seed(): List<InfoDiscovery> {
        val rows = listOf(
            InfoDiscovery("a", "LYRICS", "A", "a", "Artista A", availability="AVAILABLE", payload="words", selected=true, checkedAt=System.currentTimeMillis()),
            InfoDiscovery("shared", "LYRICS", "Compartida", "a", "Artista A", availability="AVAILABLE", payload="words", checkedAt=System.currentTimeMillis()),
            InfoDiscovery("saved", "LYRICS", "Guardada", "a", "Artista A", availability="AVAILABLE", saved=true, selected=true),
            InfoDiscovery("b", "PHOTO", "B", "b", "Artista B", availability="AVAILABLE", payload="photo", checkedAt=System.currentTimeMillis()),
            InfoDiscovery("outside", "PHOTO", "Fuera", "c", "C", availability="AVAILABLE", selected=true),
        )
        dao.insert(rows)
        dao.addScope(rows.take(4).map { InfoScope("library",it.key) })
        dao.addArtistMembers(listOf("a","shared","saved").map { InfoArtistMember("library","a","Artista A",it,"a") } +
            listOf("b","shared").map { InfoArtistMember("library","b","Artista B",it,"b") })
        dao.initQueue(InfoQueue())
        return rows
    }

    @Test fun `saved choices stay visible when the kind also has new items`(): Unit = runBlocking {
        seed()
        val type = infoTypeCounts(dao.scopeRows("library")).single { it.kind == InfoKind.LYRICS }
        assertThat(type.group.chosen).isEqualTo(2)
        assertThat(type.chosenSaved).isEqualTo(1)
        assertThat(type.group.checkState).isEqualTo(InfoCheckState.PARTIAL)
    }

    @Test fun `summary tapping an exhausted kind never chooses saved items`(): Unit = runBlocking {
        seed()
        dao.select("a",false); dao.select("saved",false)
        dao.selectAvailableKind("library","LYRICS",true)
        dao.appendSelected("library")
        dao.selectAvailableKind("library","LYRICS",true)
        assertThat(dao.get("saved")!!.selected).isFalse()
        assertThat(dao.selection("library").first().deletes).isEqualTo(0)
    }

    @Test fun `enqueue preserves choices until completion and every group sees them`(): Unit = runBlocking {
        seed(); dao.appendSelected("library")
        assertThat(dao.get("a")!!.selected).isTrue()
        assertThat(infoTypeCounts(dao.scopeRows("library")).single { it.kind == InfoKind.LYRICS }.group.chosen).isEqualTo(2)
        dao.downloaded("a")
        assertThat(dao.get("a")!!.selected).isFalse()
        assertThat(dao.get("a")!!.saved).isTrue()
    }

    private suspend fun queued(): InfoDiscovery {
        seed(); dao.select("shared",true); dao.select("b",true)
        dao.appendSelected("library")
        return dao.get("a")!!
    }

    @Test fun `unchoosing one pending row dequeues without deleting and an old worker cannot start it`(): Unit = runBlocking {
        val old = queued()
        dao.select("a",false)
        assertThat(dao.get("a")!!.downloadState).isEqualTo("IDLE")
        assertThat(dao.get("a")!!.availability).isEqualTo("AVAILABLE")
        assertThat(dao.get("a")!!.selected).isFalse()
        assertThat(dao.get("a")!!.deletedByUser).isFalse()
        assertThat(dao.get("a")!!.downloadEpoch).isGreaterThan(old.downloadEpoch)
        dao.select("a",true); dao.appendSelected("library")
        downloads.downloadOne(old)
        coVerify(exactly=0) { lyrics.saveDiscoveredLyrics(any(),any()) }
        assertThat(dao.get("a")!!.downloadState).isEqualTo("QUEUED")
    }

    @Test fun `unchoosing an artist dequeues its shared pending rows and leaves other artists alone`(): Unit = runBlocking {
        queued(); dao.selectGroup("library","a",null,false)
        assertThat(dao.get("a")!!.downloadState).isEqualTo("IDLE")
        assertThat(dao.get("shared")!!.downloadState).isEqualTo("IDLE")
        assertThat(dao.get("b")!!.downloadState).isEqualTo("QUEUED")
        assertThat(dao.get("saved")!!.saved).isTrue()
        assertThat(dao.get("saved")!!.selected).isFalse()
    }

    @Test fun `unchoosing a type dequeues only its new items and keeps saved choices`(): Unit = runBlocking {
        queued(); dao.selectAvailableKind("library","LYRICS",false)
        assertThat(dao.get("a")!!.downloadState).isEqualTo("IDLE")
        assertThat(dao.get("shared")!!.downloadState).isEqualTo("IDLE")
        assertThat(dao.get("b")!!.downloadState).isEqualTo("QUEUED")
        assertThat(dao.get("saved")!!.selected).isTrue()
    }

    @Test fun `unchoosing a downloading item lets its current generation finish`(): Unit = runBlocking {
        val old=queued(); assertThat(dao.startDownload(old.key,old.downloadEpoch)).isEqualTo(1)
        dao.select(old.key,false)
        assertThat(dao.get(old.key)!!.selected).isFalse()
        assertThat(dao.get(old.key)!!.downloadState).isEqualTo("DOWNLOADING")
        assertThat(dao.get(old.key)!!.downloadEpoch).isEqualTo(old.downloadEpoch)
        dao.downloaded(old.key,old.downloadEpoch)
        assertThat(dao.get(old.key)!!.saved).isTrue()
    }

    @Test fun `clear affects the current scope only leaves pending and saved intact and Undo restores the exact selection`(): Unit = runBlocking {
        queued()
        val before=dao.allRows().associate { it.key to it.selected }
        val states=dao.allRows().associate { it.key to Triple(it.downloadState,it.downloadEpoch,it.saved) }
        val clear=action("clearSelection","library") as InfoUndo
        assertThat(dao.scopeRows("library").none { it.selected }).isTrue()
        assertThat(infoTypeCounts(dao.scopeRows("library")).all { it.group.checkState==InfoCheckState.EMPTY }).isTrue()
        assertThat(dao.get("outside")!!.selected).isTrue()
        assertThat(dao.allRows().associate { it.key to Triple(it.downloadState,it.downloadEpoch,it.saved) }).isEqualTo(states)
        action("undo",clear.token)
        assertThat(dao.allRows().associate { it.key to it.selected }).isEqualTo(before)
        assertThat(dao.allRows().associate { it.key to Triple(it.downloadState,it.downloadEpoch,it.saved) }).isEqualTo(states)
    }

    @Test fun `collaboration explains the partial artist and deselection and clear restore both artists`(): Unit = runBlocking {
        seed(); dao.select("a",false); dao.select("saved",false)
        dao.selectGroup("library","a",null,true)
        val b=dao.choices("library","b").first()
        assertThat(infoGroupCounts(b).checkState).isEqualTo(InfoCheckState.PARTIAL)
        val getter=b.first().javaClass.methods.firstOrNull { it.name=="getSharedWithOthers" }
        org.junit.Assert.assertNotNull("Shared membership count must be projected for artist explanations",getter)
        assertThat(b.count { (getter!!.invoke(it) as Int)>0 }).isEqualTo(1)
        dao.selectGroup("library","a",null,false)
        assertThat(infoGroupCounts(dao.choices("library","b").first()).checkState).isEqualTo(InfoCheckState.EMPTY)
        dao.selectGroup("library","a",null,true)
        action("clearSelection","library")
        assertThat(listOf("a","b").all { artist -> infoGroupCounts(dao.choices("library",artist).first()).checkState==InfoCheckState.EMPTY }).isTrue()
    }

    @Test fun `Cancel Undo restores pending choices exactly after a type was dequeued`(): Unit = runBlocking {
        queued(); dao.selectAvailableKind("library","LYRICS",false)
        val before=dao.pendingDownloads().map { Triple(it.key,it.downloadState,it.selected) }
        val cancel=downloads.cancel()
        assertThat(dao.pendingDownloads()).isEmpty()
        assertThat(downloads.undo(cancel.token)).isTrue()
        assertThat(dao.pendingDownloads().map { Triple(it.key,it.downloadState,it.selected) }).isEqualTo(before)
        assertThat(dao.get("a")!!.downloadState).isEqualTo("IDLE")
    }
    @Test fun `an artist scope explains collaborators even if the library scope was never opened`(): Unit = runBlocking {
        val duet=song.copy(artist="Artista A, Artista B",albumArtist="Artista A, Artista B")
        every { music.getSongsByIds(any()) } returns flowOf(listOf(duet))
        val scope=downloads.open(listOf(song.id),"Artista A")
        val rows=dao.choices(scope,"artista a").first()
        val shared=rows.filter { it.kind in listOf("COVER","DESCRIPTION","LYRICS") }
        assertThat(shared).isNotEmpty()
        val getter=shared.first().javaClass.methods.firstOrNull { it.name=="getSharedWithOthers" }
        org.junit.Assert.assertNotNull("A scoped collaboration must retain other contributor evidence",getter)
        assertThat(shared.all { (getter!!.invoke(it) as Int)==1 }).isTrue()
    }

    @Test fun `clear Undo survives reopening the controller and expiration never cancels pending work`(): Unit = runBlocking {
        queued()
        val original=dao.scopeRows("library").filter { it.selected }.map { it.key }.toSet()
        val clear=action("clearSelection","library") as InfoUndo
        downloads=MusicInfoDownloads(context,db,music,lyrics,info)
        downloads.initialize()
        assertThat(downloads.undo(clear.token)).isTrue()
        assertThat(dao.scopeRows("library").filter { it.selected }.map { it.key }.toSet()).isEqualTo(original)
        action("clearSelection","library")
        val pending=dao.pendingDownloads().map { it.key to it.downloadEpoch }
        downloads.finishExpiredUndo(Long.MAX_VALUE)
        assertThat(dao.pendingDownloads().map { it.key to it.downloadEpoch }).isEqualTo(pending)
        assertThat(dao.scopeRows("library").none { it.selected }).isTrue()
        coVerify(exactly=0) { info.deleteDiscovered(any(),any(),any(),any()) }
    }

    @Test fun `the end of the Undo stream offers the newest active action`(): Unit = runBlocking {
        val until=System.currentTimeMillis()+5000
        dao.insertUndo(InfoUndo("older","CANCEL",until-1,1,0))
        dao.insertUndo(InfoUndo("clear","CLEAR",until,2,0))
        assertThat(dao.undoActions().first().last { !it.failed }.token).isEqualTo("clear")
    }

}
