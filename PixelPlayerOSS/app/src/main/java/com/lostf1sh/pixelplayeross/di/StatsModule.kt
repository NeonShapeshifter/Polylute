package com.lostf1sh.pixelplayeross.di

import com.lostf1sh.pixelplayeross.data.stats.CatalogSource
import com.lostf1sh.pixelplayeross.data.stats.LedgerProvider
import com.lostf1sh.pixelplayeross.data.stats.ListeningModel
import com.lostf1sh.pixelplayeross.data.stats.RoomCatalogSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The two seams of the listening model: where the library comes from, and where Stats and the collections read the ledger. */
@Module
@InstallIn(SingletonComponent::class)
abstract class StatsModule {
    @Binds
    @Singleton
    abstract fun bindCatalogSource(impl: RoomCatalogSource): CatalogSource

    @Binds
    @Singleton
    abstract fun bindLedgerProvider(impl: ListeningModel): LedgerProvider
}
