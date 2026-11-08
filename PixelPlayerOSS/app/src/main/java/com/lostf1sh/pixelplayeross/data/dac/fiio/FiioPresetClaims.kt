package com.lostf1sh.pixelplayeross.data.dac.fiio

/**
 * B60 (#53): **what `GET 16` means on the Melody.**
 *
 * The device frames of 11-06 (`B59-review/logcat-dac.txt`, `B58-review/logcat-peq.txt`) say that the Melody names its selected preset (`A2`) only until a band of a
 * user slot is touched: after the first `GET 15` / `SET 15` since the last select (`SET 16`) or save (`SET 19`), `GET 16` answers **`0x0A`**, with a fresh frame (its own
 * sequence number, a valid CRC), and goes on answering it until the next select or save. The selection did not move (a later `GET 15` still returns the slot's bands, and a
 * select of another preset is answered with that preset's id); "Close EQ" (`F0`) never does it. 0x0A is also the band count and the KA15's "Close EQ" id, so what it *means*
 * is not known; what is known is when it appears.
 *
 * Read as a pointer, `0x0A` made a read-back of an unchanged slot look like "the DAC is on another preset" (`MISMATCH … (selected 0x0A)`, the music left paused), and made
 * the restore of a half-written take-over "leave the slot alone". So the app keeps, **per attachment, the last preset the DAC really named** (or that the app selected and the
 * DAC then confirmed) **with the contents it has seen or written for it since**, and reads `0x0A` as that preset only when **all** of this holds:
 *
 * - the claim is one of the profile's **user slots** (`F0` does not produce the marker, and a new attachment has no claim);
 * - the bands and the pre-gain read together with it are **the ones this session has seen in that preset or sent to it** (the bands match: at most one of the ten bands, or the
 *   pre-gain, is not as recorded, which is what a SET the DAC stored differently leaves; the Q is not compared): a select made elsewhere that the marker hides shows as
 *   other bands, and then the marker stays `0x0A`, which no decision takes for the app's slot;
 * - the DAC has named no other preset since: a real id (anything but the marker) is always taken as it is and **replaces** the claim, and a select made elsewhere shows as
 *   its own id on the next read, because the preset is the first thing a read asks, before any band.
 *
 * The claim dies with the attachment (it is keyed by [AttachedFiio.attachKey], which changes at every re-enumeration). Everything that is *written* is still compared
 * with what it reads back, band by band; this class decides only what `GET 16` is taken to say.
 */
class FiioPresetClaims {
    private class Claim(val id: Int) {
        /** What the preset held when the DAC named it, and what each SET sent since would leave in it (the step is recorded before it goes out: it may have been applied). */
        val contents = ArrayDeque<DacEqLive>()

        fun add(content: DacEqLive) {
            contents.addLast(content)
            while (contents.size > MAX_CONTENTS) contents.removeFirst()
        }
    }

    private val claims = HashMap<String, Claim>()

    /** The app selected [id] and the DAC confirmed it (the write path calls this after its own select): the contents are seen at the next read. */
    @Synchronized
    fun noteSelected(att: AttachedFiio, id: Int) {
        claims[att.attachKey] = Claim(id)
    }

    /** A SET is about to go out that leaves [content] in the selected preset (pre-gain and every band as the plan says). */
    @Synchronized
    fun noteWritten(att: AttachedFiio, content: DacEqLive) {
        claims[att.attachKey]?.add(content)
    }

    /** The DAC went away or this attachment ended. */
    @Synchronized
    fun forget(att: AttachedFiio) {
        claims.remove(att.attachKey)
    }

    @Synchronized
    private fun named(att: AttachedFiio, id: Int, content: DacEqLive?) {
        val existing = claims[att.attachKey]
        val claim = if (existing != null && existing.id == id) existing else Claim(id).also { claims[att.attachKey] = it }
        if (content != null) claim.add(content)
    }

    @Synchronized
    private fun matches(att: AttachedFiio, content: DacEqLive?): Int? {
        val claim = claims[att.attachKey] ?: return null
        if (att.profile.writableSlots.none { it.id == claim.id }) return null
        if (content == null) return null
        return claim.id.takeIf { claim.contents.any { distance(it, content) <= 1 } }
    }

    /** How many things differ between two contents: the bands whose type, frequency or gain differ, plus the pre-gain. The Q is not looked at (the DAC rounds it, and a SET it stores differently is the case this tolerates). */
    private fun distance(a: DacEqLive, b: DacEqLive): Int {
        if (a.bands.size != b.bands.size) return Int.MAX_VALUE
        val bands = a.bands.indices.count { i ->
            val x = a.bands[i]
            val y = b.bands[i]
            if (x.isInert && y.isInert) false else x.typeCode != y.typeCode || x.frequencyHz != y.frequencyHz || x.gainTenthsDb != y.gainTenthsDb
        }
        return bands + if (a.preGainTenthsDb != b.preGainTenthsDb) 1 else 0
    }

    /**
     * [state] with its preset read as this session knows it: unchanged when the DAC names a real preset (which becomes the claim) or has no marker; the claimed slot,
     * with [DacEqState.presetReportedAs] set, when it answers the marker and the rule above holds. [log] gets one line when the marker is met.
     */
    fun resolve(att: AttachedFiio, state: DacEqState, log: (String) -> Unit): DacEqState {
        val marker = att.profile.editingPresetId ?: return state
        val reported = (state.activePreset as? EqRead.Read)?.value ?: return state
        val content = if (state.isComplete) DacEqLive((state.preGainTenthsDb as EqRead.Read).value, state.readBands, sense = PreGainSense.UNCONFIRMED) else null // the sense is not used: only the content is compared
        if (reported != marker) {
            named(att, reported, content)
            return state
        }
        val markerHex = "0x%02X".format(marker)
        val claimed = matches(att, content)
        if (claimed == null) {
            log(
                "DAC_HID|PRESET the DAC reports $markerHex (the marker it answers once a band was touched since the last select or save), and this session cannot say which preset " +
                    "it is on (none seen yet, not a user slot, or the bands are not ones it has seen in it): the selection is unknown, and no decision takes it for the app's slot"
            )
            return state
        }
        val claimedHex = "0x%02X".format(claimed)
        log(
            "DAC_HID|PRESET the DAC reports $markerHex (the marker it answers once a band was touched since the last select or save); this session last had $claimedHex " +
                "selected, the DAC has named no other preset since and the bands are ones it has seen there: read as $claimedHex"
        )
        return state.copy(activePreset = EqRead.Read(claimed), presetReportedAs = reported)
    }

    private companion object {
        const val MAX_CONTENTS = 24
    }
}
