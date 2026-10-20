package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.volume.SafetyOverrideEnd.CALIBRATION_CHANGED
import com.lostf1sh.pixelplayeross.data.volume.SafetyOverrideEnd.DAC_GONE
import com.lostf1sh.pixelplayeross.data.volume.SafetyOverrideEnd.IDLE
import com.lostf1sh.pixelplayeross.data.volume.SafetyOverrideEnd.LOWERED
import com.lostf1sh.pixelplayeross.data.volume.SafetyOverrideEnd.OUTPUT_CHANGED
import com.lostf1sh.pixelplayeross.data.volume.SafetyOverrideEnd.PROFILE_CHANGED
import com.lostf1sh.pixelplayeross.data.volume.SafetyOverrideEnd.SAFETY_OFF
import org.junit.jupiter.api.Test

/**
 * FIX-4: how long the temporary Safety Max override lasts. The matrix the owner asked for: what ends
 * it, what must not, and the transient context of a stream rebuild on the same DAC.
 *
 * Numbers are the owner's own: ceiling 17 steps (-26 dB) and an override level of 24 (-12 dB) on the
 * Melody, from the 2026-10-06 trial log.
 */
class SafetyOverridePolicyTest {
    private val minute = 60_000L
    private val hardware = SafetyVolumeBackendKind.USB_HARDWARE_VOLUME
    private val dac = "dac:profile-5ce52a068a4ac2d91f465d3d1747d7c8"
    private val path = "/dev/bus/usb/001/005"
    private val profile = "headphones-1"
    private val calibration = SafetyMaxCalibration(
        outputContextId = dac,
        backendKind = hardware,
        ceilingRaw = 17,
        backendMinRaw = 0,
        backendMaxRaw = 30,
        calibratedAtEpochMs = 1_000L,
    )

    /** An override granted at t = 0 with the level at the ceiling, as the confirmed gesture does. */
    private val granted = SafetyOverridePolicy.grant(
        profileId = profile,
        contextId = dac,
        kind = hardware,
        calibration = calibration,
        attachment = path,
        rawLevel = 17,
        ceilingRaw = 17,
        nowMs = 0L,
    )

    private fun obs(
        nowMs: Long,
        level: Int = 24,
        playing: Boolean = true,
        profileId: String? = profile,
        safetyEnabled: Boolean = true,
        stored: SafetyMaxCalibration? = calibration,
        contextId: String? = dac,
        kind: SafetyVolumeBackendKind = hardware,
        ceiling: Int? = 17,
        attachments: Set<String> = setOf(path),
        contextLoaded: Boolean = true,
    ) = SafetyOverrideObservation(
        nowMs = nowMs,
        profileId = profileId,
        safetyEnabled = safetyEnabled,
        calibration = stored,
        contextId = contextId,
        kind = kind,
        rawLevel = level,
        ceilingRaw = ceiling,
        attachments = attachments,
        playing = playing,
        contextLoaded = contextLoaded,
    )

    private fun SafetyOverrideVerdict.endedBy(): SafetyOverrideEnd? = (this as? SafetyOverrideVerdict.Ended)?.reason
    private fun SafetyOverrideVerdict.session(): SafetyOverrideSession = when (this) {
        is SafetyOverrideVerdict.Active -> session
        is SafetyOverrideVerdict.Suspended -> session
        is SafetyOverrideVerdict.Ended -> error("ended: $reason")
    }

    private fun SafetyOverrideSession.then(observation: SafetyOverrideObservation) =
        SafetyOverridePolicy.evaluate(this, observation)

    // ---------------------------------------------------------------- what ends it

    @Test
    fun `the grant moment at the ceiling stays active until the level has been above it`() {
        assertThat(granted.peaked).isFalse()
        assertThat(granted.then(obs(1_000L, level = 17))).isInstanceOf(SafetyOverrideVerdict.Active::class.java)
    }

    @Test
    fun `above the ceiling the override holds at every level up to the top`() {
        var session = granted
        for (level in 18..30) {
            val verdict = session.then(obs(level * 1_000L, level = level))
            assertThat(verdict).isInstanceOf(SafetyOverrideVerdict.Active::class.java)
            session = verdict.session()
        }
    }

