package com.lostf1sh.pixelplayeross.ui.ds

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Motion for the DAP interface. Four kinds, one curve pair, no overshoot.
 *
 * The same kind of change uses the same duration and curve everywhere. Pick by *what changed*,
 * never by how important it feels:
 *
 * - **Direct** — bound to the finger: drag, seek, reorder, EQ bands. No spec at all while the
 *   finger is down; when it lifts, [settle] carries the finger's velocity to rest.
 * - **Micro** — acknowledgement of a touch or a state: press, toggle, icon morph, selected tab.
 * - **Content** — something replaced in place: new artwork, a tab's content, results, empty/error.
 * - **Surface** — something arriving or leaving: player opened by tap, queue, sheets, dialogs,
 *   detail screens.
 *
 * Exits are shorter than entries and accelerate away; entries decelerate into place.
 *
 * Nothing here overshoots. "No bounce" means no overshoot, not "no spring": where motion continues
 * a gesture, [settle] is a critically damped spring so the finger's velocity is kept instead of
 * being thrown away by a tween.
 *
 * The system animation scale applies to every spec here automatically (Compose reads it through
 * `MotionDurationScale`); at 0 they complete immediately. Motion that never completes on its own —
 * loops, marquees — must additionally stop when [rememberDapReducedMotion] is true.
 */
object DapMotion {

    const val MicroMillis = 100
    const val MicroExitMillis = 90

    const val ContentMillis = 160
    const val ContentExitMillis = 120

    const val SurfaceMillis = 220
    const val SurfaceExitMillis = 180

    /** Entries: decelerate into place. */
    val EnterEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Exits: accelerate away. */
    val ExitEasing: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /**
     * Stiffness of [settle]. Critically damped at this stiffness, a release from rest is within 1%
     * of its target in roughly 170 ms — the same family as [SurfaceMillis], but shaped by the
     * finger's speed rather than by a clock.
     */
    const val SettleStiffness = Spring.StiffnessMedium

    fun <T> micro(): TweenSpec<T> = tween(MicroMillis, easing = EnterEasing)
    fun <T> microExit(): TweenSpec<T> = tween(MicroExitMillis, easing = ExitEasing)

    fun <T> content(): TweenSpec<T> = tween(ContentMillis, easing = EnterEasing)
    fun <T> contentExit(): TweenSpec<T> = tween(ContentExitMillis, easing = ExitEasing)

    fun <T> surface(): TweenSpec<T> = tween(SurfaceMillis, easing = EnterEasing)
    fun <T> surfaceExit(): TweenSpec<T> = tween(SurfaceExitMillis, easing = ExitEasing)

    /** Micro in one direction, micro-exit in the other: for a two-state acknowledgement. */
    fun <T> micro(entering: Boolean): FiniteAnimationSpec<T> = if (entering) micro() else microExit()

    /** Surface in one direction, surface-exit in the other. */
    fun <T> surface(entering: Boolean): FiniteAnimationSpec<T> =
        if (entering) surface() else surfaceExit()

    /**
     * Continues a gesture to rest. Pass the finger's velocity as the animation's initial velocity;
     * this spec keeps it and never overshoots.
     */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = SettleStiffness)

    /** Content replaced in place: a plain fade, no travel. */
    fun contentEnter(): EnterTransition = fadeIn(content())
    fun contentExitTransition(): ExitTransition = fadeOut(contentExit())

    /** A small state reveal (a row appearing, a hint): a plain fade at micro timing, no height. */
    fun microEnter(): EnterTransition = fadeIn(micro())
    fun microExitTransition(): ExitTransition = fadeOut(microExit())
}

/**
 * Whether the user has turned animations off (Developer options or Accessibility → Remove
 * animations), read live from the system animator scale.
 *
 * Finite specs already honour the scale on their own; this is for motion that would otherwise run
 * forever — loops, marquees, ambient decoration — which must stop instead.
 */
@Composable
fun rememberDapReducedMotion(): Boolean {
    val resolver = LocalContext.current.applicationContext.contentResolver
    var reduced by remember { mutableStateOf(readAnimatorScale(resolver) == 0f) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readAnimatorScale(resolver) == 0f
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun readAnimatorScale(resolver: android.content.ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
