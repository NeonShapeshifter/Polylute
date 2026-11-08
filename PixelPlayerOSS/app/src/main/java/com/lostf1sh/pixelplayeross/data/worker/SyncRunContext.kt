package com.lostf1sh.pixelplayeross.data.worker

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * B58 2 (#68): what a library sync lends the steps it runs, carried by the coroutine so that their own signatures stay as they were.
 *
 * @property inTransaction runs the writes it is given as ONE transaction (the DAO's), so that the observed library tables are invalidated once, when it commits, and not once per
 *   statement. A step that finds no [SyncRunContext] (a test, or any caller outside a sync) writes as it always did.
 * @property onProgress how far the step is: items done of items in all (a total of 0 = "working, cannot count").
 * @property fullRescan B61: this sync re-reads every file (a Full Rescan): what earlier syncs remembered as "decided, nothing to do" is forgotten and decided again.
 */
class SyncRunContext(
    val inTransaction: suspend (suspend () -> Unit) -> Unit,
    val onProgress: suspend (done: Int, total: Int) -> Unit,
    val fullRescan: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SyncRunContext>
}
