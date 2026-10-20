package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * AUD-098: the settings DataStore (Safety Max, headphone and DAC profiles) is excluded from backup,
 * but the diagnostic and containment stores were not. A restore or device transfer then brought back
 * a Maintenance override (e.g. FORCE_ANDROID) or Dedicated/containment flags — without the profiles
 * they belong with, on hardware they were never set for. They are device-local state and are excluded
 * from every backup channel.
 */
class BackupRulesSymmetryTest {

    private val deviceLocal = listOf("maintenance_overrides.xml", "dap_containment.xml", "product_mode.xml")

    private fun excludedSharedPrefs(file: String, section: String? = null): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$file"))
        val root = section?.let { doc.getElementsByTagName(it).item(0) as Element } ?: doc.documentElement
        val excludes = root.getElementsByTagName("exclude")
        return (0 until excludes.length).map { excludes.item(it) as Element }
            .filter { it.getAttribute("domain") == "sharedpref" }
            .map { it.getAttribute("path") }
            .toSet()
    }

    @Test
    fun `device-local diagnostic and containment state is excluded from full backup`() {
        assertThat(excludedSharedPrefs("backup_rules.xml")).containsAtLeastElementsIn(deviceLocal)
    }

    @Test
    fun `and from cloud backup and device transfer`() {
        assertThat(excludedSharedPrefs("data_extraction_rules.xml", "cloud-backup")).containsAtLeastElementsIn(deviceLocal)
        assertThat(excludedSharedPrefs("data_extraction_rules.xml", "device-transfer")).containsAtLeastElementsIn(deviceLocal)
    }
}
