package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Properties
import org.junit.Test

class B43VersionTest {
    @Test fun `the next release has its own version and user changelog`() {
        val properties = Properties().apply { File("../gradle.properties").inputStream().use { load(it) } }
        assertThat(properties.getProperty("APP_VERSION_NAME")).isEqualTo("1.2.0")
        assertThat(properties.getProperty("APP_VERSION_CODE")).isEqualTo("5")
        val changelog = File("../CHANGELOG.md").readText()
        val headings = Regex("(?m)^## (.+)$").findAll(changelog).map { it.groupValues[1] }.toList()
        assertThat(headings.take(2)).containsExactly("1.2.0", "1.1.1").inOrder()
        assertThat(changelog.substringAfter("## 1.2.0").substringBefore("## 1.1.1"))
            .contains("Purity has one playback path")
        val build = File("build.gradle.kts").readText()
        assertThat(build).contains("toInt() ?: 5")
        assertThat(build).contains("?: \"1.2.0\"")
        assertThat(File("../fastlane/metadata/android/en-US/changelogs/5.txt").isFile).isTrue()
        assertThat(File("../fastlane/metadata/android/en-US/changelogs/5.txt").readText().length).isAtMost(500)
    }
}
