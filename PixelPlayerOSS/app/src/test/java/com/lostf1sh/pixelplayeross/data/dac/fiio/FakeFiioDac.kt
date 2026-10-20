package com.lostf1sh.pixelplayeross.data.dac.fiio

/**
 * A DAC for the JVM: speaks FiiO's HID protocol the way the Melody was seen to (and the way WolfEQ's and DevicePEQ's sources
 * say it does), so the reader, the gate and the writer run end to end without hardware.
 *
 * What it models, and where each choice comes from:
 * - replies are `[reportId] BB 0B 00 SEQ CMD LEN DATA CRC EE` padded to 64 bytes, SEQ counting up by itself, CRC-8/MAXIM
 *   (`dac-hid.txt` and DevicePEQ's Melody capture);
 * - **`GET 1A` gets no well-formed reply** unless [answersSwitch]: it pushes the stale-bytes report the Melody sent
 *   (`07 00 EE 00 SEQ 16 01 F0 CRC EE`);
 * - **every SET is answered by one report** ([AckMode]; B31 P1.1). The Melody's sequence counter skips a number around each SET, which
 *   is that report, and the KA15 capture in DevicePEQ (`fiio_fiio_ka15.json`) shows its shape: the SET head (`AA 0A`), the counter, the
 *   command and the data echoed, the CRC; the save's is a bare `00 EE 00 SEQ`. The Melody's own has never been recorded. A DAC that
 *   ignores a SET sends no acknowledgement of it unless [ackIgnored] (a liar); [contraryAcks] names the commands whose acknowledgement
 *   carries other data than was sent;
 * - `GET 15`/`GET 17` answer for the **active** preset's working set; `SET 15`/`SET 17` change it; **`SET 19` persists** it
 *   into a slot ([persistsOnlyOnSave]: what was not saved is lost at [replug]); `SET 16` selects.
 *
 * What is an assumption of this fake and not a fact about the Melody: that unsaved edits are lost at a replug, and that the
 * save names its slot in its data. The runbook's unplug step is what finds out which is true.
 */