    @Test
    fun `lowering back to the ceiling after going above it ends the override`() {
        val above = granted.then(obs(1_000L, level = 24)).session()
        assertThat(above.peaked).isTrue()
        assertThat(above.then(obs(2_000L, level = 17)).endedBy()).isEqualTo(LOWERED)
        assertThat(above.then(obs(2_000L, level = 12)).endedBy()).isEqualTo(LOWERED)
    }

    @Test
    fun `one step under the ceiling ends it even if the level never went above`() {
        assertThat(granted.then(obs(1_000L, level = 16)).endedBy()).isEqualTo(LOWERED)
    }

    @Test
    fun `no playback for fifteen minutes ends it and fourteen fifty nine does not`() {
        val paused = SafetyOverridePolicy.touch(granted.then(obs(0L)).session(), 0L)
        assertThat(paused.then(obs(15 * minute - 1, playing = false))).isInstanceOf(SafetyOverrideVerdict.Active::class.java)
        assertThat(paused.then(obs(15 * minute, playing = false)).endedBy()).isEqualTo(IDLE)
        assertThat(SafetyOverridePolicy.IDLE_END_MS).isEqualTo(15 * minute)
    }

    @Test
    fun `a pause under fifteen minutes keeps it and playing again restarts the window`() {
        val pausedAt10 = SafetyOverridePolicy.touch(granted, 10 * minute)
        val back = pausedAt10.then(obs(14 * minute, playing = true)).session()
        assertThat(back.lastActiveAtMs).isEqualTo(14 * minute)
        // Paused again at minute 20: the second window runs from there, not from the grant.
        val pausedAt20 = SafetyOverridePolicy.touch(back, 20 * minute)
        assertThat(pausedAt20.then(obs(34 * minute, playing = false))).isInstanceOf(SafetyOverrideVerdict.Active::class.java)
        assertThat(pausedAt20.then(obs(35 * minute, playing = false)).endedBy()).isEqualTo(IDLE)
    }

    @Test
    fun `hours of playback never end it by themselves`() {
        var session = granted
        var now = 0L
        repeat(240) {
            now += minute
            session = session.then(obs(now, playing = true)).session()
        }
        assertThat(session.lastActiveAtMs).isEqualTo(240 * minute)
    }

    @Test
    fun `unplugging the DAC ends it at once even while playing`() {
        assertThat(granted.then(obs(1_000L, attachments = emptySet())).endedBy()).isEqualTo(DAC_GONE)
    }

    @Test
    fun `a replugged DAC is another attachment and ends it`() {
        assertThat(granted.then(obs(1_000L, attachments = setOf("/dev/bus/usb/001/006"))).endedBy()).isEqualTo(DAC_GONE)
    }

    @Test
    fun `another DAC being attached does not end it while the original stays`() {
        val verdict = granted.then(obs(1_000L, attachments = setOf(path, "/dev/bus/usb/001/009")))
        assertThat(verdict).isInstanceOf(SafetyOverrideVerdict.Active::class.java)
    }

    @Test
    fun `another DAC becoming the output is a route change that ends it after the grace`() {
        val other = "dac:profile-other"
        val first = granted.then(obs(1_000L, contextId = other, attachments = setOf(path, "/dev/bus/usb/001/009")))
        assertThat(first).isInstanceOf(SafetyOverrideVerdict.Suspended::class.java)
        val later = first.session().then(
            obs(1_000L + SafetyOverridePolicy.TRANSIENT_GRACE_MS, contextId = other, attachments = setOf(path, "/dev/bus/usb/001/009")),
        )
        assertThat(later.endedBy()).isEqualTo(OUTPUT_CHANGED)
    }

    @Test
    fun `another headphone profile or none ends it`() {
        assertThat(granted.then(obs(1_000L, profileId = "headphones-2")).endedBy()).isEqualTo(PROFILE_CHANGED)
        assertThat(granted.then(obs(1_000L, profileId = null)).endedBy()).isEqualTo(PROFILE_CHANGED)
    }

    @Test
    fun `switching Safety Max off ends it`() {
        assertThat(granted.then(obs(1_000L, safetyEnabled = false)).endedBy()).isEqualTo(SAFETY_OFF)
    }

