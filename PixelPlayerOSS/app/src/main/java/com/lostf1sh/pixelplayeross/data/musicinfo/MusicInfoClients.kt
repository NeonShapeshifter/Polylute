package com.lostf1sh.pixelplayeross.data.musicinfo

import com.lostf1sh.pixelplayeross.data.network.PriorityRequestGate
import com.lostf1sh.pixelplayeross.data.network.RequestPriority
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import com.lostf1sh.pixelplayeross.utils.splitArtistsByDelimiters
import android.os.SystemClock
import com.lostf1sh.pixelplayeross.data.network.HttpUserAgent
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * B40-F4: what the music-info features ask of the internet, and nothing else.
 *
 * MusicBrainz → (Wikidata) → Wikipedia for descriptions, MusicBrainz → Cover Art Archive for album covers, MusicBrainz → Wikidata →
 * Wikimedia Commons for artist photos. **Every request goes through [MusicInfoHttp]**: HTTPS only, the app's own User-Agent (the one that
 * names the project, because MusicBrainz and Wikimedia both ask clients to be reachable), and per-host pacing (one request per second for MusicBrainz). Nothing is sent but the name of an artist or an album, an identifier the previous answer gave, or a file name.
 *
 * The parsers are plain functions over JSON so the tests read recorded answers; the clients take the OkHttp client and the root address
 * so the tests can put a fake server in front of them.
 */
class MusicInfoHttp(
    baseClient: OkHttpClient,
    private val userAgent: String = HttpUserAgent.MUSICBRAINZ,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val client = baseClient.newBuilder().followRedirects(true).followSslRedirects(true).callTimeout(30, TimeUnit.SECONDS).build()
    private class HostGate {
        val gate = PriorityRequestGate()
        var lastStartedAt = Long.MIN_VALUE / 2
    }
    private val hosts = java.util.concurrent.ConcurrentHashMap<String, HostGate>()

    /** Every request taken so far, for the tests and the privacy account: the address, in the order it went out. */
    val sent: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private suspend fun <T> limited(url: HttpUrl, block: () -> T): T {
        require(url.isHttps) { "music info goes over HTTPS only" }
        val host = hosts.getOrPut(url.host) { HostGate() }
        return host.gate.withPermit {
            // https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting requires <=1/s for MB only.
            // https://www.mediawiki.org/wiki/API:Etiquette asks for serial reads, identification and caching, not MB's quota.
            // https://musicbrainz.org/doc/Cover_Art_Archive/API documents 503 throttling. 4/s here is our ceiling, not a claimed quota.
            val interval = if (url.host == "musicbrainz.org") minIntervalMs else minOf(minIntervalMs, 250L)
            val wait = interval - (nowMs() - host.lastStartedAt)
            if (wait > 0) sleep(wait)
            host.lastStartedAt = nowMs()
            sent += url.toString()
            block()
        }
    }

    /** A JSON answer, or null when the server says there is nothing (404). Any other failure is an [IOException]. */
    suspend fun json(url: HttpUrl): JSONObject? = limited(url) {
        client.newCall(request(url, "application/json")).execute().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${url.host}")
            JSONObject(response.body.string())
        }
    }

    /** A file (an image), or null when there is none (404) or it is larger than [maxBytes]. */
    suspend fun bytes(url: HttpUrl, maxBytes: Long): ByteArray? = limited(url) {
        client.newCall(request(url, "image/*")).execute().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${url.host}")
            if (response.body.contentType()?.type != "image") return@use null
            val declared = response.body.contentLength()
            if (declared > maxBytes) return@use null
            val data = response.body.bytes()
            data.takeIf { it.size <= maxBytes && it.isNotEmpty() }
        }
    }

    private fun request(url: HttpUrl, accept: String) =
        Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", accept).get().build()

    companion object {
        /** MusicBrainz asks for no more than one request per second. */
        const val MIN_INTERVAL_MS = 1_100L
    }
}

/** A MusicBrainz match: the identifier the next question uses, and how sure the answer was. */
data class MbMatch(val id: String, val name: String, val score: Int)

