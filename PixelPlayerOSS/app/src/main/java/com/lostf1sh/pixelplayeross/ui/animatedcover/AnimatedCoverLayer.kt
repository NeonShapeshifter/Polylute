package com.lostf1sh.pixelplayeross.ui.animatedcover

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import com.lostf1sh.pixelplayeross.presentation.lockscreen.LocalLockSurfaceShowing
import kotlinx.coroutines.flow.distinctUntilChanged
import com.lostf1sh.pixelplayeross.ui.glass.SheetPresence
import com.lostf1sh.pixelplayeross.ui.glass.rememberGlassEnvironment

/**
 * B56 phase 7.2 (#48): what the player needs to show animated covers: the finder, whether the setting is on for this mode, and the library's change counter (a sync moves it, and
 * the cover of the song that plays is looked for again). Provided once by the activity; where nothing provides it (a test of some other screen) there are no animated covers.
 */
@Immutable
class AnimatedCoverEnv(
    val finder: AnimatedCoverFinder,
    val enabled: Boolean,
    val libraryVersion: Long,
    /** B61 item 5 (#70): "Also on the lock screen" (off by default): Polylute's own lock screen plays the cover too. */
    val lockCoverOn: Boolean = false,
)

val LocalAnimatedCoverEnv = compositionLocalOf<AnimatedCoverEnv?> { null }

/**
 * B61 item 5 (#70): **the one controller of the activity**, shared by the full player's cover page and Polylute's lock screen: "at most one decoder" is then true of the whole app and not of
 * one surface. Provided by the activity ([rememberSharedAnimatedCoverController]); where nothing provides it (the tests of the host) a host makes its own, as it always did.
 */
val LocalAnimatedCoverController = compositionLocalOf<AnimatedCoverController?> { null }

/** The activity's controller: made once, with the engines the platform (or a test) gives, and released when the activity's composition ends. */
@Composable
fun rememberSharedAnimatedCoverController(): AnimatedCoverController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engines = LocalAnimatedCoverEngines.current
    val controller = remember { AnimatedCoverController(scope, engines?.invoke(context.applicationContext, scope) ?: PlatformAnimatedCoverEngines(context.applicationContext, scope)) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    return controller
}

/** The factory the player's host uses; a test of the host swaps it for a fake. */
val LocalAnimatedCoverEngines = compositionLocalOf<((android.content.Context, kotlinx.coroutines.CoroutineScope) -> AnimatedCoverEngineFactory)?> { null }

/**
 * The cover page's hold on the one animated cover: finds the song's file (off the main thread), tells the [controller] what the screen allows (the policy), and hands the page the
 * engine to draw. Not composed anywhere but the full player's cover, so lists, the mini player, Home, notifications, the lock screen and widgets never have one.
 */
@Stable
class AnimatedCoverHost internal constructor(
    internal val controller: AnimatedCoverController,
    private val sizePx: androidx.compose.runtime.MutableIntState,
    internal val token: Any,
) {
    /** The page's size, so the engine decodes at the size it is shown. */
    fun onSize(px: Int) {
        if (px > 0) sizePx.intValue = px
    }
}

/**
 * @param songPath the file of the song whose cover this is (the page shows only the current song's)
 * @param albumId the library's `album_id` of that song, or 0 when it has none (B57.5 2.1: the cover is the album's, found in the folders of all the songs of the album)
 * @param isPlaying whether a song is playing, **read in the effect and not in composition**: the cover surface is not redrawn for a pause
 * @param playerExpanded the full player is fully open
 * @param coverPageShowing the player's pager is on the Player page
 */
