package com.lostf1sh.pixelplayeross.data.stats

import com.decent.usbaudio.media3.BitPerfectReason
import com.decent.usbaudio.media3.BitPerfectTruth
import com.decent.usbaudio.media3.BitPerfectVerdict
import com.decent.usbaudio.media3.DirectProcessing
import com.decent.usbaudio.media3.DirectSignalMode
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

/**
 * B28a: what a listening event carries from now on, driven through the real tracker with the call shapes `MusicService` uses
 * and read back from the real history file.
 */
class ListenEventRecordingTest {

    private val minute = TimeUnit.MINUTES.toMillis(1)

    // --- Identity -------------------------------------------------------------------------------------------------------------

    @Test
    fun `an event opened with the services call shape carries the library identity`() {
        ListenHarness(identityOfLocator = { locator -> if (locator == "1412") "item-1412" else null }).use { h ->
            // MusicService.syncLocalListeningStatsFromPlayer passes the locator, never the identity.
            h.transition(songId = "1412")
            h.advance(2 * minute)
            h.finish(TrackEndReason.USER_NEXT)

            val event = h.events().single()
            assertThat(event.libraryItemId).isEqualTo("item-1412")
            assertThat(event.songId).isEqualTo("1412")
        }
    }

    @Test
    fun `the identity is the one the locator had when the session opened, not the one it has when it ends`() {
        var owner = "item-A"
        ListenHarness(identityOfLocator = { owner }).use { h ->
            h.transition(songId = "77")
            // The lookup runs within moments of the session opening, off the main thread.
            h.awaitLookups(1)
            h.advance(minute)
            // A sync renumbers the library mid-listen: the locator now belongs to another track.
            owner = "item-B"
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            assertThat(h.events().single().libraryItemId).isEqualTo("item-A")
        }
    }

    @Test
    fun `an external item records no identity and is never looked up as a library row`() {
        ListenHarness(identityOfLocator = { "should-not-be-asked" }).use { h ->
            h.transition(songId = "external:content://other/app/1")
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            assertThat(h.events().single().libraryItemId).isNull()
            assertThat(h.lookedUp).isEmpty()
        }
    }

    @Test
    fun `a locator that is not in the library records no identity`() {
        ListenHarness(identityOfLocator = { null }).use { h ->
            h.transition(songId = "99")
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            assertThat(h.events().single().libraryItemId).isNull()
        }
    }

    @Test
    fun `an identity the caller already knows is used as given and not looked up`() {
        ListenHarness(identityOfLocator = { "from-the-lookup" }).use { h ->
            h.tracker.onTrackChanged(
                songId = "5",
                positionMs = 0L,
                durationMs = 4 * minute,
                fallbackDurationMs = 0L,
                isPlaying = true,
                libraryItemId = "known-item",
            )
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            assertThat(h.events().single().libraryItemId).isEqualTo("known-item")
            assertThat(h.lookedUp).isEmpty()
        }
    }

    @Test
    fun `a lookup that fails still records the listen, without an identity`() {
        ListenHarness(identityOfLocator = { error("database closed") }).use { h ->
            h.transition(songId = "12")
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            val event = h.events().single()
            assertThat(event.libraryItemId).isNull()
            assertThat(event.durationMs).isEqualTo(minute)
        }
    }

    // --- Outcome, reason, length ------------------------------------------------------------------------------------------------

    @Test
    fun `an event carries the outcome and the reason the classifier was given and the length of the item`() {
        ListenHarness().use { h ->
            h.transition(songId = "1", durationMs = 4 * minute)
            h.advance(20_000L)
            h.finish(TrackEndReason.USER_NEXT)
            h.transition(songId = "2", durationMs = 3 * minute)
            h.advance(3 * minute)
            h.finish(TrackEndReason.NATURAL_END)

            val (skipped, completed) = h.events()
            assertThat(skipped.outcome).isEqualTo(PlaybackOutcome.SKIPPED)
            assertThat(skipped.endReason).isEqualTo(TrackEndReason.USER_NEXT)
            assertThat(skipped.itemDurationMs).isEqualTo(4 * minute)
            assertThat(completed.outcome).isEqualTo(PlaybackOutcome.COMPLETED)
            assertThat(completed.endReason).isEqualTo(TrackEndReason.NATURAL_END)
            assertThat(completed.itemDurationMs).isEqualTo(3 * minute)
        }
    }

    @Test
    fun `an item whose length is not known records no length`() {
        ListenHarness().use { h ->
            h.transition(songId = "1", durationMs = 0L)
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            assertThat(h.events().single().itemDurationMs).isNull()
        }
    }

    @Test
    fun `a listen under the minimum still writes nothing`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(4_000L)
            h.tracker.finalizeCurrentSession(endReason = TrackEndReason.USER_NEXT)

