package com.lostf1sh.pixelplayeross.data.dac.fiio

import kotlin.math.max

/**
 * B30-fix1 H1: one slot's working set as the DAC applies it **live**: its global gain and every band.
 *
 * Every SET to the DAC changes this at once (that is how WolfEQ and DevicePEQ drive it), so the level the listener hears between two
 * SETs is the level of the state after the first one. [after] is that state, [netPeakDb] its level by the model the gate uses.
 *
 * B31 P1.2: the level depends on what the register means ([sense], see [PreGainSense]); [netPeakDb] is the worse of the readings the
 * sense allows and [netPeaksDb] has one number per reading. Production code always passes the sense; the default is the signed
 * reading for the tests (`DacEqSupportTest` scans the sources for a construction that leaves it out).
 */
data class DacEqLive(val preGainTenthsDb: Int, val bands: List<DacEqBand>, val sense: PreGainSense = PreGainSense.BOOST) {
    /** The highest level change the state can produce, after the global gain (see [DacEqCurve]). At or below 0 dB is the rule. */
    val netPeakDb: Double get() = DacEqCurve.netPeakDb(bands, preGainTenthsDb, sense)

    /** The same, under each single reading the sense allows. */
    val netPeaksDb: List<Double> get() = DacEqCurve.netPeaksDb(bands, preGainTenthsDb, sense)

    /** The same sound (see [DacEqBand.sameEffectAs]): the plan ends here even when the DAC kept an unset band where the target has a flat one. */
    fun sameEffectAs(other: DacEqLive): Boolean =
        preGainTenthsDb == other.preGainTenthsDb && bands.size == other.bands.size &&
            bands.indices.all { bands[it].sameEffectAs(other.bands[it]) }

    /** The state after one SET frame: a gain or a band changes the working set; any other frame leaves it as it is. */
    fun after(command: Int, data: List<Byte>): DacEqLive = when (command) {
        FiioHidWrites.CMD_GAIN -> copy(preGainTenthsDb = ((data[0].toInt() shl 8) or (data[1].toInt() and 0xFF)).toShort().toInt())
        FiioHidWrites.CMD_BAND -> {
            val band = (FiioEqReader.decodeBand(data[0].toInt() and 0xFF, data.toByteArray()) as EqRead.Read).value
            copy(bands = bands.toMutableList().also { it[band.index] = band })
        }
        else -> this
    }

    companion object {
        fun of(snapshot: DacEqSlotSnapshot, sense: PreGainSense) = DacEqLive(snapshot.preGainTenthsDb, snapshot.bands, sense)

        /** The active preset's working set of a complete read; null when the read is not complete. */
        fun of(state: DacEqState, sense: PreGainSense): DacEqLive? = DacEqSlotSnapshot.of(state)?.let { of(it, sense) }
    }
}

/** One SET of a plan: the frame, and what the DAC holds (and how loud that is) right after it. */
data class DacEqPlanStep(
    val command: Int,
    val data: List<Byte>,
    val name: String,
    val after: DacEqLive,
    val netPeakDb: Double,
) {
    val frame: AllowedFrame get() = AllowedFrame(command, data)
}

/**
 * B30-fix1 H1: the whole sequence of gain and band SETs that takes a slot from [start] to [target], in the order they are sent.
 * Built by [DacEqWritePlanner]; re-checked, step by step, by the gate, which then issues a permit for exactly these frames in this order.
 */
class DacEqWritePlan(val start: DacEqLive, val target: DacEqLive, val steps: List<DacEqPlanStep>) {
    val frames: List<AllowedFrame> get() = steps.map { it.frame }

    /** The loudest state the DAC is in after any step, or null for a plan with no step. */
    val loudestNetPeakDb: Double? get() = steps.maxOfOrNull { it.netPeakDb }
}