class FakeFiioDac(
    val profile: FiioHidProfile = FiioHidProfiles.all.single { it.id == "snowsky-melody" },
    activePreset: Int = 0xF0,
    var answersSwitch: Boolean = false,
    var persistsOnlyOnSave: Boolean = true,
    var ackMode: AckMode = AckMode.ECHO,
) : FiioHidWire {
    /** What the DAC sends after a SET. */
    enum class AckMode {
        /** KA15-shaped: `AA 0A 00 SEQ CMD LEN DATA CRC EE` (the save's is the bare `00 EE 00 SEQ`). */
        ECHO,

        /** The same layout under the reply head (`BB 0B …`). */
        REPLY_HEAD,

        /** Only the bare `00 EE 00 SEQ` for every SET: a counter and nothing to read. */
        BARE,

        /** Nothing at all (the older fake). */
        NONE,
    }

    class Slot(var preGainTenths: Int, var bands: MutableList<DacEqBand>) {
        fun copy() = Slot(preGainTenths, bands.toMutableList())
    }

    override val reportLength = 64

    /** Everything written to the DAC, in order (a copy of each report). */
    val written = mutableListOf<ByteArray>()

    /** Every SET frame (head AA) decoded as `command to data`, in order. */
    val sets: List<Pair<Int, ByteArray>>
        get() = written.filter { it.size > 2 && (it[1].toInt() and 0xFF) == 0xAA }
            .map { (it[5].toInt() and 0xFF) to it.copyOfRange(7, 7 + (it[6].toInt() and 0xFF)) }

    var activePreset: Int = activePreset
        private set

    /** The working set of each slot (what GET/SET touch while that slot is active). */
    val live = mutableMapOf<Int, Slot>()

    /** What survives a replug. */
    val saved = mutableMapOf<Int, Slot>()
    private var savedActive = activePreset

    var seq = 0
    private val inbox = ArrayDeque<ByteArray>()

    // Faults for the tests.
    var ignoreSets = false

    /** The 1-based number, among the SETs received, of the one SET the DAC ignores (the report arrives whole, the later ones are applied). */
    var ignoreNthSet: Int? = null

    /** The 1-based number of the one SET whose transfer comes back short (fewer bytes than the report): the DAC never gets it. */
    var shortWriteNthSet: Int? = null

    /** Also acknowledge a SET it ignored, as if it had been applied (a DAC that lies). */
    var ackIgnored = false

    /** Commands whose acknowledgement carries other data than was sent (a clamped, rounded or old value). */
    val contraryAcks = mutableSetOf<Int>()

    /** Called with each acknowledgement report the DAC queues (the recorded frames the decoder tests read). */
    val acks = mutableListOf<ByteArray>()
    private var setsReceived = 0
    var failWrites = false

    /** After this many writes every write fails (a DAC pulled out mid-operation); null: never. */
    var failWritesAfter: Int? = null

    /** Called with the number of reports written so far, before each report is handled (lets a test look at the disk at the first SET). */
    var onWrite: ((report: ByteArray) -> Unit)? = null

    /**
     * Called **after** each SET frame has been applied, with the DAC itself: what the DAC holds, and so what is audible, between one
     * transfer and the next (B30-fix1 H1: the level of every state, not only the last).
     */
    var afterSet: ((dac: FakeFiioDac, report: ByteArray) -> Unit)? = null
    var dropReplies = false
    var corruptBandOnWrite: Int? = null
    var requireCrc: FiioCrcMode? = null
    var rejectedSetCrcs = 0
        private set
    var persistedSlots = mutableListOf<Int>()

    init {
        // Factory presets 0..6: a curve each; user slots and "Close EQ": flat. The default user slots are flat zeros.
        profile.slots.forEach { slot ->
            val bands = (0 until profile.bandCount).map { i ->
                if (!slot.writable && slot.id != profile.disabledPresetId) {
                    DacEqBand.of(i, FiioFilterType.PEAK, FREQUENCIES[i], gainDb = if (i % 2 == 0) 2.0 else -1.5, q = 0.71)
                } else {
                    DacEqBand.of(i, FiioFilterType.PEAK, FREQUENCIES[i], gainDb = 0.0, q = 0.71)
                }
            }.toMutableList()
            live[slot.id] = Slot(if (slot.id == 0xF0) 20 else 0, bands)
            saved[slot.id] = live.getValue(slot.id).copy()
        }
    }

    fun slot(id: Int): Slot = live.getValue(id)

    fun setSlot(id: Int, preGainTenths: Int, bands: List<DacEqBand>) {
        live[id] = Slot(preGainTenths, bands.toMutableList())
        saved[id] = live.getValue(id).copy()
    }

    /** Someone presses the DAC's own button: another preset is selected, with no command from the app. */
    fun selectOnDevice(id: Int) {
        activePreset = id
        savedActive = id
    }

    /** Unplug and plug back: the DAC comes back with what it had saved. */
    fun replug() {
        inbox.clear()
        if (persistsOnlyOnSave) {
            live.clear()
            saved.forEach { (id, slot) -> live[id] = slot.copy() }
        }
        activePreset = savedActive
    }

    override fun write(report: ByteArray): Int {
        if (failWrites) return -1
        failWritesAfter?.let { if (written.size >= it) return -1 }
        onWrite?.invoke(report)
        written += report.copyOf()
        val id = report[0].toInt() and 0xFF
        if (id != profile.reportId) return report.size
        val head = report[1].toInt() and 0xFF
        val command = report[5].toInt() and 0xFF
        val length = report[6].toInt() and 0xFF
        val data = report.copyOfRange(7, 7 + length)
        when (head) {
            0xBB -> get(command, data)
            0xAA -> {
                if (shortWriteNthSet == setsReceived + 1) {
                    setsReceived++
                    written.removeAt(written.lastIndex)
                    return report.size - 1
                }
                setsReceived++
                val ignored = ignoreSets || ignoreNthSet == setsReceived
                val applied = !ignored && set(report, command, data, length)
                afterSet?.invoke(this, report)
                // A report whose check byte the DAC rejects is not acknowledged either.
                if (applied || (ignored && ackIgnored)) ack(command, data)
            }
        }
        return report.size
    }

    override fun read(timeoutMs: Int): ByteArray? = if (dropReplies) null else inbox.removeFirstOrNull()

    private fun get(command: Int, data: ByteArray) {
        val active = live.getValue(activePreset)
        when (command) {
            0x16 -> reply(command, byteArrayOf(activePreset.toByte()))
            0x18 -> reply(command, byteArrayOf(active.bands.size.toByte()))
            0x17 -> reply(command, s16(active.preGainTenths))
            0x15 -> {
                val index = data.firstOrNull()?.toInt() ?: return
                val band = active.bands.getOrNull(index) ?: return
                reply(command, byteArrayOf(index.toByte()) + s16(band.gainTenthsDb) + u16(band.frequencyHz) + u16(band.qHundredths) +
                    byteArrayOf(band.typeCode.toByte()))
            }
            0x1A -> if (answersSwitch) reply(command, byteArrayOf(if (activePreset == profile.disabledPresetId) 0 else 1)) else stale()
            else -> Unit
        }
    }

    /** Applies one SET; false when the DAC rejected it (a wrong check byte). */
    private fun set(report: ByteArray, command: Int, data: ByteArray, length: Int): Boolean {
        requireCrc?.let { mode ->
            val expected = if (mode == FiioCrcMode.MAXIM) FiioCrc8.maxim(report, 1, 7 + length) else 0
            if ((report[7 + length].toInt() and 0xFF) != expected) {
                rejectedSetCrcs++
                return false
            }
        }
        val active = live.getValue(activePreset)
        when (command) {
            0x16 -> {
                val target = data[0].toInt() and 0xFF
                if (live.containsKey(target)) {
                    activePreset = target
                    // Assumption of the fake: the selection itself is remembered at once (it is a pointer, not content).
                    savedActive = target
                }
            }
            0x17 -> active.preGainTenths = ((data[0].toInt() shl 8) or (data[1].toInt() and 0xFF)).toShort().toInt()
            0x15 -> {
                val index = data[0].toInt() and 0xFF
                if (index == corruptBandOnWrite) return true // applied wrongly: the DAC still answers
                val band = DacEqBand(
                    index = index,
                    typeCode = data[7].toInt() and 0xFF,
                    frequencyHz = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF),
                    gainTenthsDb = ((data[1].toInt() shl 8) or (data[2].toInt() and 0xFF)).toShort().toInt(),
                    qHundredths = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF),
                )
                if (index in active.bands.indices) active.bands[index] = band
            }
            profile.saveCommand -> {
                val target = if (data.isNotEmpty()) data[0].toInt() and 0xFF else activePreset
                persistedSlots += target
                saved[target] = live.getValue(target).copy()
            }
        }
        return true
    }

    private fun reply(command: Int, data: ByteArray) {
        val frame = ByteArray(8 + data.size)
        frame[0] = 0xBB.toByte(); frame[1] = 0x0B; frame[2] = 0; frame[3] = (seq++ and 0xFF).toByte()
        frame[4] = command.toByte(); frame[5] = data.size.toByte()
        data.copyInto(frame, 6)
        frame[6 + data.size] = FiioCrc8.maxim(frame, 0, 6 + data.size).toByte()
        frame[7 + data.size] = 0xEE.toByte()
        inbox += padded(frame)
    }

    /** B31 P1.1: the one report that follows a SET, in the [ackMode] shape. It takes a number of the counter, as the Melody's does. */
    private fun ack(command: Int, data: ByteArray) {
        val number = (seq++ and 0xFF).toByte()
        val shown = if (command in contraryAcks) {
            // The KA15 answered a write of +0.2 dB with 00 00: a value the DAC did not take. Here: the last byte is one more than was sent.
            data.copyOf().also { if (it.isNotEmpty()) it[it.lastIndex] = (it.last() + 1).toByte() }
        } else {
            data
        }
        val frame: ByteArray = when {
            ackMode == AckMode.NONE -> { seq--; return }
            ackMode == AckMode.BARE || command == profile.saveCommand -> byteArrayOf(0x00, 0xEE.toByte(), 0x00, number)
            else -> {
                val f = ByteArray(8 + shown.size)
                val head = if (ackMode == AckMode.ECHO) 0xAA else 0xBB
                f[0] = head.toByte(); f[1] = if (ackMode == AckMode.ECHO) 0x0A else 0x0B; f[2] = 0; f[3] = number
                f[4] = command.toByte(); f[5] = shown.size.toByte()
                shown.copyInto(f, 6)
                f[6 + shown.size] = FiioCrc8.maxim(f, 0, 6 + shown.size).toByte()
                f[7 + shown.size] = 0xEE.toByte()
                f
            }
        }
        val padded = padded(frame)
        acks += padded.copyOf()
        inbox += padded
    }

    /** What the Melody sent for `1A`: the start of a short frame over the previous reply's leftovers. */
    private fun stale() {
        val bytes = byteArrayOf(0x00, 0xEE.toByte(), 0x00, (seq++ and 0xFF).toByte(), 0x16, 0x01, 0xF0.toByte(), 0xE1.toByte(), 0xEE.toByte())
        inbox += padded(bytes)
    }

    private fun padded(frame: ByteArray): ByteArray {
        val report = ByteArray(reportLength)
        report[0] = profile.reportId.toByte()
        frame.copyInto(report, 1)
        return report
    }

    private fun s16(value: Int) = byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
    private fun u16(value: Int) = byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    companion object {
        val FREQUENCIES = intArrayOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    }
}