@Composable
internal fun rememberAnimatedCoverHost(
    songPath: String,
    isPlaying: () -> Boolean,
    playerExpanded: Boolean,
    coverPageShowing: Boolean,
    albumId: Long = 0L,
    /** B59 4 (#82): the song's disc number (0 when it has none): a disc may have its own animated cover (`disc2.mp4`), else the album's applies. */
    discNumber: Int = 0,
    /**
     * B61 item 5 (#70): this is **Polylute's lock screen's** host, not the player's. It exists only with "Also on the lock screen" on (otherwise null: the lock screen has no animated cover), and it drives
     * the shared controller while the lock screen is up; the player's host stands aside meanwhile ([lockDrives]) and takes over again when the lock screen goes.
     */
    onLock: Boolean = false,
    /** Album preview: independent of audio playback, held only while its artwork can be seen. */
    albumSurface: Boolean = false,
): AnimatedCoverHost? {
    val env = LocalAnimatedCoverEnv.current ?: return null
    if (onLock && !env.lockCoverOn) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engines = LocalAnimatedCoverEngines.current
    val shared = LocalAnimatedCoverController.current
    val controller = shared ?: remember(env.finder) {
        AnimatedCoverController(scope, engines?.invoke(context.applicationContext, scope) ?: PlatformAnimatedCoverEngines(context.applicationContext, scope))
    }
    val token = remember(controller) { Any() }
    DisposableEffect(controller, token) { onDispose { controller.releaseRequest(token); if (shared == null) controller.release() } }
    val size = remember { mutableIntStateOf(0) }
    val host = remember(controller) { AnimatedCoverHost(controller, size, token) }

    // looked for off the main thread, once per album (the finder keeps the answer); again when the library changes. The answer belongs to the album it was found for (B57.5 2.2): while the
    // lookup for another album runs, the old answer is not this song's, so it is not shown and no decoder is made for it (before, a cover made for the previous song's album lived a few
    // milliseconds, until the lookup came back, and was then released as "left"). Songs of the same album keep the answer, so skipping inside an album never touches the decoder.
    val lookupKey = (if (albumId > 0L) "album:$albumId" else "folder:${songPath.substringBeforeLast('/', "")}") + (if (discNumber > 0) "|disc:$discNumber" else "")
    // B61 item 5: a host that takes over from the other surface starts from the answer the controller already has for this song, so the decoder it is playing is not released to be made again
    var found by remember(controller) { mutableStateOf<Pair<String, AnimatedCover?>?>(controller.sharedLookup?.takeIf { it.first == lookupKey }) }
    val system = rememberGlassEnvironment()
    // Reopen/resume is a new lookup even for the same track: a file or grant may have changed.
    LaunchedEffect(songPath, albumId, discNumber, env.enabled, env.finder, env.libraryVersion,
        playerExpanded, coverPageShowing, system.resumed.value) {
        val cover = if (env.enabled && songPath.isNotEmpty()) env.finder.find(songPath, albumId, discNumber) else null
        found = lookupKey to cover
        controller.sharedLookup = lookupKey to cover
    }
    val wantedKey = rememberUpdatedState(lookupKey)

    val lockShowing = rememberUpdatedState(onLock || LocalLockSurfaceShowing.current)
    val lockCoverOn = rememberUpdatedState(env.lockCoverOn)
    val enabled = rememberUpdatedState(env.enabled)
    val expanded = rememberUpdatedState(playerExpanded)
    val onCoverPage = rememberUpdatedState(coverPageShowing)
    val playing = rememberUpdatedState(isPlaying)
    LaunchedEffect(controller) {
        snapshotFlow {
            // B61 item 5: with "Also on the lock screen" on and the lock screen up, the lock screen's host decides; the player's has no opinion (null) until it is gone
            if (!onLock && lockShowing.value && lockCoverOn.value) return@snapshotFlow null
            val inputs = AnimatedCoverPolicy.Inputs(
                enabled = enabled.value,
                playerExpanded = expanded.value,
                coverPageShowing = onCoverPage.value,
                sheetOpen = SheetPresence.anyOpen,
                lockSurfaceVisible = lockShowing.value,
                resumed = system.resumed.value,
                screenInteractive = system.interactive.value,
                systemAnimationsAllowed = system.animationsAllowed.value,
                playing = playing.value(),
                lockCoverOn = lockCoverOn.value,
            )
            val want = AnimatedCoverController.Want(
                cover = found?.takeIf { it.first == wantedKey.value }?.second,
                sizePx = size.intValue,
                hold = if (albumSurface) inputs.enabled && inputs.playerExpanded && inputs.coverPageShowing else AnimatedCoverPolicy.shouldHold(inputs),
                visible = AnimatedCoverPolicy.visible(inputs) && (!albumSurface || !inputs.lockSurfaceVisible),
                run = AnimatedCoverPolicy.shouldRun(inputs),
            )
            // the inputs written out travel beside the want (they are not part of it: only a change of the want reaches the controller), for the event lines of the device log
            want to inputs.describe()
        }.distinctUntilChanged { old, new -> old?.first == new?.first }.collect { decided -> decided?.let { (want, inputs) -> controller.request(token, want, if (onLock) 100 else if (albumSurface) 20 else 50, inputs) } }
    }
    return host
}

/**
 * Draws the host's engine over the still cover. The still cover is under it all the time, and the animation is transparent until its first frame is on the view, so there is no
 * flash: it fades in over [FADE_MS] once there is something to show.
 */
@Composable
internal fun AnimatedCoverLayer(host: AnimatedCoverHost?, modifier: Modifier = Modifier, fit: Boolean = false) {
    if (host == null) return
    val surface by host.controller.surfaceOwner.collectAsStateWithLifecycle()
    if (surface != host.token) return
    // B61 item 5 (#70): the surface that claimed the view last shows it (the lock screen over the player); the others draw nothing and get it back when it goes
    val token = remember { Any() }
    DisposableEffect(host.controller, token) {
        host.controller.claimView(token)
        onDispose { host.controller.releaseView(token) }
    }
    val owner by host.controller.viewOwner.collectAsStateWithLifecycle()
    if (owner != token) return
    val engine by host.controller.engine.collectAsStateWithLifecycle()
    val current = engine ?: return
    val view = current.view ?: return
    key(current) {
        val shown by current.firstFrame.collectAsStateWithLifecycle()
        val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(FADE_MS), label = "animatedCoverFade")
        // The one engine has one view, and the carousel draws it on whichever page is the current one: when that changes, the new page can ask for the view before the old page has let it go (the
        // order Compose applies two pages' changes in is not ours to choose), and a view with a parent cannot be added to another ("The specified child already has a parent"). So it is taken
        // from the old holder first. (B57.5 2.2: found by moving the view between two pages in one recomposition; before this it threw.)
        AndroidView(factory = { (view.parent as? ViewGroup)?.removeView(view); current.setFit(fit); view }, update = { current.setFit(fit) }, modifier = modifier.fillMaxSize().alpha(alpha))
    }
}

/** How long the animation takes to appear over the still cover once its first frame is ready. */
const val FADE_MS = 180