/**
 * B31 P1.2: **the one question about levels that the planner and the gate both ask of a sequence of SETs**, so they cannot disagree.
 *
 * Every state the DAC is in after a SET is judged under each reading the sense allows ([PreGainSense.readings]); none may be louder
 * than that reading's ceiling:
 * - **sense known:** the ceiling is 0 dB. Every state after the first SET is at or below the limit, even when the slot began above it,
 *   because with the direction known a pre-gain can protect at once;
 * - **sense unconfirmed:** no register protects under both readings, so a plan that begins above the limit (the take-over of a slot that
 *   holds a boost) cannot reach 0 dB at once. Its ceiling is where that reading already was, or 0 dB if lower: **no reading ever gets
 *   louder than it was.** A slot that begins at or below 0 dB under both readings keeps the ceiling at 0 dB.
 *
 * [exemptFinal]: the last state is the backup's own (a restore, a roundtrip) and the rule does not judge it; every state on the way is.
 * While the sense is unconfirmed a plan to such a state (the way back to a factory curve that boosts) has no route that stays at or below
 * 0 dB under both readings, so there the ceiling of each reading also rises to **the level of the state being restored to**: no state on
 * the way is louder than the one the DAC is being given back. With the sense known a pre-gain protects, and the ceiling stays 0 dB.
 */
object DacEqLevelRule {
    private const val EPSILON = 1e-9

    fun ceilingsDb(start: DacEqLive, restoringTo: DacEqLive? = null): List<Double> {
        if (start.sense != PreGainSense.UNCONFIRMED) return listOf(0.0)
        val target = restoringTo?.netPeaksDb
        return start.netPeaksDb.mapIndexed { reading, level -> max(0.0, max(level, target?.get(reading) ?: level)) }
    }

    fun describe(frame: AllowedFrame): String =
        if (frame.command == FiioHidWrites.CMD_GAIN) "pre-gain" else "band ${(frame.data[0].toInt() and 0xFF) + 1}"

    /**
     * The first state of [frames], replayed from [start], that is louder than a ceiling, as a sentence; null when none is.
     * [restoringTo]: the state the frames end at, when [exemptFinal] says it is the backup's own (see above).
     */
    fun firstExcess(start: DacEqLive, frames: List<AllowedFrame>, exemptFinal: Boolean, restoringTo: DacEqLive? = null): String? {
        val ceilings = ceilingsDb(start, restoringTo.takeIf { exemptFinal })
        var live = start
        frames.forEachIndexed { i, frame ->
            live = live.after(frame.command, frame.data)
            if (i == frames.lastIndex && exemptFinal) return@forEachIndexed
            live.netPeaksDb.forEachIndexed { reading, level ->
                if (level > ceilings[reading] + EPSILON) {
                    val ceiling = ceilings[reading]
                    return "step ${i + 1} (${describe(frame)}) would leave the output ${"%.2f".format(level)} dB " +
                        (if (ceiling == 0.0) "above the limit" else "above where it was (${"%.2f".format(ceiling)} dB)") +
                        (if (start.sense == PreGainSense.UNCONFIRMED) ", reading the pre-gain as ${start.sense.readings[reading].name.lowercase()}" else "")
                }
            }
        }
        return null
    }
}

/**
 * B30-fix1 H1 (auditor R30-01): **no state between two SETs may be louder than the limit.**
 *
 * The first version wrote the final pre-gain before the bands. Moving between two curves that are each safe by themselves, a +6 dB
 * peak with −6.1 dB of pre-gain and then flat, wrote pre-gain 0 first: the +6 dB peak was live with nothing to hold it down.
 *
 * The plan is made from the **live** state (read in the session, after the slot is selected), never from a stored copy. The order the
 * first version found, and still tries first:
 * 1. the pre-gain is moved **first** to the most protective value any state on the way needs: of what it is now (never made less
 *    protective just to start), what the starting state needs if it was already above the limit, and what every band set the sequence
 *    passes through needs ([DacEqCurve.preGainTenthsFor] of each);
 * 2. the bands follow, those that do not raise their gain first, so the sequence passes through as little boost as it can;
 * 3. the pre-gain goes to its final value **last**, if the target needs another one.
 *
 * B31 P1.2: that order assumes the pre-gain protects, which only a known sense guarantees. So the planner builds up to three orders
 * (the one above; the bands and then the pre-gain; the pre-gain and then the bands), replays each with [DacEqLevelRule], and returns
 * the first that passes. When none does it returns the first, and the gate, which asks the same rule, refuses it.
 *
 * Nothing here reads or writes the DAC.
 */
object DacEqWritePlanner {
    private class Move(val command: Int, val data: ByteArray, val name: String)

