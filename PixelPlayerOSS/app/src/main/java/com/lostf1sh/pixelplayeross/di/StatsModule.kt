package com.lostf1sh.pixelplayeross.di

import com.lostf1sh.pixelplayeross.data.historyimport.ImportedHistoryStore
import com.lostf1sh.pixelplayeross.data.historyimport.ImportedStatsProvider
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.data.stats.ClockChanges
import com.lostf1sh.pixelplayeross.data.stats.LedgerProvider
import com.lostf1sh.pixelplayeross.data.stats.ListeningModel
import com.lostf1sh.pixelplayeross.data.stats.RoomCatalogSource
import com.lostf1sh.pixelplayeross.data.stats.SystemClockChanges
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

interface IncludeImportedPreference {
    val includeImported: Flow<Boolean>
    suspend fun setIncludeImported(include: Boolean)
}

@Singleton
class UserPrefsIncludeImportedPreference @Inject constructor(
    private val preferences: UserPreferencesRepository,
) : IncludeImportedPreference {
    override val includeImported: Flow<Boolean> = preferences.includeImportedHistoryFlow
    override suspend fun setIncludeImported(include: Boolean) = preferences.setIncludeImportedHistory(include)
}

/**
 * The seams of the listening model: where the library comes from, where Stats and the collections read the ledger, and (B42-fix1) what tells
 * Stats the device's clock moved.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StatsModule {
    @Binds
    @Singleton
    abstract fun bindCatalogSource(impl: RoomCatalogSource): CatalogSource

    @Binds
    @Singleton
    abstract fun bindLedgerProvider(impl: ListeningModel): LedgerProvider

    @Binds
    @Singleton
    abstract fun bindClockChanges(impl: SystemClockChanges): ClockChanges

    @Binds
    @Singleton
    abstract fun bindImportedStatsProvider(impl: ImportedHistoryStore): ImportedStatsProvider

    @Binds
    @Singleton
    abstract fun bindIncludeImportedPreference(impl: UserPrefsIncludeImportedPreference): IncludeImportedPreference
}