            Thread.sleep(150)
            assertThat(h.events()).isEmpty()
        }
    }

    // --- Context ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `the source context of the session is written as it was when the session opened`() {
        ListenHarness().use { h ->
            h.transition(songId = "external:uri", context = ListenContext(kind = ListenSourceKind.EXTERNAL_VIEW, shuffle = false))
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)
            h.transition(songId = "3", context = ListenContext(shuffle = true))
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            val (external, library) = h.events()
            assertThat(external.context).isEqualTo(ListenContext(ListenSourceKind.EXTERNAL_VIEW, false))
            assertThat(library.context).isEqualTo(ListenContext(null, true))
        }
    }

    // --- Audio facts -----------------------------------------------------------------------------------------------------------

    private fun facts(
        route: com.decent.usbaudio.media3.DirectSignalMode? = null,
        bitPerfect: BitPerfectTruth = BitPerfectTruth.PRISTINE_UNVERIFIED,
    ): ListenAudioFacts = if (route != null) {
        ListenAudioFactsAssembler.assemble(directRoute(route, bitPerfect), directDiagnostics(route, bitPerfect), null, null)
    } else {
        ListenAudioFactsAssembler.assemble(
            androidRoute(),
            com.lostf1sh.pixelplayeross.data.service.player.UsbDirectAudioDiagnostics(),
            AndroidOutput(ListenRoute.BLUETOOTH, null),
            com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine.AudioFormatSnapshot("audio/mpeg", 44_100, 2, 0, 320_000),
        )
    }

    @Test
    fun `a Direct Purity session records the route the signal the source and the transport`() {
        ListenHarness().use { h ->
            h.tracker.onAudioFacts(facts(DirectSignalMode.PRISTINE))
            h.transition(songId = "1")
            h.advance(2 * minute)
            h.finish(TrackEndReason.NATURAL_END)

            assertThat(h.events().single().audio).isEqualTo(
                ListenAudioFacts(
                    route = ListenRoute.USB_DIRECT,
                    signal = ListenSignal.PURITY,
                    // The truth model has not measured this stream: unknown stays unknown.
                    bitPerfect = ListenBitPerfect.UNKNOWN,
                    sourceRateHz = 96_000,
                    sourceBits = 24,
                    sourceCodec = "audio/flac",
                    transportRateHz = 96_000,
                    transportBits = 24,
                    deviceName = "SNOWSKY Melody",
                ),
            )
        }
    }

    @Test
    fun `a Direct Resonance session says the signal was processed and not bit-perfect`() {
        ListenHarness().use { h ->
            val processed = BitPerfectTruth.processed(setOf(DirectProcessing.EQUALIZER))
            h.tracker.onAudioFacts(facts(DirectSignalMode.PROCESSED, processed))
            h.transition(songId = "1")
            h.advance(2 * minute)
            h.finish(TrackEndReason.NATURAL_END)

            val audio = h.events().single().audio!!
            assertThat(audio.route).isEqualTo(ListenRoute.USB_DIRECT)
            assertThat(audio.signal).isEqualTo(ListenSignal.RESONANCE)
            assertThat(audio.bitPerfect).isEqualTo(ListenBitPerfect.NO)
        }
    }

    @Test
    fun `an Android route session has a route and a source but no signal mode and no transport`() {
        ListenHarness().use { h ->
            h.tracker.onAudioFacts(facts(route = null))
            h.transition(songId = "1")
            h.advance(2 * minute)
            h.finish(TrackEndReason.NATURAL_END)

            assertThat(h.events().single().audio).isEqualTo(
                ListenAudioFacts(
                    route = ListenRoute.BLUETOOTH,
                    signal = null,
                    bitPerfect = ListenBitPerfect.NO,
                    sourceRateHz = 44_100,
                    sourceCodec = "audio/mpeg",
                ),
            )
        }
    }

    @Test
    fun `a bit-perfect verdict of unknown is never upgraded and only the model can say yes`() {
        val unknown = ListenAudioFactsAssembler.assemble(
            directRoute(bitPerfect = BitPerfectTruth(BitPerfectVerdict.UNKNOWN, BitPerfectReason.NOT_MEASURED, verified = false)),
            directDiagnostics(),
            null,
            null,
        )
        val verified = ListenAudioFactsAssembler.assemble(
            directRoute(bitPerfect = BitPerfectTruth(BitPerfectVerdict.YES, verified = true)),
            directDiagnostics(),
            null,
            null,
        )
        assertThat(unknown.bitPerfect).isEqualTo(ListenBitPerfect.UNKNOWN)
        assertThat(verified.bitPerfect).isEqualTo(ListenBitPerfect.YES)
    }

    @Test
    fun `a listen with no audio facts known writes none rather than guessing`() {
        ListenHarness().use { h ->
            h.transition(songId = "1")
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            assertThat(h.events().single().audio).isNull()
        }
    }

    @Test
    fun `a route change in the middle of an occurrence records the facts that carried most of the listening and how much`() {
        ListenHarness().use { h ->
            val direct = facts(DirectSignalMode.PRISTINE)
            val bluetooth = facts(route = null)
            h.tracker.onAudioFacts(direct)
            h.transition(songId = "1", durationMs = 10 * minute)
            h.advance(3 * minute)
            h.tracker.onAudioFacts(bluetooth)
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            val audio = h.events().single().audio!!
            assertThat(audio.route).isEqualTo(ListenRoute.USB_DIRECT)
            assertThat(audio.share).isEqualTo(75)
        }
    }

    @Test
    fun `when the other facts carried more of the listening they are the ones recorded`() {
        ListenHarness().use { h ->
            h.tracker.onAudioFacts(facts(DirectSignalMode.PRISTINE))
            h.transition(songId = "1", durationMs = 10 * minute)
            h.advance(minute)
            h.tracker.onAudioFacts(facts(route = null))
            h.advance(3 * minute)
            h.finish(TrackEndReason.USER_NEXT)

            val audio = h.events().single().audio!!
            assertThat(audio.route).isEqualTo(ListenRoute.BLUETOOTH)
            assertThat(audio.share).isEqualTo(75)
        }
    }

    @Test
    fun `time spent paused is not booked against any facts`() {
        ListenHarness().use { h ->
            h.tracker.onAudioFacts(facts(DirectSignalMode.PRISTINE))
            h.transition(songId = "1", durationMs = 10 * minute)
            h.advance(minute)
            h.sync("1", playing = false, positionMs = minute)
            // An hour paused on the speaker must not outweigh the minute heard on the DAC.
            h.tracker.onAudioFacts(facts(route = null))
            h.advance(60 * minute)
            h.sync("1", playing = true, positionMs = minute)
            h.advance(10_000L)
            h.finish(TrackEndReason.USER_NEXT)

            val audio = h.events().single().audio!!
            assertThat(audio.route).isEqualTo(ListenRoute.USB_DIRECT)
            assertThat(audio.share).isEqualTo(86)
        }
    }

    @Test
    fun `a stale reading at the very start of a track is absorbed by the facts that follow it`() {
        ListenHarness().use { h ->
            val previousTrack = facts(DirectSignalMode.PRISTINE).copy(sourceRateHz = 44_100, sourceBits = 16)
            val thisTrack = facts(DirectSignalMode.PRISTINE)
            h.tracker.onAudioFacts(previousTrack)
            h.transition(songId = "2", durationMs = 4 * minute)
            // The sink publishes the new format a moment after the transition.
            h.advance(40L)
            h.tracker.onAudioFacts(thisTrack)
            h.advance(4 * minute)
            h.finish(TrackEndReason.NATURAL_END)

            val audio = h.events().single().audio!!
            assertThat(audio.sourceRateHz).isEqualTo(96_000)
            assertThat(audio.sourceBits).isEqualTo(24)
            // 40 ms of 4 minutes rounds to nothing: the event does not call itself mixed.
            assertThat(audio.share).isNull()
        }
    }

    @Test
    fun `facts that do not change cost the tracker nothing`() {
        ListenHarness().use { h ->
            val direct = facts(DirectSignalMode.PRISTINE)
            h.tracker.onAudioFacts(direct)
            h.transition(songId = "1")
            h.advance(minute)
            repeat(50) { h.tracker.onAudioFacts(direct.copy()) }
            h.advance(minute)
            h.finish(TrackEndReason.USER_NEXT)

            val audio = h.events().single().audio!!
            assertThat(audio.share).isNull()
            assertThat(audio.route).isEqualTo(ListenRoute.USB_DIRECT)
        }
    }

    @Test
    fun `facts that flap are bounded and the longest still wins`() {
        ListenHarness().use { h ->
            h.tracker.onAudioFacts(facts(DirectSignalMode.PRISTINE))
            h.transition(songId = "1", durationMs = 60 * minute)
            h.advance(10 * minute)
            // Thirty readings that each differ, one second apart.
            repeat(30) { i ->
                h.tracker.onAudioFacts(facts(route = null).copy(sourceRateHz = 1_000 + i))
                h.advance(1_000L)
            }
            h.finish(TrackEndReason.USER_NEXT)

            val audio = h.events().single().audio!!
            assertThat(audio.route).isEqualTo(ListenRoute.USB_DIRECT)
            assertThat(audio.share).isEqualTo(95)
        }
    }
}
