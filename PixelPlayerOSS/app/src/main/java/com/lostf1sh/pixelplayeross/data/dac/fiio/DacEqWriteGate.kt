package com.lostf1sh.pixelplayeross.data.dac.fiio

import kotlin.math.roundToInt

/** Who is asking for a write. Never the attach: a connect reads, it does not write. */
enum class WriteInitiator {
    /** The listener, in the UI. */
    USER_ACTION,

    /** The reviewer's debug command. */
    REVIEWER_COMMAND,

    /** Anything that happens by itself: a connect, a route change, a profile switch. The gate refuses it. */
    AUTOMATIC,
}

enum class WriteKind {
    /**
     * Select the app's slot to read what it holds, with the selection put back after: a pointer move, no content written. **The
     * reviewer's explicit step only** (B30-fix1 H1): the slot is audible at whatever level it holds while it is selected, and nothing
     * can silence a preset before it is selected, so this is never a side effect of a write. B31: only while playback is paused.
     */
    PEEK_SLOT,

    /**
     * Write a curve into the app's slot and select it. Asked in two stages (B30-fix1 H1): with another preset selected the answer
     * covers the select of a slot known to be safe; with the slot selected it is the plan, the gain and band SETs in the order that
     * keeps every state at or below the limit, then the save.
     */
    APPLY_CURVE,

    /** Close the EQ: select the model's "Close EQ" preset. */
    CLOSE_EQ,

    /**
     * Write the backup back and select what was selected. One slot's content at a time (`slotId` set; the same two stages as
     * [APPLY_CURVE], planned so that no state on the way is above the limit), and then the pointer (`slotId` null: select what the
     * backup recorded, or, for a failure that only moved the pointer, what the operation found selected). A slot the app has taken
     * over is not rewritten from the backup (that holds the factory curve): a recovery there carries its own [WriteRequest.content].
     */
    RESTORE,

    /** Write the app slot's stored content back, unchanged, and compare: the gentlest write there is. Two stages, like [APPLY_CURVE]. */
    ROUNDTRIP,

    /**
     * B31 P1.3: **take over** the app's slot: select it, put the pre-gain at the value that is neutral whatever the sense, the bands flat,
     * save, read back, select the original preset back. The factory curve it held is in the backup first, and stays restorable. Only
     * while playback is paused: the slot is audible at whatever it holds from the moment it is selected. Two stages, like [APPLY_CURVE].
     */
    TAKE_OVER,

    /**
     * B31 P1.4: read every preset the profile lists: select, read, and the original selection back. Pointer moves only, never a gain,
     * a band or a save; only while playback is paused.
     */
    INVENTORY,

    /** B31 P1.4: select one of the DAC's presets and nothing else. While playing it needs the listener's confirmation. */
    SELECT_PRESET,

    /**
     * B31 P1.2: the reviewer's listening check. On the slot the app has taken over (flat, pre-gain 0) it sets the pre-gain live,
     * unsaved, to −6 dB, +6 dB and back to 0 so the owner can say which is quieter. **The one write that is allowed to be louder than
     * the limit**, on purpose and by the reviewer only, with the DAC at a low level (the extra `--ez low_level true`).
     */
    LISTENING_CHECK,

    /** B31 P1.3: give the app's slot back its factory curve (what the backup holds) and let go of it. Only while playback is paused. */
    RESTORE_FACTORY,
}

enum class GateRefusal {
    /** B30 rule 6: never on connect, never automatically. */
    NOT_USER_INITIATED,

    /** The profile has no slot table and no slot of ours: it is read-only whatever else is switched on. */
    PROFILE_CANNOT_BE_WRITTEN,

    /** The profile is not verified on this app's hardware and the debug override is not on. */
    PROFILE_NOT_VERIFIED,

    /** The EQ read of this session did not work (a value is unknown or failed, or the count disagrees with the table). */
    READ_NOT_WORKING,

    /** The read is too old to be trusted as "the state before the write". */
    STALE_READ,

    /** No backup of this DAC's EQ, or none that holds the slot about to be touched. */
    NO_BACKUP,

    /** A backup exists, but of another DAC or another model. */
    BACKUP_FOR_OTHER_DAC,

    /** A backup exists, but it is not a whole one. */
    BACKUP_INCOMPLETE,

    /** The target is not the user slot the app owns (a factory preset, "Close EQ" for a content write, an unknown id). */
    NOT_USER_SLOT,

    /** A value outside the profile's limits: band count, frequency, gain, Q, filter type, pre-gain. */
    OUT_OF_LIMITS,

    /** The curve would make the output louder than the input even after the largest pre-gain the DAC takes. */
    NET_BOOST,

    /**
     * B30-fix1 H1: the app's slot has never been looked at (it is not in the backup), so selecting it would play a curve nobody knows.
     * The slot is inspected by one explicit step ([WriteKind.PEEK_SLOT], [WriteKind.TAKE_OVER], [WriteKind.INVENTORY]), never as a side
     * effect of a write.
     */
    SLOT_NOT_INSPECTED,

