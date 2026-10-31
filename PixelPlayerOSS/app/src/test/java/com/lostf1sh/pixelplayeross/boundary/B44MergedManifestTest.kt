package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Test

class B44MergedManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private fun manifest(type: String): org.w3c.dom.Document {
        val task = if (type == "debug") "processDebugMainManifest" else "processReleaseMainManifest"
        val path = File("build/intermediates/merged_manifest/$type/$task/AndroidManifest.xml")
        assertThat(path.isFile).isTrue()
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(path)
    }
    /** `@string/app_name` resolved the way the build does: the build type's source set first, then main. */
    private fun resolveString(type: String, reference: String): String? {
        if (!reference.startsWith("@string/")) return null
        val name = reference.removePrefix("@string/")
        for (set in listOf(type, "main")) {
            val file = File("src/$set/res/values/strings.xml")
            if (!file.isFile) continue
            Regex("""<string name="$name"[^>]*>([^<]*)</string>""").find(file.readText())?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun label(element: org.w3c.dom.Element): String = element.getAttributeNS(android, "label")

    /** B48-4: the application and every activity that is a launcher or home entry (what an installer or a launcher names the app by) carry a resource label that resolves to the product name. */
    @Test fun `merged debug and release labels are resource labels of every launcher entry and resolve to the product names`() {
        for ((type, name) in mapOf("debug" to "Polylute Debug", "release" to "Polylute")) {
            val document = manifest(type)
            val application = document.getElementsByTagName("application").item(0) as org.w3c.dom.Element
            assertThat(label(application)).isEqualTo("@string/app_name")
            assertThat(resolveString(type, label(application))).isEqualTo(name)
            val entries = (document.getElementsByTagName("activity").let { list -> (0 until list.length).map { list.item(it) as org.w3c.dom.Element } } +
                document.getElementsByTagName("activity-alias").let { list -> (0 until list.length).map { list.item(it) as org.w3c.dom.Element } })
                .filter { activity ->
                    val categories = activity.getElementsByTagName("category")
                    (0 until categories.length).map { (categories.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") }
                        .any { it == "android.intent.category.LAUNCHER" || it == "android.intent.category.HOME" }
                }
            assertThat(entries).isNotEmpty()
            for (entry in entries) {
                val entryName = entry.getAttributeNS(android, "name")
                assertWithMessage("$type $entryName label").that(label(entry)).startsWith("@string/")
                assertWithMessage("$type $entryName label resolves").that(resolveString(type, label(entry))).isEqualTo(name)
            }
            // the external player is not a launcher entry but it is what the installer's "open" can name: it carries the same label
            val external = (0 until document.getElementsByTagName("activity").length).map { document.getElementsByTagName("activity").item(it) as org.w3c.dom.Element }
                .single { it.getAttributeNS(android, "name").endsWith("ExternalPlayerActivity") }
            assertThat(resolveString(type, label(external))).isEqualTo(name)
        }
    }

    @Test fun `Android ten legacy storage and write permission are confined to the supported platform range`() {
        val document = manifest("debug")
        val application = document.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        assertThat(application.getAttributeNS(android, "requestLegacyExternalStorage")).isEqualTo("true")
        val sdk = document.getElementsByTagName("uses-sdk").item(0) as org.w3c.dom.Element
        assertThat(sdk.getAttributeNS(android, "minSdkVersion")).isEqualTo("29")
        val permissions = document.getElementsByTagName("uses-permission")
        val write = (0 until permissions.length).map { permissions.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(android, "name") == "android.permission.WRITE_EXTERNAL_STORAGE" }
        assertThat(write.getAttributeNS(android, "maxSdkVersion")).isEqualTo("29")
    }
}
