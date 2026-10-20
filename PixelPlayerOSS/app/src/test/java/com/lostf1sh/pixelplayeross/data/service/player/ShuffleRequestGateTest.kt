package com.lostf1sh.pixelplayeross.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B18-R4: the last shuffle request wins when a crossfade is running on a windowed master, and no listener is left behind.
 *
 * The host below is a player that is only a flag, a window and a fade: the engine itself needs a device, the gate does not.
 */
class ShuffleRequestGateTest {

    private class FakeHost(
        override var shuffleOn: Boolean,
        override var windowed: Boolean,
        override var transitionRunning: Boolean,
    ) : ShuffleRequestGate.Host {
        var widenings = 0
        val flagWrites = mutableListOf<Boolean>()
        var listeners = 0
        var maxListeners = 0
        var watches = 0
        var unwatches = 0
        private val queued = ArrayDeque<() -> Unit>()
        lateinit var gate: ShuffleRequestGate

        override fun widen(): Boolean {
            widenings++
            windowed = false
            return true
        }

        val freshOrders = mutableListOf<Boolean>()

        override fun setFlag(enabled: Boolean, freshOrder: Boolean) {
            flagWrites += enabled
            freshOrders += freshOrder
            shuffleOn = enabled
            // ExoPlayer reports its own change synchronously, to the engine's listener among others.
            gate.onFlagChanged()
        }

        override fun watchTransitionEnd() {
            watches++
            listeners++
            maxListeners = maxOf(maxListeners, listeners)
        }

        override fun unwatchTransitionEnd() {
            unwatches++
            listeners--
        }

        override fun later(action: () -> Unit) {
            queued.addLast(action)
        }

        /** The main handler gets round to what was posted. */
        fun drain() {
            while (queued.isNotEmpty()) queued.removeFirst()()
        }

        /** The fade ends: the listeners are told, and each posts [ShuffleRequestGate.settle] as the engine does. */
        fun endFade() {
            transitionRunning = false
            if (listeners > 0) later { gate.settle() }
            drain()
        }
    }

    private fun gateOn(host: FakeHost): ShuffleRequestGate = ShuffleRequestGate(host).also { host.gate = it }

    @Test
    fun `off then on before the fade ends - the last request, on, is what stands, with no widening`() {
        // A queue longer than 200, shuffle on, a fade in flight: the window was cut for the shuffle order.
        val host = FakeHost(shuffleOn = true, windowed = true, transitionRunning = true)
        val gate = gateOn(host)

        gate.request(false)
        gate.request(true)
        host.endFade()

        assertThat(host.shuffleOn).isTrue()
        // On to on: nothing to change, so the queue is not widened (and certainly not twice).
        assertThat(host.widenings).isEqualTo(0)
        assertThat(host.flagWrites).isEmpty()
        assertThat(gate.hasPending).isFalse()
    }

    @Test
    fun `off then on from a master with shuffle off - on is applied once, with one widening`() {
        val host = FakeHost(shuffleOn = false, windowed = true, transitionRunning = true)
        val gate = gateOn(host)

        gate.request(true)
        gate.request(false)
        gate.request(true)
        host.endFade()

        assertThat(host.shuffleOn).isTrue()
        assertThat(host.widenings).isEqualTo(1)
        assertThat(host.flagWrites).containsExactly(true)
    }

    @Test
    fun `on off on off while it fades - off stands, with one widening`() {
        val host = FakeHost(shuffleOn = true, windowed = true, transitionRunning = true)
        val gate = gateOn(host)

        gate.request(false)
        gate.request(true)
        gate.request(false)
        gate.request(true)
        gate.request(false)
        host.endFade()

        assertThat(host.shuffleOn).isFalse()
        assertThat(host.widenings).isEqualTo(1)
        assertThat(host.flagWrites).containsExactly(false)
    }

    @Test
    fun `four requests - on off on off - from off end off`() {
        val host = FakeHost(shuffleOn = false, windowed = true, transitionRunning = true)
        val gate = gateOn(host)

        listOf(true, false, true, false).forEach(gate::request)
        host.endFade()

        assertThat(host.shuffleOn).isFalse()
        assertThat(host.widenings).isEqualTo(0)
        assertThat(host.flagWrites).isEmpty()
    }

    @Test
    fun `any number of requests registers one listener, and none is left once the fade is over`() {
        val host = FakeHost(shuffleOn = true, windowed = true, transitionRunning = true)
        val gate = gateOn(host)

        repeat(25) { gate.request(it % 2 == 0) }
        assertThat(host.listeners).isEqualTo(1)
        assertThat(host.watches).isEqualTo(1)
        assertThat(gate.pendingTarget).isTrue()

        host.endFade()

        assertThat(host.listeners).isEqualTo(0)
        assertThat(host.maxListeners).isEqualTo(1)
        assertThat(host.unwatches).isEqualTo(host.watches)
        assertThat(gate.hasPending).isFalse()

        // A later fade finds nothing to apply.
        host.transitionRunning = true
        host.endFade()
        assertThat(host.flagWrites.size).isAtMost(1)
    }

