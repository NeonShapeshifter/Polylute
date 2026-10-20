package com.lostf1sh.pixelplayeross.presentation.components

import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeState
import com.lostf1sh.pixelplayeross.data.volume.VolumeBackendSnapshot
import com.lostf1sh.pixelplayeross.ui.ds.DapVolumeNotice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What the readout says about Safety Max.
 *
 * The readout is a mirror, never a second opinion: every case here is decided from the same
 * `SafetyVolumeState` the ceiling, the Settings control and playback read, and nothing in this file
 * can change a level. What it must not do is overstate — an override that has ended, or a ceiling
 * that is not armed, must not keep saying so.
 */
class VolumeHudNoticeTest {

    private val contextId = "android:wired_headphones"

    private fun backend(rawLevel: Int, rawMax: Int = 16) = VolumeBackendSnapshot(
        kind = SafetyVolumeBackendKind.ANDROID_MEDIA,
        outputContextId = contextId,
        outputLabel = "Wired headphones",
        rawLevel = rawLevel,
        rawMin = 0,
        rawMax = rawMax,
        controllable = true,
    )

    private fun calibration(ceiling: Int) = SafetyMaxCalibration(
        outputContextId = contextId,
        backendKind = SafetyVolumeBackendKind.ANDROID_MEDIA,
        ceilingRaw = ceiling,
        backendMinRaw = 0,
        backendMaxRaw = 16,
        calibratedAtEpochMs = 1L,
    )

    private fun state(
        rawLevel: Int,
        safetyEnabled: Boolean = true,
        calibrated: Boolean = true,
        temporaryOverride: Boolean = false,
    ) = SafetyVolumeState(
        backend = backend(rawLevel),
        selectedProfileId = "profile-1",
        selectedProfileName = "Wired",
        safetyEnabled = safetyEnabled,
        calibration = if (calibrated) calibration(ceiling = 10) else null,
        temporaryOverride = temporaryOverride,
    )

    @Test
    fun `ordinary volume has no state word`() {
        assertEquals(DapVolumeNotice.NONE, noticeFor(state(rawLevel = 4), refusedBySafetyMax = false))
    }

    @Test
    fun `sitting on the ceiling reads as the limit`() {
        assertEquals(DapVolumeNotice.AT_LIMIT, noticeFor(state(rawLevel = 10), refusedBySafetyMax = false))
    }

    @Test
    fun `a press refused by the ceiling reads as blocked, whatever the level is`() {
        // The refusal is the event; the level alone would read as an ordinary "at the limit".
        assertEquals(
            DapVolumeNotice.BLOCKED,
            noticeFor(state(rawLevel = 10), refusedBySafetyMax = true),
        )
    }

    @Test
    fun `an override above the ceiling reads as overridden`() {
        assertEquals(
            DapVolumeNotice.OVERRIDE,
            noticeFor(
                state(rawLevel = 13, temporaryOverride = true),
                refusedBySafetyMax = false,
            ),
        )
    }

    @Test
    fun `an override that has fallen back below the ceiling stops reading as one`() {
        assertEquals(
            DapVolumeNotice.NONE,
            noticeFor(
                state(rawLevel = 6, temporaryOverride = true),
                refusedBySafetyMax = false,
            ),
        )
    }

    @Test
    fun `a disarmed Safety Max has no ceiling to report`() {
        // The calibration is still stored, but it is not armed, so the level is free to reach the
        // top of the range — and at the top it is the range that is being reported, not a limit.
        assertEquals(
            DapVolumeNotice.NONE,
            noticeFor(state(rawLevel = 10, safetyEnabled = false), refusedBySafetyMax = false),
        )
        assertEquals(
            DapVolumeNotice.AT_MAXIMUM,
            noticeFor(state(rawLevel = 16, safetyEnabled = false), refusedBySafetyMax = false),
        )
    }

    @Test
    fun `no calibration means the top of the range is the top`() {
        assertEquals(
            DapVolumeNotice.AT_MAXIMUM,
            noticeFor(state(rawLevel = 16, calibrated = false), refusedBySafetyMax = false),
        )
    }

    @Test
    fun `the configured ceiling outranks the top of the range when they are the same`() {
        // Ceiling 16 on a 0..16 backend. Both are true; the one that describes the user's own
        // decision is the one worth saying.
        val atTop = SafetyVolumeState(
            backend = backend(rawLevel = 16),
            selectedProfileId = "profile-1",
            safetyEnabled = true,
            calibration = calibration(ceiling = 16),
        )
        assertEquals(DapVolumeNotice.AT_LIMIT, noticeFor(atTop, refusedBySafetyMax = false))
    }
}
