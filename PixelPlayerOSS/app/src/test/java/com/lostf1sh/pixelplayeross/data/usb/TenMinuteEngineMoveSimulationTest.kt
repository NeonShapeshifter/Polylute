package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.BootstrapSeekPolicy
import com.decent.usbaudio.media3.EnginePlacement
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * AUD-139, recurrence in B7-fix4 (`position-trace.txt`, 00:19:32): 4 min 16 s after a natural end
 * with a rate change, `ENGINE_SEEK origin=bootstrap media3=256368ms engine=31008ms` moved a playing
 * engine 225 s back. The engine had been created inside the transition's `configure` ("awaiting
 * caller position"), the item-start declaration that followed found it running and did nothing, so
 * nobody had placed it; the first renderer buffer the sink saw — Media3's read-ahead edge, 31 s into
 * the item, delivered when LoadControl reopened for the last 15 s — was taken as the position.
 *
 * Ten simulated minutes of a listening session: a restore, pauses, a user seek, a realign, Media3's
 * own position reset with a wrong first buffer, a cross-rate natural end whose first buffer arrives
 * minutes late, a same-rate one, a NEXT across rates. Media3 is modelled as the sink meets it: the
 * first renderer buffer after an arming event arrives when it arrives, at the position it reads.
 * The sink side is the decision classes it runs — [EnginePlacement] and [BootstrapSeekPolicy] —
 * wired as `UsbAudioSink` wires them (pinned in [ReadAheadBootstrapWiringTest]).
 *
 * The invariant is the listener's: what the DAC plays follows the clock, except where the user or
 * the session sent it (a seek, an item start, the sink's own realign, the restore before anything
 * has sounded). No other route may move it.
 */
class TenMinuteEngineMoveSimulationTest {

    private companion object {
        const val STEP_MS = 50L
        const val SESSION_MS = 600_000L
        const val PRELOAD_US = 15_000_000L
        /** The first buffer at the read-ahead edge: `startMediaTimeUs - windowOffset` of the incident. */
        const val EDGE_US = 31_008_000L
        const val TOLERANCE_US = 100_000L
        val EXPLICIT_ORIGINS = setOf("user", "item-start", "realign")
    }

    private class Move(val origin: String, val atMs: Long, val fromUs: Long, val toUs: Long, val audible: Boolean)

    /** A first renderer buffer on its way to the sink: where it reads, and when it gets there. */
    private class Pending(val positionUs: Long, val atMs: Long? = null, val atEngineUs: Long? = null)

    private class Session(private val durationsUs: List<Long>) {
        var nowMs = 0L
        val moves = ArrayList<Move>()
        val drifts = ArrayList<String>()

        // The native engine: what the DAC hears.
        private var engine = Any()
        private var engineUs = 0L
        private var running = false
        private var paused = false
        private var playedMs = 0L
        private var item = 0

        // What the listener should hear: the clock, moved only by what they asked for.
        private var expectedUs = 0L
        private var settleUntilMs = 0L

        // The sink's placement state.
        private val placement = EnginePlacement()
        private var resets = 0L
        private var explicitTargetUs = -1L
        private var explicitIssuedAt = Long.MIN_VALUE / 2
        private var needsInit = false
        private var pending: Pending? = null

        private fun duration() = durationsUs[item]

        private fun move(origin: String, toUs: Long) {
            moves += Move(origin, nowMs, engineUs, toUs, audible = running && !paused && playedMs > 0)
            engineUs = toUs
            placement.onPlaced(engine, resets, origin)
        }

        private fun explicit(origin: String, toUs: Long) {
            explicitTargetUs = toUs
            explicitIssuedAt = resets
            move(origin, toUs)
        }

        // --- what happens to the session ----------------------------------------------------------

