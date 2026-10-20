package com.lostf1sh.pixelplayeross.presentation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import java.io.File

/**
 * B30: where the renders for the owner go. Robolectric images of the screens over made-up data (not phone screenshots), saved under
 * `analysis/.../B30-evidence/renders/` so they can be looked at without the phone. `B30_RENDERS` overrides the folder.
 */
object B30Renders {
    private val root: File
        get() = File(System.getenv("B30_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B30-evidence/renders").apply { mkdirs() }

    fun save(compose: ComposeContentTestRule, name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
