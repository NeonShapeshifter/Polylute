package com.lostf1sh.pixelplayeross.data.stats

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.Kit.MIN
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import com.lostf1sh.pixelplayeross.data.stats.Kit.event
import com.lostf1sh.pixelplayeross.data.stats.Kit.song
import org.junit.jupiter.api.Test

/**
 * "Formats heard" over events that name a codec the file does not have (B28-fix2 E2).
 *
 * B28a wrote `audio/raw` as the codec of events played on Direct, because the sink is handed decoded samples; the file's own codec
 * (`audio/flac`) is what the events written since B28 carry. Raw PCM is the stream that reached the output, not a codec the listener
 * has, so reading it back as one printed "WAV" for a FLAC library. Nothing stored is rewritten: it is read as a codec that was not
 * recorded. Synthetic data only.
 */
class AudioFormatCodecTest {
    private val now = at(2026, 10, 15, 20)
    private val all = StatsPeriod.Preset(StatsTimeRange.ALL)

    private fun facts(rate: Int, bits: Int, codec: String?) = ListenAudioFacts(
        route = ListenRoute.USB_DIRECT, signal = ListenSignal.PURITY, sourceRateHz = rate, sourceBits = bits, sourceCodec = codec,
    )

    private fun audioOf(vararg events: PlaybackStatsRepository.PlaybackEvent) =
        StatsEngine.report(Kit.ledger(events.toList(), Kit.catalog((1..4).map { song(it.toLong()) })), all, now, Kit.zone).audio!!

    @Test
    fun `an old event with raw pcm and a new one with flac at the same rate and depth are two rows, one without a codec`() {
        val old = event(1, now - 100 * MIN, 3 * MIN, identity = "item-1", occurrence = "a", audio = facts(44_100, 16, "audio/raw"))
        val new = event(2, now - 80 * MIN, 3 * MIN, identity = "item-2", occurrence = "b", audio = facts(44_100, 16, "audio/flac"))

        val formats = audioOf(old, new).formats

        assertThat(formats).hasSize(2)
        assertThat(formats.map { it.key.rateHz to it.key.bits }.toSet()).containsExactly(44_100 to 16)
        assertThat(formats.map { it.key.codec }).containsExactly(null, "audio/flac")
        assertThat(formats.map { it.key.codecLabel }).containsExactly(null, "FLAC")
        assertThat(formats.map { it.key.codecLabel }).doesNotContain("WAV")
    }

    @Test
    fun `raw pcm rows group with the events that recorded no codec at all`() {
        val raw = event(1, now - 100 * MIN, 3 * MIN, identity = "item-1", occurrence = "a", audio = facts(96_000, 24, "audio/raw"))
        val rawAgain = event(2, now - 90 * MIN, 3 * MIN, identity = "item-2", occurrence = "b", audio = facts(96_000, 24, "audio/raw; rate=96000"))
        val none = event(3, now - 80 * MIN, 3 * MIN, identity = "item-3", occurrence = "c", audio = facts(96_000, 24, null))

        val formats = audioOf(raw, rawAgain, none).formats

        assertThat(formats).hasSize(1)
        assertThat(formats.single().key.codec).isNull()
        assertThat(formats.single().timeMs).isEqualTo(9 * MIN)
    }

    @Test
    fun `the highest resolution heard through raw pcm names no codec either`() {
        val raw = event(1, now - 100 * MIN, 3 * MIN, identity = "item-1", occurrence = "a", audio = facts(96_000, 24, "audio/raw"))
        val flac = event(2, now - 80 * MIN, 3 * MIN, identity = "item-2", occurrence = "b", audio = facts(44_100, 16, "audio/flac"))

        val highest = audioOf(raw, flac).highest!!

        assertThat(highest.key.rateHz).isEqualTo(96_000)
        assertThat(highest.key.bits).isEqualTo(24)
        assertThat(highest.key.codecLabel).isNull()
    }

    @Test
    fun `a real wav file is still wav, and raw pcm is never labelled as one`() {
        assertThat(AudioFormatKey.codecLabelOf("audio/wav")).isEqualTo("WAV")
        assertThat(AudioFormatKey.codecLabelOf("audio/x-wav")).isEqualTo("WAV")
        assertThat(AudioFormatKey.codecLabelOf("audio/wave")).isEqualTo("WAV")
        assertThat(AudioFormatKey.codecLabelOf("audio/raw")).isNotEqualTo("WAV")

        val wav = event(1, now - 100 * MIN, 3 * MIN, identity = "item-1", occurrence = "a", audio = facts(48_000, 24, "audio/x-wav"))
        val formats = audioOf(wav).formats

        assertThat(formats.single().key.codecLabel).isEqualTo("WAV")
    }
}