    @Test
    fun `recalibrating or removing the calibration ends it`() {
        assertThat(granted.then(obs(1_000L, stored = calibration.copy(calibratedAtEpochMs = 2_000L))).endedBy())
            .isEqualTo(CALIBRATION_CHANGED)
        assertThat(granted.then(obs(1_000L, stored = calibration.copy(ceilingRaw = 20))).endedBy())
            .isEqualTo(CALIBRATION_CHANGED)
        assertThat(granted.then(obs(1_000L, stored = null)).endedBy()).isEqualTo(CALIBRATION_CHANGED)
    }

    @Test
    fun `an output that stays different past the grace ends it, and not a moment before`() {
        val grace = SafetyOverridePolicy.TRANSIENT_GRACE_MS
        val speaker = obs(5_000L, contextId = "android:built_in_speaker", kind = SafetyVolumeBackendKind.ANDROID_MEDIA, ceiling = null)
        val suspended = granted.then(speaker)
        assertThat(suspended).isInstanceOf(SafetyOverrideVerdict.Suspended::class.java)
        val still = suspended.session().then(speaker.copy(nowMs = 5_000L + grace - 1))
        assertThat(still).isInstanceOf(SafetyOverrideVerdict.Suspended::class.java)
        assertThat(still.session().then(speaker.copy(nowMs = 5_000L + grace)).endedBy()).isEqualTo(OUTPUT_CHANGED)
    }

    @Test
    fun `a suspended override still ages and ends by the idle window`() {
        val speaker = obs(1_000L, playing = false, contextId = "android:media_default", kind = SafetyVolumeBackendKind.ANDROID_MEDIA, ceiling = null)
        val paused = SafetyOverridePolicy.touch(granted, 0L)
        // The idle window ends it even though the output never came back.
        assertThat(paused.then(speaker.copy(nowMs = 15 * minute)).endedBy()).isEqualTo(IDLE)
    }

    // ---------------------------------------------------------------- what must not end it

    @Test
    fun `the transient context of a stream rebuild on the same DAC does not end it`() {
        // 2026-10-06 11:13:19.86 to 20.10: Stream rebuild at a rate change. For ~250 ms the backend is
        // not USB Direct (the sink's delegate is routed to the speaker), the kind and the context are
        // other ones, and the DAC's level reads as it was.
        var session = granted.then(obs(10_000L, level = 24)).session()
        val rebuild = listOf(
            obs(10_100L, level = 24, kind = SafetyVolumeBackendKind.FIXED_OUTPUT, ceiling = null),
            obs(10_150L, level = 5, contextId = "android:built_in_speaker", kind = SafetyVolumeBackendKind.ANDROID_MEDIA, ceiling = null),
            obs(10_200L, level = 24, contextId = null, kind = SafetyVolumeBackendKind.FIXED_OUTPUT, ceiling = null),
            obs(10_240L, level = 24, kind = SafetyVolumeBackendKind.ANDROID_MEDIA, ceiling = null),
        )
        for (during in rebuild) {
            val verdict = session.then(during)
            // Suspended: kept, but *not applied* to the other output.
            assertThat(verdict).isInstanceOf(SafetyOverrideVerdict.Suspended::class.java)
            session = verdict.session()
        }
        val back = session.then(obs(10_350L, level = 24))
        assertThat(back).isInstanceOf(SafetyOverrideVerdict.Active::class.java)
        assertThat(back.session().mismatchSinceMs).isNull()
    }

    @Test
    fun `while the rebuild lasts the override is not applied to the other output`() {
        val during = granted.then(obs(1_000L, kind = SafetyVolumeBackendKind.ANDROID_MEDIA, contextId = "android:built_in_speaker", ceiling = 8))
        assertThat(during).isInstanceOf(SafetyOverrideVerdict.Suspended::class.java)
        assertThat(during).isNotInstanceOf(SafetyOverrideVerdict.Active::class.java)
    }

    @Test
    fun `a level read during the rebuild does not count as lowering`() {
        // The DAC reads 5 on the speaker backend for a moment; that is another output's level.
        val during = granted.then(obs(1_000L, level = 5, contextId = "android:built_in_speaker", kind = SafetyVolumeBackendKind.ANDROID_MEDIA, ceiling = 8))
        assertThat(during.endedBy()).isNull()
    }

