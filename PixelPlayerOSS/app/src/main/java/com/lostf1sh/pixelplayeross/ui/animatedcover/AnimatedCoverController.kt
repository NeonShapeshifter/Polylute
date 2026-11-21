package com.lostf1sh.pixelplayeross.ui.animatedcover

import android.view.View
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * B56 phase 7.2/7.3 (#48): one animation on the screen. What is behind it (a video player, an animated drawable) is the engine's business; the controller decides **when one exists**.
 * An engine is created for one [AnimatedCover], shows itself in [view], says when its first frame is drawn ([firstFrame]: the still cover shows until then) and obeys [setRunning].
 */
interface AnimatedCoverEngine {
    /** What the player adds to the cover's page (null for the tests' fakes). */
    val view: View?

    /** True once a frame of the animation is on the view. */
    val firstFrame: StateFlow<Boolean>

    /** Play (true) or hold the frame it is on (false). The decoder stays. */
    fun setRunning(running: Boolean)

    /** Viewer fits the complete artwork; cover surfaces crop. This never creates another decoder. */
    fun setFit(fit: Boolean) {}

    /** Stops, releases the decoder, its threads and its surface. Safe to call twice. */
    fun release()
}

/** Makes engines. [create] returns null when the cover cannot be played here at all (too big, no decoder). A later failure is reported through [onFailed], on the main thread. */
fun interface AnimatedCoverEngineFactory {
    fun create(cover: AnimatedCover, sizePx: Int, onFailed: (reason: String) -> Unit): AnimatedCoverEngine?
}

/**
 * Owns the one engine the player may have. The rules, each of them a test:
 *
 * - **at most one decoder at a time**, and a new song's cover is made only after the old one has been stopped and released (see [update]);
 * - an engine exists only while the policy holds ([AnimatedCoverPolicy.shouldHold]) and it is released on leave (the cover page, the player closed, the setting off);
 * - it runs only while [Want.run]; otherwise it holds its frame, and a cover that has not been seen for [idleReleaseMs] (screen off, app in the background, lock screen) is released
 *   and made again, from its first frame, when it is seen again;
 * - a cover that fails (no decoder, a broken file, a timeout) is remembered by its identity and not tried again, and the still cover stays: **silently**, with one log line.
 *
 * Main thread only. [scope] is the main scope of the composition that owns it.
 */
class AnimatedCoverController(
    private val scope: CoroutineScope,
    private val factory: AnimatedCoverEngineFactory,
    private val idleReleaseMs: Long = IDLE_RELEASE_MS,
    private val log: (String) -> Unit = { Timber.tag(TAG).i(it) },
    /**
     * B57.5 2.2: one line for every engine made and every engine released, with the reason and the policy's inputs at that moment (the tag is [TAG], at info). Kept apart from [log], which
     * is the "falls back to the still cover" line and nothing else, so a device log can say *why* a cover came and went and not only that a decoder failed.
     */
    private val events: (String) -> Unit = { Timber.tag(TAG).i(it) },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** What the screen asks of the controller now. [cover] is the song's animated cover (null: it has none). */
    @Immutable
    data class Want(
        val cover: AnimatedCover?,
        val sizePx: Int,
        val hold: Boolean,
        val visible: Boolean,
        val run: Boolean,
    )

    private val _engine = MutableStateFlow<AnimatedCoverEngine?>(null)

    /** The engine to show over the still cover, or null. */
    val engine: StateFlow<AnimatedCoverEngine?> = _engine.asStateFlow()

    /** The cover [engine] plays, or null. */
    var current: AnimatedCover? = null
        private set

    /**
     * B61 item 5 (#70): the last lookup any surface made (the song's key and what the finder answered), so a surface that takes over (the lock screen over the open player, and back) starts from the
     * answer the other already has and does not ask "no cover yet" of a controller that is playing it (which would release the decoder, to make it again a moment later). Main thread only.
     */
    var sharedLookup: Pair<String, AnimatedCover?>? = null

    // B61 item 5 (#70): the one engine has one view, and two surfaces can ask for it (the full player's cover page and Polylute's lock screen over it). The surface that claimed it last shows it;
    // when that one goes, the one before it gets it back. A surface that is not the owner draws nothing, so the view is never in two parents and never stolen in the middle of a recomposition.
    private val viewClaims = ArrayList<Any>()
    private val _viewOwner = MutableStateFlow<Any?>(null)

    /** The surface that shows [engine]'s view now, or null. */
    val viewOwner: StateFlow<Any?> = _viewOwner.asStateFlow()

    fun claimView(token: Any) {
        viewClaims.remove(token)
        viewClaims.add(token)
        _viewOwner.value = token
    }

    fun releaseView(token: Any) {
        viewClaims.remove(token)
        _viewOwner.value = viewClaims.lastOrNull()
    }

    // Active compositions own requests. Priority follows the surfaces: lock, full player, album.
    // Releasing one request immediately restores the next, including when its cover is the same file.
    private data class Request(val want: Want, val priority: Int, val inputs: String)
    private val requests = LinkedHashMap<Any, Request>()
    private val _surfaceOwner = MutableStateFlow<Any?>(null)
    internal val surfaceOwner = _surfaceOwner.asStateFlow()

    internal fun request(token: Any, want: Want, priority: Int, inputs: String) {
        requests[token] = Request(want, priority, inputs)
        reconcileRequests()
    }

    internal fun releaseRequest(token: Any) {
        requests.remove(token)
        reconcileRequests()
    }

    private fun reconcileRequests() {
        val selected = requests.entries.filter { it.value.want.hold }.maxByOrNull { it.value.priority }
        _surfaceOwner.value = selected?.key
        val request = selected?.value ?: requests.values.maxByOrNull { it.priority }
        update(request?.want ?: Want(null, 0, false, false, false), request?.inputs ?: "surface left")
    }

    private val failed = LinkedHashSet<String>()
    private var idleJob: Job? = null

    /** The policy's inputs as the last [update] described them (for the event lines), and when the engine now alive was made. */
    private var lastInputs: String = ""
    private var madeAtMs = 0L

    /** The cover a "wait" line was written for, and what the engine was last told to do: so that each is one line per change and not one per update. */
    private var waitingFor: String? = null
    private var lastRun: Boolean? = null

    /** How many engines were made and released: the tests' view of "one at a time". */
    internal var made = 0
        private set
    internal var released = 0
        private set

    /** The number of engines alive now; never more than one. */
    internal val alive: Int get() = made - released

    /** [inputs] is the policy's inputs written out ([AnimatedCoverPolicy.Inputs.describe]): it goes into the event lines and nowhere else. */
    fun update(want: Want, inputs: String = "") {
        lastInputs = inputs
        val target = want.cover?.takeIf { want.hold && it.identity !in failed }
        // The old one is stopped and released before a new one is made: the order below is the whole of "one decoder at a time".
        current?.let { held -> if (held.identity != target?.identity) releaseCurrent(whyLeft(held, want, target)) }
        if (target == null) {
            waitingFor = null
            return
        }
        // Made only where it can be seen: a decoder let go for not being seen comes back when the cover is seen again, from its first frame.
        // ...and once the page has a size, so the decoder is made at the size it is shown and never at the file's own
        if (current == null) {
            if (want.visible && want.sizePx > 0) {
                waitingFor = null
                create(target, want.sizePx)
            } else if (waitingFor != target.identity) {
                // found and held, but not made yet: said once, so a cover that never appears can be told from one that was never found
                waitingFor = target.identity
                events("wait kind=${target.kind} file=${target.file.path} why=\"${if (!want.visible) "not visible (screen off, app in the background or lock screen)" else "the page has no size yet"}\" inputs=[$lastInputs]")
            }
        }
        current?.let {
            val running = want.run && want.visible
            if (running != lastRun) {
                lastRun = running
                events("run=$running file=${it.file.path} inputs=[$lastInputs]")
            }
            _engine.value?.setRunning(running)
        }
        if (current != null && !want.visible) startIdleTimer() else cancelIdleTimer()
    }

    private fun create(cover: AnimatedCover, sizePx: Int) {
        val engine = try {
            factory.create(cover, sizePx) { reason -> fail(cover, reason) }
        } catch (t: Throwable) {
            fail(cover, "could not start: ${t.javaClass.simpleName}")
            return
        }
        if (engine == null) {
            fail(cover, "not playable here")
            return
        }
        made++
        current = cover
        madeAtMs = nowMs()
        lastRun = null
        _engine.value = engine
        events("make kind=${cover.kind} file=${cover.file.path} bytes=${cover.sizeBytes} px=$sizePx inputs=[$lastInputs]")
    }

    private fun fail(cover: AnimatedCover, reason: String) {
        if (failed.add(cover.identity)) {
            if (failed.size > MAX_FAILED) failed.remove(failed.first())
            log("animated cover falls back to the still cover: ${cover.file.name} ($reason)")
        }
        if (current?.identity == cover.identity) releaseCurrent("failed: $reason")
    }

    /** Why the engine that is alive is not the one the screen wants now: in words, for the event line. */
    private fun whyLeft(held: AnimatedCover, want: Want, target: AnimatedCover?): String = when {
        target != null -> "the song's cover changed (${held.file.path} -> ${target.file.path})"
        want.cover == null -> "the song has no animated cover"
        !want.hold -> "no longer held (setting off, player not open, or not on the cover page)"
        else -> "the cover failed before"
    }

    private fun releaseCurrent(why: String) {
        cancelIdleTimer()
        val engine = _engine.value
        val cover = current
        _engine.value = null
        current = null
        lastRun = null
        if (engine != null) {
            engine.release()
            released++
            events("release kind=${cover?.kind} file=${cover?.file?.path} aliveMs=${nowMs() - madeAtMs} why=\"$why\" inputs=[$lastInputs]")
        }
    }

    private fun startIdleTimer() {
        if (idleJob?.isActive == true) return
        idleJob = scope.launch {
            delay(idleReleaseMs)
            if (current != null) releaseCurrent("not seen for $idleReleaseMs ms (screen off, app in the background or the lock screen)")
        }
    }

    private fun cancelIdleTimer() {
        idleJob?.cancel()
        idleJob = null
    }

    /** The owner leaves: the engine is released at once and nothing is made again. */
    fun release() {
        requests.clear()
        _surfaceOwner.value = null
        releaseCurrent("owner left")
    }

    companion object {
        const val TAG = "AnimatedCover"

        /** How long a held cover may go unseen (screen off, app in the background, lock screen) before its decoder is let go. */
        const val IDLE_RELEASE_MS = 15_000L

        private const val MAX_FAILED = 64
    }
}
