package com.lostf1sh.pixelplayeross.presentation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import java.io.File

/**
 * B29: where the renders for the owner go. Robolectric images of the screens over made-up data (not phone screenshots), saved
 * under `analysis/.../B29-evidence/renders/` so they can be looked at without the phone. `B29_RENDERS` overrides the folder (the
 * "before" renders are taken from a copy of the tree at B28-fix2 with the same test, into another folder).
 */
object B29Renders {
    private val root: File
        get() = File(System.getenv("B29_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B29-evidence/renders").apply { mkdirs() }

    /** B29-fix1: the same, into `B29-fix1-evidence/renders/` (`B29_FIX1_RENDERS` overrides the folder, for the "before" renders). */
    private val fix1Root: File
        get() = File(System.getenv("B29_FIX1_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B29-fix1-evidence/renders").apply { mkdirs() }

    fun save(compose: ComposeContentTestRule, name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun saveFix1(compose: ComposeContentTestRule, name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(fix1Root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
