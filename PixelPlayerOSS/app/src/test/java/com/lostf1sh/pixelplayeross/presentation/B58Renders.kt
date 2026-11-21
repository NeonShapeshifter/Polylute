package com.lostf1sh.pixelplayeross.presentation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import java.io.File

/**
 * B58: where the renders go. Robolectric images over made-up data (not phone screenshots), saved under `build/test-artifacts/B58/renders/` as `<name>-after.png`; the same test run in
 * the scratch copy of the tree as it was (`B58-before`) writes `<name>-before.png`. `B58_RENDERS` overrides the folder.
 */
object B58Renders {
    private val root: File
        get() = File(System.getenv("B58_RENDERS") ?: "build/test-artifacts/B58/renders").apply { mkdirs() }

    val phase: String get() = if (File("").absoluteFile.path.contains("B58-before") || System.getenv("B58_PHASE") == "before") "before" else "after"

    fun save(compose: ComposeContentTestRule, name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(root, "$name-$phase.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
