package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * AUD-111 at the app boundary: the slider's live value goes to the DSP without the persistence
 * debounce, and the open editor holds the signal PROCESSED. Source-level: MusicService and a Hilt
 * ViewModel cannot run on this JVM; the controller and the stage are tested directly elsewhere.
 */
class EqLiveEditingBoundaryTest {

    private val service = File("src/main/java/com/lostf1sh/pixelplayeross/data/service/MusicService.kt").readText()
    private val viewModel = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/EqualizerViewModel.kt").readText()

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced $signature")
    }

    @Test
    fun `an open editor is a processing requirement`() {
        assertThat(service).contains("graphicEqController.editorOpen")
        assertThat(service).containsMatch("""graphicEq = !eq\.toDspSettings\(\)\.isBypass \|\| editorOpen""")
    }

    @Test
    fun `a band drag previews immediately and only persistence is debounced`() {
        val drag = body(viewModel, "fun setBandLevel(")
        val preview = drag.indexOf("graphicEqController.previewBands(")
        assertThat(preview).isAtLeast(0)
        assertThat(preview).isLessThan(drag.indexOf("delay(SLIDER_PERSIST_DEBOUNCE_MS)"))
    }

    @Test
    fun `the editor marks itself open and closed`() {
        assertThat(viewModel).contains("graphicEqController.setEditorOpen(true)")
        assertThat(body(viewModel, "override fun onCleared(")).contains("graphicEqController.setEditorOpen(false)")
    }
}
