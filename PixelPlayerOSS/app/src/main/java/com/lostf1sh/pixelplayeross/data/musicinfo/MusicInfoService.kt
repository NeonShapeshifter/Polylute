package com.lostf1sh.pixelplayeross.data.musicinfo

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject

/** An artist's picture on the phone, with what Commons requires to be shown beside it. */
data class ArtistPhoto(
    val path: String,
    val author: String?,
    val license: String?,
    val licenseUrl: String?,
    /** The picture's page on Wikimedia Commons. */
    val pageUrl: String?,
    val provider: String = "Wikimedia Commons",
)

/** A description, with where it is from: shown as "From Wikipedia" and a link, as CC BY-SA asks. */
data class Description(val text: String, val title: String, val pageUrl: String, val language: String)

enum class AboutKind(val musicBrainz: String) { ARTIST("artist"), ALBUM("release-group") }

/**
 * B40-F4: the three questions the app asks of the internet about music, each behind its own switch, each answered from the cache first.
 *
 * - **An artist's photo**: MusicBrainz finds the artist, its Wikidata item names the picture (P18), Commons says who made it and under what
 *   licence; both are kept with the picture and shown with it.
 * - **An album's cover**: MusicBrainz finds the release, the Cover Art Archive has its front cover.
 * - **A description**: MusicBrainz finds the artist or album, Wikidata names its Wikipedia pages, the app asks the Wikipedia summary in the
 *   app's language and then in English.
 *
 * A switch that is off means **no request at all** for that kind ([fetch*] answers from the cache or not at all); the cache is read whether
 * the switch is on or not, so what was downloaded stays visible, offline included. A request that fails for want of a network is not
 * remembered as "nothing found", so it is tried again; a request that found nothing is remembered for 30 days ([MusicInfoCache.MISSING_TTL_MS]).
 *
 * **The service owns its threading** (B40-fix1). Every question, and every read of the cache (which stats files and writes the index),
 * runs on [io] inside the service, so a caller on the main thread (a view model's scope) cannot get it wrong: the clients block on OkHttp,
 * and on Android a request from the main thread is a `NetworkOnMainThreadException`, which this class used to swallow as "no answer".
 *
 * **A description is kept per language** (B40-fix1). The cache key carries the language of the text, so the app's language changing
 * asks for the new language instead of showing the old one, and changing back finds the first one still there. What is shown is the
 * first language of [languages] that is cached; a language that is preferred over it and is neither cached nor known to be missing is
 * asked for first. Entries written before the key had a language (under the old key) are still read, by the `language` stored in them.
 *
 * Plain Kotlin on purpose: the tests drive it with a fake server in front of the clients.
 */
