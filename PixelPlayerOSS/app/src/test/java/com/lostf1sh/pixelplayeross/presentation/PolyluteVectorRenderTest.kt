package com.lostf1sh.pixelplayeross.presentation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class PolyluteVectorRenderTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun draw(id: Int, w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        context.getDrawable(id)!!.apply { setBounds(0, 0, w, h); draw(Canvas(it)) }
    }
    @Test fun wordmark_has_transparent_page_and_O_counter() {
        val b = draw(R.drawable.polylute_wordmark, 1048, 240)
        assertEquals(0, Color.alpha(b.getPixel(0, 0)))
        assertEquals("O's counter must be transparent when tinted", 0, Color.alpha(b.getPixel(260, 120)))
        assertTrue(Color.alpha(b.getPixel(200, 120)) > 0)
    }
    @Test fun P_fits_adaptive_safe_circle_and_all_local_masks_keep_its_strokes() {
        val b = draw(R.drawable.polylute_icon_foreground, 1080, 1080)
        var ink = 0
        for (y in 0 until b.height) for (x in 0 until b.width) {
            if (Color.alpha(b.getPixel(x, y)) > 0) {
                ink++
                val dx = (x - 540) / 10.0
                val dy = (y - 540) / 10.0
                assertTrue("P outside adaptive safe circle at $x,$y", dx*dx + dy*dy < 33*33)
            }
        }
        assertTrue(ink > 0)
        val dir = File("/tmp/b26-work/renders-after").apply { mkdirs() }
        for (round in listOf(true, false)) {
            val target = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
            val c = Canvas(target)
            val path = Path().apply {
                if (round) addCircle(540f, 540f, 360f, Path.Direction.CW)
                else addRoundRect(180f, 180f, 900f, 900f, 100f, 100f, Path.Direction.CW)
            }
            c.clipPath(path)
            c.drawColor(Color.rgb(24, 26, 27))
            c.drawBitmap(b, 0f, 0f, null)
            File(dir, "icon-${if (round) "circle" else "rounded-square"}.png").outputStream().use {
                target.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertTrue(b.sameAs(draw(R.drawable.polylute_icon_monochrome, 1080, 1080)))
    }
}
