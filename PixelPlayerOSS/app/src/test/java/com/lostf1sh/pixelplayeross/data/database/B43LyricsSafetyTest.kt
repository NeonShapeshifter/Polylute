package com.lostf1sh.pixelplayeross.data.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepositoryImpl
import com.lostf1sh.pixelplayeross.utils.LyricsUtils
import io.mockk.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class B43LyricsSafetyTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: PixelPlayerDatabase
    private val dao get() = db.musicDao()
    private val huge = "A lyric line\n".repeat(270_000)
    @Before fun setup() = runBlocking {
        File(context.filesDir, "lyrics-repair-1.1.1.done").delete()
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
            .addCallback(PixelPlayerDatabase.createRuntimeArtifactsCallback()).allowMainThreadQueries().build()
        dao.insertArtists(listOf(ArtistEntity(1, "Artist", 3)))
        dao.insertAlbums(listOf(AlbumEntity(1, "Album", "Artist", 1, null, 3, 0, 0)))
        dao.insertSongs((1L..3L).map { song(it) })
    }
    @After fun close() { db.close(); File(context.filesDir, "lyrics-repair-1.1.1.done").delete() }
    private fun song(id: Long, lyrics: String? = null) = SongEntity(id, "Song $id", "Artist", 1, albumName = "Album", albumId = 1,
        contentUriString = "content://songs/$id", albumArtUriString = null, duration = 180000, genre = null,
        filePath = "/music/$id.flac", parentDirectoryPath = "/music", lyrics = lyrics)
    private fun stored(id: Long): String? = db.openHelper.readableDatabase.query("SELECT lyrics FROM songs WHERE id = $id").use {
        it.moveToFirst(); if (it.isNull(0)) null else it.getString(0)
    }
    private fun repository(music: MusicDao = dao): MusicRepositoryImpl {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.allowedDirectoriesFlow } returns flowOf(emptySet<String>())
        every { prefs.blockedDirectoriesFlow } returns flowOf(emptySet<String>())
        return MusicRepositoryImpl(context, prefs, mockk(relaxed = true), mockk(relaxed = true), music,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }
    @Test fun `a three MB lyrics row does not break a whole library read or load lyrics into its songs`() = runBlocking {
        db.openHelper.writableDatabase.execSQL("UPDATE songs SET lyrics = ? WHERE id = 2", arrayOf(huge))
        val raw = runCatching {
            db.openHelper.readableDatabase.query("SELECT * FROM songs").use { cursor -> while (cursor.moveToNext()) cursor.getLong(0) }
        }
        println("B43 native SQLite raw window reproduction = ${raw.exceptionOrNull()?.toString() ?: "window accepted the row; projection/cap assertions are direct"}")
        // Reads before the asynchronous upgrade repair must not expose a truncated prefix as lyrics.
        assertThat(dao.getSongByIdOnce(2)?.lyrics).isNull()
        db.openHelper.writableDatabase.execSQL("INSERT INTO lyrics(songId, content, isSynced, source, library_item_id) VALUES (2, ?, 0, 'remote', '')", arrayOf(huge))
        assertThat(db.lyricsDao().getLyrics(2)?.content.isNullOrEmpty()).isTrue()
        val songs = repository().getAllSongsOnce()
        assertThat(songs.map { it.id }).containsExactly("1", "2", "3")
        assertThat(songs.map { it.lyrics }.all { it == null }).isTrue()
    }
    @Test fun `all song write APIs drop overlong lyrics instead of placing them in a CursorWindow row`() = runBlocking {
        dao.updateLyrics(1, huge)
        assertThat(stored(1)).isNull()
        dao.updateSongs(listOf(song(2, huge)))
        assertThat(stored(2)).isNull()
        dao.insertSongsIgnoreConflicts(listOf(song(4, huge)))
        assertThat(stored(4)).isNull()
        db.lyricsDao().insert(LyricsEntity(1, huge, false, "remote"))
        assertThat(db.lyricsDao().getLyrics(1)).isNull()
    }
    @Test fun `binary image base64 and corrupt lyrics are dropped regardless of their size`() = runBlocking {
        for (text in listOf("Hello\u0000world", "Hello\u0001world", "\uFFFD".repeat(30), "\u0089PNG\r\n\u001a\n", "/9j/" + "a".repeat(1000),
            "data:image/png;base64,iVBORw0KGgo", "Ab12+/".repeat(200))) {
            dao.updateLyrics(1, text)
            assertThat(stored(1)).isNull()
            val parsed = LyricsUtils.parseLyrics(text)
            assertThat(parsed.plain.isNullOrEmpty() && parsed.synced.isNullOrEmpty()).isTrue()
        }
    }
    @Test fun `real lyrics in Korean Japanese Arabic and LRC are kept verbatim`() = runBlocking {
        for (text in listOf("밤하늘의 별을 바라봐\n우리의 노래가 들려", "君の声が聞こえる\n夜空に光る星", "أسمع صوتك في الليل\nيا نجمة في السماء",
            "[ar:Artist]\r\n[00:01.00]Hello\tworld\r\n[00:02.30]안녕하세요 世界 مرحباً")) {
            dao.updateLyrics(1, text)
            assertThat(stored(1)).isEqualTo(text)
            val parsed = LyricsUtils.parseLyrics(text)
            assertThat(!parsed.plain.isNullOrEmpty() || !parsed.synced.isNullOrEmpty()).isTrue()
        }
    }
    @Test fun `a cursor failure skips only the unreadable row and keeps the other library songs`() = runBlocking {
        val broken = mockk<MusicDao>(relaxed = true)
        every { broken.getAllSongs(any(), any()) } returns kotlinx.coroutines.flow.flow { throw IllegalStateException("Couldn't read row from CursorWindow") }
        coEvery { broken.getAllSongIds() } returns listOf(1L, 2L, 3L)
        coEvery { broken.getSongListRow(any(), any(), any()) } answers {
            val id = firstArg<Long>()
            if (id == 2L) throw IllegalStateException("Couldn't read row from CursorWindow") else song(id)
        }
        assertThat(repository(broken).getAllSongsOnce().map { it.id }).containsExactly("1", "3")
        coVerify(exactly = 3) { broken.getSongListRow(any(), any(), any()) }
    }
    @Test fun `large artist and genre JSON cannot overflow the list projection either`() = runBlocking<Unit> {
        db.openHelper.writableDatabase.execSQL("UPDATE songs SET artists_json = ?, genres_json = ? WHERE id = 2", arrayOf(huge, huge))
        assertThat(repository().getAllSongsOnce().map { it.id }).containsExactly("1", "2", "3")
    }
    @Test fun `each rejected songs text is logged only once`() = runBlocking {
        val messages = mutableListOf<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { if (tag == "LyricsRepair") messages += message }
        }
        timber.log.Timber.plant(tree)
        try {
            dao.insertSongs(listOf(song(9001)))
            repeat(3) { dao.updateLyrics(9001, "binary\u0000text") }
            assertThat(messages.filter { "9001" in it }).hasSize(1)
        } finally { timber.log.Timber.uproot(tree) }
    }
    @Test fun `startup clears preexisting oversized and binary DB values without changing sidecar files`() = runBlocking {
        val sidecar = File(context.filesDir, "preserved.lrc").apply { writeText("[00:01]Local lyrics") }
        try {
            db.openHelper.writableDatabase.execSQL("UPDATE songs SET lyrics = ? WHERE id = 1", arrayOf(huge))
            db.openHelper.writableDatabase.execSQL("UPDATE songs SET lyrics = ? WHERE id = 2", arrayOf("before\u0000after"))
            dao.updateLyrics(3, "Real lyric line\nAnother line")
            val bad = listOf("data:image/png;base64,iVBORw0KGgo", "/9j/" + "a".repeat(700), "A1b2+/".repeat(200), "\uFFFD".repeat(30), "binary\u0001data")
            bad.forEachIndexed { index, value ->
                val id = 10L + index
                dao.insertSongs(listOf(song(id)))
                db.openHelper.writableDatabase.execSQL("UPDATE songs SET lyrics = ? WHERE id = ?", arrayOf<Any>(value, id))
                db.openHelper.writableDatabase.execSQL("INSERT INTO lyrics(songId, content, isSynced, source, library_item_id) VALUES (?, ?, 0, 'remote', '')", arrayOf<Any>(id, value))
            }
            val type = runCatching { Class.forName("com.lostf1sh.pixelplayeross.data.startup.BrokenLyricsCleanup") }.getOrNull()
            if (type != null) {
                val cleanup = type.getConstructor(Context::class.java, MusicDao::class.java, LyricsDao::class.java).newInstance(context, dao, db.lyricsDao())
                suspendCoroutine<Unit> { continuation ->
                    try {
                        val result = type.methods.single { it.name == "runOnce" }.invoke(cleanup, continuation)
                        if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
                    } catch (error: Throwable) { continuation.resumeWithException(error) }
                }
            }
            assertThat(stored(1)).isNull()
            assertThat(stored(2)).isNull()
            assertThat(stored(3)).isEqualTo("Real lyric line\nAnother line")
            bad.indices.forEach { index ->
                assertThat(stored(10L + index)).isNull()
                assertThat(db.lyricsDao().getLyrics(10L + index)).isNull()
            }
            assertThat(sidecar.readText()).isEqualTo("[00:01]Local lyrics")
        } finally { sidecar.delete() }
    }
}
