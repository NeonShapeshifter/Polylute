package com.lostf1sh.pixelplayeross.data.network

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** A failed request is not evidence that an asset does not exist. Kept local to one lookup. */
class LookupFailures : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LookupFailures>
    @Volatile var failed = false
    @Volatile var wait: SourceWait? = null
}

sealed interface RemoteOutcome<out T> {
    data class Found<T>(val value: T) : RemoteOutcome<T>
    data object NotFound : RemoteOutcome<Nothing>
    data object Failed : RemoteOutcome<Nothing>
    companion object {
        suspend fun <T : Any> lookup(onFailure: (SourceWait?) -> Unit = {}, block: suspend () -> T?): RemoteOutcome<T> {
            val failures = LookupFailures()
            return withContext(failures) {
                try {
                    val result = block()
                    if (result != null) Found(result) else if (failures.failed) { onFailure(failures.wait); Failed } else NotFound
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onFailure((error as? SourceRequestException)?.wait ?: failures.wait); Failed }
            }
        }
    }
}

suspend fun noteLookupFailure() { currentCoroutineContext()[LookupFailures]?.failed = true }
