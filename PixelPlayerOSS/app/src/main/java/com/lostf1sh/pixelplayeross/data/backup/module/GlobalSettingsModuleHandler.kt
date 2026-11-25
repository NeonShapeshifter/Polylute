package com.lostf1sh.pixelplayeross.data.backup.module

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupResolutionCounts
import com.lostf1sh.pixelplayeross.data.backup.restore.BackupRestoreMode
import com.lostf1sh.pixelplayeross.data.preferences.PreferenceBackupEntry
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobalSettingsModuleHandler @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    @BackupGson private val gson: Gson
) : BackupModuleHandler, ReportableRestoreHandler {

    override val section = BackupSection.GLOBAL_SETTINGS

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val entries = userPreferencesRepository.exportPreferencesForBackup()
            .filter { it.key !in EXCLUDED_KEYS }
        gson.toJson(entries)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        userPreferencesRepository.exportPreferencesForBackup()
            .count { it.key !in EXCLUDED_KEYS }
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) {
        restoreWithReport(payload, BackupRestoreMode.REPLACE)
    }

    override suspend fun restoreWithReport(
        payload: String,
        mode: BackupRestoreMode,
    ): BackupResolutionCounts = withContext(Dispatchers.IO) {
        val type = TypeToken.getParameterized(List::class.java, PreferenceBackupEntry::class.java).type
        val entries: List<PreferenceBackupEntry> = gson.fromJson(payload, type) ?: emptyList()
        val safeEntries = entries.filter { it.key !in EXCLUDED_KEYS }
        if (mode == BackupRestoreMode.REPLACE) {
            userPreferencesRepository.clearPreferencesExceptKeys(
                PlaylistsModuleHandler.PLAYLIST_KEYS +
                    QuickFillModuleHandler.QUICK_FILL_KEYS +
                    EqualizerModuleHandler.EQUALIZER_KEYS
            )
            if (safeEntries.isNotEmpty()) {
                userPreferencesRepository.importPreferencesFromBackup(safeEntries, clearExisting = false)
            }
        } else {
            val existingKeys = userPreferencesRepository.exportPreferencesForBackup().mapTo(HashSet()) { it.key }
            val missing = safeEntries.filter { it.key !in existingKeys }
            if (missing.isNotEmpty()) {
                userPreferencesRepository.importPreferencesFromBackup(missing, clearExisting = false)
            }
        }
        BackupResolutionCounts()
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)

    companion object {
        /** Keys managed by dedicated module handlers, excluded from global settings. */
        val EXCLUDED_KEYS = PlaylistsModuleHandler.PLAYLIST_KEYS +
            QuickFillModuleHandler.QUICK_FILL_KEYS +
            EqualizerModuleHandler.EQUALIZER_KEYS
    }
}
