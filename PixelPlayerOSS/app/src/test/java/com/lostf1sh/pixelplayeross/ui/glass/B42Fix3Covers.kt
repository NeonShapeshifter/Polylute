package com.lostf1sh.pixelplayeross.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader

/**
 * B42-fix3 (items 7 and 8): the five covers the two cover modes are judged on, drawn in code from nothing (no artwork of anyone's is in the
 * repository): a pure white, a pure black, a pale pastel, a saturated one (B41's), and a multicolour on black in the manner of a busy record sleeve.
 */
internal object B42Fix3Covers {
    private const val SIZE = 320

    private fun paint(block: Paint.() -> Unit = {}) = Paint(Paint.ANTI_ALIAS_FLAG).apply(block)

    private fun canvasOf(): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        return bmp to Canvas(bmp)
    }

    fun pureWhite(): B41Renders.Cover {
        val (bmp, c) = canvasOf()
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint { color = 0xFFFFFFFF.toInt() })
        return B41Renders.Cover("white", bmp)
    }

    fun pureBlack(): B41Renders.Cover {
        val (bmp, c) = canvasOf()
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint { color = 0xFF000000.toInt() })
        return B41Renders.Cover("black", bmp)
    }

    /** Pale pink fading into pale blue, with a lighter disc: a pastel sleeve. */
    fun pastel(): B41Renders.Cover {
        val (bmp, c) = canvasOf()
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint { shader = LinearGradient(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), 0xFFF4C6D8.toInt(), 0xFFC4DFF4.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(160f, 150f, 78f, paint { color = 0xFFFBEAF1.toInt() })
        return B41Renders.Cover("pastel", bmp)
    }

    fun saturated(): B41Renders.Cover = B41Renders.saturated().let { B41Renders.Cover("saturated", it.bitmap) }

    /** Orange, green, red, yellow and blue blocks, a disc and two stripes on a black ground: a lot of colour, mostly black. */
    fun multicolourOnBlack(): B41Renders.Cover {
        val (bmp, c) = canvasOf()
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint { color = 0xFF08080A.toInt() })
        c.drawRect(26f, 34f, 132f, 100f, paint { color = 0xFFEE7A18.toInt() })
        c.drawRect(152f, 46f, 292f, 112f, paint { color = 0xFF2EAA46.toInt() })
        c.drawRect(40f, 126f, 172f, 198f, paint { color = 0xFFD82424.toInt() })
        c.drawRect(192f, 140f, 300f, 206f, paint { color = 0xFFF0D02C.toInt() })
        c.drawCircle(160f, 262f, 40f, paint { color = 0xFF285AC8.toInt() })
        c.drawRect(24f, 224f, 296f, 232f, paint { color = 0xFFEE7A18.toInt() })
        return B41Renders.Cover("multicolour", bmp)
    }

    val all: List<B41Renders.Cover> get() = listOf(pureWhite(), pureBlack(), pastel(), saturated(), multicolourOnBlack())
}
