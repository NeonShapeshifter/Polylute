package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every `MediaController` must be bound to the main looper explicitly.
 *
 * `MediaController.Builder` resolves its application looper with `getCurrentOrMainLooper()` — the
 * **constructing thread's looper if that thread has one**, and only main if it does not. So a
 * controller built on any looper-bearing thread is bound to that thread, while every reader of it
 * in this app runs on the main dispatcher, and `verifyApplicationThread()` throws:
 *
 * ```
 * java.lang.IllegalStateException: MediaController method is called from a wrong thread.
 * ```
 *
 * That is a source-level property — it is about how a builder is *written*, not about anything a
 * running JVM can observe without a real `Looper`. So it is checked at the source, in the same
 * spirit as the rest of this package: a new controller that forgets to pin its looper fails here
 * rather than on the device three minutes into playback.
 */
class MediaControllerLooperBoundaryTest {

    private val mainSources = File("src/main/java")

    @Test
    fun `the source tree was found, so this test is not vacuous`() {
        assertThat(mainSources.isDirectory).isTrue()
    }

    @Test
    fun `every MediaController builder pins its application looper`() {
        val offenders = mainSources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("MediaController.Builder(") }
            .map { file ->
                val text = file.readText()
                val builderCount = Regex("MediaController\\.Builder\\(").findAll(text).count()
                val looperCount = Regex("setApplicationLooper\\(").findAll(text).count()
                Triple(file.path, builderCount, looperCount)
            }
            .filter { (_, builders, loopers) -> loopers < builders }
            .map { (path, builders, loopers) ->
                "$path builds $builders controller(s) but pins only $loopers looper(s)"
            }
            .toList()

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the factory builds controllers through the pinned path`() {
        // The one construction site that matters for playback. If this file stops calling
        // setApplicationLooper, the sampler's main-dispatcher assumption is no longer sound.
        val factory = File(
            "src/main/java/com/lostf1sh/pixelplayeross/data/media/MediaControllerFactory.kt"
        )

        assertThat(factory.isFile).isTrue()
        val text = factory.readText()
        assertThat(text).contains("setApplicationLooper(")
        assertThat(text).contains("Looper.getMainLooper()")
    }
}
