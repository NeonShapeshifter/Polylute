package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/** The shared Android launcher has no request ID; every producer must claim its owner first. */
class B52WritePermissionWiringTest {
    private val vm get() = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/PlayerViewModel.kt").readText()

    @Test fun `every write request uses the single owner registration before the launcher`() {
        assertThat(Regex("_writePermissionRequest\\.emit\\(").findAll(vm).count()).isEqualTo(1)
        listOf("LYRICS", "METADATA", "BATCH_METADATA", "BATCH_GENRE").forEach {
            assertThat(vm).contains("requestWritePermission(WritePermissionOwner.$it,")
        }
        val register = vm.substringAfter("private suspend fun requestWritePermission(").substringBefore("fun onWritePermissionResult(")
        assertThat(register.indexOf("writePermissionRouter.claim(owner)")).isLessThan(register.indexOf("storePending()"))
    }

    @Test fun `the returned permission belongs to its registered owner and another save tap is ignored`() {
        val result = vm.substringAfter("fun onWritePermissionResult(").substringBefore("private val lyricsStorage")
        assertThat(result).contains("writePermissionRouter.take() ?: return")
        listOf("LYRICS", "METADATA", "BATCH_METADATA", "BATCH_GENRE").forEach {
            assertThat(result).contains("owner ${if (it == "METADATA") "!=" else "=="} WritePermissionOwner.$it")
        }
        val save = vm.substringAfter("fun saveLyricsToFile(").substringBefore("fun onLyricsFolderResult(")
        assertThat(save).contains("writePermissionRouter.isPending")
    }
}