object MusicInfoMatching {
    /** Names compare as letters and digits only: "Beyoncé" and "beyonce" are not the same, "AC/DC" and "ac dc" are. */
    fun normalize(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

    /** The least score MusicBrainz gives a match the app will believe (and the name must also be the same). */
    const val MIN_SCORE = 90
}

/** Deduplicate one entity, without holding an unrelated entity behind the host queue.
 * A listener joining the same pending lookup promotes its local context in the host queue.
 */
private class MusicInfoLookupGates {
    private val entries = java.util.concurrent.ConcurrentHashMap<String, PriorityRequestGate>()
    suspend fun <T> request(key: String, block: suspend () -> T): T {
        val priority = RequestPriority(currentCoroutineContext()[RequestPriority]?.background == true)
        return withContext(priority) {
            entries.computeIfAbsent(key) { PriorityRequestGate(promoteOwner = true) }.withPermit(block)
        }
    }
}

class MusicBrainzClient(private val http: MusicInfoHttp, root: String = "https://musicbrainz.org/ws/2/") {
    private val root = root.toHttpUrl()

    private val artistGate = MusicInfoLookupGates()
    private val artistMatches = object : LinkedHashMap<String, MbMatch?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MbMatch?>?): Boolean = size > 256
    }

    /** Concurrent photo and description queues share one conclusive artist lookup; network failures are retried. */
    suspend fun findArtist(name: String): MbMatch? {
        val key = name.trim().lowercase(Locale.ROOT)
        return artistGate.request(key) {
            if (currentCoroutineContext()[MusicInfoRefresh] == null) synchronized(artistMatches) {
                if (artistMatches.containsKey(key)) return@request artistMatches[key]
            }
            val url = root.newBuilder().addPathSegment("artist")
                .addQueryParameter("query", "artist:\"${escape(name)}\"").addQueryParameter("fmt", "json").addQueryParameter("limit", "5").build()
            parseArtistSearch(http.json(url), name).also { synchronized(artistMatches) { artistMatches[key] = it } }
        }
    }

    /** The release (an edition of the album) with this title by this artist, or null. */
    suspend fun findRelease(album: String, artist: String): MbMatch? {
        val url = root.newBuilder().addPathSegment("release")
            .addQueryParameter("query", "release:\"${escape(album)}\" AND artist:\"${escape(artist)}\"")
            .addQueryParameter("fmt", "json").addQueryParameter("limit", "5").build()
        return parseReleaseSearch(http.json(url), album)
    }

    /** The release group (the album, all its editions) with this title by this artist, or null. */
    suspend fun findReleaseGroup(album: String, artist: String): MbMatch? {
        val credited = firstCreditedArtist(artist)
        if (credited.isBlank()) return null
        fun query(withArtist: Boolean) = root.newBuilder().addPathSegment("release-group")
            .addQueryParameter("query", "releasegroup:\"${escape(album)}\"" + if (withArtist) " AND artist:\"${escape(credited)}\"" else "")
            .addQueryParameter("fmt", "json").addQueryParameter("limit", "5").build()
        fun matching(json: JSONObject?, requireCredit: Boolean): MbMatch? {
            val groups = json?.optJSONArray("release-groups") ?: return null
            for (i in 0 until groups.length()) {
                val group = groups.optJSONObject(i) ?: continue
                val credits = group.optJSONArray("artist-credit")
                val names = (0 until (credits?.length() ?: 0)).mapNotNull { index ->
                    credits?.optJSONObject(index)?.let { it.optJSONObject("artist")?.optString("name") ?: it.optString("name") }
                }
                val sameArtist = names.any { MusicInfoMatching.normalize(it) == MusicInfoMatching.normalize(credited) }
                if ((requireCredit || credits != null) && !sameArtist) continue
                parseReleaseGroupSearch(JSONObject().put("release-groups", JSONArray().put(group)), album)?.let { return it }
            }
            return null
        }
        return matching(http.json(query(true)), requireCredit = false) ?: matching(http.json(query(false)), requireCredit = true)
    }

    private val detailsGate = MusicInfoLookupGates()
    private val details = object : LinkedHashMap<String, JSONObject>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?): Boolean = size > 256
    }

    /** MusicBrainz's own annotation and image relationships are checked before the Wikimedia fallback. */
    suspend fun entityDetails(kind: String, mbid: String): JSONObject? {
        val key = "$kind/$mbid"
        return detailsGate.request(key) {
            if (currentCoroutineContext()[MusicInfoRefresh] == null) synchronized(details) {
                details[key]?.let { return@request it }
            }
            val url = root.newBuilder().addPathSegment(kind).addPathSegment(mbid)
                .addQueryParameter("inc", "annotation+url-rels").addQueryParameter("fmt", "json").build()
            http.json(url)?.also { synchronized(details) { details[key] = it } }
        }
    }

    suspend fun forgetArtist(name: String) {
        synchronized(artistMatches) { artistMatches.remove(name.trim().lowercase(Locale.ROOT)) }
        synchronized(details) { details.clear() }
    }
    suspend fun forgetAlbumDetails() { synchronized(details) { details.clear() } }

    suspend fun wikidataIdOf(kind: String, mbid: String): String? = parseWikidataRelation(entityDetails(kind, mbid))

    suspend fun linkedImage(url: String): ByteArray? {
        val address = runCatching { url.toHttpUrl() }.getOrNull() ?: return null
        if (!address.isHttps) return null
        return http.bytes(address, 8L * 1024 * 1024)
    }

    companion object {
        internal fun firstCreditedArtist(artist: String): String = with(com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository) {
            artist.splitArtistsByDelimiters(DEFAULT_ARTIST_DELIMITERS, DEFAULT_ARTIST_WORD_DELIMITERS).firstOrNull().orEmpty()
        }

        internal fun annotation(json: JSONObject?): String? {
            val raw = json?.optJSONObject("annotation")?.optString("text")
                ?: json?.opt("annotation")?.takeIf { it is String }?.toString()
            return raw?.trim()?.takeIf { it.isNotBlank() }
        }

        internal fun imageRelation(json: JSONObject?): String? {
            val relations = json?.optJSONArray("relations") ?: return null
            for (i in 0 until relations.length()) {
                val relation = relations.optJSONObject(i) ?: continue
                if (relation.optString("type") == "image" || relation.optString("type-id") == "221132e9-e30e-43f2-a741-15afc4c5fa7c") {
                    relation.optJSONObject("url")?.optString("resource")?.takeIf { it.startsWith("https://") }?.let { return it }
                }
            }
            return null
        }

        internal fun escape(value: String): String = buildString {
            value.trim().forEach { c ->
                if (c in SPECIAL) append('\\')
                append(c)
            }
        }

        private val SPECIAL = setOf('+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', ':', '\\', '/')

        private fun best(array: JSONArray?, nameKey: String, expected: String): MbMatch? {
            if (array == null) return null
            val wanted = MusicInfoMatching.normalize(expected)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = item.optString(nameKey)
                val score = item.optInt("score", 0)
                if (score >= MusicInfoMatching.MIN_SCORE && MusicInfoMatching.normalize(name) == wanted) return MbMatch(id, name, score)
            }
            return null
        }

        internal fun parseArtistSearch(json: JSONObject?, name: String): MbMatch? = best(json?.optJSONArray("artists"), "name", name)
        internal fun parseReleaseSearch(json: JSONObject?, album: String): MbMatch? = best(json?.optJSONArray("releases"), "title", album)
        internal fun parseReleaseGroupSearch(json: JSONObject?, album: String): MbMatch? = best(json?.optJSONArray("release-groups"), "title", album)

        /** The `wikidata` relation's address (`https://www.wikidata.org/wiki/Q1299`) as its item id. */
        internal fun parseWikidataRelation(json: JSONObject?): String? {
            val relations = json?.optJSONArray("relations") ?: return null
            for (i in 0 until relations.length()) {
                val relation = relations.optJSONObject(i) ?: continue
                if (relation.optString("type") != "wikidata") continue
                val resource = relation.optJSONObject("url")?.optString("resource").orEmpty()
                val id = resource.substringAfterLast('/')
                if (Regex("Q\\d+").matches(id)) return id
            }
            return null
        }
    }
}