    @Test
    fun `a rebuild that takes twice as long as measured still does not end it`() {
        val duration = 500L
        val first = granted.then(obs(0L, kind = SafetyVolumeBackendKind.FIXED_OUTPUT, ceiling = null))
        val last = first.session().then(obs(duration, kind = SafetyVolumeBackendKind.FIXED_OUTPUT, ceiling = null))
        assertThat(last).isInstanceOf(SafetyOverrideVerdict.Suspended::class.java)
        assertThat(SafetyOverridePolicy.TRANSIENT_GRACE_MS).isAtLeast(20 * duration)
    }

    @Test
    fun `tracks and sample rates go by with the override standing`() {
        // Thirty tracks over two hours, with a rebuild between each pair.
        var session = granted
        var now = 0L
        repeat(30) {
            now += 4 * minute
            session = session.then(obs(now, level = 24)).session()
            session = session.then(obs(now + 100, level = 24, kind = SafetyVolumeBackendKind.FIXED_OUTPUT, ceiling = null)).session()
            session = session.then(obs(now + 300, level = 24)).session()
        }
        assertThat(session.then(obs(now + 400, level = 24))).isInstanceOf(SafetyOverrideVerdict.Active::class.java)
    }

    @Test
    fun `the screen is not an input so locking it cannot end the override`() {
        // Nothing in the observation says anything about the screen: it is the same verdict by construction.
        val fields = SafetyOverrideObservation::class.java.declaredFields.map { it.name }
        assertThat(fields.filter { it.contains("screen", ignoreCase = true) || it.contains("lock", ignoreCase = true) }).isEmpty()
    }

    // ---------------------------------------------------------------- the open guard

    @Test
    fun `the open guard keeps the level while an override stands for that DAC even through the rebuild`() {
        assertThat(SafetyOverridePolicy.coversOpen(granted, obs(1_000L, level = 24, ceiling = null))).isTrue()
        // Idle, not yet fifteen minutes.
        assertThat(
            SafetyOverridePolicy.coversOpen(
                SafetyOverridePolicy.touch(granted, 0L),
                obs(14 * minute, playing = false, ceiling = null),
            ),
        ).isTrue()
    }

    @Test
    fun `the open guard lowers to the ceiling again once the override is over`() {
        val idle = SafetyOverridePolicy.touch(granted, 0L)
        assertThat(SafetyOverridePolicy.coversOpen(idle, obs(15 * minute, playing = false))).isFalse()
        assertThat(SafetyOverridePolicy.coversOpen(granted, obs(1_000L, profileId = "headphones-2"))).isFalse()
        assertThat(SafetyOverridePolicy.coversOpen(granted, obs(1_000L, safetyEnabled = false))).isFalse()
        assertThat(SafetyOverridePolicy.coversOpen(granted, obs(1_000L, stored = calibration.copy(calibratedAtEpochMs = 9L)))).isFalse()
        assertThat(SafetyOverridePolicy.coversOpen(granted, obs(1_000L, attachments = emptySet()))).isFalse()
        assertThat(SafetyOverridePolicy.coversOpen(null, obs(1_000L))).isFalse()
    }

    @Test
    fun `the open guard is for the DAC the override was given on and no other`() {
        assertThat(SafetyOverridePolicy.coversOpen(granted, obs(1_000L, contextId = "dac:profile-other"))).isFalse()
        assertThat(SafetyOverridePolicy.coversOpen(granted, obs(1_000L, kind = SafetyVolumeBackendKind.ANDROID_MEDIA))).isFalse()
    }

    @Test
    fun `the guard still only lowers, it never raises the level`() {
        // openTarget is unchanged: with the ceiling taken out by an override it is the saved level,
        // and only when that is under the level the DAC has.
        assertThat(UsbHardwareVolumePolicy.openTarget(currentUnits = -3072, savedUnits = -3072, ceilingUnits = null)).isNull()
        assertThat(UsbHardwareVolumePolicy.openTarget(currentUnits = -3072, savedUnits = -6000, ceilingUnits = null)).isEqualTo(-6000)
        assertThat(UsbHardwareVolumePolicy.openTarget(currentUnits = -6656, savedUnits = -3072, ceilingUnits = null)).isNull()
    }

    // ---------------------------------------------------------------- restart

    private val boot = 41

