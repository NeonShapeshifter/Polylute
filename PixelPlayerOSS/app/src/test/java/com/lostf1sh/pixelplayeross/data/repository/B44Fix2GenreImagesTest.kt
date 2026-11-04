package com.lostf1sh.pixelplayeross.data.repository

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.lostf1sh.pixelplayeross.data.backup.module.ArtistImagesModuleHandler
import com.lostf1sh.pixelplayeross.data.database.*
import com.lostf1sh.pixelplayeross.data.model.Genre
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class B44Fix2GenreImagesTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val folder = Files.createTempDirectory("b44-fix2-genres").toFile()
    private val context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = folder
    }
    private val db = Room.inMemoryDatabaseBuilder(app, PixelPlayerDatabase::class.java).build()
    private fun repository(): Any = Class.forName("com.lostf1sh.pixelplayeross.data.repository.GenreImageRepository")
        .getConstructor(Context::class.java, MusicDao::class.java).newInstance(context, db.musicDao())
    private suspend fun call(repository: Any, method: String, vararg args: Any): Any? = suspendCoroutineUninterceptedOrReturn { continuation ->
        val target = repository.javaClass.methods.single { it.name == method && it.parameterCount == args.size + 1 }
        target.invoke(repository, *args, continuation)
    }
    @Suppress("UNCHECKED_CAST")
    private suspend fun path(repository: Any, key: String): String? =
        (repository.javaClass.getMethod("observe", String::class.java).invoke(repository, key) as Flow<String?>).first()
    private fun source(name: String, color: Int): File = File(folder, name).also { file ->
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @After fun cleanup() {
        db.close()
        folder.deleteRecursively()
    }

    @Test fun `set change and remove own a stable copy for merged genre spellings`(): Unit = runBlocking {
        val images = repository()
        val firstKey = Genre.idFor("Rock/Pop")
        val secondKey = Genre.idFor("rock pop")
        assertThat(firstKey).isEqualTo(secondKey)
        val original = source("picked.png", Color.RED)
        val first = call(images, "setImage", firstKey, Uri.fromFile(original)) as String
        original.delete()
        assertThat(File(first).isFile).isTrue()
        assertThat(path(images, secondKey)).isEqualTo(first)
        val second = call(images, "setImage", secondKey, Uri.fromFile(source("replacement.png", Color.BLUE))) as String
        assertThat(second).isNotEqualTo(first)
        assertThat(File(first).exists()).isFalse()
        assertThat(File(second).isFile).isTrue()
        assertThat(path(images, firstKey)).isEqualTo(second)
        call(images, "removeImage", firstKey)
        assertThat(path(images, secondKey)).isNull()
        assertThat(File(second).exists()).isFalse()
    }

    @Test fun `genre images survive backup restore and old artist backups remain compatible`(): Unit = runBlocking {
        val images = repository()
        val key = Genre.idFor("Rock")
        val original = call(images, "setImage", key, Uri.fromFile(source("backup.png", Color.GREEN))) as String
        val bytes = File(original).readBytes()
        val handler = ArtistImagesModuleHandler(context, db.musicDao(), Gson())
        val backup = handler.export()
        assertThat(backup).contains("genreKey")
        call(images, "removeImage", key)
        handler.restore(backup)
        val restored = path(images, key)!!
        assertThat(File(restored).readBytes()).isEqualTo(bytes)
        db.musicDao().insertArtistsIgnoreConflicts(listOf(ArtistEntity(7, "Old Artist", 1)))
        handler.restore("""[{"artistName":"Old Artist","imageUrl":"https://example.test/old.jpg"}]""")
        assertThat(db.musicDao().getArtistImageUrl(7)).isEqualTo("https://example.test/old.jpg")
        assertThat(path(images, key)).isEqualTo(restored)
        val snapshot = handler.snapshot()
        call(images, "setImage", "jazz", Uri.fromFile(source("new.png", Color.RED)))
        handler.rollback(snapshot)
        assertThat(path(images, "jazz")).isNull()
        assertThat(path(images, key)).isNotNull()
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `picked images propagate through the Library genre flow and page state`(): Unit = runBlocking {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        val preferences = io.mockk.mockk<com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository>(relaxed = true)
        io.mockk.every { preferences.allowedDirectoriesFlow } returns kotlinx.coroutines.flow.flowOf(emptySet())
        io.mockk.every { preferences.blockedDirectoriesFlow } returns kotlinx.coroutines.flow.flowOf(emptySet())
        db.musicDao().insertArtistsIgnoreConflicts(listOf(ArtistEntity(1, "Artist", 1)))
        db.musicDao().insertAlbumsIgnoreConflicts(listOf(AlbumEntity(10, "Album", "Artist", 1, null, 1, 0, 2026)))
        val song = com.lostf1sh.pixelplayeross.data.model.Song.emptySong().copy(id = "1", title = "Song", artistId = 1, albumId = 10, genre = "Rock Pop, rock pop")
        db.musicDao().insertSongsIgnoreConflicts(listOf(song.toEntity("/music/1.flac", "/music")))
        val music = MusicRepositoryImpl(context, preferences, io.mockk.mockk(relaxed = true), db.searchHistoryDao(), db.musicDao(),
            io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), db.favoritesDao(), io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true))
        val images = repository()
        var viewModel: com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreDetailViewModel? = null
        fun image(genre: Genre?): String? = genre?.javaClass?.getDeclaredField("imagePath")?.apply { isAccessible = true }?.get(genre) as? String
        try {
            val chosen = call(images, "setImage", "rock_pop", Uri.fromFile(source("propagate.png", Color.RED))) as String
            val genres = kotlinx.coroutines.withTimeout(5000) { music.getGenres().first { rows -> rows.any { image(it) == chosen } } }
            assertThat(genres).hasSize(1)
            assertThat(genres.single().id).isEqualTo("rock_pop")
            val type = com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreDetailViewModel::class.java
            viewModel = type.constructors.single { it.parameterCount == 5 }.newInstance(music,
                androidx.lifecycle.SavedStateHandle(mapOf("genreId" to "rock_pop")),
                com.lostf1sh.pixelplayeross.presentation.viewmodel.TestDispatcherProvider(kotlinx.coroutines.Dispatchers.Unconfined), images, io.mockk.mockk<com.lostf1sh.pixelplayeross.data.stats.LedgerProvider>(relaxed = true)) as com.lostf1sh.pixelplayeross.presentation.viewmodel.GenreDetailViewModel
            kotlinx.coroutines.withTimeout(5000) { viewModel.uiState.first { image(it.genre) == chosen } }
            call(images, "removeImage", "rock_pop")
            kotlinx.coroutines.withTimeout(5000) { viewModel.uiState.first { it.genre != null && image(it.genre) == null } }
            val rows = music.getGenres().first { it.isNotEmpty() }
            assertThat(image(rows.single())).isNull()
        } finally {
            viewModel?.viewModelScope?.cancel()
            (music.javaClass.getDeclaredField("repositoryScope").apply { isAccessible = true }.get(music) as kotlinx.coroutines.CoroutineScope).cancel()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

}