class CoverArtArchiveClient(private val http: MusicInfoHttp, root: String = "https://coverartarchive.org/") {
    private val root = root.toHttpUrl()

    /** The front cover of the release, 500 px, or null when the archive has none. */
    suspend fun front(releaseId: String, maxBytes: Long = MAX_IMAGE_BYTES): ByteArray? =
        http.bytes(root.newBuilder().addPathSegment("release").addPathSegment(releaseId).addPathSegment("front-500").build(), maxBytes)

    /** The front cover of any edition of the album (the release group), 500 px, or null. */
    suspend fun frontOfGroup(releaseGroupId: String, maxBytes: Long = MAX_IMAGE_BYTES): ByteArray? =
        http.bytes(root.newBuilder().addPathSegment("release-group").addPathSegment(releaseGroupId).addPathSegment("front-500").build(), maxBytes)

    companion object {
        const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    }
}

/** A picture on Wikimedia Commons, with who made it and under what licence: shown wherever the picture is. */
data class CommonsImage(
    val fileName: String,
    val thumbnailUrl: String,
    val author: String?,
    val license: String?,
    val licenseUrl: String?,
    val pageUrl: String,
)

class WikidataClient(private val http: MusicInfoHttp, root: String = "https://www.wikidata.org/w/api.php") {
    private val root = root.toHttpUrl()

