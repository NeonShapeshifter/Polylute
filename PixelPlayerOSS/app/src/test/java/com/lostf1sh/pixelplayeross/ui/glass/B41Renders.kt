package com.lostf1sh.pixelplayeross.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.lostf1sh.pixelplayeross.ui.ds.AlbumArtColors
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * B41: where the renders for the owner go, and the covers they are made from. The covers are drawn here, in code, from nothing: gradients
 * and shapes in the colours of four archetypes (a very light cover, a very dark one, a grey one, a saturated one), so no artwork of anyone's
 * is in the repository and the renders can be made on any machine.
 */
object B41Renders {
    private val root: File
        get() = File(System.getenv("B41_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B41-evidence").apply { mkdirs() }

    fun save(compose: ComposeContentTestRule, name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A drawn cover: the picture (what the player shows) and its pixels (what the glass is made from). */
    class Cover(val name: String, val bitmap: Bitmap) {
        val pixels: IntArray = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        val width: Int get() = bitmap.width
        val height: Int get() = bitmap.height

        /** What the app's colour extraction would call the cover's colour: the mean of its colourful pixels, or of all of them for a grey one. */
        val dominant: Color
            get() {
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0L
                for (p in pixels) {
                    val c = Color(p)
                    if (AlbumArtColors.Hsl.of(c).s > 0.25f) {
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        n++
                    }
                }
                if (n < pixels.size / 40) {
                    r = 0; g = 0; b = 0
                    for (p in pixels) {
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                    n = pixels.size.toLong()
                }
                return Color(r / (n * 255f), g / (n * 255f), b / (n * 255f))
            }
    }

    private const val SIZE = 320

    private fun paint(block: Paint.() -> Unit = {}) = Paint(Paint.ANTI_ALIAS_FLAG).apply(block)

    /** A very light cover: a pale pink portrait on a pink ground, a dark bob of hair, a red mouth. */
    fun veryLight(): Cover {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint { shader = LinearGradient(0f, 0f, 0f, SIZE.toFloat(), 0xFFFBD3E1.toInt(), 0xFFF3A9C6.toInt(), Shader.TileMode.CLAMP) })
        c.drawOval(70f, 40f, 250f, 280f, paint { color = 0xFF231721.toInt() })              // hair
        c.drawOval(95f, 75f, 225f, 265f, paint { color = 0xFFFBE0E8.toInt() })              // face
        c.drawOval(130f, 215f, 190f, 240f, paint { color = 0xFFD8284A.toInt() })            // mouth
        c.drawOval(112f, 140f, 148f, 156f, paint { color = 0xFF3A1F2B.toInt() })            // eyes
        c.drawOval(172f, 140f, 208f, 156f, paint { color = 0xFF3A1F2B.toInt() })
        return Cover("light", bmp)
    }

    /** A very dark cover: a night street, a few lit windows, a block of red windows on the right. */
    fun veryDark(): Cover {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint { shader = LinearGradient(0f, 0f, 0f, SIZE.toFloat(), 0xFF0B1630.toInt(), 0xFF04060C.toInt(), Shader.TileMode.CLAMP) })
        c.drawRect(40f, 110f, 150f, SIZE.toFloat(), paint { color = 0xFF0A1226.toInt() })
        for (y in 130 until 300 step 22) for (x in 54 until 140 step 20) c.drawRect(x.toFloat(), y.toFloat(), x + 8f, y + 12f, paint { color = 0xFFC9B37A.toInt() })
        c.drawRect(190f, 120f, SIZE.toFloat(), SIZE.toFloat(), paint { color = 0xFF050507.toInt() })
        c.drawRect(205f, 150f, 262f, 214f, paint { color = 0xFFE5231A.toInt() })              // red windows
        c.drawRect(272f, 138f, 316f, 200f, paint { color = 0xFFD91E16.toInt() })
        return Cover("dark", bmp)
    }

    /** A grey cover: no colour anywhere. */
    fun grey(): Cover {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint { shader = LinearGradient(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), 0xFFB9B9B9.toInt(), 0xFF6E6E6E.toInt(), Shader.TileMode.CLAMP) })
        c.drawOval(80f, 80f, 240f, 240f, paint { color = 0xFF4A4A4A.toInt() })
        c.drawOval(120f, 120f, 200f, 200f, paint { color = 0xFFD8D8D8.toInt() })
        return Cover("grey", bmp)
    }

    /** A saturated cover: magenta and cyan, nothing quiet in it. */
    fun saturated(): Cover {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint { color = 0xFFE6145A.toInt() })
        c.drawCircle(220f, 110f, 130f, paint { shader = RadialGradient(220f, 110f, 130f, 0xFF18D4E6.toInt(), 0xFF1560E6.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(90f, 250f, 90f, paint { color = 0xFFFFD21A.toInt() })
        return Cover("saturated", bmp)
    }

    val all: List<Cover> get() = listOf(veryLight(), veryDark(), grey(), saturated())

    @Suppress("unused")
    private fun clamp(v: Int) = max(0, min(255, v))
}
