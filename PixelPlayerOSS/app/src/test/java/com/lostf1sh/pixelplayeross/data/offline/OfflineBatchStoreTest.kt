package com.lostf1sh.pixelplayeross.data.offline

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OfflineBatchStoreTest {
    private val dir = Files.createTempDirectory("offline-batch").toFile()
    @After fun cleanup() { dir.deleteRecursively() }
    private fun batch(id: String = "a") = OfflineBatch(id, listOf("1", "2", "3"), setOf(OfflineKind.LYRICS), targets = (1..3).map { OfflineTarget(OfflineKind.LYRICS, "Song $it", "$it") })
    @Test fun `cursor and results survive a process restart`() {
        val first = OfflineBatchStore(dir)
        first.start(batch(), true)
        first.result("a", 0, OfflineResult("one", 1, 0, 0))
        val restarted = OfflineBatchStore(dir)
        assertThat(restarted.read()!!.next).isEqualTo(1)
        assertThat(restarted.read()!!.songIds).containsExactly("1", "2", "3").inOrder()
        assertThat(restarted.results()).containsExactly(OfflineResult("one", 1, 0, 0))
    }
    @Test fun `a late result from an older task cannot advance the current task`() {
        val store = OfflineBatchStore(dir)
        store.start(batch(), true)
        store.start(batch("b"), true)
        assertThat(store.result("a", 0, OfflineResult("old", 1, 0, 0))).isNull()
        assertThat(store.read()!!.next).isEqualTo(0)
        assertThat(store.state.value!!.id).isEqualTo("b")
        assertThat(store.results()).isEmpty()
    }
    @Test fun `pause refuses late progress and a resumed task keeps the cursor and results`() {
        val store = OfflineBatchStore(dir)
        store.start(batch(), true)
        store.result("a", 0, OfflineResult("one", 1, 0, 0))
        store.update("a") { it.copy(status = OfflineStatus.PAUSED) }
        assertThat(store.result("a", 1, OfflineResult("late", 1, 0, 0))).isNull()
        store.start(store.read()!!.copy(id = "resumed", status = OfflineStatus.QUEUED), false)
        assertThat(store.result("a", 1, OfflineResult("late", 1, 0, 0))).isNull()
        store.result("resumed", 1, OfflineResult("two", 0, 1, 0))
        assertThat(store.read()!!.next).isEqualTo(2)
        assertThat(store.read()!!.available).isEqualTo(1)
        assertThat(store.read()!!.unavailable).isEqualTo(1)
        assertThat(store.results().map { it.title }).containsExactly("one", "two").inOrder()
    }
    @Test fun `parallel out of order completions survive restart without skipping the pending target`() {
        val store = OfflineBatchStore(dir)
        store.start(batch(), true)
        store.result("a", 2, OfflineResult("three", 1, 0, 0))
        store.result("a", 0, OfflineResult("one", 0, 1, 0))
        assertThat(store.result("a", 2, OfflineResult("duplicate", 1, 0, 0))).isNull()
        val cold = OfflineBatchStore(dir)
        assertThat(cold.read()!!.completed).containsExactly(0, 2)
        assertThat(cold.read()!!.next).isEqualTo(2)
        assertThat(cold.read()!!.targets.indices.filter { it !in cold.read()!!.completed }).containsExactly(1)
        cold.result("a", 1, OfflineResult("two", 1, 0, 0))
        assertThat(cold.read()!!.next).isEqualTo(3)
        assertThat(cold.read()!!.available).isEqualTo(2)
    }
    @Test fun `journal commit survives a crash before the progress checkpoint`() {
        val store = OfflineBatchStore(dir)
        store.start(batch(), true)
        java.io.File(dir, "results.jsonl").appendText("""{"plan":"a","index":2,"title":"three","available":1,"unavailable":0,"failed":0}""" + "\n")
        val cold = OfflineBatchStore(dir)
        assertThat(cold.read()!!.completed).containsExactly(2)
        assertThat(cold.read()!!.available).isEqualTo(1)
        assertThat(cold.result("a", 2, OfflineResult("duplicate", 1, 0, 0))).isNull()
    }
    @Test fun `a prior plans journal cannot contaminate a new plan after an interrupted start`() {
        val store = OfflineBatchStore(dir)
        store.start(batch(), true)
        store.result("a", 0, OfflineResult("old artist", 1, 0, 0))
        val oldJournal = java.io.File(dir, "results.jsonl").readText()
        store.start(batch("new"), true)
        java.io.File(dir, "results.jsonl").writeText(oldJournal)
        assertThat(OfflineBatchStore(dir).read()!!.next).isEqualTo(0)
    }
    @Test fun `new selection clears prior results and completion cannot receive progress`() {
        val store = OfflineBatchStore(dir)
        store.start(batch(), true)
        store.result("a", 0, OfflineResult("one", 1, 0, 0))
        store.start(batch("new"), true)
        assertThat(store.results()).isEmpty()
        store.update("new") { it.copy(status = OfflineStatus.COMPLETE) }
        assertThat(store.result("new", 0, OfflineResult("late", 1, 0, 0))).isNull()
    }
}