    /** Independent fallback when MusicBrainz has no entity/link. Match the name and a musical entity, not a namesake city or person. */
    suspend fun findMusicItem(name: String, albumArtist: String? = null): String? {
        val url = root.newBuilder().addQueryParameter("action", "wbsearchentities").addQueryParameter("search", name)
            .addQueryParameter("language", "en").addQueryParameter("uselang", "en").addQueryParameter("type", "item")
            .addQueryParameter("limit", "5").addQueryParameter("format", "json").build()
        return parseMusicSearch(http.json(url), name, albumArtist)
    }

    /** The Commons file name of the item's image (property P18), or null. */
    suspend fun imageFile(qid: String): String? {
        val url = root.newBuilder().addQueryParameter("action", "wbgetclaims").addQueryParameter("entity", qid)
            .addQueryParameter("property", "P18").addQueryParameter("format", "json").build()
        return parseImageFile(http.json(url))
    }

    /** The titles of the item's Wikipedia pages in [languages] (`en`, `es`, `sv`), by language. */
    suspend fun wikipediaTitles(qid: String, languages: List<String>): Map<String, String> {
        val url = root.newBuilder().addQueryParameter("action", "wbgetentities").addQueryParameter("ids", qid)
            .addQueryParameter("props", "sitelinks").addQueryParameter("sitefilter", languages.joinToString("|") { "${it}wiki" })
            .addQueryParameter("format", "json").build()
        return parseSitelinks(http.json(url), qid, languages)
    }

    companion object {
        internal fun parseMusicSearch(json: JSONObject?, name: String, albumArtist: String?): String? {
            val results = json?.optJSONArray("search") ?: return null
            val wanted = MusicInfoMatching.normalize(name)
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val names = listOf(item.optString("label"), item.optJSONObject("match")?.optString("text").orEmpty())
                if (names.none { MusicInfoMatching.normalize(it) == wanted }) continue
                val description = item.optString("description").lowercase()
                val relevant = if (albumArtist != null) description.contains("album") && albumArtist.isNotBlank() &&
                    MusicInfoMatching.normalize(description).contains(MusicInfoMatching.normalize(albumArtist))
                else Regex("\\b(musician|singer|band|composer|rapper|orchestra|songwriter|music group|musical group|record producer|disc jockey)\\b").containsMatchIn(description)
                val id = item.optString("id")
                if (relevant && Regex("Q\\d+").matches(id)) return id
            }
            return null
        }

        internal fun parseImageFile(json: JSONObject?): String? {
            val claims = json?.optJSONObject("claims")?.optJSONArray("P18") ?: return null
            for (i in 0 until claims.length()) {
                val value = claims.optJSONObject(i)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value").orEmpty()
                if (value.isNotBlank()) return value
            }
            return null
        }

        internal fun parseSitelinks(json: JSONObject?, qid: String, languages: List<String>): Map<String, String> {
            val links = json?.optJSONObject("entities")?.optJSONObject(qid)?.optJSONObject("sitelinks") ?: return emptyMap()
            return languages.mapNotNull { lang ->
                links.optJSONObject("${lang}wiki")?.optString("title")?.takeIf { it.isNotBlank() }?.let { lang to it }
            }.toMap()
        }
    }
}

