package com.lostf1sh.pixelplayeross.data.musicinfo

import com.lostf1sh.pixelplayeross.data.offline.InfoKind
import org.json.JSONObject

/** URLs, credits and bounded text; this object never writes the downloaded-content store. */
data class ResolvedInfo(val payload: String, val sourceBytes: Long? = null)

internal class MusicInfoDiscoveryRemote(
    private val brainz: MusicBrainzClient,
    private val covers: CoverArtArchiveClient,
    private val wikidata: WikidataClient,
    private val commons: CommonsClient,
    private val wikipedia: WikipediaClient,
    private val languages: () -> List<String>,
) {
    suspend fun find(kind: InfoKind, name: String, artist: String?): ResolvedInfo? = when (kind) {
        InfoKind.PHOTO -> photo(name)
        InfoKind.COVER -> cover(name, artist.orEmpty())
        InfoKind.BIO -> about(AboutKind.ARTIST, name, null)
        InfoKind.DESCRIPTION -> about(AboutKind.ALBUM, name, artist)
        InfoKind.LYRICS -> error("Lyrics use the lyrics repository")
    }

    private suspend fun photo(name: String): ResolvedInfo? {
        val match = brainz.findArtist(name)
        val details = match?.let { brainz.entityDetails("artist", it.id) }
        val relation = MusicBrainzClient.imageRelation(details)
        if (relation != null) {
            val file = CommonsClient.relationFileName(relation)
            if (file != null) {
                commons.imageInfo(file)?.let { return image(it) }
            } else if (brainz.imageExists(relation)) {
                return ResolvedInfo(JSONObject().put("url", relation).put("provider", "MusicBrainz")
                    .put("pageUrl", match?.let { "https://musicbrainz.org/artist/${it.id}" }).toString())
            }
        }
        val qid = MusicBrainzClient.parseWikidataRelation(details) ?: wikidata.findMusicItem(name) ?: return null
        val file = wikidata.imageFile(qid) ?: return null
        return commons.imageInfo(file)?.let(::image)
    }

    private fun image(image: CommonsImage): ResolvedInfo = ResolvedInfo(
        JSONObject().put("url", image.thumbnailUrl).put("author", image.author)
            .put("license", image.license).put("licenseUrl", image.licenseUrl)
            .put("pageUrl", image.pageUrl).put("provider", "Wikimedia Commons").toString(),
        image.sizeBytes,
    )

    private suspend fun cover(album: String, artist: String): ResolvedInfo? {
        val release = brainz.findRelease(album, artist)
        val url = release?.let { covers.frontUrl(it.id, group = false) }
            ?: brainz.findReleaseGroup(album, artist)?.let { covers.frontUrl(it.id, group = true) }
            ?: return null
        return ResolvedInfo(JSONObject().put("url", url).put("provider", "Cover Art Archive").toString())
    }

    private suspend fun about(kind: AboutKind, name: String, artist: String?): ResolvedInfo? {
        val match = when (kind) {
            AboutKind.ARTIST -> brainz.findArtist(name)
            AboutKind.ALBUM -> brainz.findReleaseGroup(name, artist.orEmpty())
        }
        val details = match?.let { brainz.entityDetails(kind.musicBrainz, it.id) }
        val annotation = MusicBrainzClient.annotation(details)
        if (match != null && !annotation.isNullOrBlank()) {
            return description(Description(annotation, match.name, "https://musicbrainz.org/${kind.musicBrainz}/${match.id}", "und"))
        }
        val qid = MusicBrainzClient.parseWikidataRelation(details)
            ?: wikidata.findMusicItem(name, artist.takeIf { kind == AboutKind.ALBUM }) ?: return null
        val wanted = languages().distinct()
        val titles = wikidata.wikipediaTitles(qid, wanted)
        for (language in wanted) {
            val title = titles[language] ?: continue
            val summary = wikipedia.summary(language, title) ?: continue
            return description(Description(summary.extract, summary.title, summary.pageUrl, language))
        }
        return null
    }

    private fun description(value: Description): ResolvedInfo = ResolvedInfo(
        JSONObject().put("text", value.text).put("title", value.title)
            .put("pageUrl", value.pageUrl).put("language", value.language).toString(),
    )
}
