package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.MainCoroutineExtension
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.ShuffleRequestGate
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * B19-S2: the shuffle button lost the second toggle while a fade was pending.
 *
 * B18-R4 made the gate keep one intent (the last request wins), but the button asks `!engine.isShuffleEnabled`, and that is the
 * player's flag, which has not moved while the gate holds a request back. ON, crossfade running, windowed queue: tap (OFF waits),
 * tap (the button reads ON again and asks for OFF again), the fade ends: OFF, where the last tap meant ON.
 *
 * The holder and the engine's glue run for real here against the real [ShuffleRequestGate]; only the player behind the gate is a
 * flag, a window and a fade.
 */
@ExtendWith(MainCoroutineExtension::class)
class ShuffleToggleIntentTest {

    private class Host(
        override var shuffleOn: Boolean,
        override var windowed: Boolean,
        override var transitionRunning: Boolean,
    ) : ShuffleRequestGate.Host {
        val flagWrites = mutableListOf<Boolean>()

        /** The writes that make the classic mode deal the queue again: switched on by a listener. */
        var redraws = 0
        lateinit var gate: ShuffleRequestGate

        override fun widen(): Boolean {
            windowed = false
            return true
        }

        override fun setFlag(enabled: Boolean, freshOrder: Boolean) {
            flagWrites += enabled
            if (enabled && freshOrder) redraws++
            shuffleOn = enabled
            gate.onFlagChanged()
        }

        override fun watchTransitionEnd() {}
        override fun unwatchTransitionEnd() {}
        override fun later(action: () -> Unit) = action()
    }

    private class Setup(
        val host: Host,
        val gate: ShuffleRequestGate,
        val engine: DualPlayerEngine,
        val holder: PlaybackStateHolder,
        val requests: MutableList<Boolean>,
    ) {
        /** The fade ends and the gate gets round to what waited. */
        fun fadeEnds() {
            host.transitionRunning = false
            gate.settle()
        }
    }