    @Test
    fun `a request while no fade is running is applied at once and replaces what was deferred`() {
        val host = FakeHost(shuffleOn = true, windowed = true, transitionRunning = true)
        val gate = gateOn(host)

        gate.request(false)
        // The fade ended but the listener's post has not run yet when the next request arrives.
        host.transitionRunning = false
        gate.request(true)
        host.drain()

        assertThat(host.shuffleOn).isTrue()
        assertThat(host.flagWrites).isEmpty()
        assertThat(host.listeners).isEqualTo(0)
        assertThat(gate.hasPending).isFalse()
    }

    @Test
    fun `a master that holds the whole queue flips the flag at once, even during a fade`() {
        // The scope of the rule is a windowed queue: a normal toggle (Direct, a short queue) never waits.
        val host = FakeHost(shuffleOn = false, windowed = false, transitionRunning = true)
        val gate = gateOn(host)

        gate.request(true)

        assertThat(host.shuffleOn).isTrue()
        assertThat(host.flagWrites).containsExactly(true)
        assertThat(host.widenings).isEqualTo(0)
        assertThat(host.watches).isEqualTo(0)
    }

    @Test
    fun `a windowed master with no fade is widened once and flipped at once`() {
        val host = FakeHost(shuffleOn = true, windowed = true, transitionRunning = false)
        val gate = gateOn(host)

        gate.request(false)

        assertThat(host.shuffleOn).isFalse()
        assertThat(host.widenings).isEqualTo(1)
        assertThat(host.windowed).isFalse()
    }

    @Test
    fun `the same flag with nothing pending is not a change`() {
        val host = FakeHost(shuffleOn = true, windowed = true, transitionRunning = false)
        val gate = gateOn(host)

        gate.request(true)

        assertThat(host.flagWrites).isEmpty()
        assertThat(host.widenings).isEqualTo(0)
    }

    @Test
    fun `a head unit flipping the flag on a windowed master during a fade widens once when it ends`() {
        val host = FakeHost(shuffleOn = true, windowed = true, transitionRunning = true)
        val gate = gateOn(host)

        // The standard setShuffleModeEnabled(false) reaches the player, not the gate.
        host.shuffleOn = false
        gate.onFlagChanged()
        host.drain()
        assertThat(host.widenings).isEqualTo(0)
        assertThat(host.listeners).isEqualTo(1)

        host.endFade()

        assertThat(host.widenings).isEqualTo(1)
        assertThat(host.shuffleOn).isFalse()
        assertThat(host.listeners).isEqualTo(0)
    }

    @Test
    fun `a head unit flip with no fade running widens at once, and one with the whole queue does nothing`() {
        val windowed = FakeHost(shuffleOn = true, windowed = true, transitionRunning = false)
        val gate = gateOn(windowed)
        windowed.shuffleOn = false
        gate.onFlagChanged()
        windowed.drain()
        assertThat(windowed.widenings).isEqualTo(1)

        val whole = FakeHost(shuffleOn = true, windowed = false, transitionRunning = false)
        val wholeGate = gateOn(whole)
        whole.shuffleOn = false
        wholeGate.onFlagChanged()
        whole.drain()
        assertThat(whole.widenings).isEqualTo(0)
    }

    @Test
    fun `a head unit flip is newer than an app request waiting for the fade`() {
        val host = FakeHost(shuffleOn = true, windowed = true, transitionRunning = true)
        val gate = gateOn(host)

        gate.request(false)
        // Before the fade ends the head unit turns shuffle off itself, then on again through the standard call: it wins.
        host.shuffleOn = true
        gate.onFlagChanged()
        host.endFade()

        assertThat(gate.pendingTarget).isNull()
        assertThat(host.shuffleOn).isTrue()
        assertThat(host.widenings).isEqualTo(1)
        assertThat(host.listeners).isEqualTo(0)
    }

    @Test
    fun `the gate's own flag write is not read as a head unit's`() {
        val host = FakeHost(shuffleOn = false, windowed = false, transitionRunning = false)
        val gate = gateOn(host)

        gate.request(true)
        host.drain()

        assertThat(host.widenings).isEqualTo(0)
        assertThat(gate.hasPending).isFalse()
    }

    @Test
    fun `a fade that is cancelled instead of finishing still applies the intent`() {
        val host = FakeHost(shuffleOn = true, windowed = true, transitionRunning = true)
        val gate = gateOn(host)

        gate.request(false)
        // cancelNext: transitionRunning goes false without the finished listeners; the engine posts settle.
        host.transitionRunning = false
        host.later { gate.settle() }
        host.drain()

        assertThat(host.shuffleOn).isFalse()
        assertThat(host.listeners).isEqualTo(0)
    }

    @Test
    fun `a second fade starting before the first intent was applied keeps waiting`() {
        val host = FakeHost(shuffleOn = true, windowed = true, transitionRunning = true)
        val gate = gateOn(host)

        gate.request(false)
        host.transitionRunning = false
        host.transitionRunning = true // a new fade began before the post ran
        gate.settle()

        assertThat(host.shuffleOn).isTrue()
        assertThat(gate.pendingTarget).isFalse()
        assertThat(host.listeners).isEqualTo(1)

        host.endFade()
        assertThat(host.shuffleOn).isFalse()
        assertThat(host.listeners).isEqualTo(0)
    }
}
