package com.lostf1sh.pixelplayeross.presentation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import java.io.File

/**
 * B34: where the renders for the owner go. Robolectric images of the page over made-up data (not phone screenshots), saved under
 * `analysis/.../B34-evidence/renders/`. `B34_RENDERS` overrides the folder.
 */
object B34Renders {
    private val root: File
        get() = File(System.getenv("B34_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B34-evidence/renders").apply { mkdirs() }

    fun saveAs(compose: ComposeContentTestRule, fileName: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(root, "$fileName.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
