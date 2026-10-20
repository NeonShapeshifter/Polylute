package com.lostf1sh.pixelplayeross.data.preferences

/**
 * What switching shuffle **on** does to the order songs play in (B18-R1).
 *
 * Turning shuffle off is the same in both modes: the queue goes back to the order it was loaded in, with nothing rebuilt.
 *
 * [CLASSIC] is the default deliberately: it is what shuffle has always meant (every time it is switched on the queue gets a
 * new random order, the song that is playing stays first and keeps playing), and the only value that changes nothing for a
 * listener who never opens this setting. [FAST] is B17's behaviour: switching on is a flag and nothing else, so it costs
 * nothing on a queue of thousands, but the order is the one the player dealt when the queue was loaded.
 */
enum class RandomMode(val id: String) {
    /** A new random order every time shuffle is switched on: one timeline publication, the song that is playing stays first. */
    CLASSIC("classic"),

    /** Instant: shuffle is a flag; the order is fixed for as long as the loaded queue lives. */
    FAST("fast");

    companion object {
        val DEFAULT = CLASSIC

        /** An unrecognised or absent id resolves to [DEFAULT]: a value written by a newer build must degrade to a usable mode. */
        fun fromId(id: String?): RandomMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
