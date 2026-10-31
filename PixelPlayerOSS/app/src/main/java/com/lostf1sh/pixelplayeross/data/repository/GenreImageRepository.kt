package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import android.net.Uri
import com.lostf1sh.pixelplayeross.data.database.GenreImageEntity
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.model.Genre
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Only files chosen by the listener. Keys are the Library's existing Genre.idFor keys. */
@Singleton
class GenreImageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao,
) {
    private fun key(value: String): String = Genre.idFor(value.trim())

    fun observe(genreKey: String) = musicDao.observeGenreImages().map { rows ->
        rows.firstOrNull { it.genreKey == key(genreKey) }?.imagePath
    }.distinctUntilChanged()

    suspend fun setImage(genreKey: String, source: Uri): String? = replace(genreKey) { name ->
        CustomImageFiles.copy(context, source, name)
    }

    suspend fun restoreImage(genreKey: String, bytes: ByteArray): String? = replace(genreKey) { name ->
        CustomImageFiles.restore(context, bytes, name)
    }

    private suspend fun replace(genreKey: String, copy: (String) -> String?): String? = withContext(Dispatchers.IO) {
        mutations.withLock {
            val normalized = key(genreKey)
            val previous = musicDao.getGenreImage(normalized)?.imagePath
            val path = copy("genre_art_${UUID.randomUUID()}.jpg") ?: return@withLock null
            try {
                musicDao.setGenreImage(GenreImageEntity(normalized, path, System.currentTimeMillis()))
            } catch (error: Exception) {
                CustomImageFiles.remove(context, path, "genre_art_")
                throw error
            }
            CustomImageFiles.remove(context, previous, "genre_art_")
            path
        }
    }

    suspend fun removeImage(genreKey: String) = withContext(Dispatchers.IO) {
        mutations.withLock {
            val normalized = key(genreKey)
            val previous = musicDao.getGenreImage(normalized)?.imagePath
            musicDao.deleteGenreImage(normalized)
            CustomImageFiles.remove(context, previous, "genre_art_")
        }
    }

    companion object {
        private val mutations = Mutex()
    }
}