    /**
     * B30-fix1 H1: the backup says the slot holds a curve that boosts, and the DAC would play it the moment the slot is selected.
     * B31: unless the app has taken the slot over.
     */
    SLOT_UNSAFE_TO_SELECT,

    /** B30-fix1 H1: looking at an unknown slot is a reviewer's explicit step, not something the Resonance screen does. */
    REVIEWER_ONLY,

    /** B30-fix1 H2: the DAC, the pair or the conditions the edit was accepted under are not the ones in force any more. */
    EDIT_OUTDATED,

    /** B31 P1.3: the step selects a preset that may be audible at any level, so it only runs while playback is paused. */
    PLAYBACK_NOT_PAUSED,

    /**
     * B31 P1.2: the curve rises above 0 dB, and while the app does not know whether a positive global gain amplifies or attenuates no
     * pre-gain holds it down under both readings. Only the reviewer's listening check settles that.
     */
    PRE_GAIN_SENSE_UNCONFIRMED,

    /** B31 P1.3: the step needs the slot to be the app's (taken over), and it is not. */
    SLOT_NOT_TAKEN_OVER,

    /** B31 P1.3: the slot is the app's; a restore from the backup would put the factory curve back, which is a step of its own. */
    SLOT_IS_THE_APPS,

    /** B31 P1.4: selecting a preset while music plays changes the sound, and the listener has not confirmed. */
    CONFIRMATION_NEEDED,

    /** B31 P1.2: the listening check is louder than the limit on purpose; the reviewer says the DAC is at a low level or it does not run. */
    LOW_LEVEL_NOT_CONFIRMED,
}

/** One SET frame, as the gate whitelists it. */
data class AllowedFrame(val command: Int, val data: List<Byte>)

/**
 * The gate's yes: the exact frames the writer may send for one operation. Only [DacEqWriteGate] makes one (the constructor is
 * `internal` and a test scans the sources for any other `WritePermit(`).
 *
 * B30-fix1 H1: a permit now covers a **sequence**. The pointer and save frames are a set (any order, as before), but the gain and band
 * frames of a plan are an ordered list: only the next one of the list can be sent, and each only once. The first version let the
 * writer send the covered frames in any order, which is how a boost came to be live with no pre-gain to hold it.
 */
class WritePermit internal constructor(
    val kind: WriteKind,
    val backupKey: String,
    val slotId: Int?,
    internal val crcMode: FiioCrcMode,
    private val allowed: Set<AllowedFrame>,
    /** How the save is spelled in this permit, or [FiioSaveForm.NONE] when it carries no save at all. */
    internal val saveForm: FiioSaveForm = FiioSaveForm.WITH_SLOT,
    /** The gain and band frames of the plan, in the order they must go out. */
    private val sequence: List<AllowedFrame> = emptyList(),
) {
    private var cursor = 0

    /** Whether [command] with [data] is covered **now**: a pointer or save frame, or the next frame of the plan. Does not use it up. */
    fun allows(command: Int, data: ByteArray): Boolean {
        val frame = AllowedFrame(command, data.toList())
        return frame in allowed || sequence.getOrNull(cursor) == frame
    }

    /** [allows], and uses the frame up when it is the plan's next one. The writer's `send` goes through this and nothing else. */
    internal fun take(command: Int, data: ByteArray): Boolean {
        val frame = AllowedFrame(command, data.toList())
        if (frame in allowed) return true
        if (sequence.getOrNull(cursor) != frame) return false
        cursor++
        return true
    }

    /** Every frame of the plan was sent, in order. */
    val sequenceDone: Boolean get() = cursor == sequence.size

    /** What the permit covers, for the log. */
    val frameCount: Int get() = allowed.size + sequence.size
}

sealed interface GateDecision {
    /**
     * [preGainTenthsDb] is the pre-gain the gate computed for an [WriteKind.APPLY_CURVE]; null for the other kinds. [plan] is the
     * checked sequence of gain and band SETs the permit covers; **null when the slot still has to be selected first**: then the
     * permit covers the select (and the way back) only, and the writer asks again with a read of the selected slot.
     */
    class Authorised(val permit: WritePermit, val preGainTenthsDb: Int? = null, val plan: DacEqWritePlan? = null) : GateDecision

    data class Refused(val refusal: GateRefusal, val detail: String) : GateDecision
}

