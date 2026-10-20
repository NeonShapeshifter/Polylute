package com.lostf1sh.pixelplayeross.data.volume

import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind

/** Why a Safety Max override stopped. Logged, and the whole test matrix is written in these. */
enum class SafetyOverrideEnd {
    /** The listener brought the level back to (or under) the ceiling. */
    LOWERED,

    /** Nothing played for [SafetyOverridePolicy.IDLE_END_MS]. */
    IDLE,

    /** The DAC the override was given on is no longer attached (unplugged, or re-enumerated). */
    DAC_GONE,

    /** The output stayed something else for longer than the grace of a stream rebuild. */
    OUTPUT_CHANGED,

    /** Another headphone profile is selected (or none). */
    PROFILE_CHANGED,

    /** Safety Max was switched off for the profile. */
    SAFETY_OFF,

    /** The ceiling this override was given against was recalibrated, or removed. */
    CALIBRATION_CHANGED,
}

/**
 * One temporary override of the Safety Max ceiling: what it was given on, and the little state it
 * needs to know when to stop (FIX-4).
 *
 * It used to be a boolean that any change of profile, output context or backend kind cleared. In USB
 * Direct a track change that changes the sample rate rebuilds the stream, and for a quarter of a
 * second the controller sees another backend kind and another context (the sink's internal delegate
 * is routed to the speaker, "Delegate routed to speaker" in the log). That flicker cost the owner the
 * override at the first rate change. The override now belongs to *an output*, not to an instant:
 * [profileId] + [contextId] + [kind], the calibration it was granted against, and the physical
 * attachment of the DAC ([attachment]).
 */
data class SafetyOverrideSession(
    val profileId: String,
    val contextId: String,
    val kind: SafetyVolumeBackendKind,
    /** Identity of the calibration the override was given against: a recalibration ends it. */
    val calibratedAtEpochMs: Long,
    val ceilingRaw: Int,
    /**
     * The connection token of the DAC it was given on ([SafetyOverridePolicy.attachmentOf]); null on
     * an output that is not a USB DAC. A DAC that is unplugged, or replugged (its path changes), is
     * a new attachment: it may be other headphones.
     */
    val attachment: String?,
    /** Last moment playback was known to be running, on the elapsed-realtime clock (it counts sleep). */
    val lastActiveAtMs: Long,
    /** The level has been above the ceiling at some point: only then does "back at the ceiling" end it. */
    val peaked: Boolean = false,
    /** Since when the backend has not been the one the override was given on; null while it is. */
    val mismatchSinceMs: Long? = null,
    /** How long a mismatch is a rebuild rather than a change. */
    val graceMs: Long = SafetyOverridePolicy.TRANSIENT_GRACE_MS,
)

/** What the controller can see at one instant. Everything here is already published state. */
data class SafetyOverrideObservation(
    val nowMs: Long,
    /** The selected headphone profile, if any. */
    val profileId: String?,
    val safetyEnabled: Boolean,
    /** The selected profile's stored calibration for the *session's* context and kind, not the backend's. */
    val calibration: SafetyMaxCalibration?,
    val contextId: String?,
    val kind: SafetyVolumeBackendKind,
    val rawLevel: Int,
    /** The ceiling enforced on the backend that is current now (null when it has none). */
    val ceilingRaw: Int?,
    /** Connection tokens of every USB audio device attached right now. */
    val attachments: Set<String>,
    val playing: Boolean,
    /**
     * B12a-8 #3: the stores this observation is built from have delivered their first real read. After a restart the
     * headphone profiles are an empty list until the DataStore answers, and "no profile selected" in that state is not a
     * change of profile. False means the profile, the Safety flag and the calibration above say nothing yet.
     */
    val contextLoaded: Boolean = true,
)

sealed interface SafetyOverrideVerdict {
    /** The override applies: the ceiling is not enforced on this output. */
    data class Active(val session: SafetyOverrideSession) : SafetyOverrideVerdict

    /**
     * Kept, not applied: the backend is not (yet) the one it was given on. The ceiling of whatever is
     * current applies as if there were no override, so a flicker can only ever be *safer*.
     */
    data class Suspended(val session: SafetyOverrideSession) : SafetyOverrideVerdict

