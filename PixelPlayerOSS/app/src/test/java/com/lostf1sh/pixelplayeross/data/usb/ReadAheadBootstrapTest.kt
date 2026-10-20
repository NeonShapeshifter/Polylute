package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.BootstrapSeekPolicy
import com.decent.usbaudio.media3.EnginePlacement
import com.decent.usbaudio.media3.OffsetAuthority
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-139 (confirmed on the device, B7-fix3): 5-30 s after a natural end the track jumped forward
 * ~30 s. The next item started at 0 (item-start seek gen=20), and 30.6 s later the first renderer
 * buffer the sink saw — Media3's read-ahead edge, pts = offset + 60.65 s — drove the bootstrap:
 * "Native engine bootstrap seek to 60s (playing=true)". No flush in between: only the engine
 * creation had re-armed the bootstrap.
 *
 * AUD-138: the same path with a small jump. A fast-forward past the end moved into the next item,
 * whose engine started at 0 and was bootstrapped to 6.7 s by a buffer read ahead during the FF; and
 * the ended item latched its offset from that next item's buffer (Media3 at 229 s, engine at 7.7 s).
 *
 * The numbers are those of `device-b7fix3-mixed-2026-09-28.log`.
 */
class ReadAheadBootstrapTest {
    private val engine = Any()
    private val offset = 1_000_568_282_085L
    private val readAheadPts = 1_000_628_932_606L   // startMediaTimeUs of the 19:57:33 bootstrap

    @Test
    fun `a buffer read 30 s ahead never moves an engine an item start already placed`() {
        val placement = EnginePlacement()
        placement.onPlaced(engine, 7L, "item-start")      // gen=20, 0 us
        // 30.6 s later: the buffer is Media3's read-ahead, not the audible position.
        assertThat(readAheadPts - offset).isEqualTo(60_650_521L)
        assertThat(placement.bufferMayPlace(engine, bufferFromPlayingStream = true, enginePositionUs = 30_600_000L)).isFalse()
    }

    @Test
    fun `pause and play or a realign do not open the door either`() {
        val placement = EnginePlacement()
        placement.onPlaced(engine, 3L, "item-start")
        // Pause / play: no position reset, no placement change. (That they, and the watchdog, never
        // seek is pinned in ReadAheadBootstrapWiringTest.)
        assertThat(placement.bufferMayPlace(engine, bufferFromPlayingStream = true)).isFalse()
        // A realign places the engine at its own position (NATIVE_ENGINE anchor): still a placement.
        placement.onPlaced(engine, 3L, "realign")
        assertThat(placement.bufferMayPlace(engine, bufferFromPlayingStream = true)).isFalse()
        assertThat(placement.lastOrigin).isEqualTo("realign")
    }

    @Test
    fun `an engine nobody placed is placed by the buffer (restore at 235 s)`() {
        val placement = EnginePlacement()
        // "Native FLAC engine started for: … awaiting caller position": the buffer is the only source.
        assertThat(placement.bufferMayPlace(engine, bufferFromPlayingStream = true)).isTrue()
        // A new engine is unplaced even if the previous one was placed.
        placement.onPlaced(engine, 1L, "bootstrap")
        assertThat(placement.bufferMayPlace(Any(), bufferFromPlayingStream = true)).isTrue()
    }

    @Test
    fun `AUD-139 recurrence - an unplaced engine that has been playing for minutes is not waiting for a position`() {
        val placement = EnginePlacement()
        // 00:19:32.766 of B7-fix4: engine created inside configure, never placed; the first buffer
        // (31 008 ms, the read-ahead edge) reaches the sink when the engine is at 256 s.
        assertThat(placement.bufferMayPlace(engine, bufferFromPlayingStream = true, enginePositionUs = 256_368_000L)).isFalse()
        // Restore at start: unplaced and not heard, the buffer is the position.
        assertThat(placement.bufferMayPlace(engine, bufferFromPlayingStream = true, enginePositionUs = 0L)).isTrue()
        // The door closes after seconds of audible play, not milliseconds: a slow first read still counts.
        assertThat(placement.bufferMayPlace(engine, bufferFromPlayingStream = true,
            enginePositionUs = EnginePlacement.MAX_UNPLACED_ENGINE_POSITION_US)).isTrue()
        assertThat(placement.bufferMayPlace(engine, bufferFromPlayingStream = true,
            enginePositionUs = EnginePlacement.MAX_UNPLACED_ENGINE_POSITION_US + 1)).isFalse()
    }

    @Test
    fun `a position reset after the placement is not a way in - Media3 resets for its own reasons`() {
        val placement = EnginePlacement()
        placement.onPlaced(engine, 4L, "item-start")
        // B7-fix4 let the first buffer after a flush place a placed engine ("repeat one", an implicit
        // restart). A seek the listener asks for reaches the engine explicitly through MappingPlayer;
        // a reset without one (a renderer re-enable) reads at Media3's own position, which the engine
        // is not obliged to follow. Measured: the flush of 00:19:42.910 with Media3 at 256 s and the
        // engine at 41 s.
        assertThat(placement.bufferMayPlace(engine, bufferFromPlayingStream = true, enginePositionUs = 41_000_000L)).isFalse()
        assertThat(placement.bufferMayPlace(engine, bufferFromPlayingStream = true, enginePositionUs = 0L)).isFalse()
    }