        /**
         * A new item's engine. [viaConfigure]: created inside the transition's `configure` (a rate
         * change), so it is running and unplaced when the item-start declaration finds it;
         * [declareAfter] is whether that declaration follows (a restore's runs before the stream exists).
         */
        fun startItem(
            index: Int,
            startUs: Long,
            viaConfigure: Boolean,
            declareAfter: Boolean,
            firstBuffer: Pending,
            startPaused: Boolean = false,
        ) {
            item = index
            engine = Any()
            engineUs = 0L
            running = true
            paused = startPaused
            playedMs = 0L
            expectedUs = startUs
            settleUntilMs = nowMs + 1_000
            needsInit = true
            pending = firstBuffer
            if (!viaConfigure) {
                explicit("item-start", startUs)
            } else if (declareAfter) {
                when (val start = placement.itemStartFor(engine, engineUs, startUs)) {
                    EnginePlacement.ItemStart.InPlace -> placement.onPlaced(engine, resets, "item-start-in-place")
                    is EnginePlacement.ItemStart.SeekTo -> explicit("item-start", start.targetUs)
                    EnginePlacement.ItemStart.AlreadyPlaced -> Unit
                }
            }
        }

        fun pause() { paused = true }
        fun resume() { paused = false }

        /** A seek from the user or the session: explicit, then Media3's position reset and its buffer. */
        fun userSeek(deltaUs: Long) {
            val target = (engineUs + deltaUs).coerceIn(0L, duration() - 1_000_000L)
            explicit("user", target)
            expectedUs = target
            settleUntilMs = nowMs + 1_000
            resets++
            needsInit = true
            pending = Pending(positionUs = target, atMs = nowMs + 100)
        }

        /** A mode switch: the sink sends the engine to the position it is at. */
        fun realign() = move("realign", engineUs)

        /** Media3 resets its position on its own (a renderer re-enable) and reads somewhere else. */
        fun media3ResetReadingAt(offsetFromEngineUs: Long) {
            resets++
            needsInit = true
            pending = Pending(positionUs = (engineUs + offsetFromEngineUs).coerceAtLeast(0L), atMs = nowMs + 100)
        }

        // --- time ---------------------------------------------------------------------------------

        fun run(untilMs: Long) {
            while (nowMs < untilMs) step()
        }

        private fun step() {
            nowMs += STEP_MS
            if (running && !paused) {
                engineUs += STEP_MS * 1000
                expectedUs += STEP_MS * 1000
                playedMs += STEP_MS
            }
            deliverBuffer()
            if (running && engineUs >= duration()) endOfItem()
            if (nowMs >= settleUntilMs && abs(engineUs - expectedUs) > TOLERANCE_US) {
                drifts += "t=${nowMs}ms item=$item engine=${engineUs / 1000}ms expected=${expectedUs / 1000}ms"
                expectedUs = engineUs   // report once, then follow
            }
        }

        private fun deliverBuffer() {
            val buffer = pending ?: return
            val due = (buffer.atMs != null && nowMs >= buffer.atMs) ||
                (buffer.atEngineUs != null && engineUs >= buffer.atEngineUs)
            if (!due) return
            pending = null
            // A buffer with nothing armed is read-ahead that the sink consumes unplayed.
            if (!needsInit) return
            needsInit = false
            when (val decision = BootstrapSeekPolicy.decide(
                placement = placement,
                engine = engine,
                fromPlayingStream = true,
                enginePositionUs = engineUs,
                windowOffsetKnown = true,
                explicitOwed = false,
                bufferPositionUs = buffer.positionUs,
                explicitTargetUs = explicitTargetUs,
                explicitIsFresh = explicitIssuedAt >= resets - 1,
            )) {
                // The listener's expectation is not touched: a bootstrap that lands anywhere but where
                // the session already was shows up as drift.
                is BootstrapSeekPolicy.Decision.Seek -> move("bootstrap", decision.targetUs)
                BootstrapSeekPolicy.Decision.AlreadyThere ->
                    placement.onPlaced(engine, resets, "bootstrap-already-there")
                else -> Unit
            }
        }

        private fun endOfItem() {
            running = false
            item++
            check(item < durationsUs.size) { "the session outran its playlist" }
        }

        fun currentItem() = item
        fun engineMs() = engineUs / 1000
        fun isRunning() = running
        fun durationOf(index: Int) = durationsUs[index]
    }

    private val durations = listOf(240_000_000L, 271_653_000L, 225_333_000L, 180_000_000L, 200_000_000L)

