package com.lostf1sh.pixelplayeross.utils

import java.util.Locale

/**
 * B59 4 (#82): the file names that are **a disc's cover** in an album folder: `disc1.jpg`, `disc2.png`, `disc2.mp4` (the animated ones). The owner's rule: named `disc` because it is more
 * specific than `cover`; N is the song's disc number.
 *
 * What is accepted, and why:
 * - `disc` + the number + the extension, in any letter case (an SD card's FAT does not tell them apart): `disc1.jpg`, `Disc2.PNG`;
 * - the number may have leading zeros and **one** separator between the word and it: `disc 1`, `disc_1`, `disc-1`, `disc01`: file managers and rippers spell it all those ways;
 * - **not** `cd N`: `cd.png` / `cd1.jpg` is also the name rippers give to the picture **of the CD's label** (a different image from the album's cover), so a `cd` file is left alone;
 * - static: `.jpg`, `.jpeg`, `.png` (the three the folder-cover rules read); animated: `.mp4`, `.webp`, `.gif` (the three of `cover.*`).
 * Within a disc, **the exact `discN.<ext>` wins over a spelled-out variant**, and the extensions go in the order `jpg`, `jpeg`, `png` / `mp4`, `webp`, `gif` (the animated order is `cover.*`'s).
 */
internal object DiscCoverNames {
    /** The disc a song is on for these files: its disc tag, and **the first disc** for a song with none (an untagged song is on the first disc, as every album's track list reads). */
    fun effectiveDisc(discNumber: Int?): Int = discNumber?.takeIf { it > 0 } ?: 1

    val STILL_EXTENSIONS = listOf("jpg", "jpeg", "png")
    val ANIMATED_EXTENSIONS = listOf("mp4", "webp", "gif")

    private val NAME = Regex("^disc([ _-]?)0*([0-9]{1,3})\\.([a-z0-9]+)$")

    /** The disc [name] is the cover of with one of [extensions], or null (it is not a disc cover file, or has another extension). */
    fun discOf(name: String, extensions: Collection<String>): Int? {
        val match = NAME.matchEntire(name.lowercase(Locale.ROOT)) ?: return null
        if (match.groupValues[3] !in extensions) return null
        return match.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
    }

    /** The file among [names] that is disc [disc]'s cover, by [extensions] in their order and the exact spelling first; null when there is none. */
    fun pick(names: Iterable<String>, disc: Int, extensions: List<String>): String? {
        if (disc <= 0) return null
        val candidates = names.filter { discOf(it, extensions) == disc }
        if (candidates.isEmpty()) return null
        return candidates.sortedWith(
            compareBy<String>({ extensions.indexOf(it.substringAfterLast('.').lowercase(Locale.ROOT)) }, { if (it.lowercase(Locale.ROOT) == "disc$disc.${it.substringAfterLast('.').lowercase(Locale.ROOT)}") 0 else 1 }, { it }),
        ).first()
    }

    fun pickStill(names: Iterable<String>, disc: Int): String? = pick(names, disc, STILL_EXTENSIONS)

    fun pickAnimated(names: Iterable<String>, disc: Int): String? = pick(names, disc, ANIMATED_EXTENSIONS)
}
