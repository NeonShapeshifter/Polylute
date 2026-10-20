package com.lostf1sh.pixelplayeross.data.stats

import com.lostf1sh.pixelplayeross.data.database.MusicDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Names the library item a playing locator belongs to (B28a).
 *
 * The player's media items carry the MediaStore locator and nothing else, so the listening tracker asks for the
 * durable identity when it opens a session, off the main thread, rather than every queue item growing by one
 * 36-character id on every publication. A locator that is not a library row (an external VIEW item is not a
 * number) has no identity, and says so by returning null.
 */
@Singleton
class LibraryIdentityLookup @Inject constructor(
    private val musicDao: MusicDao,
) {
    suspend fun identityOf(locator: String): String? {
        val id = locator.toLongOrNull() ?: return null
        return musicDao.libraryItemIdForLocator(id)?.takeIf { it.isNotBlank() }
    }
}
