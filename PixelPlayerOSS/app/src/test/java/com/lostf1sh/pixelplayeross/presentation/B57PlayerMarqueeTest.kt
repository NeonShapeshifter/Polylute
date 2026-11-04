package com.lostf1sh.pixelplayeross.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.presentation.components.player.PlayerMarquee
import com.lostf1sh.pixelplayeross.presentation.components.player.playerMarquee
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B57 2.5: the title and the artist · album line scroll and never end cut through a letter. The right edge always fades out instead of being sliced; at rest the beginning is whole and
 * the left edge is clean; once it moves the left edge fades too. The numbers live in [PlayerMarquee] only (the last test reads the sources).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h200dp-xhdpi")
class B57PlayerMarqueeTest {
    @get:Rule val compose = createComposeRule()
    private val long = "Too Rare to Die! (The Extended Mix of the Very Long Title)"

    private fun bitmap(): Bitmap = compose.onNodeWithTag("strip").captureToImage().asAndroidBitmap()

    private fun show(dark: Boolean, scrolling: Boolean, text: String = long) = compose.setContent {
        val ink = if (dark) Color.White else Color.Black
        Box(Modifier.testTag("strip").background(if (dark) Color.Black else Color.White).padding(vertical = 12.dp).width(220.dp)) {
            Text(text, color = ink, maxLines = 1, softWrap = false, modifier = Modifier.playerMarquee(scrolling, text))
        }
    }

    /** The strongest ink in a column of the picture, 0..1 (against a black page, white ink; against a white page, black ink). */
    private fun ink(b: Bitmap, x: Int, dark: Boolean): Float = (0 until b.height).maxOf { y ->
        val c = b.getPixel(x, y)
        val v = (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c)) / 765f
        if (dark) v else 1f - v
    }

    private fun renderRestAndMoving(dark: Boolean) {
        compose.mainClock.autoAdvance = false
        show(dark, scrolling = true)
        compose.mainClock.advanceTimeBy(300)
        val rest = bitmap()
        B57Renders.save(compose, "marquee-title-rest-${if (dark) "dark" else "light"}")
        // right edge: the last pixels carry (almost) no ink, while the text well before it does
        assertThat(ink(rest, rest.width - 1, dark)).isLessThan(0.25f)
        assertThat((10 until rest.width - 60).maxOf { ink(rest, it, dark) }).isGreaterThan(0.6f)
        // at rest the left edge is clean: no fade has started, the first letter is whole
        assertThat((0 until 14).maxOf { ink(rest, it, dark) }).isGreaterThan(0.55f)

        compose.mainClock.advanceTimeBy(PlayerMarquee.InitialDelayMs.toLong() + 2_500)
        val moving = bitmap()
        B57Renders.save(compose, "marquee-title-moving-${if (dark) "dark" else "light"}")
        assertThat(ink(moving, 0, dark)).isLessThan(0.45f) // once moving, the left edge fades too: nothing is cut at x = 0
        assertThat(ink(moving, moving.width - 1, dark)).isLessThan(0.25f)
    }

    @Test fun the_long_title_fades_out_at_the_right_edge_and_starts_whole_dark() = renderRestAndMoving(dark = true)
    @Test fun the_long_title_fades_out_at_the_right_edge_and_starts_whole_light() = renderRestAndMoving(dark = false)

    @Test fun a_text_that_fits_is_not_faded_and_not_moved() {
        compose.mainClock.autoAdvance = false
        show(dark = true, scrolling = false, text = "Short")
        compose.mainClock.advanceTimeBy(PlayerMarquee.InitialDelayMs.toLong() + 3_000)
        val b = bitmap()
        // the whole word is there; nothing at the right edge to fade
        assertThat((0 until 60).maxOf { ink(b, it, true) }).isGreaterThan(0.6f)
    }

    @Test fun the_tuning_lives_in_one_place() {
        val surface = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/components/player/PlayerTrackInfoSurface.kt").readText()
        assertThat(surface).doesNotContain("basicMarquee")
        assertThat(surface).contains("playerMarquee(scrolling, title)")
        assertThat(surface).contains("playerMarquee(scrolling, text)")
    }
}
