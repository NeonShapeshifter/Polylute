package com.lostf1sh.pixelplayeross.di

import javax.inject.Qualifier

/**
 * Qualifier for Deezer Retrofit instance.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeezerRetrofit

/**
 * Qualifier for Fast OkHttpClient (Short timeouts).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FastOkHttpClient

/**
 * Qualifier for Gson instance configured for backup serialization.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackupGson

/**
 * Qualifier for application-lifetime coroutine scope.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

/**
 * Qualifier for an application-lifetime coroutine scope that runs on the main dispatcher.
 *
 * Distinct from [AppScope], which is an IO scope for background work. Code that touches a
 * [androidx.media3.session.MediaController] must run here: Media3 enforces main-thread affinity and
 * throws from any other thread.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainAppScope
