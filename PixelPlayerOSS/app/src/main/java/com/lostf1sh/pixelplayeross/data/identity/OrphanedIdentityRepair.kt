package com.lostf1sh.pixelplayeross.data.identity

import android.content.Context
import com.lostf1sh.pixelplayeross.data.database.LibraryItemDao
import com.lostf1sh.pixelplayeross.data.database.RepairDormantRow
import com.lostf1sh.pixelplayeross.data.database.RepairLiveRow
import com.lostf1sh.pixelplayeross.data.service.PlaybackActivityTracker
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * B57 1.2. Before the metadata editor recorded its evidence (1.1), a tag write that changed the file's size made the next scan mint a new
 * identity for the same file at the same locator, and the old item went dormant carrying the favourites, plays, playlists, lyrics and
 * bookmarks. This finds those pairs once and moves the state back, **only when the evidence is unambiguous**; any doubt is counted, logged and
 * left alone (a history that stays detached is a small loss; one attached to the wrong track is invisible).
 *
 * A pair (dormant D, live N) is merged when all of these hold:
 *  1. **Same locator.** Every locator D's user-state rows name is the same one, L, and N's projection sits on L. (A dormant item keeps no path:
 *     its projection was dropped, so the locator its own rows still carry is the only link to the file.)
 *  2. **Created by the rescan after the write.** N was first seen at or after D was last seen.
 *  3. **Same duration.** D's stored file signature is the hash of (duration, size); N's duration with *some* size within [SIZE_WINDOW] of
 *     N's size reproduces it. A hash match is proof, not a guess. D with no signature cannot be proved: not merged.
 *  4. **Nothing of N's own.** N has no user-state rows and the history does not name it (otherwise two histories would be mixed).
 *  5. **One to one.** D has exactly one candidate and no other dormant item has the same candidate.
 *
 * Idempotent: a merged D is deleted, so a second run finds nothing, and a marker file ends the repair once a pass completes. It runs off
 * the main thread, never while music plays, and stops (to resume next start) when cancelled.
 */
@Singleton
class OrphanedIdentityRepair @Inject constructor(
    @ApplicationContext context: Context,
    private val items: LibraryItemDao,
    private val history: PlaybackStatsRepository,
) {
    private val marker = File(context.filesDir, MARKER_NAME)
    private val gate = Mutex()

    /** Test hook: the old file's size minus the live file's size that the duration proof tries. */
    internal var sizeWindow: LongRange = SIZE_WINDOW

    /** Test hook: whether music is playing. */
    internal var isPlaying: () -> Boolean = { PlaybackActivityTracker.isPlaybackActive }

    data class Outcome(
        val merged: Int = 0,
        val eventsMoved: Int = 0,
        val noLocatorEvidence: Int = 0,
        val locatorsDisagree: Int = 0,
        val noLiveItem: Int = 0,
        val notCreatedAfter: Int = 0,
        val durationUnproven: Int = 0,
        val liveHasState: Int = 0,
        val ambiguous: Int = 0,
        val skippedBecausePlaying: Boolean = false,
    ) {
        val unmerged: Int get() = noLocatorEvidence + locatorsDisagree + noLiveItem + notCreatedAfter + durationUnproven + liveHasState + ambiguous
        fun describe(): String = "merged=$merged eventsMoved=$eventsMoved unmerged=$unmerged " +
            "(noLocatorEvidence=$noLocatorEvidence locatorsDisagree=$locatorsDisagree noLiveItem=$noLiveItem notCreatedAfter=$notCreatedAfter " +
            "durationUnproven=$durationUnproven liveHasState=$liveHasState ambiguous=$ambiguous)"
    }

    /** Runs the repair once per install: the marker is written when a pass has looked at every dormant item. Returns null when it had already run. */
    suspend fun runOnce(): Outcome? = withContext(Dispatchers.IO) {
        gate.withLock {
            if (marker.exists()) return@withLock null
            if (isPlaying()) {
                Timber.tag(TAG).i("Identity repair waits: music is playing")
                return@withLock Outcome(skippedBecausePlaying = true)
            }
            val outcome = runPass()
            marker.writeText(outcome.describe())
            outcome
        }
    }

    private suspend fun runPass(): Outcome {
        val namedByHistory = history.withHistoryIdentities { it }
        var outcome = Outcome()
        val dormant = items.dormantForRepair()

        // 1-4 per dormant item; the candidate it settles on (or null) is kept for the one-to-one check.
        val settled = LinkedHashMap<String, Pair<RepairDormantRow, RepairLiveRow>>()
        for (d in dormant) {
            currentCoroutineContext().ensureActive()
            if (items.userStateRowCount(d.libraryItemId) == 0) continue // nothing to give back
            val locators = items.userStateLocators(d.libraryItemId).filter { it > 0L }.toSet()
            if (locators.isEmpty()) { outcome = outcome.copy(noLocatorEvidence = outcome.noLocatorEvidence + 1); continue }
            if (locators.size > 1) { outcome = outcome.copy(locatorsDisagree = outcome.locatorsDisagree + 1); continue }
            val live = items.liveAtLocator(locators.single(), excluding = d.libraryItemId)
            if (live.isEmpty()) { outcome = outcome.copy(noLiveItem = outcome.noLiveItem + 1); continue }
            if (live.size > 1) { outcome = outcome.copy(ambiguous = outcome.ambiguous + 1); continue }
            val n = live.single()
            if (n.firstSeenAt < d.lastSeenAt) { outcome = outcome.copy(notCreatedAfter = outcome.notCreatedAfter + 1); continue }
            if (items.userStateRowCount(n.libraryItemId) > 0 || n.libraryItemId in namedByHistory) {
                outcome = outcome.copy(liveHasState = outcome.liveHasState + 1); continue
            }
            if (!sameDuration(d, n)) { outcome = outcome.copy(durationUnproven = outcome.durationUnproven + 1); continue }
            settled[d.libraryItemId] = d to n
        }

        // 5: one to one.
        val claimedBy = settled.values.groupBy { it.second.libraryItemId }
        val moves = LinkedHashMap<String, String>()
        for ((dId, pair) in settled) {
            if ((claimedBy[pair.second.libraryItemId]?.size ?: 0) != 1) { outcome = outcome.copy(ambiguous = outcome.ambiguous + 1); continue }
            currentCoroutineContext().ensureActive()
            items.mergeOrphan(from = dId, to = pair.second.libraryItemId)
            moves[dId] = pair.second.libraryItemId
            outcome = outcome.copy(merged = outcome.merged + 1)
            Timber.tag(TAG).i("Identity repair: moved the state of dormant %s onto %s (locator %d)", dId, pair.second.libraryItemId, pair.second.locator)
        }
        val eventsMoved = try {
            history.reassignIdentities(moves)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The database moves are committed; the plays that named the old item keep naming it until a later run cannot see them. Say so.
            Timber.tag(TAG).w(e, "Identity repair: could not rewrite the history for %d moved items", moves.size)
            0
        }
        outcome = outcome.copy(eventsMoved = eventsMoved)
        Timber.tag(TAG).i("Identity repair: %s", outcome.describe())
        return outcome
    }

    /** Proof of equal duration: N's duration with a size near N's size reproduces D's stored file signature. */
    private suspend fun sameDuration(d: RepairDormantRow, n: RepairLiveRow): Boolean {
        if (d.fileSignature.isEmpty() || n.duration <= 0L || n.fileSize <= 0L) return false
        val low = (n.fileSize + sizeWindow.first).coerceAtLeast(1L)
        val high = n.fileSize + sizeWindow.last
        return FileSignatureSearch.findSize(n.duration, d.fileSignature, low..high) != null
    }

    companion object {
        private const val TAG = "IdentityRepair"
        const val MARKER_NAME = "identity-repair-b57.done"

        /**
         * The old file's size minus the live file's: a tag write usually grows the file (a cover, lyrics), so the old one is smaller, by up to 6 MiB; a write that removes
         * a cover or trims a tag leaves it larger, by up to 1 MiB. About 7 million tries, about a second of one core.
         */
        val SIZE_WINDOW: LongRange = -(6L shl 20)..(1L shl 20)
    }
}

