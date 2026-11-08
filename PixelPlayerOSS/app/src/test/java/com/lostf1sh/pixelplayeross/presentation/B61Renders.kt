package com.lostf1sh.pixelplayeross.presentation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import java.io.File

/**
 * B61: where the renders go. Robolectric images over made-up data (not phone screenshots), saved under `analysis/.../B61-gates/renders/` as `<name>-after.png`; the same test run in the
 * scratch copy of the tree as it was (`B61-before`) writes `<name>-before.png`. `B61_RENDERS` overrides the folder.
 */
object B61Renders {
    private val root: File
        get() = File(System.getenv("B61_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B61-gates/renders").apply { mkdirs() }

    val phase: String get() = if (File("").absoluteFile.path.contains("B61-before") || System.getenv("B61_PHASE") == "before") "before" else "after"

    fun save(compose: ComposeContentTestRule, name: String, phase: String = this.phase) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(root, "$name-$phase.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
