package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.media3.AnchorSource
import com.decent.usbaudio.media3.OffsetAuthority
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * AUD-136 (a), the two latches that put a fresh track at +167 s and a seek past the end at -3 s:
 *
 * - `device-b7fix-selfskip`: PREV (seek to 0) was issued to the engine explicitly and completed in
 *   44 ms, before Media3's flush. The flush then saw no seek "owed", replaced the explicit epoch
 *   with the host mirror's stale 167 443 ms, latched `pts - 167 s` and bootstrapped the engine to
 *   167 s — the track jumped and ended by itself.
 * - `device-b7fix-ffpastend`: a seek to 168 696 ms on a 165 634 ms track latched `pts - 168.7 s`
 *   (the first buffer is the last frame, 165.6 s): an offset 3 s wrong.
 *
 * Media3's own renderer offset for the buffer's stream settles both: the buffer's media position is
 * `pts - offset`. It is used only where the anchor is grossly wrong, so an ordinary seek (first
 * buffer a frame before the target) latches exactly as before.
 */
class OffsetAuthorityTest {
    private val start = 1_000_000_000_000L

    @Test
    fun `a stale anchor is replaced by the buffer's media position from Media3's offset`() {
        val anchor = OffsetAuthority.media3Anchor(
            presentationTimeUs = start + 237_026_666L,
            media3OffsetUs = start + 237_026_666L,
            epochAnchorUs = 167_443_000L,
            durationUs = 212_853_000L,
        )
        assertThat(anchor).isEqualTo(0L)
    }

    @Test
    fun `a seek past the end latches at the last frame, not at the requested target`() {
        val anchor = OffsetAuthority.media3Anchor(
            presentationTimeUs = start + 165_634_666L,
            media3OffsetUs = start,
            epochAnchorUs = 168_696_000L,
            durationUs = 165_634_666L,
        )
        assertThat(anchor).isEqualTo(165_634_666L)
    }

    @Test
    fun `an ordinary seek keeps its own anchor`() {
        assertThat(
            OffsetAuthority.media3Anchor(start + 59_990_000L, start, 60_000_000L, 200_000_000L)
        ).isNull()
    }

    @Test
    fun `without a usable Media3 offset nothing changes`() {
        // No report yet.
        assertThat(OffsetAuthority.media3Anchor(start + 5_000_000L, null, 90_000_000L, 200_000_000L)).isNull()
        // A buffer from before that offset's stream, or a media position the item cannot have.
        assertThat(OffsetAuthority.media3Anchor(start + 5_000_000L, start + 9_000_000L, 90_000_000L, 200_000_000L)).isNull()
        assertThat(OffsetAuthority.media3Anchor(start + 500_000_000L, start, 90_000_000L, 200_000_000L)).isNull()
    }

    @Test
    fun `a flush keeps the explicit epoch the seek it follows opened, even if the engine already did it`() {
        // Issued at reset count 7, flush has just counted 8: this flush is that seek's.
        assertThat(OffsetAuthority.keepExplicitEpochAtFlush(false, AnchorSource.EXPLICIT_SEEK_TARGET, 7, 8)).isTrue()
        // A later, unrelated reset: the host position is the authority again.
        assertThat(OffsetAuthority.keepExplicitEpochAtFlush(false, AnchorSource.EXPLICIT_SEEK_TARGET, 5, 8)).isFalse()
        // Already latched, or not an explicit epoch: unchanged rules.
        assertThat(OffsetAuthority.keepExplicitEpochAtFlush(true, AnchorSource.EXPLICIT_SEEK_TARGET, 7, 8)).isFalse()
        assertThat(OffsetAuthority.keepExplicitEpochAtFlush(false, AnchorSource.IMPLICIT_RESTART, 7, 8)).isFalse()
    }
}