class CommonsClient(private val http: MusicInfoHttp, root: String = "https://commons.wikimedia.org/w/api.php") {
    private val root = root.toHttpUrl()

    /** The picture's 800 px thumbnail address, its author and its licence, or null. */
    suspend fun imageInfo(fileName: String): CommonsImage? {
        val url = root.newBuilder().addQueryParameter("action", "query").addQueryParameter("titles", "File:$fileName")
            .addQueryParameter("prop", "imageinfo").addQueryParameter("iiprop", "url|extmetadata").addQueryParameter("iiurlwidth", "800")
            .addQueryParameter("format", "json").build()
        return parseImageInfo(http.json(url), fileName)
    }

    suspend fun download(image: CommonsImage, maxBytes: Long = MAX_IMAGE_BYTES): ByteArray? = http.bytes(image.thumbnailUrl.toHttpUrl(), maxBytes)

    companion object {
        const val MAX_IMAGE_BYTES = 4L * 1024 * 1024

        /** Commons relations may name a file page or an original/thumbnail on the upload host. */
        internal fun relationFileName(relation: String): String? {
            val url = runCatching { relation.toHttpUrl() }.getOrNull() ?: return null
            val segments = url.pathSegments
            return when {
                url.host == "commons.wikimedia.org" && segments.firstOrNull() == "wiki" ->
                    segments.drop(1).joinToString("/").takeIf { it.startsWith("File:") }?.removePrefix("File:")
                url.host == "upload.wikimedia.org" && segments.take(2) == listOf("wikipedia", "commons") ->
                    segments.getOrNull(if (segments.getOrNull(2) == "thumb") 5 else 4)
                else -> null
            }?.takeIf { it.isNotBlank() }?.replace('_', ' ')
        }

        internal fun parseImageInfo(json: JSONObject?, fileName: String): CommonsImage? {
            val pages = json?.optJSONObject("query")?.optJSONObject("pages") ?: return null
            val key = pages.keys().asSequence().firstOrNull() ?: return null
            val info = pages.optJSONObject(key)?.optJSONArray("imageinfo")?.optJSONObject(0) ?: return null
            val thumb = info.optString("thumburl").ifBlank { info.optString("url") }
            if (thumb.isBlank() || !thumb.startsWith("https://")) return null
            val meta = info.optJSONObject("extmetadata")
            fun field(name: String) = meta?.optJSONObject(name)?.optString("value")?.let(::plain)?.takeIf { it.isNotBlank() }
            return CommonsImage(
                fileName = fileName,
                thumbnailUrl = thumb,
                author = field("Artist"),
                license = field("LicenseShortName"),
                licenseUrl = field("LicenseUrl"),
                pageUrl = info.optString("descriptionurl").ifBlank { "https://commons.wikimedia.org/wiki/File:${fileName.replace(' ', '_')}" },
            )
        }

        /** Commons gives the author as HTML ("<a href=...>Name</a>"): the text of it. */
        internal fun plain(html: String): String = html.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#039;", "'").replace(Regex("\\s+"), " ").trim()
    }
}

/** A Wikipedia summary in one language, with where it came from. */
data class WikiSummary(val language: String, val title: String, val extract: String, val pageUrl: String)

class WikipediaClient(private val http: MusicInfoHttp, private val rootFor: (String) -> String = { lang -> "https://$lang.wikipedia.org/api/rest_v1/page/summary/" }) {
    suspend fun summary(language: String, title: String): WikiSummary? {
        val url = rootFor(language).toHttpUrl().newBuilder().addPathSegment(title.replace(' ', '_')).build()
        return parseSummary(http.json(url), language)
    }

    companion object {
        internal fun parseSummary(json: JSONObject?, language: String): WikiSummary? {
            if (json == null) return null
            if (json.optString("type") == "disambiguation") return null
            val extract = json.optString("extract").trim()
            if (extract.isBlank()) return null
            val page = json.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page").orEmpty()
            if (!page.startsWith("https://")) return null
            return WikiSummary(language, json.optString("title"), extract, page)
        }
    }
}
