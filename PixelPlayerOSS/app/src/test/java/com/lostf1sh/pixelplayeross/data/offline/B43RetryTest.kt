package com.lostf1sh.pixelplayeross.data.offline

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B43RetryTest {
    @Test fun `a failure stays pending across pause and restart while an empty answer completes`() {
        val dir = Files.createTempDirectory("b43-retry").toFile()
        try {
            val store = OfflineBatchStore(dir)
            store.start(OfflineBatch("a", listOf("1", "2"), setOf(OfflineKind.LYRICS), targets = listOf(
                OfflineTarget(OfflineKind.LYRICS, "one", "1"), OfflineTarget(OfflineKind.LYRICS, "two", "2"))), true)
            store.result("a", 0, OfflineResult("one", 0, 0, 1))
            store.result("a", 1, OfflineResult("two", 0, 1, 0))
            assertThat(store.read()!!.completed).containsExactly(1)
            assertThat(store.read()!!.unavailable).isEqualTo(1)
            assertThat(java.io.File(dir, "progress.json").readText()).contains("retry")
            store.update("a") { it.copy(status = OfflineStatus.PAUSED) }
            val cold = OfflineBatchStore(dir)
            assertThat(cold.read()!!.completed).containsExactly(1)
            assertThat(cold.read()!!.failed).isEqualTo(1)
            cold.start(cold.read()!!.copy(id = "resumed", status = OfflineStatus.QUEUED), false)
            cold.result("resumed", 0, OfflineResult("one", 1, 0, 0))
            assertThat(cold.read()!!.completed).containsExactly(0, 1)
            assertThat(cold.read()!!.failed).isEqualTo(0)
        } finally { dir.deleteRecursively() }
    }
}