/** What the gate is asked. */
data class WriteRequest(
    val kind: WriteKind,
    val initiator: WriteInitiator,
    /** The slot an [WriteKind.APPLY_CURVE], [WriteKind.PEEK_SLOT] or [WriteKind.ROUNDTRIP] is about. */
    val slotId: Int? = null,
    /** The curve to write, for an [WriteKind.APPLY_CURVE]: one band per filter of the profile. The pre-gain is the gate's to compute. */
    val bands: List<DacEqBand>? = null,
    /** The preset the DAC had selected when the operation began; a [WriteKind.ROUNDTRIP] covers selecting it again afterwards. */
    val returnTo: Int? = null,
    /**
     * B31 P1.3: the content a [WriteKind.RESTORE] of a slot the app has taken over puts back (what the slot held when the operation that
     * failed began). Without it such a slot is not rewritten from the backup, which holds the factory curve.
     */
    val content: DacEqSlotSnapshot? = null,
    /** B31: the preset a [WriteKind.SELECT_PRESET] selects. */
    val presetId: Int? = null,
    /**
     * B31: the listener confirmed a select while music plays ([WriteKind.SELECT_PRESET]); the reviewer says the DAC is at a low level
     * ([WriteKind.LISTENING_CHECK]).
     */
    val confirmed: Boolean = false,
)

/** What the gate looks at besides the request. */
data class WriteContext(
    val profile: FiioHidProfile,
    val backupKey: String,
    /** The state read **in this session, just before**. */
    val read: DacEqState,
    val readAgeMs: Long,
    val backup: DacEqBackup?,
    /** The debug override is on (always false in a release build) or the profile is verified: see [DacEqWriteSwitch]. */
    val overrideAllowsWrites: Boolean,
    /**
     * B30-fix1 H2: why the operation must not begin now (its DAC was unplugged or changed, Purity was turned on, writes were switched
     * off), or null. The writer asks while nothing has been sent; once a SET has gone out the operation finishes or restores.
     */
    val staleReason: String? = null,
    /** B31 P1.2: what a positive global gain does to the level on this DAC (the profile's, or the reviewer's answer on this phone). */
    val sense: PreGainSense = PreGainSense.BOOST,
    /** B31 P1.3: music is playing. The steps that select a preset nobody has vetted refuse it. */
    val playing: Boolean = false,
)

/**
 * B30 rule 6: **the one gate every write to a DAC's persistent configuration passes.** Each condition has its own refusal and its
 * own test (`DacEqWriteGateTest`):
 *
 * 1. a user action or a reviewer command: never on connect, never automatically ([GateRefusal.NOT_USER_INITIATED]);
 * 2. only a profile that can be written at all ([GateRefusal.PROFILE_CANNOT_BE_WRITTEN]) and is marked **verified**, or that the
 *    debug override has switched on for this phone ([GateRefusal.PROFILE_NOT_VERIFIED]);
 * 3. only after this session read the DAC's whole EQ state ([GateRefusal.READ_NOT_WORKING], [GateRefusal.STALE_READ]);
 * 4. only after that state is saved as a backup for this DAC, which holds every slot about to be touched
 *    ([GateRefusal.NO_BACKUP], [GateRefusal.BACKUP_FOR_OTHER_DAC], [GateRefusal.BACKUP_INCOMPLETE]);
 * 5. only to the user slot the app owns; never a factory preset ([GateRefusal.NOT_USER_SLOT]);
 * 6. only values inside the profile's limits ([GateRefusal.OUT_OF_LIMITS]);
 * 7. only a curve whose **net peak after the pre-gain is at most 0 dB** ([GateRefusal.NET_BOOST]), under every reading of the
 *    pre-gain's sense ([GateRefusal.PRE_GAIN_SENSE_UNCONFIRMED] when no pre-gain can do that). The pre-gain is the gate's to
 *    compute: [DacEqCurve.preGainTenthsFor];
 * 8. (B30-fix1 H1) **every state the DAC passes through** is at most 0 dB too ([DacEqLevelRule]): the gate replays the plan's frames
 *    from the state the slot holds now and evaluates the level after each one ([GateRefusal.NET_BOOST]); the permit then covers that
 *    sequence in that order;
 * 9. (B30-fix1 H1) a slot is selected only when the backup says what it holds and that is safe to play ([GateRefusal.SLOT_NOT_INSPECTED],
 *    [GateRefusal.SLOT_UNSAFE_TO_SELECT]) or the app has taken it over; looking at an unknown slot is the explicit [WriteKind.PEEK_SLOT],
 *    [WriteKind.TAKE_OVER] or [WriteKind.INVENTORY] and nothing else;
 * 10. (B30-fix1 H2) the operation is still the one that was asked for: its DAC and conditions are in force
 *    ([GateRefusal.EDIT_OUTDATED]), checked at every stage until the first SET has gone out;
 * 11. (B31 P1.3) the steps that select a preset nobody has vetted run only while playback is paused ([GateRefusal.PLAYBACK_NOT_PAUSED]).
 *
 * A [WriteKind.RESTORE], a [WriteKind.ROUNDTRIP] and a [WriteKind.RESTORE_FACTORY] write back what the DAC held before the app touched
 * it, which is not a curve the app authored, so rule 7 does not apply to their **final** state (rules 1–6 and 8–11 do, the limits
 * included); a [WriteKind.CLOSE_EQ] moves a pointer. The [WriteKind.LISTENING_CHECK] is the one exception to rule 7 and 8, by design.
 *
 * The read-back that follows a write and the restore on a mismatch are the writer's ([DacEqWriter]); the gate decides what may be
 * sent at all, and in what order.
 */