class MusicInfoService(
    private val cache: MusicInfoCache,
    private val brainz: MusicBrainzClient,
    private val coverArt: CoverArtArchiveClient,
    private val wikidata: WikidataClient,
    private val commons: CommonsClient,
    private val wikipedia: WikipediaClient,
    private val artistPhotosEnabled: suspend () -> Boolean,
    private val coversEnabled: suspend () -> Boolean,
    private val descriptionsEnabled: suspend () -> Boolean,
    /** The languages of a description, the app's first and English last. */
    private val languages: () -> List<String>,
    /** Where the questions and the cache reads run. The tests give their own. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val assets: com.lostf1sh.pixelplayeross.data.network.DownloadAssetRegistry? = null,
) {
    private val deletedDownloads = cache.deletedDownloads

    suspend fun suppressArtist(name: String) {
        deletedDownloads.mark(DownloadKeys.artist(name))
    }

    suspend fun suppressAlbum(name: String, artist: String) {
        deletedDownloads.mark(DownloadKeys.album(name, artist))
    }

    private suspend fun refreshing() = kotlinx.coroutines.currentCoroutineContext()[MusicInfoRefresh] != null

    private suspend fun note(kind: MusicInfoCache.Kind, key: String, file: File? = null, written: Boolean = false) {
        assets?.note(com.lostf1sh.pixelplayeross.data.network.DownloadAsset("info|${kind.name}|$key", file?.absolutePath), written)
    }

    suspend fun refreshArtist(name: String) = withContext(io + MusicInfoRefresh()) {
        deletedDownloads.allow(DownloadKeys.artist(name))
        brainz.forgetArtist(name)
        kotlinx.coroutines.coroutineScope {
            launch { fetchArtistPhoto(name, userRequested = true) }
            launch { fetchAbout(AboutKind.ARTIST, name, userRequested = true) }
        }
    }

    suspend fun refreshAlbum(album: String, artist: String) = withContext(io + MusicInfoRefresh()) {
        deletedDownloads.allow(DownloadKeys.album(album, artist))
        brainz.forgetAlbumDetails()
        kotlinx.coroutines.coroutineScope {
            launch { fetchCover(album, artist, userRequested = true) }
            launch { fetchAbout(AboutKind.ALBUM, album, artist, userRequested = true) }
        }
    }

    // ── artist photo ─────────────────────────────────────────────────────────────────────────────

    suspend fun cachedArtistPhoto(name: String): ArtistPhoto? = withContext(io) { readArtistPhoto(name) }

    private suspend fun readArtistPhoto(name: String): ArtistPhoto? {
        if (deletedDownloads.contains(DownloadKeys.photo(name))) return null
        val hit = cache.get(MusicInfoCache.Kind.ARTIST_PHOTO, photoKey(name)) ?: return null
        val file = hit.file?.takeIf { it.isFile } ?: return null
        val meta = hit.meta?.let { runCatching { JSONObject(it) }.getOrNull() }
        fun field(key: String) = meta?.optString(key)?.takeIf { it.isNotBlank() }
        note(MusicInfoCache.Kind.ARTIST_PHOTO, photoKey(name), file)
        return ArtistPhoto(file.absolutePath, field("author"), field("license"), field("licenseUrl"), field("pageUrl"), field("provider") ?: "Wikimedia Commons")
    }

    suspend fun fetchArtistPhoto(name: String, userRequested: Boolean = false): ArtistPhoto? = withContext(io) {
        fetchArtistPhotoOnIo(name, userRequested)
    }

    private suspend fun fetchArtistPhotoOnIo(name: String, userRequested: Boolean): ArtistPhoto? {
        if (deletedDownloads.contains(DownloadKeys.photo(name))) return null
        val previous = readArtistPhoto(name)
        val refresh = refreshing()
        if (!refresh && previous != null) return previous
        if (name.isBlank() || (!userRequested && !artistPhotosEnabled()) || (!refresh && cache.isMissing(MusicInfoCache.Kind.ARTIST_PHOTO, photoKey(name)))) return null
        val found = attempt("artist photo of $name") {
            var uncertain = false
            val missing: suspend () -> ArtistPhoto? = {
                if (!uncertain && !refresh) {
                    deletedDownloads.commitIfAllowed(DownloadKeys.photo(name)) {
                        cache.putMissing(MusicInfoCache.Kind.ARTIST_PHOTO, photoKey(name))
                    }
                }
                null
            }
            val artist = brainz.findArtist(name)
            val details = artist?.let { brainz.entityDetails("artist", it.id) }
            val linked = MusicBrainzClient.imageRelation(details)
            if (linked != null) {
                val commonsName = CommonsClient.relationFileName(linked)
                try {
                    if (commonsName != null) {
                        commons.imageInfo(commonsName)?.let { image -> saveCommonsPhoto(name, image)?.let { return@attempt it } }
                    } else {
                        val data = brainz.linkedImage(linked)?.takeIf(DownloadedImage::decodes)
                        if (data != null && artist != null) {
                            val page = "https://musicbrainz.org/artist/${artist.id}"
                            val meta = JSONObject().put("provider", "MusicBrainz").put("pageUrl", page).toString()
                            return@attempt deletedDownloads.commitIfAllowed(DownloadKeys.photo(name)) {
                                val file = cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, photoKey(name), data, "img", meta)
                                note(MusicInfoCache.Kind.ARTIST_PHOTO, photoKey(name), file, written = true)
                                ArtistPhoto(file.absolutePath, null, null, null, page, "MusicBrainz")
                            }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: IOException) { uncertain = true; com.lostf1sh.pixelplayeross.data.network.noteLookupFailure() }
            }
            val qid = MusicBrainzClient.parseWikidataRelation(details) ?: wikidata.findMusicItem(name) ?: return@attempt missing()
            val fileName = wikidata.imageFile(qid) ?: return@attempt missing()
            val image = commons.imageInfo(fileName) ?: return@attempt missing()
            saveCommonsPhoto(name, image) ?: missing()
        }
        return if (deletedDownloads.contains(DownloadKeys.photo(name))) null else found ?: previous
    }

    private suspend fun saveCommonsPhoto(name: String, image: CommonsImage): ArtistPhoto? {
        val data = commons.download(image)?.takeIf(DownloadedImage::decodes) ?: return null
        val meta = JSONObject().put("author", image.author ?: "").put("license", image.license ?: "")
            .put("licenseUrl", image.licenseUrl ?: "").put("pageUrl", image.pageUrl).toString()
        return deletedDownloads.commitIfAllowed(DownloadKeys.photo(name)) {
            val file = cache.putFile(MusicInfoCache.Kind.ARTIST_PHOTO, photoKey(name), data, "jpg", meta)
            note(MusicInfoCache.Kind.ARTIST_PHOTO, photoKey(name), file, written = true)
            ArtistPhoto(file.absolutePath, image.author, image.license, image.licenseUrl, image.pageUrl)
        }
    }

    // ── album cover ──────────────────────────────────────────────────────────────────────────────

    suspend fun cachedCover(album: String, artist: String): File? = withContext(io) { readCover(album, artist) }

    private suspend fun readCover(album: String, artist: String): File? {
        if (deletedDownloads.contains(DownloadKeys.cover(album, artist))) return null
        val file = cache.get(MusicInfoCache.Kind.COVER, coverKey(album, artist))?.file?.takeIf { it.isFile } ?: return null
        note(MusicInfoCache.Kind.COVER, coverKey(album, artist), file)
        return file
    }

    suspend fun fetchCover(album: String, artist: String, userRequested: Boolean = false): File? = withContext(io) {
        fetchCoverOnIo(album, artist, userRequested)
    }

    private suspend fun fetchCoverOnIo(album: String, artist: String, userRequested: Boolean): File? {
        if (deletedDownloads.contains(DownloadKeys.cover(album, artist))) return null
        val previous = readCover(album, artist)
        val refresh = refreshing()
        if (!refresh && previous != null) return previous
        if (album.isBlank() || artist.isBlank() || (!userRequested && !coversEnabled()) || (!refresh && cache.isMissing(MusicInfoCache.Kind.COVER, coverKey(album, artist)))) return null
        val found = attempt("cover of $album") {
            val key = coverKey(album, artist)
            var data = brainz.findRelease(album, artist)?.let { coverArt.front(it.id) }
            if (data == null) {
                // the edition MusicBrainz found has no cover; another edition of the album may
                data = brainz.findReleaseGroup(album, artist)?.let { coverArt.frontOfGroup(it.id) }
            }
            data = data?.takeIf(DownloadedImage::decodes)
            if (data == null) {
                if (!refresh) {
                    deletedDownloads.commitIfAllowed(DownloadKeys.cover(album, artist)) {
                        cache.putMissing(MusicInfoCache.Kind.COVER, key)
                    }
                }
                null
            } else {
                deletedDownloads.commitIfAllowed(DownloadKeys.cover(album, artist)) {
                    cache.putFile(MusicInfoCache.Kind.COVER, key, data, "jpg", "Cover Art Archive").also {
                        note(MusicInfoCache.Kind.COVER, key, it, written = true)
                    }
                }
            }
        }
        return if (deletedDownloads.contains(DownloadKeys.cover(album, artist))) null else found ?: previous
    }

    // ── description ──────────────────────────────────────────────────────────────────────────────

    /** The description in the first of the app's languages that has one on the phone, or null. Never asks the network. */
    suspend fun cachedAbout(kind: AboutKind, name: String, artist: String?): Description? = withContext(io) {
        if (deletedDownloads.contains(DownloadKeys.about(kind, name, artist))) return@withContext null
        val wanted = languages().distinct()
        readAbout(kind, name, artist, "und") ?: wanted.firstNotNullOfOrNull { readAbout(kind, name, artist, it) }
    }

    /** What was downloaded in [language]: under its own key, or (before keys had a language) under the old key with that language stored in it. */
    private suspend fun readAbout(kind: AboutKind, name: String, artist: String?, language: String): Description? =
        readAboutEntry(aboutKey(kind, name, artist, language))?.takeIf { it.language == language }
            ?: readAboutEntry(aboutKey(kind, name, artist))?.takeIf { it.language == language }

    private suspend fun readAboutEntry(key: String): Description? {
        val hit = cache.get(MusicInfoCache.Kind.ABOUT, key) ?: return null
        val json = hit.meta?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val text = json.optString("text")
        val url = json.optString("pageUrl")
        if (text.isBlank() || !url.startsWith("https://")) return null
        note(MusicInfoCache.Kind.ABOUT, key)
        return Description(text, json.optString("title"), url, json.optString("language"))
    }

    suspend fun fetchAbout(kind: AboutKind, name: String, artist: String? = null, userRequested: Boolean = false): Description? = withContext(io) {
        fetchAboutOnIo(kind, name, artist, userRequested)
    }

    private suspend fun fetchAboutOnIo(kind: AboutKind, name: String, artist: String?, userRequested: Boolean): Description? {
        if (deletedDownloads.contains(DownloadKeys.about(kind, name, artist))) return null
        val wanted = languages().distinct()
        // what is on the phone, by the app's preference; and the languages preferred over it that nobody has said are missing
        val refresh = refreshing()
        if (!refresh) readAbout(kind, name, artist, "und")?.let { return it }
        val best = wanted.firstNotNullOfOrNull { readAbout(kind, name, artist, it) }
        val preferredOverBest = if (refresh) wanted else wanted.take(best?.let { wanted.indexOf(it.language) } ?: wanted.size)
        val worthAsking = preferredOverBest.filterNot { !refresh && cache.isMissing(MusicInfoCache.Kind.ABOUT, aboutKey(kind, name, artist, it)) }
        if (worthAsking.isEmpty() || name.isBlank() || (!userRequested && !descriptionsEnabled())) return best
        val found = attempt("description of $name") {
            val missingFor: suspend (List<String>) -> Unit = { absentIn ->
                if (!refresh) {
                    deletedDownloads.commitIfAllowed(DownloadKeys.about(kind, name, artist)) {
                        absentIn.forEach { cache.putMissing(MusicInfoCache.Kind.ABOUT, aboutKey(kind, name, artist, it)) }
                    }
                }
            }
            val match = when (kind) {
                AboutKind.ARTIST -> brainz.findArtist(name)
                AboutKind.ALBUM -> brainz.findReleaseGroup(name, artist.orEmpty())
            }
            val details = match?.let { brainz.entityDetails(kind.musicBrainz, it.id) }
            MusicBrainzClient.annotation(details)?.takeIf { match != null }?.let { text ->
                val description = Description(text, requireNotNull(match).name, "https://musicbrainz.org/${kind.musicBrainz}/${match.id}", "und")
                return@attempt saveDescription(kind, name, artist, description)
            }
            val qid = MusicBrainzClient.parseWikidataRelation(details)
                ?: wikidata.findMusicItem(name, if (kind == AboutKind.ALBUM) artist.orEmpty() else null)
            if (qid == null) { missingFor(worthAsking); return@attempt null }
            val titles = wikidata.wikipediaTitles(qid, worthAsking)
            var description: Description? = null
            val absent = mutableListOf<String>()
            for (language in worthAsking) {
                val title = titles[language]
                val summary = title?.let { wikipedia.summary(language, it) }
                if (summary == null) { absent += language; continue }
                description = Description(summary.extract, summary.title.ifBlank { title.orEmpty() }, summary.pageUrl, language)
                description = saveDescription(kind, name, artist, description)
                break
            }
            // a language that was looked for and not found is remembered as such, and only that language (the others were not asked)
            missingFor(absent)
            description
        }
        return if (deletedDownloads.contains(DownloadKeys.about(kind, name, artist))) null else found ?: best
    }

    private suspend fun saveDescription(
        kind: AboutKind,
        name: String,
        artist: String?,
        description: Description,
    ): Description? = deletedDownloads.commitIfAllowed(DownloadKeys.about(kind, name, artist)) {
        val key = aboutKey(kind, name, artist, description.language)
        val metadata = JSONObject()
            .put("text", description.text)
            .put("title", description.title)
            .put("pageUrl", description.pageUrl)
            .put("language", description.language)
            .toString()
        cache.putText(MusicInfoCache.Kind.ABOUT, key, metadata)
        note(MusicInfoCache.Kind.ABOUT, key, written = true)
        description
    }

    /** A failure of the network is not an answer: it is logged by the caller's tag, not remembered, and tried again next time. */
    private suspend fun <T> attempt(what: String, block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        com.lostf1sh.pixelplayeross.data.network.noteLookupFailure()
        timber.log.Timber.tag("MusicInfo").i("%s: no answer (%s)", what, error.message)
        null
    } catch (error: Exception) {
        com.lostf1sh.pixelplayeross.data.network.noteLookupFailure()
        if (isMainThreadViolation(error)) {
            // not a missing network: the call was made from the main thread. Loud, because it is a bug in the caller (or in this class).
            timber.log.Timber.tag("MusicInfo").e(error, "%s: PROGRAMMING ERROR, the request ran on the main thread", what)
        } else {
            timber.log.Timber.tag("MusicInfo").w(error, "%s failed", what)
        }
        null
    }

    /** `NetworkOnMainThreadException`, or a StrictMode violation, anywhere in the cause chain. */
    private fun isMainThreadViolation(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any {
        it.javaClass.name == "android.os.NetworkOnMainThreadException" || it.javaClass.name.startsWith("android.os.strictmode.")
    }

    companion object {
        fun photoKey(name: String) = MusicInfoMatching.normalize(name)
        fun coverKey(album: String, artist: String) = MusicInfoMatching.normalize(album) + "|" + MusicInfoMatching.normalize(artist)
        /** The key of a description. Without a [language] it is the key entries had before they carried one (still read, by their stored language). */
        fun aboutKey(kind: AboutKind, name: String, artist: String?, language: String? = null) =
            "${kind.name}|${MusicInfoMatching.normalize(name)}|${MusicInfoMatching.normalize(artist.orEmpty())}" +
                (language?.let { "|$it" } ?: "")
    }
}
