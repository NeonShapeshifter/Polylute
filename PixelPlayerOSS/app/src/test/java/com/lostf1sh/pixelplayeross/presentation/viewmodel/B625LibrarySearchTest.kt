package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.LibraryTabId
import org.junit.jupiter.api.Test
import java.io.File

class B625LibrarySearchTest {
    @Test fun `each tab keeps its own query and empty query restores its list`() {
        for (tab in LibraryTabId.entries) {
            val search = LibraryTabSearch()
            search.set(tab, "BEYONCE")
            assertThat(search.matches(tab, "Beyoncé")).isTrue()
            assertThat(search.matches(tab, "Beethoven")).isFalse()
            LibraryTabId.entries.filter { it != tab }.forEach { other ->
                assertThat(search.query(other)).isEmpty()
                assertThat(search.matches(other, "Beethoven")).isTrue()
            }
            search.set(tab, "")
            assertThat(search.matches(tab, "Beethoven")).isTrue()
            search.set(tab, "anything"); search.clear()
            assertThat(search.queries.value).isEmpty()
        }
    }
    @Test fun `accent folding is the existing Search fold and order stays stable`() {
        val rows = listOf("Beyoncé", "Beethoven", "BEYONCE live", "Beyoncè acoustic")
        assertThat(LibraryTabSearch.filter(rows, "beyonce") { it }).containsExactly(rows[0], rows[2], rows[3]).inOrder()
        assertThat(LibraryTabSearch.filter(rows, "") { it }).isSameInstanceAs(rows)
    }
    @Test fun `benchmark a synthetic five thousand row list`() {
        val rows = (0 until 5000).map { "Track $it Beyoncé / Composer / Album $it /m/$it.flac" }
        repeat(10) { LibraryTabSearch.filter(rows, "beyonce") { it } }
        val ms = (0 until 30).map {
            val begin = System.nanoTime()
            assertThat(LibraryTabSearch.filter(rows, "beyonce") { it }).hasSize(5000)
            (System.nanoTime() - begin) / 1e6
        }.sorted()
        val report = "5000 rows, 10 warmups, 30 samples; median=${ms[15]} ms; p95=${ms[28]} ms; max=${ms.last()} ms\n"
        println(report)
        File((System.getenv("B625_UI_RENDERS") ?: "build/tmp/b625-ui-renders")).apply { mkdirs() }.resolve("search-bench.txt").writeText(report)
    }
}
