package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B37 (C1): the app's own `assets/` holds what the app reads and nothing else.
 *
 * `vm_new.js` was 21 KB of obfuscated, VM-protected JavaScript of unknown origin and licence that nothing opened (B36 §3.6.2); it shipped in
 * every APK because a file in `assets/` ships whether or not any code asks for it. The only code that opens an asset is the Licenses screen,
 * and the only assets are the three documents it shows. What the *built* bundle carries, libraries' assets included, is pinned by
 * `tools/release-surface/check_release_surface.py`.
 */
class AppAssetsBoundaryTest {

    private val licenseDocuments = setOf("licenses/LICENSE", "licenses/OFL.txt", "licenses/THIRD_PARTY_NOTICES.md")

    @Test
    fun `the app's own assets are the three license documents and nothing else`() {
        val assets = File(root, "src/main/assets")
        val found = assets.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(assets).invariantSeparatorsPath }.toSortedSet()
        assertThat(found).containsExactlyElementsIn(licenseDocuments)
    }

    @Test
    fun `no asset is opened by the app or by the decent-player libraries other than through the Licenses screen`() {
        val readers = (sources(File(root, "src/main")) + sources(File(root, "../../decent-player/libs").canonicalFile))
            .filter { file ->
                val text = file.readText()
                ASSET_API.any { text.contains(it) }
            }
            .map { it.name }
            .toSortedSet()
        assertThat(readers).containsExactly("LicensesScreen.kt")
    }

    @Test
    fun `every asset path the code names is one of the license documents`() {
        val named = sources(File(root, "src/main")).flatMap { file ->
            ASSET_PATH.findAll(file.readText()).map { it.groupValues[1] }.toList()
        }.toSet()
        assertThat(named).isEqualTo(licenseDocuments)
    }

    private fun sources(dir: File): List<File> = dir.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".cxx" }
        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
        .toList()

    private companion object {
        /** Every way the platform lets code read an asset by name. */
        val ASSET_API = listOf(".assets", "AssetManager", "android_asset", "openFd(")

        /** A document path under `licenses/`, as a string literal or a constant's value. */
        val ASSET_PATH = Regex("\"(licenses/[A-Za-z_.]+)\"")

        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
    }
}
