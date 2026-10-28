package com.lostf1sh.pixelplayeross.data.offline

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class B43Fix1ChangelogTest {
    @Test fun `the changelog describes the frosted bar and durable deletion accurately`() {
        val changelog = File("../CHANGELOG.md").readText().substringBefore("## 1.1.0")
        assertThat(changelog).contains("The bottom bar is frosted enough that the list behind it no longer shows through.")
        assertThat(changelog).contains("Deleted info stays deleted until you refresh it or prepare it again.")
        val store = File("../fastlane/metadata/android/en-US/changelogs/3.txt").readText()
        assertThat(store).contains("frosted bottom bar")
        assertThat(store.codePointCount(0, store.length)).isAtMost(500)
    }
}