    /**
     * @param forceAll send every band even when the live one already equals the target's (the reviewer's roundtrip: the point is that
     *   the whole machinery runs), and the leading pre-gain frame even when it changes nothing.
     * @param exemptFinal the last state is the backup's own and the level rule does not judge it (see [DacEqLevelRule]).
     */
    fun plan(start: DacEqLive, target: DacEqLive, forceAll: Boolean = false, exemptFinal: Boolean = false): DacEqWritePlan {
        require(start.bands.size == target.bands.size) { "a plan keeps the band count" }
        require(start.sense == target.sense) { "a plan is judged under one sense" }
        val count = target.bands.size
        val changed = (0 until count).filter { !start.bands[it].sameEffectAs(target.bands[it]) }
        // Bands whose gain does not go up first: they leave less boost behind than they found.
        val ordered = changed.sortedWith(compareBy({ target.bands[it].gainTenthsDb > start.bands[it].gainTenthsDb }, { it }))
        val sequence = ordered + if (forceAll) (0 until count).filter { it !in changed } else emptyList()

        val candidates = listOf(
            holdFirst(start, target, sequence, forceAll),
            bandsThenGain(start, target, sequence, forceAll),
            gainThenBands(start, target, sequence, forceAll),
        ).distinctBy { moves -> moves.map { it.command to it.data.toList() } }
        val chosen = candidates.firstOrNull { moves ->
            DacEqLevelRule.firstExcess(start, moves.map { AllowedFrame(it.command, it.data.toList()) }, exemptFinal, target) == null
        } ?: candidates.first()
        return build(start, target, chosen)
    }

    private fun gain(tenths: Int) = Move(FiioHidWrites.CMD_GAIN, FiioHidWrites.encodeGain(tenths), "pre-gain")
    private fun band(target: DacEqLive, index: Int) =
        Move(FiioHidWrites.CMD_BAND, FiioHidWrites.encodeBand(target.bands[index]), "band ${index + 1}")

    /** The first version's order (B30-fix1 H1), generalised to a sense. */
    private fun holdFirst(start: DacEqLive, target: DacEqLive, sequence: List<Int>, forceAll: Boolean): List<Move> {
        val sense = start.sense
        val protective = HashMap<List<DacEqBand>, Int?>()
        fun protectiveFor(bands: List<DacEqBand>) = protective.getOrPut(bands) { DacEqCurve.preGainTenthsFor(bands, sense) }

        var hold = start.preGainTenthsDb
        if (start.netPeakDb > 0.0) protectiveFor(start.bands)?.let { hold = sense.moreProtective(hold, it) }
        var bands = start.bands
        for (index in sequence) {
            bands = bands.toMutableList().also { it[index] = target.bands[index] }
            protectiveFor(bands)?.let { hold = sense.moreProtective(hold, it) }
        }
        val moves = ArrayList<Move>()
        var gainNow = start.preGainTenthsDb
        if (hold != start.preGainTenthsDb || forceAll) {
            moves += gain(hold)
            gainNow = hold
        }
        for (index in sequence) moves += band(target, index)
        if (target.preGainTenthsDb != gainNow) moves += gain(target.preGainTenthsDb)
        return moves
    }

    private fun bandsThenGain(start: DacEqLive, target: DacEqLive, sequence: List<Int>, forceAll: Boolean): List<Move> {
        val moves = ArrayList<Move>()
        for (index in sequence) moves += band(target, index)
        if (target.preGainTenthsDb != start.preGainTenthsDb || forceAll) moves += gain(target.preGainTenthsDb)
        return moves
    }

    private fun gainThenBands(start: DacEqLive, target: DacEqLive, sequence: List<Int>, forceAll: Boolean): List<Move> {
        val moves = ArrayList<Move>()
        if (target.preGainTenthsDb != start.preGainTenthsDb || forceAll) moves += gain(target.preGainTenthsDb)
        for (index in sequence) moves += band(target, index)
        return moves
    }

    private fun build(start: DacEqLive, target: DacEqLive, moves: List<Move>): DacEqWritePlan {
        val steps = ArrayList<DacEqPlanStep>()
        var live = start
        for (move in moves) {
            val bytes = move.data.toList()
            live = live.after(move.command, bytes)
            steps += DacEqPlanStep(move.command, bytes, move.name, live, live.netPeakDb)
        }
        return DacEqWritePlan(start, target, steps)
    }
}