    @Test
    fun `an override survives a restart inside the idle window on the same boot`() {
        val saved = SafetyOverridePolicy.touch(granted.copy(peaked = true), 5 * minute)
        val restored = SafetyOverridePolicy.restore(SafetyOverrideRecord(saved, boot), currentBootCount = boot, nowMs = 9 * minute)
        assertThat(restored).isNotNull()
        // It waits for the same output to come back, with a longer grace than a rebuild.
        assertThat(restored!!.mismatchSinceMs).isEqualTo(9 * minute)
        assertThat(restored.graceMs).isEqualTo(SafetyOverridePolicy.RESTORE_GRACE_MS)
        assertThat(restored.peaked).isTrue()
    }

    /**
     * B12a-8 #3: `restoreOverride()` runs in `start()`, before the profile store has answered. The profile list is then the
     * empty default and "no profile selected" read as a change of profile: the restored override ended at its first reconcile.
     */
    @Test
    fun `a restored override is held while the profile store has not answered, and stands once it has and the profile matches`() {
        val boot = 7
        val saved = granted.copy(lastActiveAtMs = 8 * minute, peaked = true)
        val restored = SafetyOverridePolicy.restore(SafetyOverrideRecord(saved, boot), currentBootCount = boot, nowMs = 9 * minute)!!
        // what the controller sees right after a restart: the empty default profile state, the USB list already there
        val notLoaded = obs(
            nowMs = 9 * minute + 50, profileId = null, safetyEnabled = false, stored = null,
            contextId = "speaker", kind = SafetyVolumeBackendKind.ANDROID_MEDIA, ceiling = null,
            playing = false, contextLoaded = false,
        )
        val held = restored.then(notLoaded)
        assertThat(held).isInstanceOf(SafetyOverrideVerdict.Suspended::class.java)
        assertThat(held.session()).isEqualTo(restored)              // nothing about it changed: not ended, not touched
        // and again, and again: any number of reconciles before the store answers
        assertThat(held.session().then(notLoaded.copy(nowMs = 9 * minute + 400)).endedBy()).isNull()
        // the store answers, the profile is the one it was given on, and the output is back: it stands
        val loaded = restored.then(obs(nowMs = 9 * minute + 900, level = 24))
        assertThat(loaded).isInstanceOf(SafetyOverrideVerdict.Active::class.java)
    }

    @Test
    fun `once the profile store has answered, a profile that really differs or is gone still ends it`() {
        val restored = SafetyOverridePolicy.restore(
            SafetyOverrideRecord(granted.copy(lastActiveAtMs = 8 * minute), 7), currentBootCount = 7, nowMs = 9 * minute,
        )!!
        assertThat(restored.then(obs(9 * minute + 900, profileId = "headphones-2")).endedBy()).isEqualTo(PROFILE_CHANGED)
        assertThat(restored.then(obs(9 * minute + 900, profileId = null, contextLoaded = true)).endedBy()).isEqualTo(PROFILE_CHANGED)
        assertThat(restored.then(obs(9 * minute + 900, safetyEnabled = false)).endedBy()).isEqualTo(SAFETY_OFF)
        assertThat(restored.then(obs(9 * minute + 900, stored = null)).endedBy()).isEqualTo(CALIBRATION_CHANGED)
    }

    @Test
    fun `an override that is not restored stays unaffected by the load gate - a live session is judged as before`() {
        // contextLoaded defaults to true: every observation the controller builds after the first read, and every test above
        assertThat(granted.then(obs(1_000L, profileId = "other")).endedBy()).isEqualTo(PROFILE_CHANGED)
    }

    @Test
    fun `an override is not restored after a reboot, a long idle, no attachment or a clock that went back`() {
        val saved = SafetyOverridePolicy.touch(granted, 5 * minute)
        fun restore(record: SafetyOverrideRecord = SafetyOverrideRecord(saved, boot), currentBoot: Int = boot, now: Long = 9 * minute) =
            SafetyOverridePolicy.restore(record, currentBoot, now)
        assertThat(restore(currentBoot = boot + 1)).isNull()
        assertThat(restore(currentBoot = -1)).isNull()
        assertThat(restore(now = 5 * minute + SafetyOverridePolicy.IDLE_END_MS)).isNull()
        assertThat(restore(SafetyOverrideRecord(saved.copy(attachment = null), boot))).isNull()
        assertThat(restore(now = 4 * minute)).isNull()
    }

