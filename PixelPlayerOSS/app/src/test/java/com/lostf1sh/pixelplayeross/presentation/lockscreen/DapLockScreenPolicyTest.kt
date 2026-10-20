package com.lostf1sh.pixelplayeross.presentation.lockscreen

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * When the DAP lock surface is allowed to appear.
 *
 * Every condition is a refusal as much as a permission, so each one gets its own test: a surface
 * that appears when it should not is worse than one that fails to appear, because it stands between
 * the user and their music.
 */
class DapLockScreenPolicyTest {

    private fun show(
        enabled: Boolean = true,
        dedicated: Boolean = true,
        keyguardLocked: Boolean = false,
        wokeFromScreenOff: Boolean = true,
        hasSong: Boolean = true,
    ) = DapLockScreenPolicy.shouldShow(enabled, dedicated, keyguardLocked, wokeFromScreenOff, hasSong)

    @Test
    fun `the intended configuration shows the surface`() {
        assertThat(show()).isTrue()
    }

    @Test
    fun `it does not appear unless the user asked for it`() {
        assertThat(show(enabled = false)).isFalse()
    }

    @Test
    fun `it does not appear outside Dedicated Mode`() {
        // On a normal phone this would be a second home screen nobody asked for.
        assertThat(show(dedicated = false)).isFalse()
    }

    @Test
    fun `it never appears over a locked device`() {
        // The whole Keyguard boundary in one line: if the platform owns the screen, it owns it.
        assertThat(show(keyguardLocked = true)).isFalse()
    }

    @Test
    fun `it does not appear on an ordinary return to the app`() {
        // Only a wake that followed a screen-off counts.
        assertThat(show(wokeFromScreenOff = false)).isFalse()
    }

    @Test
    fun `it does not appear with nothing to control`() {
        assertThat(show(hasSong = false)).isFalse()
    }

    @Test
    fun `every condition is required, not merely any of them`() {
        listOf(
            show(enabled = false),
            show(dedicated = false),
            show(keyguardLocked = true),
            show(wokeFromScreenOff = false),
            show(hasSong = false),
        ).forEach { assertThat(it).isFalse() }
    }

    @Test
    fun `arming requires the setting and Dedicated Mode, and nothing else`() {
        // Arming happens as the screen goes away, when the Keyguard answer is not yet meaningful
        // and no song may be loaded. Deciding both at wake time is what makes a lock that appeared
        // mid-session get respected.
        assertThat(DapLockScreenPolicy.shouldArm(enabled = true, dedicated = true)).isTrue()
        assertThat(DapLockScreenPolicy.shouldArm(enabled = false, dedicated = true)).isFalse()
        assertThat(DapLockScreenPolicy.shouldArm(enabled = true, dedicated = false)).isFalse()
    }

    // --- Source contracts ---------------------------------------------------------------------------

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("app", relative),
            File("src/main/java/com/lostf1sh/pixelplayeross/$relative"),
            File("app/src/main/java/com/lostf1sh/pixelplayeross/$relative"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
    }

    @Test
    fun `the surface is not a window overlay and claims no security`() {
        val host = source("presentation/lockscreen/DapLockScreenHost.kt")
        val surface = source("presentation/lockscreen/DapLockScreen.kt")
        listOf(host, surface).forEach { text ->
            assertThat(text).doesNotContain("WindowManager")
            assertThat(text).doesNotContain("TYPE_APPLICATION_OVERLAY")
            assertThat(text).doesNotContain("TYPE_SYSTEM_ALERT")
            assertThat(text).doesNotContain("FLAG_SHOW_WHEN_LOCKED")
            assertThat(text).doesNotContain("KeyguardManager.KeyguardLock")
            assertThat(text).doesNotContain("disableKeyguard")
            assertThat(text).doesNotContain("PIN")
        }
    }

    @Test
    fun `the surface owns no playback state of its own`() {
        // Everything it draws is passed in, and every control is a callback. No view model, no
        // controller, no second shuffle flag.
        val surface = source("presentation/lockscreen/DapLockScreen.kt")
        assertThat(surface).doesNotContain("playerViewModel")
        assertThat(surface).doesNotContain("MediaController")
        assertThat(surface).doesNotContain("ExoPlayer")
        assertThat(surface).doesNotContain("SafetyVolume")
        val host = source("presentation/lockscreen/DapLockScreenHost.kt")
        assertThat(host).doesNotContain("playerViewModel")
    }

    @Test
    fun `progress is read-only, so there is no second seek model and no pocket seek`() {
        // Owner decision (frontend/motion session, 2026-09-26): a brush on a lock screen must not
        // move the song. With no seek on the surface there is nothing to reconcile, so the old
        // guard against a second seek model holds by construction; this pins that it stays so.
        val surface = source("presentation/lockscreen/DapLockScreen.kt")
        assertThat(surface).doesNotContain("PlayerSeekBar(")
        assertThat(surface).doesNotContain("onSeek")
        assertThat(surface).doesNotContain("targetSeekFraction")
        assertThat(surface).doesNotContain("lastSeekFinishedTime")
        assertThat(surface).contains("progressBarRangeInfo")
    }

    @Test
    fun `artwork uses the existing pipeline and a rendered-size target`() {
        val surface = source("presentation/lockscreen/DapLockScreen.kt")
        assertThat(surface).contains("SmartImage(")
        // No second loader, no network fetch, no oversized decode.
        assertThat(surface).doesNotContain("AsyncImage(")
        assertThat(surface).doesNotContain("rememberAsyncImagePainter")
        assertThat(surface).contains("targetSize = coil.size.Size(")
    }

