package com.lostf1sh.pixelplayeross.data.volume

import android.content.Context
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind

/**
 * The temporary override as it survives a restart of the app (FIX-4). Written when it is granted,
 * when playback pauses, and about once a minute while playing, so a restart sees an activity time at
 * most a minute old; cleared the moment the override ends. [SafetyOverridePolicy.restore] decides
 * whether what is read here may be trusted (same boot, inside the idle window, a DAC attachment).
 *
 * Nothing here is a setting: it is a short-lived fact about the present listening session, read once
 * at start-up, and it never grants anything by itself: the policy still has to see the same output,
 * profile and calibration before the override applies.
 */
class SafetyOverrideStore(context: Context) {
    private val prefs = context.getSharedPreferences("safety_override_session", Context.MODE_PRIVATE)

    fun save(record: SafetyOverrideRecord) {
        val session = record.session
        prefs.edit()
            .putString(PROFILE, session.profileId)
            .putString(CONTEXT, session.contextId)
            .putString(KIND, session.kind.name)
            .putLong(CALIBRATED_AT, session.calibratedAtEpochMs)
            .putInt(CEILING, session.ceilingRaw)
            .putString(ATTACHMENT, session.attachment)
            .putLong(LAST_ACTIVE, session.lastActiveAtMs)
            .putBoolean(PEAKED, session.peaked)
            .putInt(BOOT_COUNT, record.bootCount)
            .apply()
    }

    fun load(): SafetyOverrideRecord? {
        val profile = prefs.getString(PROFILE, null) ?: return null
        val context = prefs.getString(CONTEXT, null) ?: return null
        val kind = prefs.getString(KIND, null)
            ?.let { name -> runCatching { SafetyVolumeBackendKind.valueOf(name) }.getOrNull() }
            ?: return null
        return SafetyOverrideRecord(
            session = SafetyOverrideSession(
                profileId = profile,
                contextId = context,
                kind = kind,
                calibratedAtEpochMs = prefs.getLong(CALIBRATED_AT, 0L),
                ceilingRaw = prefs.getInt(CEILING, 0),
                attachment = prefs.getString(ATTACHMENT, null),
                lastActiveAtMs = prefs.getLong(LAST_ACTIVE, 0L),
                peaked = prefs.getBoolean(PEAKED, false),
            ),
            bootCount = prefs.getInt(BOOT_COUNT, -1),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PROFILE = "profile"
        const val CONTEXT = "context"
        const val KIND = "kind"
        const val CALIBRATED_AT = "calibrated_at"
        const val CEILING = "ceiling"
        const val ATTACHMENT = "attachment"
        const val LAST_ACTIVE = "last_active"
        const val PEAKED = "peaked"
        const val BOOT_COUNT = "boot_count"
    }
}
