package com.lostf1sh.pixelplayeross.presentation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import java.io.File

/**
 * B60: where the renders go. Robolectric images over made-up data (not phone screenshots), saved under `build/test-artifacts/B60/renders/` as `<name>-after.png`; the same test run in
 * the scratch copy of the tree as it was (`B60-before`) writes `<name>-before.png`. `B60_RENDERS` overrides the folder.
 */
object B60Renders {
    private val root: File
        get() = File(System.getenv("B60_RENDERS") ?: "build/test-artifacts/B60/renders").apply { mkdirs() }

    val phase: String get() = if (File("").absoluteFile.path.contains("B60-before") || System.getenv("B60_PHASE") == "before") "before" else "after"

    fun save(compose: ComposeContentTestRule, name: String, phase: String = this.phase) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(root, "$name-$phase.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