    @Test
    fun `themes come from the shared authorities`() {
        val surface = source("presentation/lockscreen/DapLockScreen.kt")
        // The accent reaches the surface through the DS controls (the state button's active mark)
        // rather than being read here; what matters is that nothing brings its own colours.
        assertThat(surface).contains("DapStateIconButton(")
        assertThat(surface).contains("Dap.")
        // No independent palette.
        assertThat(surface).doesNotContain("Color(0xFF")
    }

    @Test
    fun `the surface has no infinite visual loop`() {
        val surface = source("presentation/lockscreen/DapLockScreen.kt")
        assertThat(surface).doesNotContain("rememberInfiniteTransition")
        assertThat(surface).doesNotContain("infiniteRepeatable")
        assertThat(surface).doesNotContain("basicMarquee")
        assertThat(surface).doesNotContain("withFrameNanos")
    }

    @Test
    fun `long titles wrap and ellipsize instead of moving`() {
        // Owner decision (2026-09-26): the surface can stay lit a long time, so nothing on it moves
        // by itself — two lines and an ellipsis, no scrolling title of any kind.
        val surface = source("presentation/lockscreen/DapLockScreen.kt")
        assertThat(surface).doesNotContain("AutoScrollingTextOnDemand(")
        assertThat(surface).contains("maxLines = 2")
        assertThat(surface).contains("TextOverflow.Ellipsis")
    }

    @Test
    fun `the dismiss gesture is vertical only, so it cannot collide with seek or edge Back`() {
        val surface = source("presentation/lockscreen/DapLockScreen.kt")
        assertThat(surface).contains("detectVerticalDragGestures(")
        assertThat(surface).doesNotContain("detectHorizontalDragGestures")
        assertThat(surface).doesNotContain("detectDragGestures(")
    }

    @Test
    fun `there is an accessible alternative to the gesture`() {
        val surface = source("presentation/lockscreen/DapLockScreen.kt")
        assertThat(surface).contains("R.string.lock_screen_open_app")
    }

    @Test
    fun `every icon control carries a description`() {
        val surface = source("presentation/lockscreen/DapLockScreen.kt")
        // Decorative glyphs inside a labelled control are the only allowed nulls.
        // B22: every control is a DS state button or the play disc, and each is given its description.
        assertThat(surface).contains("contentDescription = stringResource(R.string.player_shuffle)")
        assertThat(surface).contains("contentDescription = stringResource(R.string.lock_transport_previous)")
        assertThat(surface).contains("contentDescription = stringResource(R.string.lock_transport_next)")
        assertThat(surface).contains("contentDescription = stringResource(R.string.lock_transport_repeat)")
        assertThat(surface).contains("stateDescription = stringResource(")
        assertThat(surface).contains("contentDescription = if (isPlaying)")
    }

    @Test
    fun `repeat-one is a different glyph, not only a different colour`() {
        val surface = source("presentation/lockscreen/DapLockScreen.kt")
        assertThat(surface).contains("DapIconKind.RepeatOne")
        assertThat(surface).contains("DapIconKind.Repeat")
    }

    @Test
    fun `the preference is opt-in and lives in the existing store`() {
        val prefs = source("data/preferences/UserPreferencesRepository.kt")
        assertThat(prefs).contains("DEFAULT_DAP_LOCK_SCREEN_ENABLED = false")
        assertThat(prefs).contains("dap_lock_screen_enabled")
        assertThat(prefs).contains("val dapLockScreenEnabledFlow")
        assertThat(prefs).contains("suspend fun setDapLockScreenEnabled")
    }

    // --- Post-Keyguard handoff -------------------------------------------------------------------

    @Test
    fun `the policy is unchanged by the handoff, because the Keyguard answer is re-read`() {
        // The handoff is a lifecycle change, not a policy change: the same five conditions decide,
        // evaluated again at the moment Android says the device is usable.
        assertThat(show()).isTrue()
        assertThat(show(keyguardLocked = true)).isFalse()
    }

    private fun host(): String = source("presentation/lockscreen/DapLockScreenHost.kt")

    @Test
    fun `an ordinary resume is not a trigger`() {
        // The host DOES observe resume now — the device trace showed it fires as the Keyguard
        // clears, so ignoring it would discard a usable signal. What must not change is that a
        // resume with no wake cycle behind it raises nothing, which is the state machine's job
        // rather than the trigger's. See WakeCycleTest, where that is pinned directly.
        val src = host()
        assertThat(src).contains("Lifecycle.Event.ON_RESUME")
        assertThat(src).contains("machine.onUnlockEvidence(")
        // No direct show on resume: it may only ever go through the gated machine.
        val resumeBranch = src.substringAfter("Lifecycle.Event.ON_RESUME")
        assertThat(resumeBranch.substringBefore("}")).doesNotContain("visible = true")
    }

    @Test
    fun `App Mode still never shows the surface`() {
        assertThat(show(dedicated = false)).isFalse()
        assertThat(DapLockScreenPolicy.shouldArm(enabled = true, dedicated = false)).isFalse()
    }

    @Test
    fun `the host delegates every decision to the wake cycle machine`() {
        // The wake-cycle shape is no longer asserted by matching text in the host: the machine owns
        // it, and WakeCycleTest drives every transition directly. What is pinned here is that the
        // host has no decision of its own left — it reads the Keyguard and asks the machine.
        val src = host()
        assertThat(src).contains("WakeCycleMachine()")
        assertThat(src).contains("machine.onScreenOff(")
        assertThat(src).contains("machine.onScreenOn(")
        assertThat(src).contains("machine.onUnlockEvidence(")
        // No leftover second opinion about when a surface may appear.
        assertThat(src).doesNotContain("DapLockScreenPolicy.shouldShow(")
    }
}
