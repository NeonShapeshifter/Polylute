package com.lostf1sh.pixelplayeross.presentation.lockscreen

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * B17-H2: the wake window counts from `SCREEN_ON`, not from `SCREEN_OFF`.
 *
 * The owner's pattern: the DAP lock surface "sometimes does not come up even though the screen was off" — after a
 * while with the screen off, never after an off-and-on straight away. B16 armed at `SCREEN_OFF` and the host's
 * effect gave the cycle up 60 s later, so a pocketed player woke into `IDLE`. Reproduced on B16's own code in
 * virtual time (`B17-resultado.md`): 30 s off → `VISIBLE`, 5 min off → `IDLE`.
 *
 * Everything here is pure or runs the host's real timer function ([superviseWaitingCycle]) on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeCycleWindowTest {

    private val minute = 60_000L

    private fun WakeCycleMachine.on(
        nowMs: Long,
        keyguardLocked: Boolean = false,
        hasSong: Boolean = true,
    ) = onScreenOn(
        enabled = true,
        dedicated = true,
        keyguardLocked = keyguardLocked,
        hasSong = hasSong,
        nowMs = nowMs,
    )

    private fun WakeCycleMachine.evidence(
        nowMs: Long,
        keyguardLocked: Boolean,
        hasSong: Boolean = true,
    ) = onUnlockEvidence(
        enabled = true,
        dedicated = true,
        keyguardLocked = keyguardLocked,
        hasSong = hasSong,
        nowMs = nowMs,
    )

    // --- The reported bug -------------------------------------------------------------------------

    @Test
    fun `off for five minutes then on shows the surface`() {
        val m = WakeCycleMachine()
        m.onScreenOff(enabled = true, dedicated = true, nowMs = 0L)

        assertThat(m.on(nowMs = 5 * minute)).isEqualTo(WakeCycleState.VISIBLE)
        assertThat(m.shouldShowSurface).isTrue()
    }

    @Test
    fun `an armed cycle has no deadline however long the screen stays off`() {
        for (offFor in listOf(59_000L, 61_000L, 5 * minute, 60 * minute, 12 * 60 * minute)) {
            val m = WakeCycleMachine()
            m.onScreenOff(enabled = true, dedicated = true, nowMs = 0L)
            assertThat(m.expireIfStale(offFor)).isFalse()
            assertThat(m.state).isEqualTo(WakeCycleState.ARMED)
            assertThat(m.on(nowMs = offFor)).isEqualTo(WakeCycleState.VISIBLE)
        }
    }

    @Test
    fun `the host's timer does nothing for an armed cycle even after ten minutes of virtual time`() = runTest {
        // B16 ran its 60 s from here and reset the cycle; the real host effect now returns at once.
        val m = WakeCycleMachine()
        m.onScreenOff(enabled = true, dedicated = true, nowMs = currentTime)
        var rechecks = 0
        var expired = 0
        val timer = launch {
            superviseWaitingCycle(
                machine = m,
                nowMs = { currentTime },
                recheck = { rechecks++ },
                onExpired = { expired++ },
            )
        }
        advanceTimeBy(10 * minute)
        runCurrent()

        assertThat(timer.isCompleted).isTrue()
        assertThat(m.state).isEqualTo(WakeCycleState.ARMED)
        assertThat(rechecks).isEqualTo(0)
        assertThat(expired).isEqualTo(0)
        assertThat(m.on(nowMs = currentTime)).isEqualTo(WakeCycleState.VISIBLE)
    }

    // --- The wake window --------------------------------------------------------------------------

    @Test
    fun `the window runs from the wake, not from when the screen went off`() {
        val m = WakeCycleMachine()
        m.onScreenOff(enabled = true, dedicated = true, nowMs = 0L)
        // Ten minutes dark, then the screen comes on with the Keyguard up.
        assertThat(m.on(nowMs = 10 * minute, keyguardLocked = true)).isEqualTo(WakeCycleState.WAITING_FOR_KEYGUARD)

        // Unlocked 59 s after the wake: inside the window.
        assertThat(m.evidence(nowMs = 10 * minute + 59_000L, keyguardLocked = false)).isEqualTo(WakeCycleState.VISIBLE)
    }

    @Test
    fun `an unlock after the window ran out shows nothing`() {
        val m = WakeCycleMachine()
        m.onScreenOff(enabled = true, dedicated = true, nowMs = 0L)
        m.on(nowMs = 10 * minute, keyguardLocked = true)

        assertThat(m.evidence(nowMs = 10 * minute + WakeCycleMachine.WAKE_CYCLE_TIMEOUT_MS, keyguardLocked = false))
            .isEqualTo(WakeCycleState.IDLE)
        assertThat(m.shouldShowSurface).isFalse()
        // And it does not come back: an ordinary resume later still shows nothing.
        assertThat(m.evidence(nowMs = 12 * minute, keyguardLocked = false)).isEqualTo(WakeCycleState.IDLE)
    }

    @Test
    fun `off then on with the keyguard up waits and an unlock within the window shows`() = runTest {
        val m = WakeCycleMachine()
        m.onScreenOff(enabled = true, dedicated = true, nowMs = currentTime)
        advanceTimeBy(3 * minute)
        assertThat(m.on(nowMs = currentTime, keyguardLocked = true)).isEqualTo(WakeCycleState.WAITING_FOR_KEYGUARD)

        var keyguardLocked = true
        var expired = 0
        val timer = launch {
            superviseWaitingCycle(
                machine = m,
                nowMs = { currentTime },
                recheck = { m.evidence(nowMs = currentTime, keyguardLocked = keyguardLocked) },
                onExpired = { expired++ },
            )
        }
        advanceTimeBy(2_000L) // the ladder keeps looking, and finds the Keyguard still up
        assertThat(m.state).isEqualTo(WakeCycleState.WAITING_FOR_KEYGUARD)

        advanceTimeBy(10_000L)
        keyguardLocked = false // the user swipes; the focus gain is the evidence the device really produces
        assertThat(m.evidence(nowMs = currentTime, keyguardLocked = false)).isEqualTo(WakeCycleState.VISIBLE)
        timer.cancel()
        assertThat(expired).isEqualTo(0)
    }

    @Test
    fun `a waiting cycle the Keyguard never releases is given up one window after the wake`() = runTest {
        val m = WakeCycleMachine()
        m.onScreenOff(enabled = true, dedicated = true, nowMs = currentTime)
        advanceTimeBy(20 * minute)
        val wokeAt = currentTime
        m.on(nowMs = wokeAt, keyguardLocked = true)

        var expiredAt = -1L
        val timer = launch {
            superviseWaitingCycle(
                machine = m,
                nowMs = { currentTime },
                recheck = { m.evidence(nowMs = currentTime, keyguardLocked = true) },
                onExpired = { expiredAt = currentTime },
            )
        }
        advanceTimeBy(WakeCycleMachine.WAKE_CYCLE_TIMEOUT_MS - 1)
        assertThat(m.state).isEqualTo(WakeCycleState.WAITING_FOR_KEYGUARD)
        assertThat(timer.isCompleted).isFalse()

        advanceTimeBy(1)
        runCurrent()
        assertThat(timer.isCompleted).isTrue()
        assertThat(m.state).isEqualTo(WakeCycleState.IDLE)
        // From the wake, not from the screen going off twenty minutes earlier.
        assertThat(expiredAt - wokeAt).isEqualTo(WakeCycleMachine.WAKE_CYCLE_TIMEOUT_MS)
    }

    @Test
    fun `the timer anchors to the wake even when it starts late`() = runTest {
        // The activity is stopped while the screen is off, so the effect that runs this can start long after the
        // SCREEN_ON that anchored the window (the first recomposition after the activity restarts).
        val m = WakeCycleMachine()
        m.onScreenOff(enabled = true, dedicated = true, nowMs = currentTime)
        m.on(nowMs = currentTime, keyguardLocked = true)
        advanceTimeBy(40_000L)

        var expiredAt = -1L
        launch {
            superviseWaitingCycle(
                machine = m,
                nowMs = { currentTime },
                recheck = { m.evidence(nowMs = currentTime, keyguardLocked = true) },
                onExpired = { expiredAt = currentTime },
            )
        }
        advanceTimeBy(30_000L)
        runCurrent()

        assertThat(expiredAt).isEqualTo(WakeCycleMachine.WAKE_CYCLE_TIMEOUT_MS)
    }

    @Test
    fun `the ladder stops at the first probe that finds the Keyguard gone`() = runTest {
        val m = WakeCycleMachine()
        m.onScreenOff(enabled = true, dedicated = true, nowMs = currentTime)
        m.on(nowMs = currentTime, keyguardLocked = true)
        var probes = 0
        val timer = launch {
            superviseWaitingCycle(
                machine = m,
                nowMs = { currentTime },
                recheck = {
                    probes++
                    m.evidence(nowMs = currentTime, keyguardLocked = probes < 3)
                },
                onExpired = {},
            )
        }
        advanceTimeBy(10_000L)
        runCurrent()

        assertThat(probes).isEqualTo(3)
        assertThat(m.state).isEqualTo(WakeCycleState.VISIBLE)
        assertThat(timer.isCompleted).isTrue()
    }

    @Test
    fun `the remaining window is counted from the wake`() {
        val m = WakeCycleMachine()
        m.onScreenOff(enabled = true, dedicated = true, nowMs = 0L)
        assertThat(m.wakeWindowRemainingMs(5 * minute)).isNull() // armed: nothing to wait out

        m.on(nowMs = 5 * minute, keyguardLocked = true)
        assertThat(m.wakeWindowRemainingMs(5 * minute)).isEqualTo(WakeCycleMachine.WAKE_CYCLE_TIMEOUT_MS)
        assertThat(m.wakeWindowRemainingMs(5 * minute + 45_000L)).isEqualTo(15_000L)
        assertThat(m.wakeWindowRemainingMs(10 * minute)).isEqualTo(0L)
    }

    // --- What the cap is still for ----------------------------------------------------------------

    @Test
    fun `a resume with no cycle behind it never shows, whenever it comes`() {
        val m = WakeCycleMachine()
        for (at in listOf(0L, 2 * minute, 30 * minute)) {
            assertThat(m.evidence(nowMs = at, keyguardLocked = false)).isEqualTo(WakeCycleState.IDLE)
        }
    }

    @Test
    fun `a resume two minutes after a wake that already showed or lapsed shows nothing new`() {
        val shown = WakeCycleMachine().apply {
            onScreenOff(enabled = true, dedicated = true, nowMs = 0L)
            on(nowMs = minute)
        }
        assertThat(shown.state).isEqualTo(WakeCycleState.VISIBLE)
        shown.reset() // the user swiped it away
        assertThat(shown.evidence(nowMs = 3 * minute, keyguardLocked = false)).isEqualTo(WakeCycleState.IDLE)

        val lapsed = WakeCycleMachine().apply {
            onScreenOff(enabled = true, dedicated = true, nowMs = 0L)
            on(nowMs = minute, keyguardLocked = true)
        }
        assertThat(lapsed.evidence(nowMs = 3 * minute, keyguardLocked = false)).isEqualTo(WakeCycleState.IDLE)
    }

    @Test
    fun `a second screen on re-anchors a live cycle and cannot revive a dead one`() {
        val m = WakeCycleMachine()
        m.onScreenOff(enabled = true, dedicated = true, nowMs = 0L)
        m.on(nowMs = minute, keyguardLocked = true)
        // The screen blinks off and on again with the Keyguard still up: the window restarts from the new wake.
        m.onScreenOff(enabled = true, dedicated = true, nowMs = minute + 10_000L)
        m.on(nowMs = minute + 20_000L, keyguardLocked = true)
        assertThat(m.evidence(nowMs = minute + 20_000L + 59_000L, keyguardLocked = false)).isEqualTo(WakeCycleState.VISIBLE)

        val dead = WakeCycleMachine()
        assertThat(dead.on(nowMs = 5 * minute)).isEqualTo(WakeCycleState.IDLE) // never armed: a SCREEN_ON alone shows nothing
    }

    // --- A song that blinks out while the screen is off --------------------------------------------

    @Test
    fun `the song is judged at the wake, not while the screen is off`() {
        // Armed cycles do not look at the song at all (the machine is only asked at the wake): a queue rebuild that
        // makes it null in between costs nothing, and what counts is whether there is one when the screen comes on.
        val withSong = WakeCycleMachine().apply { onScreenOff(enabled = true, dedicated = true, nowMs = 0L) }
        assertThat(withSong.on(nowMs = 5 * minute, hasSong = true)).isEqualTo(WakeCycleState.VISIBLE)

        // Nothing to control at the wake: an empty surface is not a lock screen (unchanged from B16).
        val withoutSong = WakeCycleMachine().apply { onScreenOff(enabled = true, dedicated = true, nowMs = 0L) }
        assertThat(withoutSong.on(nowMs = 5 * minute, hasSong = false)).isEqualTo(WakeCycleState.IDLE)
    }

    @Test
    fun `the host leaves an armed cycle alone when the composed song goes null`() {
        val host = hostSource()
        val songEffect = host.substringAfter("DisposableEffect(song?.id)").substringBefore("DisposableEffect(latestEnabled")
        assertThat(songEffect).contains("machine.state != WakeCycleState.ARMED")
        assertThat(songEffect).contains("abandon(\"song-null\")")
    }

    // --- Losing the receiver while the screen is off ----------------------------------------------

    @Test
    fun `a host attached with the screen already off arms the cycle`() {
        val m = WakeCycleMachine()
        assertThat(m.onHostAttached(enabled = true, dedicated = true, screenInteractive = false, nowMs = 0L))
            .isEqualTo(WakeCycleState.ARMED)
        assertThat(m.on(nowMs = 8 * minute)).isEqualTo(WakeCycleState.VISIBLE)
    }

    @Test
    fun `a host attached with the screen on arms nothing, and one that cannot be eligible neither`() {
        assertThat(WakeCycleMachine().onHostAttached(true, true, screenInteractive = true, nowMs = 0L))
            .isEqualTo(WakeCycleState.IDLE)
        assertThat(WakeCycleMachine().onHostAttached(false, true, screenInteractive = false, nowMs = 0L))
            .isEqualTo(WakeCycleState.IDLE)
        assertThat(WakeCycleMachine().onHostAttached(true, false, screenInteractive = false, nowMs = 0L))
            .isEqualTo(WakeCycleState.IDLE)
    }

    @Test
    fun `attaching again never replaces a live cycle or a shown surface`() {
        val armed = WakeCycleMachine().apply { onScreenOff(enabled = true, dedicated = true, nowMs = 100L) }
        armed.onHostAttached(true, true, screenInteractive = false, nowMs = 900L)
        assertThat(armed.armedAtMs).isEqualTo(100L)

        val waiting = WakeCycleMachine().apply {
            onScreenOff(enabled = true, dedicated = true, nowMs = 0L)
            on(nowMs = 10L, keyguardLocked = true)
        }
        waiting.onHostAttached(true, true, screenInteractive = false, nowMs = 20L)
        assertThat(waiting.state).isEqualTo(WakeCycleState.WAITING_FOR_KEYGUARD)

        val shown = WakeCycleMachine().apply {
            onScreenOff(enabled = true, dedicated = true, nowMs = 0L)
            on(nowMs = 10L)
        }
        shown.onHostAttached(true, true, screenInteractive = false, nowMs = 20L)
        assertThat(shown.state).isEqualTo(WakeCycleState.VISIBLE)
    }

    @Test
    fun `the host arms on attach and reads the real screen state`() {
        val host = hostSource()
        assertThat(host).contains("machine.onHostAttached(")
        assertThat(host).contains("isInteractive")
    }

    // --- The host's timer and its log ---------------------------------------------------------------

    @Test
    fun `the host no longer starts a deadline when the cycle is armed`() {
        val host = hostSource()
        assertThat(host).contains("superviseWaitingCycle(")
        assertThat(host).doesNotContain("elapsedRealtime() + WakeCycleMachine.WAKE_CYCLE_TIMEOUT_MS")
        assertThat(host).doesNotContain("delay(")
    }

    @Test
    fun `the host logs every way a cycle starts, resolves or is lost under one tag`() {
        val host = hostSource()
        assertThat(host).contains("private const val LOG_TAG = \"DapLockScreen\"")
        listOf(
            "\"SCREEN_OFF\"",
            "\"SCREEN_ON\"",
            "EVIDENCE(",
            "receiver registered",
            "receiver unregistered",
            "surface visible=",
            "reset reason=timeout",
            "abandon(\"song-null\")",
            "abandon(\"ineligible\"",
            "abandon(\"dismissed\")",
        ).forEach { assertThat(host).contains(it) }
        // The reason is a parameter of one function, so no reset can go unexplained.
        assertThat(host).contains("reset reason=%s")
    }

    private fun hostSource(): String =
        java.io.File(
            "src/main/java/com/lostf1sh/pixelplayeross/presentation/lockscreen/DapLockScreenHost.kt",
        ).let { if (it.isFile) it else java.io.File("app/${it.path}") }.readText()
}
