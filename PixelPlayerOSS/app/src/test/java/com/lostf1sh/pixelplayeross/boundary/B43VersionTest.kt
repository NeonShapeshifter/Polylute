package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Properties
import org.junit.Test

class B43VersionTest {
    @Test fun `the next release has its own version and user changelog`() {
        val properties = Properties().apply { File("../gradle.properties").inputStream().use { load(it) } }
        assertThat(properties.getProperty("APP_VERSION_NAME")).isEqualTo("1.1.1")
        assertThat(properties.getProperty("APP_VERSION_CODE")).isEqualTo("3")
        assertThat(File("../CHANGELOG.md").readText().substringAfter("## ").substringBefore("\n").trim()).isEqualTo("1.1.1")
        assertThat(File("../fastlane/metadata/android/en-US/changelogs/3.txt").isFile).isTrue()
    }
}