class DacEqWriteGate(
    private val options: FiioWriteOptions = FiioWriteOptions(),
    private val maxReadAgeMs: Long = MAX_READ_AGE_MS,
    /**
     * Who makes the plan the gate then checks. The real one is [DacEqWritePlanner]; a test hands in a wrong one, to show that the
     * gate's own replay refuses it whoever made it (B30-fix1 H1). The last argument says the final state is the backup's own.
     */
    private val planner: (start: DacEqLive, target: DacEqLive, forceAll: Boolean, exemptFinal: Boolean) -> DacEqWritePlan =
        { start, target, forceAll, exemptFinal -> DacEqWritePlanner.plan(start, target, forceAll, exemptFinal) },
) {
    fun authorise(request: WriteRequest, context: WriteContext): GateDecision {
        val profile = context.profile
        if (request.initiator == WriteInitiator.AUTOMATIC) {
            return refuse(GateRefusal.NOT_USER_INITIATED, "a write is never made on connect or by itself")
        }
        context.staleReason?.let { return refuse(GateRefusal.EDIT_OUTDATED, it) }
        if (!profile.canBeWritten) {
            return refuse(GateRefusal.PROFILE_CANNOT_BE_WRITTEN, "${profile.displayName} has no slot of ours to write")
        }
        if (!profile.verified && !context.overrideAllowsWrites) {
            return refuse(GateRefusal.PROFILE_NOT_VERIFIED, "${profile.displayName} is not verified on this app's hardware")
        }
        if (!context.read.isComplete) {
            return refuse(GateRefusal.READ_NOT_WORKING, "the EQ read is incomplete: ${firstProblem(context.read)}")
        }
        if (context.readAgeMs > maxReadAgeMs || context.readAgeMs < 0) {
            return refuse(GateRefusal.STALE_READ, "the state was read ${context.readAgeMs} ms ago")
        }
        if (request.kind in PAUSED_ONLY && context.playing) {
            return refuse(GateRefusal.PLAYBACK_NOT_PAUSED, "${request.kind} selects a preset that may play at any level: pause the music first")
        }
        val appSlot = profile.appSlotId!!
        val currentPreset = (context.read.activePreset as EqRead.Read).value

        return when (request.kind) {
            WriteKind.PEEK_SLOT -> {
                if (request.initiator != WriteInitiator.REVIEWER_COMMAND) {
                    return refuse(GateRefusal.REVIEWER_ONLY, "an unknown slot is looked at by the reviewer's step, not from the screen")
                }
                if (request.slotId != appSlot) return refuse(GateRefusal.NOT_USER_SLOT, "only slot 0x%02X is ours".format(appSlot))
                backupProblem(context, emptySet())?.let { return it }
                grant(request, context, appSlot, setOf(preset(appSlot), preset(currentPreset)))
            }
            WriteKind.CLOSE_EQ -> {
                val close = profile.disabledPresetId ?: return refuse(GateRefusal.NOT_USER_SLOT, "the model has no Close EQ preset")
                backupProblem(context, emptySet())?.let { return it }
                grant(request, context, null, setOf(preset(close)))
            }
            WriteKind.APPLY_CURVE -> apply(request, context, appSlot, currentPreset)
            WriteKind.RESTORE ->
                if (request.slotId == null) restorePointer(request, context) else restoreContent(request, context, request.slotId, false, currentPreset)
            WriteKind.ROUNDTRIP -> {
                if (request.slotId != appSlot) return refuse(GateRefusal.NOT_USER_SLOT, "only slot 0x%02X is ours".format(appSlot))
                restoreContent(request, context, appSlot, true, currentPreset)
            }
            WriteKind.TAKE_OVER -> takeOver(request, context, appSlot, currentPreset)
            WriteKind.INVENTORY -> {
                backupProblem(context, emptySet())?.let { return it }
                grant(request, context, null, profile.slots.map { preset(it.id) }.toSet() + preset(currentPreset))
            }
            WriteKind.SELECT_PRESET -> selectPreset(request, context, currentPreset)
            WriteKind.LISTENING_CHECK -> listeningCheck(request, context, appSlot, currentPreset)
            WriteKind.RESTORE_FACTORY -> restoreFactory(request, context, appSlot, currentPreset)
        }
    }

    /**
     * An app-authored curve, in two stages that the writer asks for one after the other:
     * - the app's slot is **not** the one selected: everything is checked (limits, net level, backup, that the slot is known and safe
     *   to select) and the permit covers the select only; no content is planned from a slot that has not been read;
     * - it **is** selected: the plan is made from what the slot holds now, and every state it passes through is checked.
     */
    private fun apply(request: WriteRequest, context: WriteContext, appSlot: Int, currentPreset: Int): GateDecision {
        val profile = context.profile
        val sense = context.sense
        if (request.slotId != appSlot) return refuse(GateRefusal.NOT_USER_SLOT, "only slot 0x%02X is ours".format(appSlot))
        val bands = request.bands ?: return refuse(GateRefusal.OUT_OF_LIMITS, "no curve to write")
        backupProblem(context, setOf(appSlot))?.let { return it }
        limitsProblem(profile, bands, preGainTenths = 0, onlyAppFilters = true)?.let { return refuse(GateRefusal.OUT_OF_LIMITS, it) }
        val preGain = DacEqCurve.preGainTenthsFor(bands, sense)
            ?: return refuse(
                GateRefusal.PRE_GAIN_SENSE_UNCONFIRMED,
                "the curve rises ${"%.1f".format(DacEqCurve.peakBoostDb(bands))} dB above 0 and the app does not know yet whether a positive global gain " +
                    "amplifies or attenuates, so no pre-gain holds it down under both readings (the reviewer's listening check settles that)",
            )
        if (preGain !in tenths(profile.minGainDb)..tenths(profile.maxGainDb)) {
            return refuse(GateRefusal.NET_BOOST, "the curve needs a pre-gain of ${preGain / 10.0} dB; the DAC takes ${profile.minGainDb}..${profile.maxGainDb}")
        }
        // The rule itself, checked on the result and not just trusted from the computation above.
        val net = DacEqCurve.netPeakDb(bands, preGain, sense)
        if (net > 0.0) return refuse(GateRefusal.NET_BOOST, "net peak ${"%.2f".format(net)} dB after the pre-gain")
        if (currentPreset != appSlot) {
            slotSelectProblem(context, appSlot)?.let { return it }
            return grant(request, context, appSlot, setOf(preset(appSlot), preset(currentPreset)), preGain)
        }
        val plan = planner(DacEqLive.of(context.read, sense)!!, DacEqLive(preGain, bands, sense), false, false)
        planProblem(profile, plan, exemptFinal = false)?.let { return refuse(GateRefusal.NET_BOOST, it) }
        val allowed = setOfNotNull(save(profile, appSlot))
        return grant(request, context, appSlot, allowed, preGain, plan)
    }

    /**
     * Writing a slot back to what the backup holds (a [WriteKind.RESTORE] of one slot, or the reviewer's [WriteKind.ROUNDTRIP]): the
     * same two stages as [apply]. What the backup holds is what the DAC held before the app touched it, not a curve the app
     * authored, so the final state is exempt from the level rule; every state **on the way** is not.
     *
     * B31: a slot the app has taken over is not what the backup holds. A roundtrip writes back **what it holds now**, and a restore of it
     * needs the content the failed operation began with ([WriteRequest.content]); without one the backup's factory curve would come back.
     */
    private fun restoreContent(request: WriteRequest, context: WriteContext, slot: Int, force: Boolean, currentPreset: Int): GateDecision {
        val profile = context.profile
        val sense = context.sense
        if (profile.writableSlots.none { it.id == slot }) return refuse(GateRefusal.NOT_USER_SLOT, "slot 0x%02X is not one the app writes".format(slot))
        backupProblem(context, setOf(slot))?.let { return it }
        val backup = context.backup!!
        val owned = backup.owns(slot)
        val source: DacEqSlotSnapshot = when {
            !owned -> backup.slots.getValue(slot)
            request.content != null -> request.content
            force && currentPreset == slot -> DacEqSlotSnapshot.of(context.read)!!
            force -> null // a roundtrip of the app's own slot: nothing is known to write until it is selected and read
            else -> return refuse(GateRefusal.SLOT_IS_THE_APPS, "slot 0x%02X is the app's now; the backup holds its factory curve (restore factory curve puts that back)".format(slot))
        } ?: return grant(request, context, slot, setOf(preset(slot), preset(currentPreset)))
        val snapshot = source.writable()
        limitsProblem(profile, snapshot.bands, snapshot.preGainTenthsDb, onlyAppFilters = false)
            ?.let { return refuse(GateRefusal.OUT_OF_LIMITS, "the backup of 0x%02X: $it".format(slot)) }
        if (currentPreset != slot) {
            if (!owned) slotSelectProblem(context, slot, snapshot)?.let { return it }
            return grant(request, context, slot, setOf(preset(slot), preset(currentPreset)))
        }
        val plan = planner(DacEqLive.of(context.read, sense)!!, DacEqLive.of(snapshot, sense), force, !owned)
        planProblem(profile, plan, exemptFinal = !owned)?.let { return refuse(GateRefusal.NET_BOOST, it) }
        val allowed = setOfNotNull(save(profile, slot), request.returnTo?.let(::preset))
        return grant(request, context, slot, allowed, plan = plan)
    }

    /**
     * The last step of a restore: the DAC selects what it had selected when the backup was taken, or, when a failed operation only
     * moved the pointer, what it had selected when that operation began ([WriteRequest.returnTo], which must be a preset the model has).
     * A pointer move, no content.
     */
    private fun restorePointer(request: WriteRequest, context: WriteContext): GateDecision {
        backupProblem(context, emptySet())?.let { return it }
        val returnTo = request.returnTo
        if (returnTo != null && context.profile.slots.none { it.id == returnTo }) {
            return refuse(GateRefusal.NOT_USER_SLOT, "preset 0x%02X is not one of ${context.profile.displayName}'s".format(returnTo))
        }
        return grant(request, context, null, setOfNotNull(preset(context.backup!!.activePresetId), returnTo?.let(::preset)))
    }

    /**
     * B31 P1.3: taking over the app's slot, in two stages like [apply]: with another preset selected the answer covers the select of the
     * slot and the way back (**no safety check on the slot: that it may hold a boost is why it is being taken over**); with the slot
     * selected, and its factory content in the backup, the plan is the move to a flat curve with the pre-gain at 0, which is neutral
     * under both readings of the sense. Every state on the way is judged by [DacEqLevelRule].
     */
    private fun takeOver(request: WriteRequest, context: WriteContext, appSlot: Int, currentPreset: Int): GateDecision {
        val profile = context.profile
        if (request.slotId != appSlot) return refuse(GateRefusal.NOT_USER_SLOT, "only slot 0x%02X is ours".format(appSlot))
        backupProblem(context, if (currentPreset == appSlot) setOf(appSlot) else emptySet())?.let { return it }
        if (currentPreset != appSlot) return grant(request, context, appSlot, setOf(preset(appSlot), preset(currentPreset)))
        val target = DacEqLive(0, DacEqDefaults.flatBands(profile.bandCount), context.sense)
        val plan = planner(DacEqLive.of(context.read, context.sense)!!, target, false, false)
        planProblem(profile, plan, exemptFinal = false)?.let { return refuse(GateRefusal.NET_BOOST, it) }
        return grant(request, context, appSlot, setOfNotNull(save(profile, appSlot), request.returnTo?.let(::preset)), 0, plan)
    }

    /** B31 P1.3: the way back. The slot must be the app's; its factory curve is written back and the app lets go of it. */
    private fun restoreFactory(request: WriteRequest, context: WriteContext, appSlot: Int, currentPreset: Int): GateDecision {
        val profile = context.profile
        if (request.slotId != appSlot) return refuse(GateRefusal.NOT_USER_SLOT, "only slot 0x%02X is ours".format(appSlot))
        backupProblem(context, setOf(appSlot))?.let { return it }
        val backup = context.backup!!
        if (!backup.owns(appSlot)) return refuse(GateRefusal.SLOT_NOT_TAKEN_OVER, "slot 0x%02X still holds what the DAC had; nothing to give back".format(appSlot))
        val snapshot = backup.slots.getValue(appSlot).writable()
        limitsProblem(profile, snapshot.bands, snapshot.preGainTenthsDb, onlyAppFilters = false)
            ?.let { return refuse(GateRefusal.OUT_OF_LIMITS, "the factory curve of 0x%02X: $it".format(appSlot)) }
        if (currentPreset != appSlot) return grant(request, context, appSlot, setOf(preset(appSlot), preset(currentPreset)))
        val plan = planner(DacEqLive.of(context.read, context.sense)!!, DacEqLive.of(snapshot, context.sense), false, true)
        planProblem(profile, plan, exemptFinal = true)?.let { return refuse(GateRefusal.NET_BOOST, it) }
        return grant(request, context, appSlot, setOfNotNull(save(profile, appSlot), request.returnTo?.let(::preset)), plan = plan)
    }

    /** B31 P1.4: a select and nothing else, of a preset the inventory has read and that is safe to play. */
    private fun selectPreset(request: WriteRequest, context: WriteContext, currentPreset: Int): GateDecision {
        val profile = context.profile
        val target = request.presetId ?: return refuse(GateRefusal.NOT_USER_SLOT, "no preset to select")
        if (profile.slots.none { it.id == target }) {
            return refuse(GateRefusal.NOT_USER_SLOT, "preset 0x%02X is not one of ${profile.displayName}'s".format(target))
        }
        if (target != profile.disabledPresetId) {
            backupProblem(context, setOf(target))?.let { return it }
            slotSelectProblem(context, target)?.let { return it }
        } else {
            backupProblem(context, emptySet())?.let { return it }
        }
        if (context.playing && !request.confirmed) {
            return refuse(GateRefusal.CONFIRMATION_NEEDED, "music is playing: selecting a preset changes the sound, and nobody confirmed")
        }
        return grant(request, context, null, setOf(preset(target), preset(currentPreset)))
    }

    /**
     * B31 P1.2: the listening check. Reviewer only; on the slot the app has taken over, which must be what it left it as (flat, pre-gain
     * 0) when it is already selected; the reviewer says the DAC is at a low level. The permit covers the select, the way back, and the
     * three pre-gains of the check (−6, +6, 0 dB) with no plan and no save: this is the one place the level rule does not apply.
     */
    private fun listeningCheck(request: WriteRequest, context: WriteContext, appSlot: Int, currentPreset: Int): GateDecision {
        if (request.initiator != WriteInitiator.REVIEWER_COMMAND) {
            return refuse(GateRefusal.REVIEWER_ONLY, "the listening check is louder than the limit on purpose: a reviewer's step")
        }
        if (request.slotId != appSlot) return refuse(GateRefusal.NOT_USER_SLOT, "only slot 0x%02X is ours".format(appSlot))
        backupProblem(context, setOf(appSlot))?.let { return it }
        if (!context.backup!!.owns(appSlot)) {
            return refuse(GateRefusal.SLOT_NOT_TAKEN_OVER, "the check runs on the app's own slot, flat: take USER 3 over first")
        }
        if (!request.confirmed) {
            return refuse(GateRefusal.LOW_LEVEL_NOT_CONFIRMED, "the check puts the output 6 dB above where it was for a few seconds: say the DAC is at a low level")
        }
        if (currentPreset == appSlot) {
            val live = DacEqSlotSnapshot.of(context.read)!!
            if (live.preGainTenthsDb != 0 || live.bands.any { !it.isInert }) {
                return refuse(GateRefusal.SLOT_NOT_TAKEN_OVER, "slot 0x%02X is selected and not flat with the pre-gain at 0; the check starts from there".format(appSlot))
            }
        }
        val frames = setOf(preset(appSlot), preset(currentPreset), gain(-LISTENING_CHECK_TENTHS), gain(LISTENING_CHECK_TENTHS), gain(0))
        return grant(request, context, appSlot, frames)
    }

    private fun grant(
        request: WriteRequest,
        context: WriteContext,
        slotId: Int?,
        allowed: Set<AllowedFrame>,
        preGain: Int? = null,
        plan: DacEqWritePlan? = null,
    ) = GateDecision.Authorised(
        WritePermit(request.kind, context.backupKey, slotId, options.crcMode, allowed, options.saveForm, plan?.frames.orEmpty()),
        preGain,
        plan,
    )

    /**
     * B30-fix1 H1: **the check after every SET.** The plan's frames are replayed on the state it starts from, and the level of the DAC
     * after each one is evaluated with the model the final curve is checked with ([DacEqLevelRule], under each reading of the sense); a
     * step that leaves it above the limit refuses the whole plan. [exemptFinal]: the last state is the backup's own, which the level
     * rule does not judge.
     */
    private fun planProblem(profile: FiioHidProfile, plan: DacEqWritePlan, exemptFinal: Boolean): String? {
        var live = plan.start
        plan.frames.forEachIndexed { i, frame ->
            live = live.after(frame.command, frame.data)
            if (live.preGainTenthsDb !in tenths(profile.minGainDb)..tenths(profile.maxGainDb)) {
                return "step ${i + 1} (${DacEqLevelRule.describe(frame)}): pre-gain ${live.preGainTenthsDb / 10.0} dB is outside ${profile.minGainDb}..${profile.maxGainDb}"
            }
        }
        DacEqLevelRule.firstExcess(plan.start, plan.frames, exemptFinal, plan.target)?.let { return it }
        if (!live.sameEffectAs(plan.target)) return "the plan does not end at the state asked for"
        return null
    }

    /**
     * B30-fix1 H1: what a slot is known to hold decides whether the DAC may be told to play it; B31: unless the app has taken it over,
     * and judged under every reading of the pre-gain's sense. [snapshot]: the content to judge instead of the backup's copy.
     */
    private fun slotSelectProblem(context: WriteContext, slot: Int, snapshot: DacEqSlotSnapshot? = null): GateDecision.Refused? {
        val backup = context.backup ?: return null
        if (backup.owns(slot)) return null
        val known = snapshot ?: backup.slots[slot] ?: return null
        val net = DacEqLive.of(known, context.sense).netPeakDb
        if (net <= 0.0) return null
        return GateDecision.Refused(
            GateRefusal.SLOT_UNSAFE_TO_SELECT,
            "slot 0x%02X holds a curve that boosts by %.1f dB net (as backed up%s), and the DAC plays it as soon as it is selected"
                .format(slot, net, if (context.sense == PreGainSense.UNCONFIRMED) ", on the worse of the two readings of the pre-gain" else ""),
        )
    }

    private fun backupProblem(context: WriteContext, requiredSlots: Set<Int>): GateDecision.Refused? {
        val backup = context.backup ?: return GateDecision.Refused(GateRefusal.NO_BACKUP, "this DAC's EQ has not been backed up")
        if (backup.backupKey != context.backupKey || backup.profileId != context.profile.id) {
            return GateDecision.Refused(GateRefusal.BACKUP_FOR_OTHER_DAC, "the backup is of ${backup.dacName} (${backup.profileId})")
        }
        if (!backup.isComplete) return GateDecision.Refused(GateRefusal.BACKUP_INCOMPLETE, "the backup is not a whole one")
        val missing = requiredSlots.filter { it !in backup.slots }
        if (missing.isNotEmpty()) {
            return GateDecision.Refused(
                GateRefusal.SLOT_NOT_INSPECTED,
                "slot ${missing.joinToString { "0x%02X".format(it) }} is not in the backup: it has never been looked at, so selecting it would play what nobody knows",
            )
        }
        return null
    }

    private fun refuse(refusal: GateRefusal, detail: String) = GateDecision.Refused(refusal, detail)

    private fun firstProblem(state: DacEqState): String = listOf(
        "preset" to state.activePreset, "count" to state.bandCount, "gain" to state.preGainTenthsDb,
    ).firstOrNull { it.second !is EqRead.Read }?.let { "${it.first} ${it.second}" }
        ?: state.bands.firstOrNull { it !is EqRead.Read }?.toString()
        ?: "the count disagrees with the table"

    private fun preset(id: Int) = AllowedFrame(FiioHidWrites.CMD_PRESET, FiioHidWrites.encodePreset(id).toList())
    private fun gain(tenths: Int) = AllowedFrame(FiioHidWrites.CMD_GAIN, FiioHidWrites.encodeGain(tenths).toList())
    private fun tenths(db: Double) = (db * 10).roundToInt()

    /** The save frame for [slot] in the configured form; null for [FiioSaveForm.NONE]: no save frame is covered, so none can be sent. */
    private fun save(profile: FiioHidProfile, slot: Int): AllowedFrame? = when (options.saveForm) {
        FiioSaveForm.WITH_SLOT -> AllowedFrame(profile.saveCommand, FiioHidWrites.encodePreset(slot).toList())
        FiioSaveForm.EMPTY -> AllowedFrame(profile.saveCommand, emptyList())
        FiioSaveForm.NONE -> null
    }

    companion object {
        const val MAX_READ_AGE_MS = 60_000L

        /** How far the listening check moves the pre-gain either way: 6.0 dB, in tenths. */
        const val LISTENING_CHECK_TENTHS = 60

        /** The steps that select a preset the app has not vetted, or give a slot back its factory curve: they run only while paused. */
        private val PAUSED_ONLY = setOf(WriteKind.PEEK_SLOT, WriteKind.TAKE_OVER, WriteKind.INVENTORY, WriteKind.RESTORE_FACTORY)

        /**
         * The limits of the profile, band by band. Null when everything is inside. [onlyAppFilters]: an app-authored curve may
         * only use the filter types the app can evaluate (peak and the two shelves); a restore may rewrite any type the DAC knows.
         */
        fun limitsProblem(profile: FiioHidProfile, bands: List<DacEqBand>, preGainTenths: Int, onlyAppFilters: Boolean): String? {
            if (bands.size != profile.bandCount) return "${bands.size} bands, the DAC has ${profile.bandCount}"
            fun tenths(db: Double) = (db * 10).roundToInt()
            if (preGainTenths !in tenths(profile.minGainDb)..tenths(profile.maxGainDb)) {
                return "pre-gain ${preGainTenths / 10.0} dB is outside ${profile.minGainDb}..${profile.maxGainDb}"
            }
            bands.forEachIndexed { i, band ->
                val n = i + 1
                if (band.index != i) return "band $n has index ${band.index}"
                val type = band.type ?: return "band $n has an unknown filter type ${band.typeCode}"
                if (type !in profile.supportedFilters) return "band $n: ${type.name} is not a filter of ${profile.displayName}"
                if (onlyAppFilters && !type.isGainFilter) return "band $n: ${type.name} is not a filter the app writes"
                if (band.frequencyHz !in profile.minFrequencyHz..profile.maxFrequencyHz) {
                    return "band $n: ${band.frequencyHz} Hz is outside ${profile.minFrequencyHz}..${profile.maxFrequencyHz}"
                }
                if (band.gainTenthsDb !in tenths(profile.minGainDb)..tenths(profile.maxGainDb)) {
                    return "band $n: ${band.gainDb} dB is outside ${profile.minGainDb}..${profile.maxGainDb}"
                }
                if (band.qHundredths !in (profile.minQ * 100).roundToInt()..(profile.maxQ * 100).roundToInt()) {
                    return "band $n: Q ${band.q} is outside ${profile.minQ}..${profile.maxQ}"
                }
            }
            return null
        }
    }
}
