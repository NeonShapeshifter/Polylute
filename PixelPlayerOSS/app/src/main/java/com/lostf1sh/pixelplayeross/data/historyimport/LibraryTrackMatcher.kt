package com.lostf1sh.pixelplayeross.data.historyimport

import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.CatalogItem
import com.lostf1sh.pixelplayeross.data.stats.LibraryCatalog
import com.lostf1sh.pixelplayeross.utils.DEFAULT_WORD_DELIMITERS
import com.lostf1sh.pixelplayeross.utils.TextFold
import com.lostf1sh.pixelplayeross.utils.extractArtistsFromTitle
import com.lostf1sh.pixelplayeross.utils.splitArtistsByDelimiters
import java.util.Locale
import kotlin.math.abs

/**
 * Matches imported plays against a [LibraryCatalog] snapshot.
 *
 * Reuses the repository's existing Unicode NFD diacritic folding (`Normalizer.Form.NFD` + `\p{M}+`,
 * matching `GenreNames`/`AlbumFill`) and artist/title credit helpers (`splitArtistsByDelimiters`,
 * `extractArtistsFromTitle` in `Extensions.kt`).
 *
 * ## Stripped suffixes
 * Only edition/release tags are stripped when folding:
 *  - Remaster tags: `(Remastered)`, `(Remastered 2011)`, `(2011 Remaster)`, `- Remastered 2011`, `- 2011 Remaster`, `(Digital Master)`
 *  - Version/tag qualifiers: `(Radio Edit)`, `(Single Version)`, `(Album Version)`, `(Explicit)`, `[Explicit]`,
 *    `(Clean)`, `[Clean]`, `(Bonus Track)`, `(Deluxe)`, `(Deluxe Edition)`, `(Anniversary Edition)`, `(Mono)`, `(Stereo)`
 *  - Live qualifiers: `(Live)`, `[Live]`, `(Live at …)`, `(Live in …)`, `- Live`
 *  - Featured credits in brackets or trailing: `(feat. …)`, `[ft. …]`, `- feat. …`, `feat. …`
 *
 * ## Protected song-changing modifiers
 * Words that change the song itself — **`Remix`**, **`Acoustic`**, **`Instrumental`** — are NEVER stripped
 * unless no version of that track with that modifier word exists in the library.
 */
