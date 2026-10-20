package com.lostf1sh.pixelplayeross.data.dac.fiio

import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/** What a write operation came to. Everything a screen or a debug command says about it is in here. */
sealed interface DacEqWriteResult {
    /** Written (or, for a read-only step, done), read back and equal; [state] is what the DAC reports now. */
    data class Done(val state: DacEqState, val note: String) : DacEqWriteResult

    /** The gate said no, and nothing was sent. */
    data class Refused(val refusal: GateRefusal, val detail: String) : DacEqWriteResult

    /** The HID interface could not be used: nothing was sent. */
    data class Unavailable(val why: String) : DacEqWriteResult

    /**
     * The DAC did not hold what was written (the read-back differs). [restored] says whether the backup was written back and the
     * DAC reads as it did before; [state] is the last thing read.
     */
    data class Mismatch(val expected: String, val actual: String, val restored: Boolean, val state: DacEqState?) : DacEqWriteResult

    /** Something went wrong before a read-back could be compared (a transfer failed, the DAC stopped answering). */
    data class Failed(val why: String, val restored: Boolean?, val state: DacEqState?) : DacEqWriteResult
}

/** What the second stage of an operation on the app's slot came to: the gate's plan for the selected slot, or the result it ends with. */
private sealed interface Selected {
    class Ready(val authorisation: GateDecision.Authorised) : Selected
    class Stop(val result: DacEqWriteResult) : Selected
}

/** The result of taking (or declining to take) a backup. */
sealed interface DacEqBackupResult {
    data class Created(val backup: DacEqBackup, val state: DacEqState) : DacEqBackupResult

    /** One exists and was kept: a backup is never overwritten silently. */
    data class Exists(val backup: DacEqBackup, val state: DacEqState) : DacEqBackupResult

    data class NotReadable(val state: DacEqState) : DacEqBackupResult
    data class StoreFailed(val state: DacEqState) : DacEqBackupResult
    data class Unavailable(val why: String) : DacEqBackupResult
}

/** B31 P1.6: the caller's guard said, just before the first SET, that the operation is not the one wanted any more. Nothing was sent. */
private class OutdatedBeforeFirstSet(val reason: String) : RuntimeException(reason)

/**
 * B30-P2 / B30-fix1 / B31: the operations that write a DAC's EQ, each of them through [DacEqWriteGate], each read back and compared.
 *
 * Every operation runs in one HID session ([DacEqHidAccess]: its own thread, one session at a time) and follows the same shape:
 *
 * 1. **read** the DAC's whole EQ state;
 * 2. ask the **gate**. If the only thing missing is the backup of a DAC that has none, make it (the state just read is saved) and
 *    ask again. A slot the backup does not hold is **not** looked at here (B30-fix1 H1): that is [discoverSlot], [takeOver] or
 *    [inventory], each an explicit step;
 * 3. when the app's slot is not the one selected, the gate's first answer covers the select only: select it, **read what the slot
 *    holds**, and ask again; the second answer is a **plan**, the gain and band SETs in the order that keeps every state the DAC
 *    passes through at or below the level limit, and the permit covers exactly those frames in that order;
 * 4. **write** only the plan, **each SET confirmed before the next goes out** (B31 P1.1): by the DAC's own acknowledgement when it
 *    sends a usable one, else by reading the value back; a contrary acknowledgement, or a value that reads back wrong, stops the plan.
 *    Where the plan *relies* on an earlier SET (a band that adds boost relies on the lowered pre-gain; a pre-gain that gives headroom
 *    back relies on the cuts) the earlier value is read back from the DAC first, whatever the acknowledgement said. Then the save;
 * 5. **read back** the whole state and compare it with what was asked (WolfEQ's `BandsMatch`: every field exact at the wire's units);
 * 6. on a **mismatch, a failure or an exception after the first SET, restore** (gated too, planned and confirmed the same way): the
 *    backup for a slot the app has not taken over; what the slot held when the operation began for one it has. Then say what the DAC
 *    reads **now**: the state read after the recovery is published, and when there is none the old reading is invalidated
 *    (B30-fix1 H3): no label keeps Purity because nothing new was published.
 *
 * Nothing here runs by itself: a caller is the Resonance screen or a reviewer's debug command ([WriteInitiator]). The attach read
 * ([DacEqController]) cannot reach this class.
 *
 * Whether the DAC needs the separate save command to keep a write, and whether a write survives unplugging, are exactly what the
 * reviewer's runbook finds out; [DacEqWriter] sends the save after every content write, so the answer to the first only matters
 * if a DAC turns out to refuse it.
 *
 * @param pause blocks the HID thread (the DAC needs a moment after a select and after the save); the tests do not wait.
 * @param invalidate called when a write may have changed the DAC and no read says what it holds now.
 * @param playbackActive whether music is playing: the steps that select a preset nobody has vetted run only while it is not. The service
 *   passes the real answer; the default is for the tests.
 * @param senseOf what a positive global gain does to the level on a profile (B31 P1.2); the service passes the profile's, or the
 *   reviewer's answer on this phone.
 * @param onBackupChanged called with the backup as it is on disk after this class changed it (a slot added, a slot taken over).
 */
