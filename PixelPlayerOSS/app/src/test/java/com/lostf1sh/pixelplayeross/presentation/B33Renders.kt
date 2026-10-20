package com.lostf1sh.pixelplayeross.presentation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import java.io.File

/**
 * B33: where the renders for the owner go. Robolectric images of the screens over made-up data (not phone screenshots), saved under
 * `analysis/.../B33-evidence/renders/` so they can be looked at without the phone. `B33_RENDERS` overrides the folder and `B33_PREFIX`
 * names the run (`after` by default; the "before" renders are taken from a copy of the tree as it was at the start of the batch, with the
 * same test, into the same folder under `before`).
 */
object B33Renders {
    private val root: File
        get() = File(System.getenv("B33_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B33-evidence/renders").apply { mkdirs() }

    val prefix: String get() = System.getenv("B33_PREFIX") ?: "after"

    /** Saves the whole root as `<name>-<prefix>.png` (`after` unless `B33_PREFIX` says otherwise). */
    fun save(compose: ComposeContentTestRule, name: String) = saveAs(compose, "$name-$prefix")

    /** Saves the whole root as exactly `<fileName>.png`: for a test that draws its own "before". */
    fun saveAs(compose: ComposeContentTestRule, fileName: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(root, "$fileName.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