    @Test
    fun `a running engine at its item start is placed there instead of waiting for a buffer`() {
        val placement = EnginePlacement()
        // Created inside configure a few ms ago: at the declared start already, so no seek (no cut).
        assertThat(placement.itemStartFor(engine, enginePositionUs = 23_000L, declaredStartUs = 0L))
            .isEqualTo(EnginePlacement.ItemStart.InPlace)
        // Restore declared at 74 675 ms: the engine is at 0, so it is sent there explicitly.
        val seek = placement.itemStartFor(engine, enginePositionUs = 0L, declaredStartUs = 74_675_000L)
        assertThat((seek as EnginePlacement.ItemStart.SeekTo).targetUs).isEqualTo(74_675_000L)
        // Already placed by anything: hands off.
        placement.onPlaced(engine, 1L, "user")
        assertThat(placement.itemStartFor(engine, enginePositionUs = 5_000_000L, declaredStartUs = 0L))
            .isEqualTo(EnginePlacement.ItemStart.AlreadyPlaced)
    }

    @Test
    fun `decide - the four outcomes of a first buffer`() {
        val placement = EnginePlacement()
        fun decide(engine: Any = this.engine, enginePos: Long = 0L, offsetKnown: Boolean = true, owed: Boolean = false,
                   bufferPos: Long = 74_675_000L, explicitTarget: Long = -1L, fresh: Boolean = false, playing: Boolean = true) =
            BootstrapSeekPolicy.decide(placement, engine, playing, enginePos, offsetKnown, owed, bufferPos, explicitTarget, fresh)

        // Restore: nobody placed it, not heard, offset known → send it where the buffer is.
        assertThat((decide() as BootstrapSeekPolicy.Decision.Seek).targetUs).isEqualTo(74_675_000L)
        // The engine is already there (a manual skip whose engine was created at 0).
        assertThat(decide(bufferPos = 0L, enginePos = 20_000L)).isEqualTo(BootstrapSeekPolicy.Decision.AlreadyThere)
        // An explicit seek still owed outranks the buffer.
        assertThat(decide(owed = true)).isEqualTo(BootstrapSeekPolicy.Decision.ExplicitOwed)
        // No offset yet: nothing to decide.
        assertThat(decide(offsetKnown = false)).isEqualTo(BootstrapSeekPolicy.Decision.None)
        // Another item's stream: refused.
        assertThat(decide(playing = false)).isEqualTo(BootstrapSeekPolicy.Decision.Refused)
        // Once placed, any buffer is refused — whatever it says.
        placement.onPlaced(engine, 1L, "item-start")
        assertThat(decide(bufferPos = 31_008_000L, enginePos = 256_368_000L)).isEqualTo(BootstrapSeekPolicy.Decision.Refused)
    }

    @Test
    fun `a buffer of the next item's stream is never a position of the playing item`() {
        val placement = EnginePlacement()
        assertThat(BootstrapSeekPolicy.bufferFromPlayingStream(latestOffsetUs = 1_000_656_226_666L,
            playingOffsetUs = 1_000_430_893_598L)).isFalse()
        assertThat(BootstrapSeekPolicy.bufferFromPlayingStream(latestOffsetUs = offset, playingOffsetUs = offset)).isTrue()
        // Unknown (Media3 has not reported yet): as before, the buffer is not excluded.
        assertThat(BootstrapSeekPolicy.bufferFromPlayingStream(latestOffsetUs = null, playingOffsetUs = null)).isTrue()
        assertThat(placement.bufferMayPlace(engine, bufferFromPlayingStream = false)).isFalse()
    }

    @Test
    fun `AUD-138 a seek past the end is a seek to the end, and the ended item keeps its own offset`() {
        // 20:01:26.857: seek to 228 995 ms on a 225 333 ms track.
        assertThat(OffsetAuthority.seekTargetWithinItem(228_995_000L, durationUs = 225_333_000L)).isEqualTo(225_333_000L)
        assertThat(OffsetAuthority.seekTargetWithinItem(100_000_000L, durationUs = 225_333_000L)).isEqualTo(100_000_000L)
        assertThat(OffsetAuthority.seekTargetWithinItem(5_000L, durationUs = 0L)).isEqualTo(5_000L)
        // The first buffer after it belongs to the next item's stream: the playing item latches Media3's
        // offset for itself, not `pts - anchor` (which gave the next item's offset, and 229 s of drift).
        val playingOffsetUs = 1_000_430_893_598L
        assertThat(OffsetAuthority.latchPresentationForForeignBuffer(playingOffsetUs, anchorUs = 225_333_000L))
            .isEqualTo(playingOffsetUs + 225_333_000L)
    }
}