class DacEqWriter(
    private val access: DacEqHidAccess,
    private val gate: DacEqWriteGate,
    private val backups: DacEqBackupStore,
    private val overrideAllowsWrites: () -> Boolean,
    private val elapsedMs: () -> Long,
    private val wallClockMs: () -> Long,
    private val publish: (AttachedFiio, DacEqState) -> Unit = { _, _ -> },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
    private val invalidate: (AttachedFiio, String) -> Unit = { _, _ -> },
    private val playbackActive: () -> Boolean = { false },
    private val senseOf: (FiioHidProfile) -> PreGainSense = { it.preGainSense },
    private val onBackupChanged: (AttachedFiio, DacEqBackup) -> Unit = { _, _ -> },
) {
    // ── Backup (read-only) ───────────────────────────────────────────────────────────────────────────────

    /** Reads the whole state and stores it as this DAC's backup. Writes nothing to the DAC. [replace] keeps the old one aside. */
    suspend fun backup(att: AttachedFiio, replace: Boolean = false): DacEqBackupResult {
        val use = try {
            access.withSession(att, log) { session ->
                logHeader("BACKUP", att, WriteInitiator.REVIEWER_COMMAND)
                val state = FiioEqReader.read(session).also { logState(session, it) }
                val existing = backups.load(att.backupKey)
                if (existing != null && !replace) return@withSession DacEqBackupResult.Exists(existing, state)
                val backup = DacEqBackup.of(att, state, wallClockMs()) ?: return@withSession DacEqBackupResult.NotReadable(state)
                if (backups.save(backup, replace = replace, nowEpochMs = wallClockMs())) {
                    onBackupChanged(att, backup)
                    DacEqBackupResult.Created(backup, state)
                } else {
                    DacEqBackupResult.StoreFailed(state)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return DacEqBackupResult.Unavailable("${error.javaClass.simpleName}: ${error.message}")
        }
        return when (use) {
            is HidUse.Ran -> use.value.also { log("DAC_HID|RESULT BACKUP ${it.javaClass.simpleName}") }
            is HidUse.Unavailable -> DacEqBackupResult.Unavailable(use.why)
        }
    }

    // ── Writes ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Looks at the app's slot: selects it, reads what it holds, adds that to the backup, and puts the selection back. **The slot is
     * audible at whatever level it holds while it is selected** (the protocol offers no way to silence a preset before selecting
     * it), so this is the reviewer's explicit step, never part of a write; see the runbook. Only while playback is paused.
     */
    suspend fun discoverSlot(att: AttachedFiio): DacEqWriteResult =
        run("DISCOVER_SLOT", att, WriteInitiator.REVIEWER_COMMAND, { null }) { op -> op.discoverSlot() }

    /**
     * Writes [bands] into the app's slot (the pre-gain is computed by the gate), selects it, saves it, reads it back. [stale] is the
     * caller's guard (B30-fix1 H2): asked after the HID lock is won and before the first SET; an answer other than null ends the
     * operation as [GateRefusal.EDIT_OUTDATED] with nothing sent.
     */
    suspend fun applyCurve(
        att: AttachedFiio,
        bands: List<DacEqBand>,
        initiator: WriteInitiator,
        stale: () -> String? = { null },
    ): DacEqWriteResult = run("APPLY_CURVE", att, initiator, stale) { op -> op.applyCurve(bands) }

    /** One band of the app's slot, relative to its base: gain only, everything else as the slot has it. */
    suspend fun setBand(
        att: AttachedFiio,
        bandIndex: Int,
        gainDb: Double,
        initiator: WriteInitiator,
        stale: () -> String? = { null },
    ): DacEqWriteResult = run("SET_BAND", att, initiator, stale) { op -> op.setBand(bandIndex, gainDb) }

    /** Closes the DAC's EQ: selects the model's "Close EQ" preset. */
    suspend fun closeEq(att: AttachedFiio, initiator: WriteInitiator, stale: () -> String? = { null }): DacEqWriteResult =
        run("CLOSE_EQ", att, initiator, stale) { op -> op.closeEq() }

    /** Writes the app slot's stored content back, unchanged, and compares: the gentlest write there is. */
    suspend fun roundtrip(att: AttachedFiio, initiator: WriteInitiator, stale: () -> String? = { null }): DacEqWriteResult =
        run("ROUNDTRIP", att, initiator, stale) { op -> op.roundtrip() }

    /** Writes the backup back and selects what was selected when it was taken, then compares. */
    suspend fun restore(att: AttachedFiio, initiator: WriteInitiator, stale: () -> String? = { null }): DacEqWriteResult =
        run("RESTORE", att, initiator, stale) { op -> op.restore(reason = "requested") }

    /**
     * B31 P1.3: takes the app's slot over: its factory curve goes into the backup, then the slot is saved flat with the pre-gain at 0
     * and the selection goes back where it was. Only while playback is paused.
     */
    suspend fun takeOver(att: AttachedFiio, initiator: WriteInitiator, stale: () -> String? = { null }): DacEqWriteResult =
        run("TAKE_OVER", att, initiator, stale) { op -> op.takeOver() }

    /** B31 P1.3: gives the slot its factory curve back and lets go of it. Only while playback is paused. */
    suspend fun restoreFactory(att: AttachedFiio, initiator: WriteInitiator, stale: () -> String? = { null }): DacEqWriteResult =
        run("RESTORE_FACTORY", att, initiator, stale) { op -> op.restoreFactory() }

    /** B31 P1.4: reads every preset the profile lists, by select → read → select back; pointer moves only. Only while paused. */
    suspend fun inventory(att: AttachedFiio, initiator: WriteInitiator, stale: () -> String? = { null }): DacEqWriteResult =
        run("INVENTORY", att, initiator, stale) { op -> op.inventory() }

    /** B31 P1.4: selects one of the DAC's presets and nothing else. While music plays it needs [confirmed]. */
    suspend fun selectPreset(
        att: AttachedFiio,
        presetId: Int,
        initiator: WriteInitiator,
        confirmed: Boolean = false,
        stale: () -> String? = { null },
    ): DacEqWriteResult = run("SELECT_PRESET", att, initiator, stale) { op -> op.selectPreset(presetId, confirmed) }

    /**
     * B31 P1.2: the reviewer's listening check: on the app's own slot, flat, the pre-gain goes to −6 dB, back to 0, to +6 dB, back to 0,
     * live and unsaved, [holdMs] apart, so the owner can say which is quieter. [lowLevel]: the reviewer says the DAC is at a low level.
     */
    suspend fun listeningCheck(att: AttachedFiio, lowLevel: Boolean, holdMs: Long = LISTEN_HOLD_MS): DacEqWriteResult =
        run("LISTENING_CHECK", att, WriteInitiator.REVIEWER_COMMAND, { null }) { op -> op.listeningCheck(lowLevel, holdMs) }

    private suspend fun run(
        name: String,
        att: AttachedFiio,
        initiator: WriteInitiator,
        stale: () -> String?,
        block: (Op) -> DacEqWriteResult,
    ): DacEqWriteResult {
        // B30-fix1 H2: a write is for one attachment of one DAC; without its connection path it cannot be told from another unit.
        if (att.connectionPath == null) return DacEqWriteResult.Unavailable("the DAC has no connection path to write to")
        val use = try {
            access.withSession(att, log, stale) { session ->
                logHeader(name, att, initiator)
                val op = Op(session, att, initiator, stale)
                val result = try {
                    block(op)
                } catch (outdated: OutdatedBeforeFirstSet) {
                    // B31 P1.6: the guard said so just before the first SET: nothing was sent.
                    session.logLine("DAC_HID|REFUSED EDIT_OUTDATED just before the first SET: ${outdated.reason}")
                    DacEqWriteResult.Refused(GateRefusal.EDIT_OUTDATED, outdated.reason)
                } catch (error: Exception) {
                    op.recoverAfterException(error)
                }
                op.finalRead()
                log("DAC_HID|RESULT $name ${describe(result)}")
                settle(att, result, op)
                result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return DacEqWriteResult.Unavailable("${error.javaClass.simpleName}: ${error.message}")
        }
        return when (use) {
            is HidUse.Ran -> use.value
            is HidUse.Unavailable ->
                if (use.stale) DacEqWriteResult.Refused(GateRefusal.EDIT_OUTDATED, use.why) else DacEqWriteResult.Unavailable(use.why)
        }
    }

    /**
     * B30-fix1 H3: **what the labels are told after an operation.** Done: the state read back. Nothing was sent: nothing changed, so the
     * reading stays. Something was sent and it ended otherwise (a mismatch, a failure, an exception, a refusal after the select):
     * the DAC may differ from the reading the app holds, so the state read **after the last SET** (the recovery's own read-back) is
     * published, and when there is none the old reading is invalidated. Runs on the HID thread, inside the session, so that a
     * cancelled caller cannot skip it.
     */
    private fun settle(att: AttachedFiio, result: DacEqWriteResult, op: Op) {
        if (result is DacEqWriteResult.Done) return publish(att, result.state)
        if (op.setsSent == 0) return
        val after = op.stateAfter
        if (after != null) {
            log("DAC_HID|PUBLISH the state read after the recovery: ${after.verdict}")
            publish(att, after)
        } else {
            log("DAC_HID|INVALIDATE the DAC may have changed and no read says what it holds now")
            invalidate(att, "a write may have changed the DAC's EQ and the result could not be read back")
        }
    }

    private fun describe(result: DacEqWriteResult): String = when (result) {
        is DacEqWriteResult.Done -> "done: ${result.note}"
        is DacEqWriteResult.Refused -> "REFUSED ${result.refusal}: ${result.detail}"
        is DacEqWriteResult.Unavailable -> "unavailable: ${result.why}"
        is DacEqWriteResult.Mismatch -> "MISMATCH expected=[${result.expected}] actual=[${result.actual}] restored=${result.restored}"
        is DacEqWriteResult.Failed -> "FAILED ${result.why} restored=${result.restored}"
    }

    private fun logHeader(name: String, att: AttachedFiio, initiator: WriteInitiator) {
        log(
            "DAC_HID|OP $name dac=${att.displayName} profile=${att.profile.id} verified=${att.profile.verified} " +
                "override=${overrideAllowsWrites()} initiator=$initiator preGainSense=${senseOf(att.profile)} " +
                "playing=${playbackActive()} backupKey=${att.backupKey}"
        )
    }

    private fun logState(session: FiioHidSession, state: DacEqState) = DacEqStateFormat.lines(state).forEach(session::logLine)

    // ── One operation ────────────────────────────────────────────────────────────────────────────────────

    private inner class Op(val session: FiioHidSession, val att: AttachedFiio, val initiator: WriteInitiator, val stale: () -> String?) {
        val profile = att.profile
        val appSlot: Int get() = profile.appSlotId ?: -1
        val sense: PreGainSense get() = senseOf(profile)

        /** SET frames this operation has sent. While it is zero the operation can still be dropped; after the first it finishes or restores. */
        var setsSent = 0
            private set

        /** Gain, band and save frames this operation has sent: what a recovery has to undo beyond moving the pointer. */
        private var contentSent = 0

        /** The preset the DAC had selected when this operation first read it: where a recovery that only moved the pointer puts it back. */
        private var startPreset: Int? = null

        /** What the app's slot held when this operation's first plan began: where a recovery of a slot the app owns puts it back. */
        private var opStart: DacEqSlotSnapshot? = null

        /** The last whole-state read taken **after the last SET** this operation sent (null when there is none yet). */
        var stateAfter: DacEqState? = null
            private set

        private fun read(label: String): Pair<DacEqState, Long> {
            val state = FiioEqReader.read(session)
            stateAfter = state
            if (startPreset == null) startPreset = (state.activePreset as? EqRead.Read)?.value
            session.logLine("DAC_HID|STATE ($label) ${state.verdict}, complete=${state.isComplete}")
            return state to elapsedMs()
        }

        /**
         * The one way this operation puts a SET on the wire: it counts it, and what was read before it is no longer what the DAC holds.
         * B31 P1.6: **just before the first SET** the caller's guard is asked once more; an answer ends the operation with nothing sent.
         * The check and the mark of the start are one step, so the start is marked only when the SET is really going out.
         */
        private fun send(permit: WritePermit, command: Int, data: ByteArray, name: String): Boolean {
            if (setsSent == 0) stale()?.let { throw OutdatedBeforeFirstSet(it) }
            setsSent++
            if (command != FiioHidWrites.CMD_PRESET) contentSent++
            stateAfter = null
            return FiioHidWrites.send(session, permit, command, data, name)
        }

        /**
         * B30-fix1 H3: after an operation that sent a SET and did not end in a read-back, one more whole-state read, so that what the
         * labels are told is what the DAC holds **now**. When it cannot be read the writer invalidates instead.
         */
        fun finalRead() {
            if (setsSent == 0 || stateAfter != null) return
            try {
                read("final")
            } catch (error: Exception) {
                session.logLine("DAC_HID|ERROR the final read failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }

        private fun authorise(request: WriteRequest, state: DacEqState, readAt: Long): GateDecision =
            gate.authorise(
                request,
                WriteContext(
                    profile, att.backupKey, state, elapsedMs() - readAt, backups.load(att.backupKey), overrideAllowsWrites(),
                    staleReason = if (setsSent == 0) stale() else null,
                    sense = sense,
                    playing = playbackActive(),
                ),
            ).also { decision ->
                session.logLine(
                    when (decision) {
                        is GateDecision.Authorised -> "DAC_HID|GATE ${request.kind} authorised (${decision.permit.frameCount} frame(s) covered" +
                            (decision.plan?.let { ", plan of ${it.steps.size} step(s)" } ?: ", no plan yet: a pointer move only") + ")"
                        is GateDecision.Refused -> "DAC_HID|GATE ${request.kind} REFUSED ${decision.refusal}: ${decision.detail}"
                    }
                )
            }

        /**
         * The gate's answer for [request] on a fresh read. When the only thing missing is the backup of a DAC that has none, it is made
         * (the state just read is saved: a read-only step) and the gate is asked again. [onRead] sees each state read before the gate does.
         */
        private fun authorised(request: WriteRequest, onRead: (DacEqState) -> Unit = {}): Pair<GateDecision, Pair<DacEqState, Long>> {
            var snapshot = read("before")
            onRead(snapshot.first)
            var decision = authorise(request, snapshot.first, snapshot.second)
            if ((decision as? GateDecision.Refused)?.refusal == GateRefusal.NO_BACKUP && backups.load(att.backupKey) == null) {
                makeBackup(snapshot.first)?.let { return it to snapshot }
                snapshot = read("after the backup")
                decision = authorise(request, snapshot.first, snapshot.second)
            }
            return decision to snapshot
        }

        /** Saves the state just read as this DAC's backup. Null when it is saved; else the refusal to report. */
        private fun makeBackup(state: DacEqState): GateDecision.Refused? {
            val created = DacEqBackup.of(att, state, wallClockMs())
                ?: return GateDecision.Refused(GateRefusal.READ_NOT_WORKING, "no backup could be made: the state is not complete")
            if (!backups.save(created)) return GateDecision.Refused(GateRefusal.NO_BACKUP, "the backup could not be stored")
            onBackupChanged(att, created)
            session.logLine("DAC_HID|BACKUP taken: active=0x%02X, ${created.slots.size} slot(s)".format(created.activePresetId))
            return null
        }

        /**
         * The second stage of an operation on the app's slot: the gate said "select it first" (its permit covers the select and the
         * way back), so select it, read what it holds, and ask again with [ask] (given the preset that was selected before). Ends with
         * a refusal (the selection put back: nothing was written) or a failure (the backup restored), or with the plan.
         * [onRead] sees the state read inside the slot before the gate does.
         */
        private fun selectAndAsk(
            first: GateDecision.Authorised,
            original: Int,
            slot: Int,
            onRead: (DacEqState) -> Unit = {},
            ask: (Int) -> WriteRequest,
        ): Selected {
            if (!selectAndVerify(first.permit, slot)) {
                return Selected.Stop(failAndRestore("slot 0x%02X could not be selected".format(slot)))
            }
            val live = read("after selecting slot 0x%02X".format(slot))
            if ((live.first.activePreset as? EqRead.Read)?.value != slot || !live.first.isComplete) {
                return Selected.Stop(failAndRestore("slot 0x%02X could not be read after selecting it".format(slot), live.first))
            }
            onRead(live.first)
            val second = authorise(ask(original), live.first, live.second)
            if (second is GateDecision.Refused) {
                // Nothing was written: only the pointer moved. Put it back (the first permit covers it).
                return Selected.Stop(
                    if (selectAndVerify(first.permit, original)) {
                        DacEqWriteResult.Refused(second.refusal, second.detail)
                    } else {
                        failAndRestore("the selection (0x%02X) could not be put back".format(original))
                    }
                )
            }
            return Selected.Ready(second as GateDecision.Authorised)
        }

        // ── confirming a SET (B31 P1.1) ──────────────────────────────────────────────────────────────────

        /** `GET 17` and compare: null when the register reads [expectedTenths]; else why not. */
        private fun readBackGain(expectedTenths: Int, why: String): String? = when (val got = FiioEqReader.readGain(session)) {
            is EqRead.Read ->
                if (got.value == expectedTenths) {
                    session.logLine("DAC_HID|READBACK pre-gain ${DacEqStateFormat.tenths(got.value)} dB ($why)")
                    null
                } else {
                    "the pre-gain reads ${DacEqStateFormat.tenths(got.value)} dB, not ${DacEqStateFormat.tenths(expectedTenths)} dB"
                }
            is EqRead.Unknown -> "the pre-gain could not be read back (${got.why})"
            is EqRead.Failed -> "the pre-gain could not be read back (${got.why})"
        }

        /** `GET 15 [i]` and compare: null when the band reads as [expected]; else why not. */
        private fun readBackBand(expected: DacEqBand, why: String): String? = when (val got = FiioEqReader.readBand(session, expected.index)) {
            is EqRead.Read ->
                if (got.value.sameAs(expected)) {
                    session.logLine("DAC_HID|READBACK band ${expected.index + 1} ${DacEqStateFormat.band(got.value)} ($why)")
                    null
                } else {
                    "band ${expected.index + 1} reads [${DacEqStateFormat.band(got.value)}], not [${DacEqStateFormat.band(expected)}]"
                }
            is EqRead.Unknown -> "band ${expected.index + 1} could not be read back (${got.why})"
            is EqRead.Failed -> "band ${expected.index + 1} could not be read back (${got.why})"
        }

        /**
         * **Confirms the SET just sent**: the DAC's own acknowledgement when it sends a usable one that says what was sent; a contrary one
         * stops here; when there is no usable one (none, garbled, only replies to other commands) the value is read back instead, and
         * the log says so. Null when confirmed; else why not.
         */
        private fun confirm(command: Int, data: ByteArray, name: String): String? {
            val ack = session.awaitSetAck(command, data, name)
            return when (ack) {
                is FiioSetAck.Confirmed -> null
                is FiioSetAck.Contrary ->
                    "the DAC acknowledged $name with [${FiioHidFrames.hex(ack.reported)}] after being sent [${FiioHidFrames.hex(ack.sent)}]"
                is FiioSetAck.Unusable, FiioSetAck.Missing -> {
                    session.logLine("DAC_HID|CONFIRM $name: no usable acknowledgement ($ack); reading the value back instead")
                    when (command) {
                        FiioHidWrites.CMD_GAIN -> readBackGain(FiioEqReader.signed16(data[0], data[1]), "instead of an acknowledgement")
                        FiioHidWrites.CMD_BAND -> {
                            val band = (FiioEqReader.decodeBand(data[0].toInt() and 0xFF, data) as EqRead.Read).value
                            readBackBand(band, "instead of an acknowledgement")
                        }
                        else -> null
                    }
                }
            }
        }

        /**
         * Sends the steps of [plan] through [permit], in order, **each confirmed before the next goes out**; at the two places where the
         * plan *relies* on an earlier SET, that SET is read back first. Null when every step went; else why it stopped (the caller recovers).
         * - a band that adds boost relies on the pre-gain this plan lowered: `GET 17` must read what was sent;
         * - a pre-gain that gives headroom back (less protective) relies on the bands this plan changed: each must read as sent.
         */
        private fun sendPlan(permit: WritePermit, plan: DacEqWritePlan): String? {
            if (opStart == null) opStart = DacEqSlotSnapshot(appSlot, plan.start.preGainTenthsDb, plan.start.bands)
            session.logLine("DAC_HID|PLAN ${plan.steps.size} step(s) from ${levels(plan.start)}")
            plan.steps.forEachIndexed { i, step -> session.logLine("DAC_HID|PLAN step ${i + 1}: ${step.name} → ${levels(step.after)}") }
            var before = plan.start
            var gainSent: Int? = null
            val bandsSent = linkedMapOf<Int, DacEqBand>()
            for (step in plan.steps) {
                val data = step.data.toByteArray()
                if (step.command == FiioHidWrites.CMD_BAND) {
                    val index = data[0].toInt() and 0xFF
                    val adds = step.after.bands[index].gainTenthsDb > before.bands[index].gainTenthsDb
                    if (adds && gainSent != null) {
                        readBackGain(gainSent, "before band ${index + 1} adds boost")?.let { return "the pre-gain this plan lowered is not confirmed: $it" }
                    }
                } else if (step.command == FiioHidWrites.CMD_GAIN) {
                    val looser = step.after.preGainTenthsDb != before.preGainTenthsDb &&
                        plan.start.sense.moreProtective(before.preGainTenthsDb, step.after.preGainTenthsDb) == before.preGainTenthsDb
                    if (looser) {
                        for ((index, band) in bandsSent) {
                            readBackBand(band, "before the pre-gain gives headroom back")?.let { return "a band this plan changed is not confirmed: $it" }
                        }
                    }
                }
                if (!send(permit, step.command, data, step.name)) return "${step.name} could not be sent"
                confirm(step.command, data, step.name)?.let { return "${step.name} was not confirmed: $it" }
                if (step.command == FiioHidWrites.CMD_GAIN) {
                    gainSent = step.after.preGainTenthsDb
                } else {
                    val index = data[0].toInt() and 0xFF
                    bandsSent[index] = step.after.bands[index]
                }
                before = step.after
            }
            check(permit.sequenceDone) { "the plan was not sent to its end" }
            return null
        }

        /** Sends every step of [plan]. Null when all went; else the failure (the recovery has run). */
        private fun execute(permit: WritePermit, plan: DacEqWritePlan): DacEqWriteResult? =
            sendPlan(permit, plan)?.let { failAndRestore(it) }

        /** `net x dB` for one confirmed sense, both readings while it is not. */
        private fun levels(live: DacEqLive): String {
            val peaks = live.netPeaksDb
            return if (live.sense == PreGainSense.UNCONFIRMED) {
                "net ${"%.2f".format(peaks[0])} dB if a positive pre-gain amplifies / ${"%.2f".format(peaks[1])} dB if it attenuates"
            } else {
                "net ${"%.2f".format(peaks[0])} dB"
            }
        }

        // ── discovery (the reviewer's step) ──────────────────────────────────────────────────────────────

        fun discoverSlot(): DacEqWriteResult {
            val (decision, snapshot) = authorised(WriteRequest(WriteKind.PEEK_SLOT, initiator, appSlot))
            if (decision is GateDecision.Refused) return DacEqWriteResult.Refused(decision.refusal, decision.detail)
            val permit = (decision as GateDecision.Authorised).permit
            val backup = backups.load(att.backupKey)!!
            val original = (snapshot.first.activePreset as EqRead.Read).value
            if (appSlot in backup.slots) {
                return DacEqWriteResult.Done(snapshot.first, "slot 0x%02X is already in the backup: nothing selected".format(appSlot))
            }
            var problem: String? = null
            val gainBefore = (snapshot.first.preGainTenthsDb as EqRead.Read).value
            if (original == appSlot) {
                rememberSnapshot(DacEqSlotSnapshot.of(snapshot.first)!!)?.let { problem = it }
            } else {
                session.logLine("DAC_HID|DISCOVER selecting slot 0x%02X: it is audible at whatever level it holds until the selection is put back".format(appSlot))
                var putBack = true
                try {
                    if (!selectAndVerify(permit, appSlot)) {
                        problem = "slot 0x%02X could not be selected".format(appSlot)
                    } else {
                        val inside = read("inside slot 0x%02X".format(appSlot)).first
                        val held = DacEqSlotSnapshot.of(inside)
                        if (held == null || held.presetId != appSlot) {
                            problem = "slot 0x%02X could not be read completely".format(appSlot)
                        } else {
                            session.logLine(
                                "DAC_HID|DISCOVER pre-gain on the original preset ${DacEqStateFormat.tenths(gainBefore)} dB, on the slot ${DacEqStateFormat.tenths(held.preGainTenthsDb)} dB " +
                                    "(equal on two presets that differ would mean the pre-gain is global, not per preset)"
                            )
                            rememberSnapshot(held)?.let { problem = it }
                        }
                    }
                } finally {
                    putBack = selectAndVerify(permit, original)
                    if (!putBack) session.logLine("DAC_HID|ERROR the selection (0x%02X) could not be put back after the look".format(original))
                }
                if (!putBack) return failAndRestore("the selection (0x%02X) could not be put back after the look".format(original))
            }
            val after = read("after the look").first.also { logState(session, it) }
            problem?.let { return DacEqWriteResult.Failed(it, restored = null, state = after) }
            return DacEqWriteResult.Done(after, "slot 0x%02X added to the backup; selection back on 0x%02X".format(appSlot, original))
        }

        /**
         * Adds [snapshot] to the backup when it is not there (a slot's first look is its factory content and is never overwritten) and
         * says what it holds. Null when done; else the problem.
         */
        private fun rememberSnapshot(snapshot: DacEqSlotSnapshot): String? {
            val backup = backups.load(att.backupKey) ?: return "the backup disappeared"
            val net = DacEqLive.of(snapshot, sense).netPeakDb
            val known = backup.slots[snapshot.presetId]
            if (known != null) {
                session.logLine(
                    "DAC_HID|BACKUP slot 0x%02X already in the backup (%s)".format(
                        snapshot.presetId, if (known.sameContentAs(snapshot)) "same content" else "the DAC holds something else now: the backup keeps the first look",
                    )
                )
                return null
            }
            val updated = backup.withSlot(snapshot)
            if (!backups.update(updated)) return "the slot could not be added to the backup"
            onBackupChanged(att, updated)
            session.logLine("DAC_HID|BACKUP slot 0x%02X added".format(snapshot.presetId))
            if (net > 0.0) {
                session.logLine(
                    "DAC_HID|WARNING slot 0x%02X holds a curve that boosts by %.1f dB net (%s): it was audible while it was selected, and the app will not select it again unless it takes it over"
                        .format(snapshot.presetId, net, if (sense == PreGainSense.UNCONFIRMED) "on the worse of the two readings of the pre-gain" else "on the $sense reading")
                )
            }
            return null
        }

        // ── the operations ───────────────────────────────────────────────────────────────────────────────

        fun applyCurve(bands: List<DacEqBand>): DacEqWriteResult {
            val request = WriteRequest(WriteKind.APPLY_CURVE, initiator, appSlot, bands)
            val (first, snapshot) = authorised(request)
            if (first is GateDecision.Refused) return DacEqWriteResult.Refused(first.refusal, first.detail)
            first as GateDecision.Authorised
            var authorisation = first
            if (first.plan == null) {
                val original = (snapshot.first.activePreset as EqRead.Read).value
                when (val selected = selectAndAsk(first, original, appSlot) { request }) {
                    is Selected.Stop -> return selected.result
                    is Selected.Ready -> authorisation = selected.authorisation
                }
            }
            val plan = authorisation.plan!!
            val permit = authorisation.permit
            val preGain = plan.target.preGainTenthsDb
            val expected = DacEqSlotSnapshot(appSlot, preGain, bands)
            session.logLine("DAC_HID|WRITE slot 0x%02X pre-gain %s dB, %d band(s)".format(appSlot, DacEqStateFormat.tenths(preGain), bands.size))
            execute(permit, plan)?.let { return it }
            if (plan.steps.isNotEmpty()) {
                pause(SAVE_LEAD_MS)
                if (!sendSave(permit, appSlot)) return failAndRestore("the save could not be sent")
                pause(SAVE_TAIL_MS)
            }
            return verify(expected, permitPointer = appSlot, note = if (plan.steps.isNotEmpty()) "written, saved and read back equal" else "already as asked, read back equal")
        }

        fun setBand(bandIndex: Int, gainDb: Double): DacEqWriteResult {
            if (bandIndex !in 0 until profile.bandCount) {
                return DacEqWriteResult.Refused(GateRefusal.OUT_OF_LIMITS, "band ${bandIndex + 1} does not exist (1..${profile.bandCount})")
            }
            // The base is the app's slot as it is meant to be: a slot the app has taken over is flat; any other is the backup's copy of
            // it. The backup (and the slot in it) comes first. It is made here when the DAC has none (every other condition is asked
            // first); the slot is **not** looked at here: it is in the backup or this is refused. The request below is the gate's plain
            // "is this DAC readable and may it be written".
            val (first, _) = authorised(WriteRequest(WriteKind.RESTORE, initiator))
            if (first is GateDecision.Refused) return DacEqWriteResult.Refused(first.refusal, first.detail)
            val backup = backups.load(att.backupKey)
                ?: return DacEqWriteResult.Refused(GateRefusal.NO_BACKUP, "the backup is not there")
            val base = backup.slots[appSlot]
                ?: return DacEqWriteResult.Refused(
                    GateRefusal.SLOT_NOT_INSPECTED,
                    "slot 0x%02X is not in the backup: it has never been looked at (take it over, or run the reviewer's discover step)".format(appSlot),
                )
            val bands = (if (backup.owns(appSlot)) DacEqDefaults.flatBands(profile.bandCount) else base.bands.map(DacEqDefaults::writable)).toMutableList()
            bands[bandIndex] = bands[bandIndex].copy(gainTenthsDb = (gainDb * 10).roundToInt())
            return applyCurve(bands)
        }

        fun closeEq(): DacEqWriteResult {
            val close = profile.disabledPresetId ?: return DacEqWriteResult.Refused(GateRefusal.NOT_USER_SLOT, "the model has no Close EQ preset")
            val (decision, state) = authorised(WriteRequest(WriteKind.CLOSE_EQ, initiator))
            if (decision is GateDecision.Refused) return DacEqWriteResult.Refused(decision.refusal, decision.detail)
            val permit = (decision as GateDecision.Authorised).permit
            if ((state.first.activePreset as EqRead.Read).value != close && !selectAndVerify(permit, close)) {
                return failAndRestore("the DAC would not close its EQ")
            }
            val after = read("after closing").first.also { logState(session, it) }
            return if ((after.activePreset as? EqRead.Read)?.value == close) {
                DacEqWriteResult.Done(after, "EQ closed (preset 0x%02X)".format(close))
            } else {
                failAndRestore("the DAC does not report the Close EQ preset", after)
            }
        }

        fun roundtrip(): DacEqWriteResult {
            val request = WriteRequest(WriteKind.ROUNDTRIP, initiator, appSlot)
            val (first, snapshot) = authorised(request)
            if (first is GateDecision.Refused) return DacEqWriteResult.Refused(first.refusal, first.detail)
            first as GateDecision.Authorised
            val originalPreset = (snapshot.first.activePreset as EqRead.Read).value
            var authorisation = first
            var returnTo: Int? = null
            if (first.plan == null) {
                when (val selected = selectAndAsk(first, originalPreset, appSlot) { original -> request.copy(returnTo = original) }) {
                    is Selected.Stop -> return selected.result
                    is Selected.Ready -> authorisation = selected.authorisation
                }
                returnTo = originalPreset
            }
            val permit = authorisation.permit
            val plan = authorisation.plan!!
            // The whole stored state, every frame, whether or not it differs: that is the point of a roundtrip. What is "stored" is what the
            // gate planned toward: the backup's copy of a slot the app has not taken over, what the slot holds now for one it has.
            val stored = DacEqSlotSnapshot(appSlot, plan.target.preGainTenthsDb, plan.target.bands)
            execute(permit, plan)?.let { return it }
            pause(SAVE_LEAD_MS)
            if (!sendSave(permit, appSlot)) return failAndRestore("the save could not be sent")
            pause(SAVE_TAIL_MS)
            val result = verify(stored, permitPointer = appSlot, note = "the stored state of slot 0x%02X written back and read back equal".format(appSlot))
            if (result !is DacEqWriteResult.Done) return result
            // Put the selection back where it was.
            if (returnTo != null && !selectAndVerify(permit, returnTo)) {
                return failAndRestore("the selection (0x%02X) could not be put back".format(returnTo))
            }
            val after = read("after the roundtrip").first.also { logState(session, it) }
            return DacEqWriteResult.Done(after, result.note)
        }

        fun restore(reason: String): DacEqWriteResult {
            // A restore needs a backup that already exists: it does not make one from whatever the DAC holds now.
            val snapshot = read("before the restore")
            val first = authorise(WriteRequest(WriteKind.RESTORE, initiator), snapshot.first, snapshot.second)
            if (first is GateDecision.Refused) return DacEqWriteResult.Refused(first.refusal, first.detail)
            val backup = backups.load(att.backupKey)!!
            session.logLine("DAC_HID|RESTORE ($reason): ${backup.slots.size} slot(s) in the backup, selection 0x%02X".format(backup.activePresetId))
            var slotProblem: DacEqWriteResult.Refused? = null
            for ((id, saved) in backup.slots) {
                // Only the slot the app writes can have been changed by the app (the inventory puts the others in the backup too).
                if (id != appSlot) continue
                if (backup.owns(id)) {
                    // The backup holds this slot's factory curve; the app's own slot goes back to what it held when this operation began.
                    val began = opStart
                    if (began == null) {
                        session.logLine("DAC_HID|RESTORE slot 0x%02X is the app's: left as it is (restore factory curve is its own step)".format(id))
                        continue
                    }
                    restoreSlot(id, began, content = began)?.let { if (it is DacEqWriteResult.Refused) slotProblem = it else return it }
                } else {
                    restoreSlot(id, saved, content = null)?.let { if (it is DacEqWriteResult.Refused) slotProblem = it else return it }
                }
            }
            val fresh = read("before the selection is put back")
            val pointer = authorise(WriteRequest(WriteKind.RESTORE, initiator), fresh.first, fresh.second)
            if (pointer is GateDecision.Refused) return DacEqWriteResult.Refused(pointer.refusal, pointer.detail)
            val current = (fresh.first.activePreset as? EqRead.Read)?.value
            if (current != backup.activePresetId &&
                !selectAndVerify((pointer as GateDecision.Authorised).permit, backup.activePresetId)
            ) {
                return DacEqWriteResult.Failed("the selection (0x%02X) could not be put back".format(backup.activePresetId), restored = false, state = null)
            }
            val after = read("after the restore").first.also { logState(session, it) }
            val back = (after.activePreset as? EqRead.Read)?.value == backup.activePresetId
            slotProblem?.let { problem ->
                // The gate refused to write that slot back (nothing of it was sent): the selection is back where it was, and the
                // slot is not what the backup holds. Said, not reported as a restore.
                return DacEqWriteResult.Failed(
                    "slot 0x%02X was not written back (${problem.refusal}: ${problem.detail}); the selection is back on 0x%02X".format(appSlot, backup.activePresetId),
                    restored = false, state = after,
                )
            }
            return if (back) {
                DacEqWriteResult.Done(after, "backup written back; selection 0x%02X".format(backup.activePresetId))
            } else {
                DacEqWriteResult.Failed("the DAC does not report selection 0x%02X after the restore".format(backup.activePresetId), restored = false, state = after)
            }
        }

        /**
         * One slot back to [saved]: select it when it is not the selected one, plan from what it holds, send (each step confirmed),
         * compare. [content]: what a slot the app owns goes back to (the gate does not take it from the backup). Null when done.
         */
        private fun restoreSlot(id: Int, saved: DacEqSlotSnapshot, content: DacEqSlotSnapshot?): DacEqWriteResult? {
            val request = WriteRequest(WriteKind.RESTORE, initiator, id, content = content)
            val snapshot = read("before restoring slot 0x%02X".format(id))
            val first = authorise(request, snapshot.first, snapshot.second)
            if (first is GateDecision.Refused) {
                if (first.refusal == GateRefusal.SLOT_UNSAFE_TO_SELECT) {
                    // The backup says this slot plays a boost: the app never selected it, so it never wrote it either. Left alone.
                    session.logLine("DAC_HID|RESTORE slot 0x%02X left alone: ${first.detail}".format(id))
                    return null
                }
                return DacEqWriteResult.Refused(first.refusal, first.detail)
            }
            first as GateDecision.Authorised
            var authorisation = first
            if (first.plan == null) {
                val original = (snapshot.first.activePreset as EqRead.Read).value
                if (!selectAndVerify(first.permit, id)) {
                    return DacEqWriteResult.Failed("slot 0x%02X could not be selected".format(id), restored = false, state = null)
                }
                val live = read("inside slot 0x%02X".format(id))
                if ((live.first.activePreset as? EqRead.Read)?.value != id || !live.first.isComplete) {
                    return DacEqWriteResult.Failed("slot 0x%02X could not be read".format(id), restored = false, state = live.first)
                }
                val second = authorise(request, live.first, live.second)
                if (second is GateDecision.Refused) {
                    selectAndVerify(first.permit, original)
                    return DacEqWriteResult.Refused(second.refusal, second.detail)
                }
                authorisation = second as GateDecision.Authorised
            }
            val plan = authorisation.plan!!
            val permit = authorisation.permit
            if (plan.steps.isEmpty()) {
                session.logLine("DAC_HID|RESTORE slot 0x%02X already holds its backup".format(id))
                return null
            }
            sendPlan(permit, plan)?.let { why ->
                // A rollback has no rollback: it stops, and the writer tells the labels what the DAC reads now (or that it is unknown).
                session.logLine("DAC_HID|ERROR the rollback stopped: $why")
                return DacEqWriteResult.Failed(why, restored = false, state = stateAfter)
            }
            pause(SAVE_LEAD_MS)
            if (!sendSave(permit, id)) return DacEqWriteResult.Failed("the save could not be sent", restored = false, state = stateAfter)
            pause(SAVE_TAIL_MS)
            val check = DacEqSlotSnapshot.of(read("after restoring slot 0x%02X".format(id)).first)
            if (check == null || !check.sameEffectAs(saved.writable())) {
                return DacEqWriteResult.Mismatch(describeSnapshot(saved), check?.let(::describeSnapshot) ?: "unreadable", restored = false, state = null)
            }
            return null
        }

        // ── B31 P1.3 / P1.4 / P1.2: take over, give back, inventory, select, listen ─────────────────────

        /** The slot's first look is its factory curve: when this operation is inside it and the backup does not hold it, it goes in now. */
        private fun rememberFactoryIfInside(state: DacEqState) {
            val backup = backups.load(att.backupKey) ?: return
            if (appSlot in backup.slots) return
            if ((state.activePreset as? EqRead.Read)?.value != appSlot) return
            DacEqSlotSnapshot.of(state)?.let { rememberSnapshot(it) }
        }

        fun takeOver(): DacEqWriteResult {
            val request = WriteRequest(WriteKind.TAKE_OVER, initiator, appSlot)
            val (first, snapshot) = authorised(request, ::rememberFactoryIfInside)
            if (first is GateDecision.Refused) return DacEqWriteResult.Refused(first.refusal, first.detail)
            first as GateDecision.Authorised
            val original = (snapshot.first.activePreset as EqRead.Read).value
            var authorisation = first
            if (first.plan == null) {
                session.logLine("DAC_HID|TAKE_OVER selecting slot 0x%02X: it is audible at whatever level it holds (playback is paused)".format(appSlot))
                when (val selected = selectAndAsk(first, original, appSlot, ::rememberFactoryIfInside) { request.copy(returnTo = it) }) {
                    is Selected.Stop -> return selected.result
                    is Selected.Ready -> authorisation = selected.authorisation
                }
            }
            val plan = authorisation.plan!!
            val permit = authorisation.permit
            session.logLine("DAC_HID|TAKE_OVER from ${levels(plan.start)} to a flat curve with the pre-gain at 0 (neutral whatever the sense is)")
            val flat = DacEqSlotSnapshot(appSlot, 0, plan.target.bands)
            execute(permit, plan)?.let { return it }
            if (plan.steps.isNotEmpty()) {
                pause(SAVE_LEAD_MS)
                if (!sendSave(permit, appSlot)) return failAndRestore("the save could not be sent")
                pause(SAVE_TAIL_MS)
            }
            val verified = verify(flat, permitPointer = appSlot, note = "slot 0x%02X taken over: flat, pre-gain 0, saved and read back".format(appSlot))
            if (verified !is DacEqWriteResult.Done) return verified
            if (original != appSlot && !selectAndVerify(permit, original)) {
                return failAndRestore("the selection (0x%02X) could not be put back".format(original))
            }
            val after = read("after the take-over").first.also { logState(session, it) }
            return markOwned(true, after, verified.note)
        }

        /**
         * Where the selection goes when the factory curve is back and the app's slot was the selected preset (B31): **off the slot.** What it
         * holds then is the factory curve, which plays louder than the limit under either reading of the pre-gain (+10.7 dB on the owner's
         * USER 3 if the register amplifies), and the next press of play would be the first thing anyone hears of it. It is the preset the DAC
         * had when the backup was taken if that one does not boost, else the model's own "Close EQ".
         */
        private fun restingPreset(): Int? {
            val backup = backups.load(att.backupKey) ?: return profile.disabledPresetId
            val taken = backup.activePresetId
            val held = backup.slots[taken]
            val safe = taken != appSlot && held != null && DacEqLive.of(held, sense).netPeakDb <= 0.0
            return if (safe) taken else profile.disabledPresetId
        }

        fun restoreFactory(): DacEqWriteResult {
            val resting = restingPreset()
            val request = WriteRequest(WriteKind.RESTORE_FACTORY, initiator, appSlot, returnTo = resting)
            val (first, snapshot) = authorised(request)
            if (first is GateDecision.Refused) return DacEqWriteResult.Refused(first.refusal, first.detail)
            first as GateDecision.Authorised
            val original = (snapshot.first.activePreset as EqRead.Read).value
            var authorisation = first
            if (first.plan == null) {
                when (val selected = selectAndAsk(first, original, appSlot) { request.copy(returnTo = it) }) {
                    is Selected.Stop -> return selected.result
                    is Selected.Ready -> authorisation = selected.authorisation
                }
            }
            val plan = authorisation.plan!!
            val permit = authorisation.permit
            val factory = DacEqSlotSnapshot(appSlot, plan.target.preGainTenthsDb, plan.target.bands)
            session.logLine("DAC_HID|RESTORE_FACTORY slot 0x%02X: writing its factory curve back (pre-gain %s dB)".format(appSlot, DacEqStateFormat.tenths(factory.preGainTenthsDb)))
            execute(permit, plan)?.let { return it }
            if (plan.steps.isNotEmpty()) {
                pause(SAVE_LEAD_MS)
                if (!sendSave(permit, appSlot)) return failAndRestore("the save could not be sent")
                pause(SAVE_TAIL_MS)
            }
            val verified = verify(factory, permitPointer = appSlot, note = "slot 0x%02X holds its factory curve again".format(appSlot))
            if (verified !is DacEqWriteResult.Done) return verified
            if (original != appSlot) {
                if (!selectAndVerify(permit, original)) return failAndRestore("the selection (0x%02X) could not be put back".format(original))
            } else if (resting != null) {
                session.logLine("DAC_HID|RESTORE_FACTORY the slot was the selected preset: moving the selection off the factory curve, to 0x%02X".format(resting))
                if (!selectAndVerify(permit, resting)) {
                    return failAndRestore("the selection could not be moved off the factory curve (0x%02X)".format(resting))
                }
            }
            val after = read("after the factory curve is back").first.also { logState(session, it) }
            return markOwned(false, after, verified.note)
        }

        /** Records (or lets go of) the app's ownership of its slot, after everything else worked. */
        private fun markOwned(owned: Boolean, after: DacEqState, note: String): DacEqWriteResult {
            val backup = backups.load(att.backupKey) ?: return DacEqWriteResult.Failed("the backup disappeared", restored = null, state = after)
            val updated = if (owned) backup.withOwned(appSlot) else backup.withoutOwned(appSlot)
            if (!backups.update(updated)) {
                return DacEqWriteResult.Failed(
                    "the slot was changed but the backup could not record that it is ${if (owned) "the app's" else "given back"}", restored = null, state = after,
                )
            }
            onBackupChanged(att, updated)
            session.logLine("DAC_HID|BACKUP slot 0x%02X is ${if (owned) "the app's now" else "given back"}".format(appSlot))
            return DacEqWriteResult.Done(after, note)
        }

        fun inventory(): DacEqWriteResult {
            val (decision, snapshot) = authorised(WriteRequest(WriteKind.INVENTORY, initiator))
            if (decision is GateDecision.Refused) return DacEqWriteResult.Refused(decision.refusal, decision.detail)
            val permit = (decision as GateDecision.Authorised).permit
            val original = (snapshot.first.activePreset as EqRead.Read).value
            var problem: String? = null
            var putBack = true
            session.logLine("DAC_HID|INVENTORY ${profile.slots.size} preset(s): select, read, and the selection back on 0x%02X; no gain, band or save is sent".format(original))
            try {
                for (slot in profile.slots) {
                    val state = if (slot.id == original) {
                        snapshot.first
                    } else {
                        if (!selectAndVerify(permit, slot.id)) {
                            problem = "preset 0x%02X (${slot.name}) could not be selected".format(slot.id)
                            break
                        }
                        read("inventory 0x%02X".format(slot.id)).first
                    }
                    val held = DacEqSlotSnapshot.of(state)
                    if (held == null || held.presetId != slot.id) {
                        problem = "preset 0x%02X (${slot.name}) could not be read completely".format(slot.id)
                        break
                    }
                    DacEqStateFormat.lines(state, "DAC_HID|INVENTORY 0x%02X ${slot.name}".format(slot.id)).forEach(session::logLine)
                    rememberSnapshot(held)?.let { problem = it }
                    if (problem != null) break
                }
            } finally {
                putBack = (stateAfter?.activePreset as? EqRead.Read)?.value == original || selectAndVerify(permit, original)
                if (!putBack) session.logLine("DAC_HID|ERROR the selection (0x%02X) could not be put back after the inventory".format(original))
            }
            if (!putBack) return failAndRestore("the selection (0x%02X) could not be put back after the inventory".format(original))
            val after = read("after the inventory").first.also { logState(session, it) }
            problem?.let { return DacEqWriteResult.Failed(it, restored = null, state = after) }
            return DacEqWriteResult.Done(after, "${profile.slots.size} preset(s) read and kept in the backup; selection back on 0x%02X".format(original))
        }

        fun selectPreset(presetId: Int, confirmed: Boolean): DacEqWriteResult {
            val (decision, snapshot) = authorised(WriteRequest(WriteKind.SELECT_PRESET, initiator, presetId = presetId, confirmed = confirmed))
            if (decision is GateDecision.Refused) return DacEqWriteResult.Refused(decision.refusal, decision.detail)
            val permit = (decision as GateDecision.Authorised).permit
            val current = (snapshot.first.activePreset as EqRead.Read).value
            if (current != presetId && !selectAndVerify(permit, presetId)) return failAndRestore("preset 0x%02X could not be selected".format(presetId))
            val after = read("after selecting 0x%02X".format(presetId)).first.also { logState(session, it) }
            return DacEqWriteResult.Done(after, "preset 0x%02X selected".format(presetId))
        }

        fun listeningCheck(lowLevel: Boolean, holdMs: Long): DacEqWriteResult {
            val request = WriteRequest(WriteKind.LISTENING_CHECK, initiator, appSlot, confirmed = lowLevel)
            val (decision, snapshot) = authorised(request)
            if (decision is GateDecision.Refused) return DacEqWriteResult.Refused(decision.refusal, decision.detail)
            val permit = (decision as GateDecision.Authorised).permit
            val original = (snapshot.first.activePreset as EqRead.Read).value
            if (original != appSlot) {
                if (!selectAndVerify(permit, appSlot)) return failAndRestore("slot 0x%02X could not be selected".format(appSlot))
                val inside = read("inside slot 0x%02X".format(appSlot))
                val held = DacEqSlotSnapshot.of(inside.first)
                if (held == null || held.preGainTenthsDb != 0 || held.bands.any { !it.isInert }) {
                    val why = "slot 0x%02X is not flat with the pre-gain at 0 (it holds ${held?.let(::describeSnapshot) ?: "something unreadable"})".format(appSlot)
                    return if (selectAndVerify(permit, original)) DacEqWriteResult.Refused(GateRefusal.SLOT_NOT_TAKEN_OVER, why) else failAndRestore(why)
                }
            }
            session.logLine("DAC_HID|LISTEN the app's slot is flat. Pre-gain −6.0 dB (A), 0 (neutral), +6.0 dB (B), 0, each for ${holdMs / 1000} s. Which is quieter than neutral?")
            val steps = listOf("A" to -DacEqWriteGate.LISTENING_CHECK_TENTHS, "neutral" to 0, "B" to DacEqWriteGate.LISTENING_CHECK_TENTHS, "neutral" to 0)
            for ((label, tenths) in steps) {
                val data = FiioHidWrites.encodeGain(tenths)
                val why = if (!send(permit, FiioHidWrites.CMD_GAIN, data, "pre-gain")) {
                    "the pre-gain could not be sent"
                } else {
                    confirm(FiioHidWrites.CMD_GAIN, data, "pre-gain")
                }
                if (why != null) return neutraliseAfterListening(permit, original, why)
                session.logLine("DAC_HID|LISTEN $label: pre-gain ${DacEqStateFormat.tenths(tenths)} dB now")
                pause(holdMs)
            }
            // Still inside the slot: the register must read 0 and the bands flat before the pointer goes back.
            val inside = DacEqSlotSnapshot.of(read("end of the listening check").first)
            if (inside == null || inside.preGainTenthsDb != 0 || inside.bands.any { !it.isInert }) {
                return neutraliseAfterListening(permit, original, "the pre-gain does not read 0 at the end of the listening check")
            }
            if (original != appSlot && !selectAndVerify(permit, original)) {
                return failAndRestore("the selection (0x%02X) could not be put back".format(original))
            }
            val after = read("after the listening check").first.also { logState(session, it) }
            return DacEqWriteResult.Done(after, "A was −6.0 dB and B was +6.0 dB of pre-gain; the pre-gain is back at 0 (nothing was saved)")
        }

        /** The listening check stopped half way: the pre-gain goes back to 0 (confirmed), then the selection. */
        private fun neutraliseAfterListening(permit: WritePermit, original: Int, why: String): DacEqWriteResult {
            session.logLine("DAC_HID|FAILED $why")
            val data = FiioHidWrites.encodeGain(0)
            val neutral = send(permit, FiioHidWrites.CMD_GAIN, data, "pre-gain") && confirm(FiioHidWrites.CMD_GAIN, data, "pre-gain") == null
            session.logLine("DAC_HID|LISTEN neutralised: ${if (neutral) "the pre-gain is back at 0" else "COULD NOT confirm the pre-gain is back at 0; read the DAC"}")
            if (neutral && original != appSlot) selectAndVerify(permit, original)
            return DacEqWriteResult.Failed(why, restored = neutral, state = null)
        }

        // ── pieces ───────────────────────────────────────────────────────────────────────────────────────

        /** The read-back: the whole state read again and compared with what was asked. A mismatch restores. */
        private fun verify(expected: DacEqSlotSnapshot, permitPointer: Int, note: String): DacEqWriteResult {
            val after = read("read-back").first.also { logState(session, it) }
            val actual = DacEqSlotSnapshot.of(after)
            val selected = (after.activePreset as? EqRead.Read)?.value
            if (actual != null && selected == permitPointer && actual.sameEffectAs(expected)) {
                return DacEqWriteResult.Done(after, note)
            }
            val mismatch = "expected [${describeSnapshot(expected)}], the DAC reports [${actual?.let(::describeSnapshot) ?: "an incomplete state"}]" +
                " (selected ${selected?.let { "0x%02X".format(it) } ?: "?"})"
            session.logLine("DAC_HID|MISMATCH $mismatch")
            val restored = restoreAfterFailure()
            return DacEqWriteResult.Mismatch(describeSnapshot(expected), actual?.let(::describeSnapshot) ?: "incomplete", restored, after)
        }

        private fun failAndRestore(why: String, state: DacEqState? = null): DacEqWriteResult {
            session.logLine("DAC_HID|FAILED $why")
            val restored = restoreAfterFailure()
            return DacEqWriteResult.Failed(why, restored, state)
        }

        /**
         * B30-fix1 H3: **the one recovery path.** A returned failure, a mismatch and an exception all end here once a SET has gone out:
         * the backup is written back while the transport still works. True when the DAC reads as the backup says afterwards. Whatever
         * it came to, [stateAfter] is the read taken after the last SET or null, and the writer tells the labels from that.
         */
        fun recoverAfterException(error: Exception): DacEqWriteResult {
            val why = "${error.javaClass.simpleName}: ${error.message}"
            // A USB error or a bug in the middle of a write: the state of the DAC is not known, so say so and put the backup back.
            session.logLine("DAC_HID|ERROR $why")
            if (setsSent == 0) return DacEqWriteResult.Failed(why, restored = null, state = null)
            val restored = restoreAfterFailure()
            return DacEqWriteResult.Failed(why, restored, stateAfter)
        }

        /** Gated like everything else; true when the DAC reads as the backup says afterwards. */
        private fun restoreAfterFailure(): Boolean {
            val result = try {
                // Only the pointer moved (no gain, band or save went out): there is no content to put back, and the slot is not
                // selected again just to find that out. The selection goes back to where it was.
                if (contentSent == 0) undoSelection() else restore(reason = "after a failure")
            } catch (error: Exception) {
                session.logLine("DAC_HID|ERROR the restore failed: ${error.javaClass.simpleName}: ${error.message}")
                return false
            }
            session.logLine("DAC_HID|RESTORE after a failure: ${describe(result)}")
            return result is DacEqWriteResult.Done
        }

        /** Puts the pointer back on the preset this operation found selected. Done when the DAC reports it. */
        private fun undoSelection(): DacEqWriteResult {
            val target = startPreset ?: return DacEqWriteResult.Failed("the starting selection is not known", restored = false, state = null)
            val fresh = read("before the selection is undone")
            val decision = authorise(WriteRequest(WriteKind.RESTORE, initiator, returnTo = target), fresh.first, fresh.second)
            if (decision is GateDecision.Refused) return DacEqWriteResult.Refused(decision.refusal, decision.detail)
            val current = (fresh.first.activePreset as? EqRead.Read)?.value
            if (current != target && !selectAndVerify((decision as GateDecision.Authorised).permit, target)) {
                return DacEqWriteResult.Failed("the selection (0x%02X) could not be put back".format(target), restored = false, state = null)
            }
            val after = read("after the selection is undone").first.also { logState(session, it) }
            return if ((after.activePreset as? EqRead.Read)?.value == target) {
                DacEqWriteResult.Done(after, "selection back on 0x%02X".format(target))
            } else {
                DacEqWriteResult.Failed("the DAC does not report selection 0x%02X".format(target), restored = false, state = after)
            }
        }

        private fun selectAndVerify(permit: WritePermit, id: Int): Boolean {
            if (!send(permit, FiioHidWrites.CMD_PRESET, FiioHidWrites.encodePreset(id), "select 0x%02X".format(id))) {
                return false
            }
            // The acknowledgement is read and logged (B31 P1.1); the select is confirmed by the DAC reporting the preset, below.
            session.awaitSetAck(FiioHidWrites.CMD_PRESET, FiioHidWrites.encodePreset(id), "select 0x%02X".format(id))
            repeat(SELECT_POLLS) {
                pause(SELECT_POLL_MS)
                if (FiioEqReader.readPreset(session).valueOrNull == id) return true
            }
            session.logLine("DAC_HID|the DAC never reported preset 0x%02X".format(id))
            return false
        }

        private fun sendSave(permit: WritePermit, slot: Int): Boolean {
            if (permit.saveForm == FiioSaveForm.NONE) {
                session.logLine("DAC_HID|no save sent for slot 0x%02X (save=none: the reviewer's unplug experiment)".format(slot))
                return true
            }
            val data = if (permit.saveForm == FiioSaveForm.WITH_SLOT) FiioHidWrites.encodePreset(slot) else ByteArray(0)
            if (!send(permit, profile.saveCommand, data, "save 0x%02X".format(slot))) return false
            // Read and logged, not depended on: the KA15's acknowledgement of a save is a bare counter (`00 EE 00 SEQ`), and the read-back
            // that follows compares the content.
            session.awaitSetAck(profile.saveCommand, data, "save 0x%02X".format(slot))
            return true
        }
    }

    private fun describeSnapshot(snapshot: DacEqSlotSnapshot): String =
        "pre-gain ${DacEqStateFormat.tenths(snapshot.preGainTenthsDb)} dB; " +
            snapshot.bands.joinToString("; ") { "${it.index + 1}: ${DacEqStateFormat.band(it)}" }

    companion object {
        /** The DAC needs a moment after the last band and before it is read again (WolfEQ waits 350 ms after a save, DevicePEQ 100 ms before). */
        const val SAVE_LEAD_MS = 100L
        const val SAVE_TAIL_MS = 350L
        const val SELECT_POLL_MS = 150L
        const val SELECT_POLLS = 8

        /** How long each state of the listening check is held: long enough to hear it, short enough to be one sitting. */
        const val LISTEN_HOLD_MS = 5_000L

        val DEFAULT_FREQUENCIES: IntArray get() = DacEqDefaults.FREQUENCIES
        const val DEFAULT_Q = DacEqDefaults.Q
    }
}