/**
 * The Melody's row with the pre-gain read as a signed gain (positive = louder). B30's tests were written under that reading, and so use
 * this row: the real row's sense is [PreGainSense.UNCONFIRMED] until the reviewer's listening check (B31 P1.2).
 */
internal val signedMelody: FiioHidProfile = FiioHidProfiles.all.single { it.id == "snowsky-melody" }.copy(preGainSense = PreGainSense.BOOST)

/**
 * A wire that replays recorded reports: [perWrite] has one group of reports for each report written, in order, delivered after
 * that write (an empty group is a DAC that says nothing). Once the script runs out the DAC is silent.
 */
class ReplayWire(perWrite: List<List<ByteArray>>, override val reportLength: Int = 64) : FiioHidWire {
    private val script = ArrayDeque(perWrite)
    private val inbox = ArrayDeque<ByteArray>()
    val written = mutableListOf<ByteArray>()
    override fun write(report: ByteArray): Int {
        written += report.copyOf()
        script.removeFirstOrNull()?.let { inbox.addAll(it) }
        return report.size
    }

    override fun read(timeoutMs: Int): ByteArray? = inbox.removeFirstOrNull()
}

/** `07 BB 0B …` as hex text to a 64-byte report. */
internal fun report(hex: String, length: Int = 64): ByteArray {
    val bytes = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
    return bytes.copyOf(maxOf(length, bytes.size))
}
