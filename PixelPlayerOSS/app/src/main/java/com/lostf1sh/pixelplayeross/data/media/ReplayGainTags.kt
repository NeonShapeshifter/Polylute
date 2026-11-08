package com.lostf1sh.pixelplayeross.data.media

/**
 * #79: how a file's loudness tags become one ReplayGain value in dB. The one place that reads them: playback ([ReplayGainManager]) and the editor
 * ([AudioMetadataReader]) both come here.
 *
 * - `REPLAYGAIN_*_GAIN` is text in dB ("-6.54 dB", "+3,2").
 * - `R128_*_GAIN` (Opus, and FLAC files tagged by some tools) is **not** dB: it is a whole number in Q7.8 (1/256 dB) relative to −23 LUFS. ReplayGain 2 aims at −18 LUFS,
 *   so the equivalent ReplayGain is `raw / 256 + 5` dB. Read as plain dB, an ordinary `-438` became −438 dB, a factor of 10⁻²², and the song played silent.
 * - A value outside [MIN_DB, MAX_DB] is no real loudness correction (a broken tag, or a unit read wrongly): it is ignored, so the next tag (or none) is used.
 */
internal object ReplayGainTags {
    /** The quietest correction taken as real: a mastered track that needs more than 30 dB less is not one. */
    const val MIN_DB = -30f

    /** The loudest correction taken as real: very quiet classical or old recordings reach about +15 dB. */
    const val MAX_DB = 20f

    /** R128 tags are relative to −23 LUFS, ReplayGain 2 to −18 LUFS. */
    private const val R128_TO_REPLAYGAIN_DB = 5f

    private val DB_SUFFIX = Regex("(?i)db")

    /** The first of [keys] the file has with a usable value, in dB; null when none has one. */
    fun gainDb(propertyMap: Map<String, Array<String>>, keys: List<String>): Float? {
        for (key in keys) {
            val raw = propertyMap[key]?.firstOrNull() ?: continue
            parse(key, raw)?.let { return it }
        }
        return null
    }

    /** One tag's value in ReplayGain dB, or null when it cannot be read or is no real correction. */
    fun parse(key: String, raw: String): Float? {
        val db = if (key.startsWith("R128_", ignoreCase = true)) {
            raw.trim().toIntOrNull()?.let { it / 256f + R128_TO_REPLAYGAIN_DB }
        } else {
            raw.trim().replace(',', '.').replace(DB_SUFFIX, "").trim().toFloatOrNull()
        }
        return db?.takeIf { it.isFinite() && it >= MIN_DB && it <= MAX_DB }
    }
}