/**
 * Finds the size that, with a duration, hashes to a stored file signature, without changing [LibraryIdentity].
 *
 * `computeFileSignature` hashes `"<duration><separator><size>"` with SHA-1. The separator is private to the frozen identity file, so it is learned once by
 * asking the real function (the character that makes our hash of a probe equal its answer), and `FileSignatureSearchTest` proves the search agrees with
 * `computeFileSignature` for any input. The loop reuses one digest and one buffer: millions of tries in about a second.
 */
internal object FileSignatureSearch {
    private val separator: Char by lazy {
        val probe = LibraryIdentity.computeFileSignature(1L, 1L)
        val digest = MessageDigest.getInstance("SHA-1")
        var found: Char? = null
        for (code in 0 until 0x10000) {
            digest.reset()
            if (hex(digest.digest("1${code.toChar()}1".toByteArray(Charsets.UTF_8))) == probe) { found = code.toChar(); break }
        }
        checkNotNull(found) { "could not learn the file signature separator" }
    }

    /** The first size in [sizes] whose signature with [durationMs] equals [expected], or null. */
    suspend fun findSize(durationMs: Long, expected: String, sizes: LongRange): Long? {
        val wanted = unhex(expected) ?: return null
        val digest = MessageDigest.getInstance("SHA-1")
        val prefix = "${durationMs.coerceAtLeast(0L)}$separator".toByteArray(Charsets.UTF_8)
        val buffer = ByteArray(prefix.size + 20)
        System.arraycopy(prefix, 0, buffer, 0, prefix.size)
        val out = ByteArray(20)
        var tried = 0
        var size = sizes.first
        while (size <= sizes.last) {
            var length = prefix.size
            // decimal digits of a non-negative number, written in place
            val value = size.coerceAtLeast(0L)
            val start = length
            if (value == 0L) { buffer[length++] = '0'.code.toByte() } else {
                var rest = value
                var digits = 0
                while (rest > 0) { digits++; rest /= 10 }
                var position = start + digits
                rest = value
                while (rest > 0) { buffer[--position] = ('0'.code + (rest % 10).toInt()).toByte(); rest /= 10 }
                length = start + digits
            }
            digest.update(buffer, 0, length)
            digest.digest(out, 0, 20)
            if (out[0] == wanted[0] && out.contentEquals(wanted)) return size
            if ((++tried and 0xFFFF) == 0) currentCoroutineContext().ensureActive()
            size++
        }
        return null
    }

    private val HEX = "0123456789abcdef".toCharArray()
    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) { bytes.forEach { b -> append(HEX[(b.toInt() and 0xFF) ushr 4]); append(HEX[b.toInt() and 0x0F]) } }
    private fun unhex(text: String): ByteArray? {
        if (text.length != 40) return null
        return ByteArray(20) { i -> (Character.digit(text[i * 2], 16) shl 4 or Character.digit(text[i * 2 + 1], 16)).toByte() }
    }
}