    data class Ended(val reason: SafetyOverrideEnd) : SafetyOverrideVerdict
}

/** The session as it was written to disk, with the boot it belongs to. */
data class SafetyOverrideRecord(val session: SafetyOverrideSession, val bootCount: Int)

/**
 * FIX-4: when a temporary Safety Max override lasts, and when it ends. Pure: no clock, no Android.
 *
 * The ceiling, and the gesture that lifts it (three blocked Volume-Up presses and a confirmation),
 * are untouched; only the *duration* changed, from "until anything flickers" to:
 *
 * **Ends when** the listener lowers the level to the ceiling ([SafetyOverrideEnd.LOWERED]); nothing
 * has played for [IDLE_END_MS] ([SafetyOverrideEnd.IDLE]); the output really changed (DAC gone, other
 * profile, a route that stays different past [TRANSIENT_GRACE_MS]); Safety Max is switched off; the
 * calibration changes.
 *
 * **Does not end with** a track change, a rate or bit-depth change, a stream rebuild of the same DAC,
 * a pause shorter than [IDLE_END_MS], or locking the screen (none of those is in the observation, by
 * design), and it survives a restart of the app inside the idle window ([restore]).
 */
object SafetyOverridePolicy {
    /**
     * Idle window: no playback for this long ends the override. 15 minutes is the owner's figure, and
     * it is the right order of magnitude: a coffee, a call or changing the record is shorter, so the
     * override does not nag; "I went to bed and picked it up in the morning" is longer, so it never
     * carries a level that was only acceptable for one listening session into the next.
     */
    const val IDLE_END_MS = 15 * 60_000L

    /**
     * How long a backend that is not the one the override was given on is a *rebuild*. Measured on the
     * device (2026-10-06 11:13:19.86 to 20.10): a rate-change rebuild takes about 250 ms. Ten seconds
     * is forty times that, so a slow rebuild or one retry does not end it, and still short enough
     * that a route that really changed (Direct switched off, Bluetooth took over) ends within the
     * time it takes to notice. While suspended the override does *not* apply to the other output, so
     * a long grace costs no protection.
     */
    const val TRANSIENT_GRACE_MS = 10_000L

    /** After a process restart the stream is not up yet: wait for the same output to come back. */
    const val RESTORE_GRACE_MS = 120_000L

    /** While playing, `lastActiveAtMs` is refreshed (and persisted) this often, which bounds how stale a restart sees it. */
    const val HEARTBEAT_MS = 60_000L

    const val DAC_CONTEXT_PREFIX = "dac:"

    /** The token that says "this physical attachment": the connection path, or the identity key without one. */
    fun attachmentOf(connectionPath: String?, identityKey: String): String = connectionPath ?: identityKey

    fun grant(
        profileId: String,
        contextId: String,
        kind: SafetyVolumeBackendKind,
        calibration: SafetyMaxCalibration,
        attachment: String?,
        rawLevel: Int,
        ceilingRaw: Int,
        nowMs: Long,
    ) = SafetyOverrideSession(
        profileId = profileId,
        contextId = contextId,
        kind = kind,
        calibratedAtEpochMs = calibration.calibratedAtEpochMs,
        ceilingRaw = calibration.ceilingRaw,
        attachment = attachment,
        lastActiveAtMs = nowMs,
        peaked = rawLevel > ceilingRaw,
    )

    /** Playback is (or just was) running: the idle window restarts from [nowMs]. */
    fun touch(session: SafetyOverrideSession, nowMs: Long): SafetyOverrideSession =
        session.copy(lastActiveAtMs = maxOf(session.lastActiveAtMs, nowMs))

    /** The facts that decide, independent of the level and of which backend is current. */
    private fun identityEnd(session: SafetyOverrideSession, obs: SafetyOverrideObservation): SafetyOverrideEnd? {
        if (obs.profileId == null || obs.profileId != session.profileId) return SafetyOverrideEnd.PROFILE_CHANGED
        if (!obs.safetyEnabled) return SafetyOverrideEnd.SAFETY_OFF
        val calibration = obs.calibration
        if (calibration == null ||
            calibration.calibratedAtEpochMs != session.calibratedAtEpochMs ||
            calibration.ceilingRaw != session.ceilingRaw
        ) {
            return SafetyOverrideEnd.CALIBRATION_CHANGED
        }
        if (session.attachment != null && session.attachment !in obs.attachments) return SafetyOverrideEnd.DAC_GONE
        if (!obs.playing && obs.nowMs - session.lastActiveAtMs >= IDLE_END_MS) return SafetyOverrideEnd.IDLE
        return null
    }