    private fun setup(shuffleOn: Boolean, windowed: Boolean, fading: Boolean): Setup {
        val host = Host(shuffleOn, windowed, fading)
        val gate = ShuffleRequestGate(host)
        host.gate = gate
        val requests = mutableListOf<Boolean>()
        // What DualPlayerEngine does with the gate (`setShuffleEnabled`, `shuffleIntent`, `isShuffleEnabled`), without a device.
        val engine = mockk<DualPlayerEngine>(relaxed = true) {
            every { isShuffleEnabled } answers { host.shuffleOn }
            every { shuffleIntent } answers { gate.intent }
            every { setShuffleEnabled(any()) } answers {
                val enabled = firstArg<Boolean>()
                requests += enabled
                gate.request(enabled)
            }
        }
        val power = mockk<PowerManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true) { every { getSystemService(Context.POWER_SERVICE) } returns power }
        val holder = PlaybackStateHolder(
            engine, mockk<UserPreferencesRepository>(relaxed = true), mockk(relaxed = true), context,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), TestDispatcherProvider(Dispatchers.Unconfined),
        )
        return Setup(host, gate, engine, holder, requests)
    }

    @Test
    fun `two UI toggles while a fade defers changes must finish ON`() {
        val s = setup(shuffleOn = true, windowed = true, fading = true)

        s.holder.toggleShuffle()
        assertThat(s.holder.stablePlayerState.value.isShuffleEnabled).isFalse()
        s.holder.toggleShuffle()
        assertThat(s.holder.stablePlayerState.value.isShuffleEnabled).isTrue()
        s.fadeEnds()

        // The second tap flipped what the first one asked for (OFF -> ON), it did not repeat it.
        assertThat(s.requests).containsExactly(false, true).inOrder()
        assertThat(s.host.shuffleOn).isTrue()
        assertThat(s.holder.stablePlayerState.value.isShuffleEnabled).isTrue()
    }

    @Test
    fun `three toggles end OFF, and the screen says what the player applied`() {
        val s = setup(shuffleOn = true, windowed = true, fading = true)

        repeat(3) { s.holder.toggleShuffle() }
        assertThat(s.holder.stablePlayerState.value.isShuffleEnabled).isFalse()
        s.fadeEnds()

        assertThat(s.requests).containsExactly(false, true, false).inOrder()
        assertThat(s.host.shuffleOn).isFalse()
        assertThat(s.holder.stablePlayerState.value.isShuffleEnabled).isFalse()
        // Only the last intent was applied, once.
        assertThat(s.host.flagWrites).containsExactly(false)
    }

    @Test
    fun `from OFF, taps in a fade end ON and the queue is dealt again once`() {
        val s = setup(shuffleOn = false, windowed = true, fading = true)

        repeat(3) { s.holder.toggleShuffle() }
        s.fadeEnds()

        assertThat(s.host.shuffleOn).isTrue()
        assertThat(s.holder.stablePlayerState.value.isShuffleEnabled).isTrue()
        assertThat(s.host.flagWrites).containsExactly(true)
        assertThat(s.host.redraws).isEqualTo(1)
    }

    @Test
    fun `with no fade two toggles end ON and the classic redraw is drawn once, for the final ON`() {
        val s = setup(shuffleOn = true, windowed = false, fading = false)

        s.holder.toggleShuffle() // OFF
        s.holder.toggleShuffle() // ON

        assertThat(s.host.shuffleOn).isTrue()
        assertThat(s.holder.stablePlayerState.value.isShuffleEnabled).isTrue()
        // Two flips, and only the one that switched shuffle on deals the queue again (off never does): not once per tap.
        assertThat(s.host.flagWrites).containsExactly(false, true).inOrder()
        assertThat(s.host.redraws).isEqualTo(1)
    }

    @Test
    fun `what a toggle flips is the last intent, and a head unit's flip is the newest one`() {
        val s = setup(shuffleOn = true, windowed = true, fading = true)
        s.holder.toggleShuffle() // OFF waits for the fade
        assertThat(s.gate.intent).isFalse()
        assertThat(s.host.shuffleOn).isTrue()

        // A head unit flips the player's flag itself during the fade (Android Auto's standard setShuffleModeEnabled(false)).
        s.host.shuffleOn = false
        s.gate.onFlagChanged()
        assertThat(s.gate.intent).isFalse()

        s.holder.toggleShuffle()
        s.fadeEnds()

        assertThat(s.requests).containsExactly(false, true).inOrder()
        assertThat(s.host.shuffleOn).isTrue()
    }

    @Test
    fun `the session action asks the same question as the button, so two taps from a head unit also end ON`() {
        val s = setup(shuffleOn = true, windowed = true, fading = true)
        val engine = s.engine

        // MusicService's CUSTOM_COMMAND_TOGGLE_SHUFFLE: `updateManualShuffleState(session, enabled = !engine.shuffleIntent)`, which
        // is `engine.setShuffleEnabled(enabled)`.
        repeat(2) { engine.setShuffleEnabled(!engine.shuffleIntent) }
        s.fadeEnds()

        assertThat(s.requests).containsExactly(false, true).inOrder()
        assertThat(s.host.shuffleOn).isTrue()
    }

    @Test
    fun `an explicit off after Play is one request, whatever a stale toggle would have read`() {
        val s = setup(shuffleOn = false, windowed = true, fading = true)
        s.holder.toggleShuffle() // ON waits for the fade

        // A screen that read "on" and now means "off" (playSongs): an explicit request, not a flip of what is pending.
        s.holder.setShuffleEnabled(false)
        s.fadeEnds()

        assertThat(s.host.shuffleOn).isFalse()
        assertThat(s.holder.stablePlayerState.value.isShuffleEnabled).isFalse()
        assertThat(s.host.flagWrites).isEmpty()
    }
}