    /**
     * The whole session. Item 1 is "Sin pausa" of the incident (271.653 s); its first buffer arrives
     * when the engine reaches the last 15 s, at the read-ahead edge.
     */
    private fun runSession(): Session {
        val s = Session(durations)
        // 0 s: restore at 74.675 s, paused. The stream did not exist when the item start was declared,
        // so the engine is created by configure and only the first buffer says where the session is.
        s.startItem(0, 74_675_000L, viaConfigure = true, declareAfter = false,
            firstBuffer = Pending(positionUs = 74_675_000L, atMs = 300), startPaused = true)
        s.run(2_000); s.resume()
        s.run(30_000); s.pause()
        s.run(50_000); s.resume()
        s.run(60_000); s.userSeek(+30_000_000L)
        s.run(90_000); s.realign()
        s.run(100_000); s.media3ResetReadingAt(+20_000_000L)        // Media3's own reset, reads 20 s ahead
        // Item 0 ends by itself around 157 s. The next item's engine is created by configure (the
        // rate changes) and its first buffer arrives only when LoadControl reopens for the last 15 s.
        while (s.currentItem() == 0) s.run(s.nowMs + 50)
        s.startItem(1, 0L, viaConfigure = true, declareAfter = true,
            firstBuffer = Pending(positionUs = EDGE_US, atEngineUs = durations[1] - PRELOAD_US))
        s.run(s.nowMs + 40_000); s.pause()
        s.run(s.nowMs + 10_000); s.resume()
        // No seek before that buffer: a flush would replace it with the one Media3 reads at the target.
        while (s.engineMs() < 258_000L) s.run(s.nowMs + 50)
        s.userSeek(-20_000_000L)
        while (s.currentItem() == 1) s.run(s.nowMs + 50)
        // Item 2 follows at the same rate: created by the item start; a buffer at the edge comes 12 s in.
        s.startItem(2, 0L, viaConfigure = false, declareAfter = true,
            firstBuffer = Pending(positionUs = EDGE_US, atMs = s.nowMs + 12_000))
        s.run(s.nowMs + 60_000); s.startItemByNext(3)
        return s
    }

    private fun Session.startItemByNext(index: Int) {
        // NEXT across rates: configure creates the engine, the declaration follows, the buffer is at 0.
        startItem(index, 0L, viaConfigure = true, declareAfter = true, firstBuffer = Pending(positionUs = 0L, atMs = nowMs + 300))
        run(nowMs + 30_000); pause()
        run(nowMs + 10_000); userSeek(+10_000_000L)                 // a seek while paused
        run(nowMs + 5_000); resume()
        run(nowMs + 15_000); media3ResetReadingAt(-15_000_000L)      // and Media3 reads behind
        run(nowMs + 20_000); pause(); run(nowMs + 5_000); resume()
        run(SESSION_MS.coerceAtLeast(nowMs))
    }

    @Test
    fun `ten minutes - the engine follows the clock and moves only where the user or the session sent it`() {
        val s = runSession()

        assertThat(s.nowMs).isAtLeast(SESSION_MS)
        val rogue = s.moves.filter { it.origin !in EXPLICIT_ORIGINS && !(it.origin == "bootstrap" && !it.audible) }
        assertThat(rogue.map { "${it.origin} t=${it.atMs}ms ${it.fromUs / 1000}ms→${it.toUs / 1000}ms audible=${it.audible}" })
            .isEmpty()
        assertThat(s.drifts).isEmpty()
    }

    @Test
    fun `the restore is the only bootstrap, and it happens before anything has sounded`() {
        val s = runSession()

        val bootstraps = s.moves.filter { it.origin == "bootstrap" }
        assertThat(bootstraps).hasSize(1)
        assertThat(bootstraps.single().toUs).isEqualTo(74_675_000L)
        assertThat(bootstraps.single().audible).isFalse()
    }

    @Test
    fun `the read-ahead edge of the incident arrives minutes late and moves nothing`() {
        val s = runSession()

        // The buffer of item 1 reached the sink at ~256 s of playing; the engine was nowhere near 31 s.
        assertThat(s.moves.none { it.origin == "bootstrap" && it.toUs == EDGE_US }).isTrue()
    }
}
