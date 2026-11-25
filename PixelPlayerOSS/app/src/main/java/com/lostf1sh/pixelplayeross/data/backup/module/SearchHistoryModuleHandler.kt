package com.lostf1sh.pixelplayeross.data.backup.module

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupResolutionCounts
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupRestoreMode
import com.lostf1sh.pixelplayeross.data.database.SearchHistoryDao
import com.lostf1sh.pixelplayeross.data.database.SearchHistoryEntity
import com.lostf1sh.pixelplayeross.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchHistoryModuleHandler @Inject constructor(
    private val searchHistoryDao: SearchHistoryDao,
    @BackupGson private val gson: Gson
) : BackupModuleHandler, ReportableRestoreHandler {

    override val section = BackupSection.SEARCH_HISTORY

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        gson.toJson(searchHistoryDao.getAll())
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        searchHistoryDao.getAll().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) {
        restoreWithReport(payload, BackupRestoreMode.REPLACE)
    }

    override suspend fun restoreWithReport(
        payload: String,
        mode: BackupRestoreMode,
    ): BackupResolutionCounts = withContext(Dispatchers.IO) {
        val type = TypeToken.getParameterized(List::class.java, SearchHistoryEntity::class.java).type
        val history: List<SearchHistoryEntity> = gson.fromJson(payload, type) ?: emptyList()
        if (mode == BackupRestoreMode.REPLACE) {
            searchHistoryDao.replaceAll(history)
        } else {
            val existingQueries = searchHistoryDao.getAll().mapTo(HashSet()) { it.query }
            val toInsert = history.filter { it.query !in existingQueries }
            if (toInsert.isNotEmpty()) {
                searchHistoryDao.insertAll(toInsert)
            }
        }
        BackupResolutionCounts()
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)
}
