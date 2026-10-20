package com.lostf1sh.pixelplayeross.presentation.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * One piece of work per key at a time, shared by everyone who asks for it meanwhile (B29-L11).
 *
 * The cover palette is asked for from several places for the same cover at the same moment (the song change, the sheet's own request,
 * a preload): each used to decode its own 128 x 128 bitmap and run its own extraction. Here the first caller starts the work **in [scope]**,
 * not in its own coroutine, and every other caller awaits the same [Deferred]. A caller that is cancelled (a fast skip leaves the song
 * it asked for) stops waiting and nothing else: the work finishes, the palette is cached, and whoever asked for it still gets it.
 * Once the work is done the key is free again, so a later request runs fresh (the caller's own caches decide whether it has to).
 *
 * Thread-safe; [run] may be called from any dispatcher.
 */
internal class SingleFlight<K : Any, V>(private val scope: CoroutineScope) {
    private val lock = Any()
    private val running = HashMap<K, Deferred<V>>()

    /** How many keys have work in flight now. For tests and logs. */
    val inFlight: Int get() = synchronized(lock) { running.size }

    suspend fun run(key: K, work: suspend () -> V): V {
        val deferred = synchronized(lock) {
            running[key] ?: scope.async { work() }.also { started ->
                running[key] = started
                started.invokeOnCompletion { synchronized(lock) { if (running[key] === started) running.remove(key) } }
            }
        }
        return deferred.await()
    }
}
