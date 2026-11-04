package com.lostf1sh.pixelplayeross.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf

/**
 * B41: the glass the whole app can reach: what is behind it, how it moves, and the theme's background it sits on. Provided once by the root
 * while a cover backdrop is on ("Cover glass" or "From album art") and a cover is being shown; null otherwise. The lock screen reads it to
 * draw its own copy of the glass (it is an opaque sheet that slides away, so it carries its glass with it), in the same phase as the app's,
 * so a reveal never jumps.
 *
 * @property kind which backdrop this is: only [GlassKind.GLASS] moves.
 */
@Stable
class CoverGlassScene(val crossfade: GlassCrossfade, val clock: GlassClock, val base: Color, val kind: GlassKind = GlassKind.GLASS) {
    /** The glass is on screen (or fading out): the pages are see-through and the lock screen draws it. */
    val visible: Boolean get() = crossfade.visible

    /** Whether this backdrop ever moves ("From album art" does not). */
    val canMove: Boolean get() = kind.moves

    /** Opaque cover colour for the mini player and the folded queue strip. No page content can show through it. */
    val shelf: Color
        get() {
            val mean = (crossfade.current ?: crossfade.previous)?.mean ?: return base.copy(alpha = 1f)
            return androidx.compose.ui.graphics.lerp(mean.copy(alpha = 1f), base.copy(alpha = 1f), GlassScrim.SHEET_ALPHA)
        }

    /**
     * What a header image fades into where it meets the page: the glass's own average colour under the page's veil, a solid colour near the
     * glass it meets, in place of the theme's own background, which would be a slab on the glass. Read in composition by the few headers that
     * use it, so it follows the field as it changes (and it is the only per-song colour any page reads from here).
     */
    val edge: Color
        get() {
            val mean = (crossfade.current ?: crossfade.previous)?.mean ?: return base
            val a = GlassScrim.PAGE_ALPHA
            return Color(mean.red + (base.red - mean.red) * a, mean.green + (base.green - mean.green) * a, mean.blue + (base.blue - mean.blue) * a)
        }
}

val LocalCoverGlass = staticCompositionLocalOf<CoverGlassScene?> { null }

/**
 * What the platform says about whether the glass may move: the activity is in front, the screen is on, and animations are allowed (neither
 * "Remove animations" nor Battery saver). The real one follows the system; a test hands in its own.
 */
@Stable
class GlassEnvironment(val resumed: State<Boolean>, val interactive: State<Boolean>, val animationsAllowed: State<Boolean>)

@Composable
fun rememberGlassEnvironment(): GlassEnvironment = GlassEnvironment(rememberResumed(), rememberScreenInteractive(), rememberSystemAnimationsAllowed())

/**
 * The glass for the app: waits for the song's cover to settle ([settleMs], the app's own colour settle, so skipping fast changes it once),
 * makes the cover's backdrop of [kind] for [band] (off the main thread, from [fields]'s cache when it can) and cross-fades to it. Returns the
 * scene the root and the lock screen draw; [CoverGlassScene.visible] says whether there is anything to draw (or still fading out).
 *
 * B42-fix3 (item 7): the band is the same for every cover and every song (the ink is always light), so what a field is made from is the cover,
 * the kind and nothing else: the accent the song gives is not an input, and a song change is one build, for the new cover.
 *
 * @param kind the backdrop wanted, or null: the colour source is "Theme and accent" and there is no glass.
 * @param coverUri the cover of the song that is playing, as the theme holder publishes it: null for a song with no cover.
 */