    fun evaluate(session: SafetyOverrideSession, obs: SafetyOverrideObservation): SafetyOverrideVerdict {
        // B12a-8 #3: a session restored after a restart is read before the profile store has answered. What the
        // observation says about profile, Safety and calibration is then the empty default, not the listener's choice:
        // judging it would end the override on its first reconcile ("another profile is selected (or none)"). Held,
        // not applied (the ceiling of the current output stands), and judged once the stores have loaded.
        if (!obs.contextLoaded) return SafetyOverrideVerdict.Suspended(session)
        identityEnd(session, obs)?.let { return SafetyOverrideVerdict.Ended(it) }
        val refreshed = if (obs.playing) touch(session, obs.nowMs) else session
        if (obs.contextId != session.contextId || obs.kind != session.kind) {
            val since = session.mismatchSinceMs ?: obs.nowMs
            if (obs.nowMs - since >= session.graceMs) return SafetyOverrideVerdict.Ended(SafetyOverrideEnd.OUTPUT_CHANGED)
            return SafetyOverrideVerdict.Suspended(refreshed.copy(mismatchSinceMs = since))
        }
        // The same output as the one it was given on: the level decides.
        val ceiling = obs.ceilingRaw ?: return SafetyOverrideVerdict.Ended(SafetyOverrideEnd.CALIBRATION_CHANGED)
        val peaked = session.peaked || obs.rawLevel > ceiling
        if (obs.rawLevel < ceiling || (peaked && obs.rawLevel <= ceiling)) {
            return SafetyOverrideVerdict.Ended(SafetyOverrideEnd.LOWERED)
        }
        return SafetyOverrideVerdict.Active(
            refreshed.copy(peaked = peaked, mismatchSinceMs = null, graceMs = TRANSIENT_GRACE_MS),
        )
    }

    /**
     * Whether the DAC may keep the level it has when it is opened (or reconfigured for a new rate),
     * instead of being lowered to the ceiling: true while an override stands for that very output.
     *
     * Pure and read-only: it runs on whichever thread opens the DAC and must not end or change the
     * session. It never raises anything either; the open guard only ever lowers (see
     * [UsbHardwareVolumePolicy.openTarget]).
     */
    fun coversOpen(session: SafetyOverrideSession?, obs: SafetyOverrideObservation): Boolean =
        session != null &&
            session.contextId == obs.contextId &&
            session.kind == obs.kind &&
            identityEnd(session, obs) == null

    /** When time alone ends the session (a mismatch outliving its grace, or the idle window), if it can. */
    private fun endDeadlineMs(session: SafetyOverrideSession, playing: Boolean): Long? = listOfNotNull(
        session.mismatchSinceMs?.let { it + session.graceMs },
        if (playing) null else session.lastActiveAtMs + IDLE_END_MS,
    ).minOrNull()

    /**
     * Whether time alone has ended the session by [nowMs]. The timer that should have fired can be
     * late (a coroutine delay does not run while the device sleeps), so every consumer of the
     * override asks this first instead of trusting that the timer ran.
     */
    fun isDue(session: SafetyOverrideSession, playing: Boolean, nowMs: Long): Boolean =
        endDeadlineMs(session, playing)?.let { it <= nowMs } ?: false

    /** How long until the verdict could change by time alone, for the controller's one timer. */
    fun nextCheckDelayMs(session: SafetyOverrideSession, playing: Boolean, nowMs: Long): Long {
        val untilEnd = endDeadlineMs(session, playing)?.minus(nowMs)
        // While playing the clock of activity keeps moving: refresh (and persist) it once a heartbeat.
        val delayMs = if (playing) minOf(untilEnd ?: HEARTBEAT_MS, HEARTBEAT_MS) else untilEnd ?: HEARTBEAT_MS
        return delayMs.coerceAtLeast(1L)
    }

