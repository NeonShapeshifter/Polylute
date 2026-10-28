package com.lostf1sh.pixelplayeross.data.offline

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.network.*
import java.io.File
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B43JobQueueTest {
    private fun batch(id: String, vararg songs: String) = OfflineBatch(id, songs.toList(), setOf(OfflineKind.LYRICS),
        targets = songs.map { OfflineTarget(OfflineKind.LYRICS, "Song $it", it) })
    @Test fun `a small job preserves a paused library journal and reuses shared targets in both directions`() {
        val dir = Files.createTempDirectory("b43-jobs").toFile()
        try {
            val store = OfflineJobStore(dir)
            store.enqueue(batch("library", "1", "2", "3"))
            store.result("library", 0, OfflineResult("one", 1, 0, 0))
            store.update("library") { it.copy(status = OfflineStatus.PAUSED) }
            val journal = File(dir, "jobs/library/results.jsonl").readText()
            store.enqueue(batch("album", "1", "2"))
            assertThat(File(dir, "jobs/library/results.jsonl").readText()).isEqualTo(journal)
            assertThat(store.read()!!.planId).isEqualTo("album")
            assertThat(store.read()!!.completed).containsExactly(0)
            store.result("album", 1, OfflineResult("two", 1, 0, 0))
            store.update("album") { it.copy(status = OfflineStatus.COMPLETE) }
            assertThat(store.read()!!.planId).isEqualTo("library")
            assertThat(store.read()!!.status).isEqualTo(OfflineStatus.PAUSED)
            assertThat(store.read()!!.completed).containsExactly(0, 1)
            val cold = OfflineJobStore(dir)
            assertThat(cold.read()!!.completed).containsExactly(0, 1)
            assertThat(cold.all()).hasSize(2)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `a running library yields to an album and resumes without accepting its stale worker`() {
        val dir = Files.createTempDirectory("b43-preempt").toFile()
        try {
            val store = OfflineJobStore(dir)
            store.enqueue(batch("library", "1", "2", "3"))
            store.update("library") { it.copy(status = OfflineStatus.RUNNING) }
            store.enqueue(batch("album", "2"))
            assertThat(store.read()!!.planId).isEqualTo("album")
            assertThat(store.result("library", 0, OfflineResult("stale", 1, 0, 0))).isNull()
            store.result("album", 0, OfflineResult("two", 1, 0, 0))
            store.update("album") { it.copy(status = OfflineStatus.COMPLETE) }
            assertThat(store.read()!!.planId).isEqualTo("library")
            assertThat(store.read()!!.active).isTrue()
            assertThat(store.read()!!.id).isNotEqualTo("library")
            assertThat(store.read()!!.completed).containsExactly(1)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `completed shared targets adopt ownership and deleting one job cannot remove another jobs or a visits files`() {
        val dir = Files.createTempDirectory("b43-claims").toFile()
        try {
            val registry = DownloadAssetRegistry(File(dir, "claims.jsonl"))
            val shared = DownloadAsset("info|COVER|shared", File(dir, "shared.jpg").path)
            val only = DownloadAsset("info|COVER|only", File(dir, "only.jpg").path)
            val visited = DownloadAsset("info|ARTIST_PHOTO|visited", File(dir, "visited.jpg").path)
            registry.claim(shared, "one", newlyWritten = true)
            registry.claim(only, "one", newlyWritten = true)
            registry.claim(visited, "one", newlyWritten = true)
            registry.claim(visited, "page", newlyWritten = false)
            val store = OfflineJobStore(File(dir, "jobs"), registry)
            store.enqueue(batch("one", "1"))
            store.result("one", 0, OfflineResult("one", 1, 0, 0, setOf(shared.id)))
            store.update("one") { it.copy(status = OfflineStatus.COMPLETE) }
            store.enqueue(batch("two", "1"))
            assertThat(store.read()!!.next).isEqualTo(1)
            assertThat(registry.release("one", "info|")).containsExactly(only)
            assertThat(registry.release("two", "info|")).containsExactly(shared)
            assertThat(registry.release("page", "info|")).containsExactly(visited)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `an interrupted legacy migration retries from the intact original journal`() {
        val dir = Files.createTempDirectory("b43-interrupted-upgrade").toFile()
        try {
            val old = OfflineBatchStore(dir)
            old.start(batch("old", "1", "2"), true)
            old.result("old", 0, OfflineResult("one", 1, 0, 0))
            old.update("old") { it.copy(status = OfflineStatus.PAUSED) }
            val original = File(dir, "results.jsonl").readText()
            val partial = File(dir, "jobs/old").apply { mkdirs() }
            File(partial, "job.json").writeText("{incomplete")
            File(partial, "progress.json").writeText("{incomplete")
            val store = OfflineJobStore(dir)
            store.enqueue(batch("album", "2"))
            val library = store.all().single { it.planId == "old" }
            assertThat(library.next).isEqualTo(1)
            assertThat(library.status).isEqualTo(OfflineStatus.PAUSED)
            assertThat(File(partial, "results.jsonl").readText()).isEqualTo(original)
            assertThat(File(dir, "results.jsonl").readText()).isEqualTo(original)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `the 1 1 0 library journal migrates with its cursor intact`() {
        val dir = Files.createTempDirectory("b43-upgrade").toFile()
        try {
            val old = OfflineBatchStore(dir)
            old.start(batch("old", "1", "2", "3"), true)
            old.result("old", 0, OfflineResult("one", 1, 0, 0))
            old.claim("info|ARTIST_PHOTO|a")
            old.update("old") { it.copy(status = OfflineStatus.PAUSED) }
            val upgraded = OfflineJobStore(dir)
            upgraded.enqueue(batch("album", "2"))
            val library = upgraded.all().single { it.planId == "old" }
            assertThat(library.next).isEqualTo(1)
            assertThat(library.status).isEqualTo(OfflineStatus.PAUSED)
            assertThat(File(dir, "jobs/old/results.jsonl").readText()).contains("info|ARTIST_PHOTO|a")
        } finally { dir.deleteRecursively() }
    }
}