@Composable
fun rememberCoverGlassScene(
    kind: GlassKind?,
    band: GlassBand,
    coverUri: Flow<String?>,
    fields: CoverGlassFields,
    settleMs: Long = GlassMotion.SETTLE_MS,
): CoverGlassScene {
    val crossfade = remember { GlassCrossfade() }
    val clock = remember { GlassClock() }
    val settled by remember(kind, coverUri, settleMs) {
        if (kind != null) coverUri.debounce(settleMs) else flowOf<String?>(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val inputs = rememberUpdatedState(GlassInputs(kind, band, settled))
    LaunchedEffect(crossfade, fields) {
        snapshotFlow { inputs.value }
            .debounce(GlassMotion.COALESCE_MS)
            .collectLatest { (wantedKind, wantedBand, uri) ->
                if (wantedKind == null || uri == null) {
                    // A deliberate theme change, or a known track without artwork.
                    crossfade.show(null)
                } else {
                    // A failed/not-yet-readable next cover is not an empty background. Keep the
                    // last complete field until a replacement has actually been built.
                    fields.fieldFor(uri, wantedKind, wantedBand)?.let { crossfade.show(it) }
                }
            }
    }
    return remember(crossfade, clock, band.base, kind) { CoverGlassScene(crossfade, clock, band.base, kind ?: GlassKind.GLASS) }
}

/** What the backdrop is made from: the kind wanted, the band, and the settled cover. */
private data class GlassInputs(val kind: GlassKind?, val band: GlassBand, val uri: String?)

/**
 * The root's glass: draws the backdrop once behind the app, and runs the clock while the glass should move. Its own composable, with the
 * state it reads inside it, so the playback state and the player's expansion never recompose the activity's content: the only things that
 * change per second here are the clock (read in the layers' draw phase) and, twice per open or close of the player, the one `Boolean`
 * the ticker is keyed on.
 *
 * @param playing whether a song is playing (collected here, with the lifecycle).
 * @param playerExpansion how far open the full player is, 0..1; read in the draw phase and in a `derivedStateOf` for the one flag.
 * @param movingBackgroundOn Appearance, Player, "Moving background" (only "Cover glass" moves: see [CoverGlassScene.canMove]).
 * @param lockSurfaceVisible the DAP lock screen is up; [lockCovers] it is covering the app completely (not being swiped away).
 */
@Composable
fun CoverGlassHost(
    scene: CoverGlassScene,
    playing: Flow<Boolean>,
    playerExpansion: () -> Float,
    movingBackgroundOn: Boolean,
    lockSurfaceVisible: Boolean,
    lockCovers: Boolean,
    modifier: Modifier = Modifier,
    /** B56-7.1: Still, Gentle (today's) or Lively. Still is the same as [movingBackgroundOn] false. */
    backgroundMotion: com.lostf1sh.pixelplayeross.data.preferences.BackgroundMotion = com.lostf1sh.pixelplayeross.data.preferences.BackgroundMotion.GENTLE,
    /** A bottom sheet is open over the player: the live background holds still (a sheet has its own still copy of the glass). */
    sheetOpen: Boolean = SheetPresence.anyOpen,
    onFieldDrawn: () -> Unit = {},
    environment: GlassEnvironment = rememberGlassEnvironment(),
    /** Called after each step of the glass (once per display frame while it moves); a test counts them. */
    onStep: () -> Unit = {},
) {
    val resumed by environment.resumed
    val interactive by environment.interactive
    val animationsAllowed by environment.animationsAllowed
    val playerExpanded by remember(playerExpansion) { derivedStateOf { playerExpansion() >= 0.99f } }
    val isPlaying by playing.collectAsStateWithLifecycle(initialValue = false)
    val moving = GlassMotionPolicy.shouldMove(
        GlassMotionPolicy.Inputs(
            glassShown = scene.crossfade.current != null,
            // "From album art" is a still backdrop: whatever the preference says, nothing moves
            movingBackgroundOn = movingBackgroundOn && scene.canMove,
            playing = isPlaying,
            playerExpanded = playerExpanded,
            lockSurfaceVisible = lockSurfaceVisible,
            resumed = resumed,
            screenInteractive = interactive,
            systemAnimationsAllowed = animationsAllowed,
        )
    )
    scene.clock.motion = backgroundMotion
    // the live blobs (Android 13+) are stricter than the classic glass: still under the DAP lock screen and under a sheet; and stepped at 30 a second
    val live = LiveBlobs.available() && backgroundMotion.moves && scene.canMove
    val running = moving && (!live || LiveBackgroundPolicy.shouldRun(backgroundMotion.moves, android.os.Build.VERSION.SDK_INT, moving, lockSurfaceVisible, sheetOpen))
    GlassTicker(scene.clock, running = running, onStep = onStep, minFrameNanos = if (live) LiveBlobs.FRAME_NANOS else 0L)
    // Under a lock screen that covers it the app's glass is not seen: it is not drawn at all (the lock screen draws its own, in step).
    if (!lockCovers) {
        CoverGlassBackdrop(scene.crossfade, scene.clock, scene.base, expansion = playerExpansion, modifier = modifier, onFieldDrawn = onFieldDrawn)
    }
}

/**
 * A panel's fill over the glass: [color] (the opaque theme surface for the mini player) in [shape], giving way to nothing as [fadeOut] goes from 0 to
 * 1, read in the draw phase. The sheet that is a mini player when it is closed and the whole screen when the player is open uses it: closed
 * it blocks the page behind it, open it is the glass itself.
 */
fun Modifier.glassPanelBackground(color: Color, shape: Shape = RectangleShape, fadeOut: () -> Float = { 0f }): Modifier =
    drawBehind {
        val fade = fadeOut().coerceIn(0f, 1f)
        val a = color.alpha * (1f - fade)
        if (a > 0.001f) drawOutline(shape.createOutline(Size(size.width, size.height), layoutDirection, this), color.copy(alpha = a))
    }
