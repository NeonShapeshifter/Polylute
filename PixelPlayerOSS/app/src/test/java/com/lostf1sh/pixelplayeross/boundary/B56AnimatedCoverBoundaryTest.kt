package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/** B56 phase 7.2/7.3: where the animated cover is allowed to exist, and what it must not touch. */
class B56AnimatedCoverBoundaryTest {
    private val main = File("src/main/java/com/lostf1sh/pixelplayeross")

    private fun kotlinFiles() = main.walkTopDown().filter { it.extension == "kt" }.toList()

    private fun rel(f: File) = f.relativeTo(main).path

    @Test
    fun `the animated cover is drawn by the full player's cover surface and by nothing else`() {
        val users = kotlinFiles().filter { rel(it) != "ui/animatedcover/AnimatedCoverLayer.kt" }
            .filter { it.readText().let { t -> t.contains("AnimatedCoverLayer(") || t.contains("rememberAnimatedCoverHost(") } }
            .map(::rel)
        assertThat(users).containsExactly("presentation/components/player/PlayerCoverSurface.kt")
    }

    @Test
    fun `the lists, the mini player, Home, notifications, the lock screen and widgets never mention it`() {
        val animated = kotlinFiles().filter { !rel(it).startsWith("ui/animatedcover/") }.filter { it.readText().contains("ui.animatedcover") }.map(::rel).toSet()
        // who may import the package: the player's cover, the activity that provides it, the settings that switch it, the artwork path that takes its first frame
        assertThat(animated).containsExactly(
            "MainActivity.kt",
            "presentation/components/player/PlayerCoverSurface.kt",
            "utils/FolderArtworkSession.kt",
        )
        for (area in listOf("presentation/lockscreen", "presentation/widget", "widget", "presentation/components/MiniPlayer", "presentation/screens/HomeScreen.kt", "data/service")) {
            val files = kotlinFiles().filter { rel(it).startsWith(area) }
            assertThat(files.none { it.readText().contains("animatedcover") }).isTrue()
        }
    }

    @Test
    fun `the carousel's overlay slot is empty unless the cover surface fills it, and the carousel is used only there`() {
        val carousel = File(main, "presentation/components/AlbumCarouselSelection.kt").readText()
        assertThat(carousel).contains("pageOverlay: @Composable BoxScope.(song: Song, isCurrent: Boolean) -> Unit = { _, _ -> }")
        assertThat(carousel).contains("pageOverlay(song, isFocusedItem && song.id == currentSong?.id)")
        val callers = kotlinFiles().filter { it.name != "AlbumCarouselSelection.kt" && it.readText().contains("AlbumCarouselSection(") }.map(::rel)
        assertThat(callers).containsExactly("presentation/components/player/PlayerCoverSurface.kt")
    }

    @Test
    fun `the cover surface keeps the playing flag out of composition`() {
        val surface = File(main, "presentation/components/player/PlayerCoverSurface.kt").readText()
        assertThat(surface).contains("isPlaying = cover.isPlayingProvider,")
        assertThat(surface).doesNotContain("cover.isPlayingProvider()")
    }

    @Test
    fun `the find step is off the main thread, and the finder is the only thing that lists a folder`() {
        val finder = File(main, "ui/animatedcover/AnimatedCoverFiles.kt").readText()
        // B57.5 2.1: the cover is the album's, so the finder is asked for the song's path and its album
        assertThat(finder).contains("withContext(io) { findNow(songPath, albumId) }")
        assertThat(finder).contains("Dispatchers.IO")
        val layer = File(main, "ui/animatedcover/AnimatedCoverLayer.kt").readText()
        assertThat(layer).contains("env.finder.find(songPath, albumId)")
        assertThat(layer).doesNotContain("findNow")
        // no list row and no composable under presentation/screens asks for a folder's cover file
        assertThat(kotlinFiles().filter { rel(it).startsWith("presentation/screens") }.none { it.readText().contains("AnimatedCoverFinder") }).isTrue()
    }

    @Test
    fun `the animated cover files stay out of the audio output path`() {
        for (file in File(main, "ui/animatedcover").listFiles { f -> f.extension == "kt" }!!) {
            val imports = file.readLines().filter { it.startsWith("import ") }
            for (line in imports) {
                assertThat(line).doesNotContain("decent")
                assertThat(line).doesNotContain(".data.service")
                assertThat(line).doesNotContain("androidx.media3.session")
                assertThat(line).doesNotContain("androidx.media3.exoplayer.audio")
                assertThat(line).doesNotContain("android.media.AudioTrack")
                assertThat(line).doesNotContain("android.media.AudioManager")
            }
        }
    }

    @Test
    fun `the manifest asks for no new permission for it`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).doesNotContain("READ_MEDIA_VIDEO")
        assertThat(manifest).doesNotContain("READ_MEDIA_IMAGES")
        assertThat(manifest).doesNotContain("MANAGE_EXTERNAL_STORAGE")
    }
}
