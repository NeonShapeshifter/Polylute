package com.lostf1sh.pixelplayeross.di

import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * What the modal input bridge reaches the app's authorities through.
 *
 * The bridge runs inside whatever composition a modal window happens to have — a `Dialog`, a
 * `ModalBottomSheet`, several of which build their own view tree — so it cannot assume it is below
 * any composition local the activity provides. It asks Hilt instead, which is the one thing that is
 * true in every one of those windows.
 *
 * Both are the same `@Singleton` instances the activity holds, so the modal path and the activity
 * path are not two authorities that have to be kept in agreement: they are one.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ModalInputEntryPoint {
    fun safetyVolumeController(): SafetyVolumeController

    fun userPreferencesRepository(): UserPreferencesRepository
}
