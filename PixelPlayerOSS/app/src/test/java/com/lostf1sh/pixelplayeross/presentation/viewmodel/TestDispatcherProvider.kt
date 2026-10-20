package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.di.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Routes every dispatcher a holder asks for onto one test dispatcher, so a holder that used to
 * escape virtual time through a literal `Dispatchers.IO` now stays on the scheduler the test
 * controls.
 */
class TestDispatcherProvider(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
}