class LibraryTrackMatcher private constructor(
    private val exactByArtistTitle: Map<Pair<String, String>, List<CatalogItem>>,
    private val foldedByArtistTitle: Map<Pair<String, String>, List<CatalogItem>>,
    private val foldedStrippedByArtistTitle: Map<Pair<String, String>, List<CatalogItem>>,
    private val foldedBySingleArtistStrippedTitle: Map<Pair<String, String>, List<CatalogItem>>,
    private val candidateModifiersByArtistBaseTitle: Map<Pair<String, String>, Set<String>>,
    private val artistDelimiters: List<String>,
) {
    fun match(
        artistRaw: String,
        titleRaw: String,
        albumRaw: String? = null,
        durationHintMs: Long? = null,
    ): Pair<TrackMatchResult, CatalogItem?> {
        val exactArtist = artistRaw.trim().lowercase(Locale.ROOT)
        val exactTitle = titleRaw.trim().lowercase(Locale.ROOT)
        val exactAlbum = albumRaw?.trim()?.lowercase(Locale.ROOT).orEmpty()

        // 1. Exact artist + title (+ album tie-break)
        exactByArtistTitle[exactArtist to exactTitle]?.let { candidates ->
            val picked = pickBestCandidate(candidates, exactAlbum, durationHintMs, preferAlbumFolded = false)
            return TrackMatchResult(picked.identity, picked.locator, MatchConfidence.EXACT) to picked
        }

        val playArtists = splitArtists(artistRaw, titleRaw, artistDelimiters)
        val foldedArtist = foldBase(artistRaw)
        val foldedTitleKeepSuffix = foldBase(titleRaw)
        val foldedAlbum = foldBase(albumRaw.orEmpty())

        // 2a. Folded (case, accents, punctuation) without stripping suffixes
        if (foldedArtist.isNotEmpty() && foldedTitleKeepSuffix.isNotEmpty()) {
            foldedByArtistTitle[foldedArtist to foldedTitleKeepSuffix]?.let { candidates ->
                val picked = pickBestCandidate(candidates, foldedAlbum, durationHintMs, preferAlbumFolded = true)
                return TrackMatchResult(picked.identity, picked.locator, MatchConfidence.FOLDED) to picked
            }
        }

        // 2b. Folded with safe edition suffixes and featured credits stripped
        val strippedTitle = foldStrippedTitle(titleRaw, artistDelimiters)
        if (foldedArtist.isNotEmpty() && strippedTitle.isNotEmpty()) {
            foldedStrippedByArtistTitle[foldedArtist to strippedTitle]?.let { candidates ->
                val picked = pickBestCandidate(candidates, foldedAlbum, durationHintMs, preferAlbumFolded = true)
                return TrackMatchResult(picked.identity, picked.locator, MatchConfidence.FOLDED) to picked
            }
        }

        // 3a. Partial: multi-artist credits (any split artist + folded/stripped title)
        if (strippedTitle.isNotEmpty()) {
            val multiCandidates = LinkedHashSet<CatalogItem>()
            for (artist in playArtists) {
                val fa = foldBase(artist)
                if (fa.isEmpty()) continue
                foldedBySingleArtistStrippedTitle[fa to strippedTitle]?.let(multiCandidates::addAll)
            }
            if (multiCandidates.isNotEmpty()) {
                val picked = pickBestCandidate(multiCandidates.toList(), foldedAlbum, durationHintMs, preferAlbumFolded = true)
                return TrackMatchResult(picked.identity, picked.locator, MatchConfidence.PARTIAL) to picked
            }
        }

        // 3b. Partial fallback for song-changing modifiers (Remix / Acoustic / Instrumental):
        // only strip if NO version with that modifier exists in the library for that artist + base title.
        val modifiers = extractProtectedModifiers(titleRaw)
        if (modifiers.isNotEmpty()) {
            val baseWithoutModifiers = foldStrippedTitle(stripProtectedModifiers(titleRaw), artistDelimiters)
            if (baseWithoutModifiers.isNotEmpty()) {
                val artistKeys = buildList {
                    if (foldedArtist.isNotEmpty()) add(foldedArtist)
                    playArtists.map { foldBase(it) }.filter { it.isNotEmpty() }.forEach(::add)
                }.distinct()

                val libraryHasModifier = artistKeys.any { ak ->
                    val existing = candidateModifiersByArtistBaseTitle[ak to baseWithoutModifiers].orEmpty()
                    modifiers.any { it in existing }
                }

                if (!libraryHasModifier) {
                    val fallbackCandidates = LinkedHashSet<CatalogItem>()
                    for (ak in artistKeys) {
                        foldedStrippedByArtistTitle[ak to baseWithoutModifiers]?.let(fallbackCandidates::addAll)
                        foldedBySingleArtistStrippedTitle[ak to baseWithoutModifiers]?.let(fallbackCandidates::addAll)
                    }
                    if (fallbackCandidates.isNotEmpty()) {
                        val picked = pickBestCandidate(fallbackCandidates.toList(), foldedAlbum, durationHintMs, preferAlbumFolded = true)
                        return TrackMatchResult(picked.identity, picked.locator, MatchConfidence.PARTIAL) to picked
                    }
                }
            }
        }

        return TrackMatchResult.Unmatched to null
    }

    private fun pickBestCandidate(
        candidates: List<CatalogItem>,
        albumKey: String,
        durationHintMs: Long?,
        preferAlbumFolded: Boolean,
    ): CatalogItem {
        if (candidates.size == 1) return candidates.first()

        if (albumKey.isNotEmpty()) {
            val albumMatches = candidates.filter { item ->
                val itemAlbum = if (preferAlbumFolded) foldBase(item.album) else item.album.trim().lowercase(Locale.ROOT)
                itemAlbum == albumKey
            }
            if (albumMatches.size == 1) return albumMatches.first()
            if (albumMatches.isNotEmpty() && durationHintMs != null && durationHintMs > 0L) {
                return albumMatches.minBy { abs(it.lengthMs - durationHintMs) }
            }
            if (albumMatches.isNotEmpty()) return albumMatches.first()
        }

        if (durationHintMs != null && durationHintMs > 0L) {
            return candidates.minBy { abs(it.lengthMs - durationHintMs) }
        }
        return candidates.first()
    }

    companion object {
        private val wordDelimitersWithX = DEFAULT_WORD_DELIMITERS + "x"

        private val PROTECTED_MODIFIERS = setOf("remix", "acoustic", "instrumental")

        private val SAFE_BRACKET_SUFFIX = Regex(
            """\s*[\(\[]\s*(?:""" +
                """(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?(?:\s+version)?|""" +
                """digital\s+master|""" +
                """radio\s+edit|""" +
                """single\s+version|""" +
                """album\s+version|""" +
                """explicit|""" +
                """clean|""" +
                """bonus\s+track|""" +
                """deluxe(?:\s+edition)?|""" +
                """anniversary(?:\s+edition)?|""" +
                """mono|""" +
                """stereo|""" +
                """live(?:\s+(?:at|in|from)\s+[^\)\]]+|\s+\d{4})?""" +
                """)\s*[\)\]]""",
            RegexOption.IGNORE_CASE,
        )

        private val SAFE_DASH_SUFFIX = Regex(
            """\s+[-–—]\s*(?:""" +
                """(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?(?:\s+version)?|""" +
                """digital\s+master|""" +
                """radio\s+edit|""" +
                """single\s+version|""" +
                """album\s+version|""" +
                """explicit|""" +
                """clean|""" +
                """bonus\s+track|""" +
                """deluxe(?:\s+edition)?|""" +
                """anniversary(?:\s+edition)?|""" +
                """mono|""" +
                """stereo|""" +
                """live(?:\s+(?:at|in|from)\s+.+|\s+\d{4})?""" +
                """)\s*$""",
            RegexOption.IGNORE_CASE,
        )

        private val TRAILING_FEAT = Regex(
            """\s+(?:featuring|feat\.?|ft\.?)\s+.+$""",
            RegexOption.IGNORE_CASE,
        )

        private val PROTECTED_MODIFIER_BRACKET = Regex(
            """\s*[\(\[][^\)\]]*\b(remix|acoustic|instrumental)\b[^\)\]]*[\)\]]""",
            RegexOption.IGNORE_CASE,
        )

        private val PROTECTED_MODIFIER_DASH = Regex(
            """\s+[-–—]\s*[^-]*\b(remix|acoustic|instrumental)\b.*$""",
            RegexOption.IGNORE_CASE,
        )

        private val PROTECTED_MODIFIER_WORD = Regex(
            """\b(remix|acoustic|instrumental)\b""",
            RegexOption.IGNORE_CASE,
        )

        fun foldBase(text: String): String = TextFold.foldWords(text)

        fun stripSafeSuffixes(title: String, artistDelimiters: List<String> = UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS): String {
            var working = title.extractArtistsFromTitle(artistDelimiters, wordDelimitersWithX).first
            var changed: Boolean
            do {
                val before = working
                working = SAFE_BRACKET_SUFFIX.replace(working, "")
                working = SAFE_DASH_SUFFIX.replace(working, "")
                working = TRAILING_FEAT.replace(working, "")
                working = working.trim()
                changed = working != before
            } while (changed)
            return working
        }

        fun foldStrippedTitle(
            title: String,
            artistDelimiters: List<String> = UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS,
        ): String = foldBase(stripSafeSuffixes(title, artistDelimiters))

        fun extractProtectedModifiers(title: String): Set<String> {
            val folded = TextFold.fold(title)
            return PROTECTED_MODIFIER_WORD.findAll(folded)
                .map { it.groupValues[1].lowercase(Locale.ROOT) }
                .filterTo(HashSet()) { it in PROTECTED_MODIFIERS }
        }

        fun stripProtectedModifiers(title: String): String {
            var working = PROTECTED_MODIFIER_BRACKET.replace(title, "")
            working = PROTECTED_MODIFIER_DASH.replace(working, "")
            return working.trim()
        }

        fun splitArtists(
            artistRaw: String,
            titleRaw: String = "",
            artistDelimiters: List<String> = UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS,
        ): List<String> {
            val fromArtist = artistRaw.splitArtistsByDelimiters(artistDelimiters, wordDelimitersWithX)
            if (titleRaw.isBlank()) return fromArtist
            val (_, titleArtists) = titleRaw.extractArtistsFromTitle(artistDelimiters, wordDelimitersWithX)
            if (titleArtists.isEmpty()) return fromArtist
            val combined = fromArtist.toMutableList()
            for (ta in titleArtists) {
                if (combined.none { it.equals(ta, ignoreCase = true) }) combined += ta
            }
            return combined
        }

        fun build(
            catalog: LibraryCatalog,
            artistDelimiters: List<String> = UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS,
        ): LibraryTrackMatcher {
            val exact = HashMap<Pair<String, String>, MutableList<CatalogItem>>(catalog.items.size * 2)
            val folded = HashMap<Pair<String, String>, MutableList<CatalogItem>>(catalog.items.size * 2)
            val foldedStripped = HashMap<Pair<String, String>, MutableList<CatalogItem>>(catalog.items.size * 2)
            val bySingleArtist = HashMap<Pair<String, String>, MutableList<CatalogItem>>(catalog.items.size * 2)
            val modifiersByBase = HashMap<Pair<String, String>, MutableSet<String>>(catalog.items.size)

            for (item in catalog.items) {
                val exactTitle = item.title.trim().lowercase(Locale.ROOT)
                val foldedTitle = foldBase(item.title)
                val strippedTitle = foldStrippedTitle(item.title, artistDelimiters)
                val baseNoMod = foldStrippedTitle(stripProtectedModifiers(item.title), artistDelimiters)
                val itemModifiers = extractProtectedModifiers(item.title)

                val rawArtistVariants = buildSet {
                    if (item.song.artist.isNotBlank()) add(item.song.artist.trim())
                    if (item.artistLine.isNotBlank()) add(item.artistLine.trim())
                }
                for (rawArtist in rawArtistVariants) {
                    exact.getOrPut(rawArtist.lowercase(Locale.ROOT) to exactTitle) { ArrayList(1) }.add(item)
                    val fa = foldBase(rawArtist)
                    if (fa.isNotEmpty()) {
                        if (foldedTitle.isNotEmpty()) {
                            folded.getOrPut(fa to foldedTitle) { ArrayList(1) }.add(item)
                        }
                        if (strippedTitle.isNotEmpty()) {
                            foldedStripped.getOrPut(fa to strippedTitle) { ArrayList(1) }.add(item)
                        }
                        if (baseNoMod.isNotEmpty() && itemModifiers.isNotEmpty()) {
                            modifiersByBase.getOrPut(fa to baseNoMod) { HashSet() }.addAll(itemModifiers)
                        }
                    }
                }

                val singleArtists = buildSet {
                    for (a in item.artists) {
                        add(a)
                        splitArtists(a, item.title, artistDelimiters).forEach(::add)
                    }
                    splitArtists(item.song.artist, item.title, artistDelimiters).forEach(::add)
                }
                for (artist in singleArtists) {
                    val fa = foldBase(artist)
                    if (fa.isEmpty()) continue
                    if (strippedTitle.isNotEmpty()) {
                        bySingleArtist.getOrPut(fa to strippedTitle) { ArrayList(1) }.add(item)
                    }
                    if (baseNoMod.isNotEmpty() && itemModifiers.isNotEmpty()) {
                        modifiersByBase.getOrPut(fa to baseNoMod) { HashSet() }.addAll(itemModifiers)
                    }
                }
            }

            return LibraryTrackMatcher(
                exactByArtistTitle = exact,
                foldedByArtistTitle = folded,
                foldedStrippedByArtistTitle = foldedStripped,
                foldedBySingleArtistStrippedTitle = bySingleArtist,
                candidateModifiersByArtistBaseTitle = modifiersByBase,
                artistDelimiters = artistDelimiters,
            )
        }
    }
}