    /**
     * The session as read from disk after a restart, or null when it must not be trusted. It is
     * conservative on purpose (a doubt ends it, the ceiling comes back): same boot (the clock and the
     * boot counter agree), inside the idle window, and only a DAC attachment, which can be checked
     * against the live USB list. An override on Bluetooth or the speaker ends with the process: there
     * is nothing to tell that the headphones are the same ones.
     */
    fun restore(record: SafetyOverrideRecord, currentBootCount: Int, nowMs: Long): SafetyOverrideSession? {
        val session = record.session
        if (currentBootCount < 0 || record.bootCount != currentBootCount) return null
        if (session.attachment == null) return null
        if (session.lastActiveAtMs > nowMs) return null
        if (nowMs - session.lastActiveAtMs >= IDLE_END_MS) return null
        return session.copy(mismatchSinceMs = nowMs, graceMs = RESTORE_GRACE_MS)
    }
}

/**
 * When an override ends while music plays and the level is above the ceiling, the level is brought
 * down in steps instead of in one jump (FIX-4).
 *
 * Why a ramp rather than "apply the ceiling at the next start of playback": with music playing there
 * may be no next start for an hour, and the ceiling is a hearing-safety limit, so leaving the level
 * above it until then would make the end of the override cosmetic. Why not an immediate clamp: a
 * 10-20 dB drop in one write, in the middle of a track, is the abrupt change the owner asked not to
 * have. One step (about 2 dB on a DAC) every [STEP_INTERVAL_MS] is a fade of about a second.
 *
 * It only ever moves down. A level raised above what the ramp last wrote (anyone raising it while
 * the ramp runs) stops the ramp and is clamped at once: a rise is the hazard, and gets no courtesy.
 * With nothing playing there is nothing to be abrupt about, and the clamp is immediate and silent.
 *
 * B12a-8: **it never writes a level above the real one.** The ramp used to carry its own counter and only
 * looked for a *rise*: if the user lowered the volume by hand while it ran (23 -> 17 under way, the user going
 * down to 10), the next step wrote `nextLevel(23) = 22` — twelve steps *up* — and the ramp finished at the ceiling,
 * above what the user had chosen. Each step is now taken from what the backend reads ([next]).
 */
object SafetyOverrideRamp {
    const val STEP_INTERVAL_MS = 120L

    /** Ramp (rather than clamp at once) only when it is the end of an override, music plays, and the level is above. */
    fun shouldRamp(endedHere: Boolean, playing: Boolean, level: Int, ceilingRaw: Int?): Boolean =
        endedHere && playing && ceilingRaw != null && level > ceilingRaw

    /** The next level to write: one step down, never below the ceiling, never up. */
    fun nextLevel(level: Int, ceilingRaw: Int): Int = if (level > ceilingRaw) level - 1 else level

    /**
     * The level the ramp writes next, or null when it stops. [observedLevel] is what the backend reads now and
     * [lastWritten] what the ramp wrote last.
     *
     * - raised above what the ramp wrote: stop (the reconcile that saw it clamps at once);
     * - at or under the ceiling: stop (done, or the user is already below it);
     * - otherwise one step down **from the real level**. A user who lowered it by hand (`observedLevel < lastWritten`)
     *   takes the ramp's counter with them: it carries on from where they are, so it can never write a level above
     *   the current one, and a level they left above the ceiling still comes down to it (the override has ended).
     */
    fun next(observedLevel: Int, lastWritten: Int, ceilingRaw: Int): Int? = when {
        raisedDuringRamp(observedLevel, lastWritten) -> null
        observedLevel <= ceilingRaw -> null
        else -> nextLevel(observedLevel, ceilingRaw)
    }

    /** Whether [observedLevel] means somebody raised the level above what the ramp last wrote. */
    fun raisedDuringRamp(observedLevel: Int, lastWritten: Int): Boolean = observedLevel > lastWritten

    fun durationMs(level: Int, ceilingRaw: Int): Long = (level - ceilingRaw).coerceAtLeast(0) * STEP_INTERVAL_MS
}
