package com.lostf1sh.pixelplayeross.data.image

import android.net.Uri
import coil.ImageLoader
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Dimension
import coil.size.Size
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.LocalArtworkUri
import okio.Path.Companion.toPath
import javax.inject.Inject

class LocalArtworkCoilFetcher(
    private val uri: Uri,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val songId = LocalArtworkUri.parseSongId(uri.toString()) ?: return null
        // A list row asks for ~100 px. Pulling the whole cover out of the audio file, scaling it to
        // 1536 px and writing a ~470 KB JPEG for that is what made a fling over unseen songs eat the
        // CPU (B13), so small requests are served from a small thumbnail instead.
        val cachedFile = (
            if (isThumbnailRequest(options.size)) {
                AlbumArtUtils.ensureAlbumArtThumbnailFile(
                    appContext = options.context,
                    songId = songId
                )
            } else {
                AlbumArtUtils.ensureAlbumArtCachedFile(
                    appContext = options.context,
                    songId = songId
                )
            }
        ) ?: return null

        return SourceResult(
            source = coil.decode.ImageSource(
                file = cachedFile.absolutePath.toPath(),
                fileSystem = okio.FileSystem.SYSTEM
            ),
            mimeType = null,
            dataSource = coil.decode.DataSource.DISK
        )
    }

    companion object {
        /** Both edges known and no larger than [AlbumArtUtils.THUMBNAIL_EDGE_PX]. */
        internal fun isThumbnailRequest(size: Size): Boolean {
            val width = (size.width as? Dimension.Pixels)?.px ?: return false
            val height = (size.height as? Dimension.Pixels)?.px ?: return false
            return width <= AlbumArtUtils.THUMBNAIL_EDGE_PX && height <= AlbumArtUtils.THUMBNAIL_EDGE_PX
        }
    }

    class Factory @Inject constructor() : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            return if (LocalArtworkUri.isLocalArtworkUri(data)) {
                LocalArtworkCoilFetcher(data, options)
            } else {
                null
            }
        }
    }
}
