package com.lostf1sh.pixelplayeross.data.preferences

/**
 * B56 phase 7.1 (#48): how much the full player's background moves. One three-valued choice (Appearance, Player: "Background motion") over the two keys the app already stores:
 * the old "Moving background" switch (`cover_glass_moving_v1`: off is [STILL], on is moving) and a second one, [LIVELY] (`cover_glass_lively_v1`, absent = gentle). Nothing is
 * migrated or renamed: an install that had the switch off still has it off, and one that had it on is on [GENTLE], which is what the default has been.
 *
 * The value sets **speed and amplitude only**: [speed] multiplies how fast the motion clock runs and [travel] how far each blob or layer goes. [GENTLE] is exactly today's glass
 * tuning (`GlassMotion.Tuning`, 1 and 1); [STILL] runs nothing at all and keeps [travel] 1, so a still glass is the layers at the phase they were left at, pixel for pixel what the
 * glass has always shown when the switch was off.
 */
enum class BackgroundMotion(val storageKey: String, val speed: Float, val travel: Float) {
    STILL("still", speed = 0f, travel = 1f),
    GENTLE("gentle", speed = 1f, travel = 1f),
    LIVELY("lively", speed = 1.8f, travel = 1.4f);

    val moves: Boolean get() = this != STILL

    companion object {
        /** Gentle, in both modes (app and DAP). */
        val DEFAULT = GENTLE

        fun of(moving: Boolean, lively: Boolean): BackgroundMotion = when {
            !moving -> STILL
            lively -> LIVELY
            else -> GENTLE
        }

        fun fromStorageKey(key: String?): BackgroundMotion? = entries.firstOrNull { it.storageKey == key }
    }
}
