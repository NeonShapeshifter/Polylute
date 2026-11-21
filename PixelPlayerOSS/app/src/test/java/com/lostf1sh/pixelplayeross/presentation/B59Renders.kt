package com.lostf1sh.pixelplayeross.presentation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import java.io.File

/**
 * B59: where the renders go. Robolectric images over made-up data (not phone screenshots), saved under `build/test-artifacts/B59/renders/` as `<name>-after.png`; the same test run in
 * the scratch copy of the tree as it was (`B59-before`) writes `<name>-before.png`. `B59_RENDERS` overrides the folder.
 */
object B59Renders {
    private val root: File
        get() = File(System.getenv("B59_RENDERS") ?: "build/test-artifacts/B59/renders").apply { mkdirs() }

    val phase: String get() = if (File("").absoluteFile.path.contains("B59-before") || System.getenv("B59_PHASE") == "before") "before" else "after"

    fun save(compose: ComposeContentTestRule, name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(root, "$name-$phase.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