    @Test
    fun `a restored override applies when the same output is seen and ends if it never is`() {
        val restored = SafetyOverridePolicy.restore(
            SafetyOverrideRecord(SafetyOverridePolicy.touch(granted.copy(peaked = true), 0L), boot), boot, minute,
        )!!
        // Android route first (Direct is not up yet): suspended.
        val early = restored.then(obs(minute + 2_000L, playing = false, kind = SafetyVolumeBackendKind.ANDROID_MEDIA, ceiling = null))
        assertThat(early).isInstanceOf(SafetyOverrideVerdict.Suspended::class.java)
        // Direct starts 40 s after the restart and the level is where the owner left it.
        val up = early.session().then(obs(minute + 40_000L, level = 24))
        assertThat(up).isInstanceOf(SafetyOverrideVerdict.Active::class.java)
        assertThat(up.session().graceMs).isEqualTo(SafetyOverridePolicy.TRANSIENT_GRACE_MS)
        // Whereas if Direct never comes up the restored session ends, after the restore grace.
        val never = restored.then(obs(minute + SafetyOverridePolicy.RESTORE_GRACE_MS, playing = false, kind = SafetyVolumeBackendKind.ANDROID_MEDIA, ceiling = null))
        assertThat(never.endedBy()).isEqualTo(OUTPUT_CHANGED)
    }

    // ---------------------------------------------------------------- the one timer

    @Test
    fun `the timer is the idle deadline while paused and a heartbeat while playing`() {
        val paused = SafetyOverridePolicy.touch(granted, 4 * minute)
        assertThat(SafetyOverridePolicy.nextCheckDelayMs(paused, playing = false, nowMs = 5 * minute)).isEqualTo(14 * minute)
        assertThat(SafetyOverridePolicy.nextCheckDelayMs(paused, playing = true, nowMs = 5 * minute))
            .isEqualTo(SafetyOverridePolicy.HEARTBEAT_MS)
    }

    @Test
    fun `the timer wakes at the end of a grace if that comes first`() {
        val suspended = granted.copy(mismatchSinceMs = 100_000L)
        assertThat(SafetyOverridePolicy.nextCheckDelayMs(suspended, playing = false, nowMs = 104_000L))
            .isEqualTo(SafetyOverridePolicy.TRANSIENT_GRACE_MS - 4_000L)
    }

    @Test
    fun `a timer that ran late is noticed by the next reader`() {
        // The device slept through the deadline: nothing fired, but every consumer asks isDue first.
        val paused = SafetyOverridePolicy.touch(granted, 0L)
        assertThat(SafetyOverridePolicy.isDue(paused, playing = false, nowMs = 15 * minute - 1)).isFalse()
        assertThat(SafetyOverridePolicy.isDue(paused, playing = false, nowMs = 40 * minute)).isTrue()
        assertThat(SafetyOverridePolicy.isDue(paused, playing = true, nowMs = 40 * minute)).isFalse()
    }

    // ---------------------------------------------------------------- the fade when it ends

    @Test
    fun `an override that ends under music fades down one step at a time and never up`() {
        var level = 24
        val written = mutableListOf<Int>()
        while (level > 17) {
            level = SafetyOverrideRamp.nextLevel(level, 17)
            written += level
        }
        assertThat(written).containsExactly(23, 22, 21, 20, 19, 18, 17).inOrder()
        assertThat(SafetyOverrideRamp.nextLevel(17, 17)).isEqualTo(17)
        assertThat(SafetyOverrideRamp.nextLevel(12, 17)).isEqualTo(12)
        // 7 steps of 120 ms: under a second for the owner's -12 dB to -26 dB.
        assertThat(SafetyOverrideRamp.durationMs(24, 17)).isEqualTo(7 * SafetyOverrideRamp.STEP_INTERVAL_MS)
        assertThat(SafetyOverrideRamp.durationMs(24, 17)).isLessThan(1_000L)
    }

