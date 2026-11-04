package com.lostf1sh.pixelplayeross.presentation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import java.io.File

/**
 * B57: where the renders go. Robolectric images over made-up data (not phone screenshots), saved under `analysis/.../B57-gates/renders/` as `<name>-after.png`;
 * the same test run in the tree before the batch writes `<name>-before.png` for the screens that already existed. `B57_RENDERS` overrides the folder.
 */
object B57Renders {
    private val root: File
        get() = File(System.getenv("B57_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B57-gates/renders").apply { mkdirs() }

    val phase: String get() = if (File("").absoluteFile.path.contains("B57-before")) "before" else "after"

    fun save(compose: ComposeContentTestRule, name: String, phaseOverride: String? = null) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(root, "$name-${phaseOverride ?: phase}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
