package com.lostf1sh.pixelplayeross.presentation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import java.io.File

/** Where the B42-fix1 renders go: `B42_RENDERS`, or `analysis/.../batches/B42-fix1-evidence` next to the result. */
object B42Fix1Renders {
    private val root: File
        get() = File(System.getenv("B42FIX1_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B42-fix1-evidence").apply { mkdirs() }

    /** A screen already captured (one with windows of their own composited over the activity's). */
    fun save(bitmap: Bitmap, name: String) {
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun save(compose: ComposeContentTestRule, name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