    /**
     * What the ramp loop does against a backend the user can also move: each tick reads the level, asks [SafetyOverrideRamp.next]
     * and writes. [userAt] maps a tick to the level the user sets by hand just before it (or null).
     */
    private fun runRamp(start: Int, ceiling: Int, userAt: Map<Int, Int>): Pair<List<Int>, Int> {
        var backend = start          // what the backend reads
        var level = start            // the ramp's counter
        val written = mutableListOf<Int>()
        var tick = 0
        while (level > ceiling) {
            userAt[tick]?.let { backend = it }
            tick++
            val next = SafetyOverrideRamp.next(backend, level, ceiling) ?: break
            level = next
            // the write: nothing the ramp writes may ever be above what the backend read when it wrote
            assertThat(next).isAtMost(backend)
            written += next
            backend = next
            if (tick > 200) error("the ramp does not end")
        }
        return written to backend
    }

    @Test
    fun `a user who lowers the volume while the override fades is never raised again`() {
        // 23 -> 17 under way; after two steps (23, 22, 21) the user goes down to 10: nothing more is written
        val (written, finalLevel) = runRamp(start = 24, ceiling = 17, userAt = mapOf(3 to 10))
        assertThat(written).containsExactly(23, 22, 21).inOrder()
        assertThat(written.maxOrNull() ?: 0).isAtMost(23)
        assertThat(finalLevel).isEqualTo(10)                       // the user's own level: the ramp did not push it back to 17
    }

    @Test
    fun `a user who lowers it but stays above the ceiling is carried down from there, never up`() {
        val (written, finalLevel) = runRamp(start = 24, ceiling = 17, userAt = mapOf(2 to 20))
        // 23, 22 by the ramp; the user sets 20; the ramp resumes from 20, not from its own 21
        assertThat(written).containsExactly(23, 22, 19, 18, 17).inOrder()
        assertThat(finalLevel).isEqualTo(17)
    }

    @Test
    fun `every tick of the ramp is below what the backend read, wherever the user drops it`() {
        for (dropAt in 0..6) for (to in 0..24) {
            val (written, _) = runRamp(start = 24, ceiling = 17, userAt = mapOf(dropAt to to))
            assertThat(written.all { it <= 23 }).isTrue()
        }
    }

    @Test
    fun `a rise during the ramp still stops it, and nothing at or under the ceiling is ever written`() {
        assertThat(SafetyOverrideRamp.next(observedLevel = 23, lastWritten = 21, ceilingRaw = 17)).isNull()     // raised
        assertThat(SafetyOverrideRamp.next(observedLevel = 17, lastWritten = 17, ceilingRaw = 17)).isNull()     // done
        assertThat(SafetyOverrideRamp.next(observedLevel = 10, lastWritten = 21, ceilingRaw = 17)).isNull()     // already below it
        assertThat(SafetyOverrideRamp.next(observedLevel = 21, lastWritten = 21, ceilingRaw = 17)).isEqualTo(20)
        assertThat(SafetyOverrideRamp.next(observedLevel = 19, lastWritten = 21, ceilingRaw = 17)).isEqualTo(18)
    }

    @Test
    fun `only the end of an override with music playing above the ceiling fades`() {
        assertThat(SafetyOverrideRamp.shouldRamp(endedHere = true, playing = true, level = 24, ceilingRaw = 17)).isTrue()
        // Nothing playing: the clamp is immediate and silent, there is nothing to be abrupt about.
        assertThat(SafetyOverrideRamp.shouldRamp(endedHere = true, playing = false, level = 24, ceilingRaw = 17)).isFalse()
        // Any other clamp (the platform raised it, another output, first connection) is immediate.
        assertThat(SafetyOverrideRamp.shouldRamp(endedHere = false, playing = true, level = 24, ceilingRaw = 17)).isFalse()
        assertThat(SafetyOverrideRamp.shouldRamp(endedHere = true, playing = true, level = 17, ceilingRaw = 17)).isFalse()
        assertThat(SafetyOverrideRamp.shouldRamp(endedHere = true, playing = true, level = 24, ceilingRaw = null)).isFalse()
    }

    @Test
    fun `a rise during the fade is not softened`() {
        assertThat(SafetyOverrideRamp.raisedDuringRamp(observedLevel = 22, lastWritten = 21)).isTrue()
        assertThat(SafetyOverrideRamp.raisedDuringRamp(observedLevel = 21, lastWritten = 21)).isFalse()
        assertThat(SafetyOverrideRamp.raisedDuringRamp(observedLevel = 20, lastWritten = 21)).isFalse()
    }
}
