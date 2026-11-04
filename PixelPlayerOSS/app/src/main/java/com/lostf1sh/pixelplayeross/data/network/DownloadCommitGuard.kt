package com.lostf1sh.pixelplayeross.data.network

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Checked inside the existing deletion/save lock, immediately before writing any downloaded asset. */
class DownloadCommitGuard(
    val allowed: suspend () -> Boolean,
    val committed: suspend () -> Unit = {},
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DownloadCommitGuard>
}
