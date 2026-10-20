package com.lostf1sh.pixelplayeross.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MediaItemBuilderTest {

    @Test
    fun shouldPreferDirectLocalFileUri_prefersDirectFileUriForLocalM4aMediaStoreItems() {
        val shouldPreferFile = MediaItemBuilder.shouldPreferDirectLocalFileUri(
            contentUriString = "content://media/external/audio/media/42",
            filePath = "/storage/emulated/0/Music/test-track.m4a",
            mimeType = "audio/mp4"
        )

        assertThat(shouldPreferFile).isTrue()
    }

    @Test
    fun shouldPreferDirectLocalFileUri_keepsContentUriForFormatsThatAlreadySeekCorrectly() {
        val shouldPreferFile = MediaItemBuilder.shouldPreferDirectLocalFileUri(
            contentUriString = "content://media/external/audio/media/24",
            filePath = "/storage/emulated/0/Music/test-track.flac",
            mimeType = "audio/flac"
        )

        assertThat(shouldPreferFile).isFalse()
    }

    /**
     * A pre-v6 install could still hold a song row whose content URI uses a remote
     * scheme. The guard must keep ignoring those, so a stale row can never redirect
     * playback at a local file that merely happens to share the same id.
     */
    @Test
    fun shouldPreferDirectLocalFileUri_ignoresLegacyRemoteContentUris() {
        val shouldPreferFile = MediaItemBuilder.shouldPreferDirectLocalFileUri(
            contentUriString = "navidrome://song-123",
            filePath = "/storage/emulated/0/Download/cached-track.m4a",
            mimeType = "audio/mp4"
        )

        assertThat(shouldPreferFile).isFalse()
    }

    @Test
    fun playbackMimeType_clearsAmbiguousLocalM4aMimeType() {
        val playbackMimeType = MediaItemBuilder.playbackMimeType(
            contentUriString = "content://media/external/audio/media/42",
            filePath = "/storage/emulated/0/Music/test-track.m4a",
            mimeType = "audio/mp4"
        )

        assertThat(playbackMimeType).isNull()
    }

    @Test
    fun playbackMimeType_keepsNonMp4MimeTypeForLocalPlayback() {
        val playbackMimeType = MediaItemBuilder.playbackMimeType(
            contentUriString = "content://media/external/audio/media/84",
            filePath = "/storage/emulated/0/Music/test-track.flac",
            mimeType = "audio/flac"
        )

        assertThat(playbackMimeType).isEqualTo("audio/flac")
    }
}
