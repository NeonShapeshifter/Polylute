package com.lostf1sh.pixelplayeross.data.network

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext

/** A lookup can be promoted when playback joins a search first started by an offline job. */
class RequestPriority(@Volatile var background: Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestPriority>
}

/** FIFO within each class. The next interactive caller passes all queued batch work. Cancellation releases ownership exactly once. */
internal class PriorityRequestGate(private val promoteOwner: Boolean = false) {
    private class Waiter(val priority: RequestPriority?, val ready: CompletableDeferred<Unit> = CompletableDeferred())
    private val lock = Any()
    private val waiting = mutableListOf<Waiter>()
    private var busy = false
    private var active: Waiter? = null
    suspend fun <T> withPermit(block: suspend () -> T): T {
        val waiter = Waiter(currentCoroutineContext()[RequestPriority])
        synchronized(lock) {
            if (promoteOwner && waiter.priority?.background != true) active?.priority?.background = false
            waiting += waiter
            if (!busy) dispatch()
        }
        try { waiter.ready.await() }
        catch (error: Throwable) {
            synchronized(lock) { if (!waiting.remove(waiter)) dispatch() }
            throw error
        }
        try { return block() } finally { synchronized(lock) { dispatch() } }
    }
    private fun dispatch() {
        val next = waiting.firstOrNull { it.priority?.background != true } ?: waiting.firstOrNull()
        active = next
        busy = next != null
        if (next != null) { waiting.remove(next); next.ready.complete(Unit) }
    }
}
