package com.lostf1sh.pixelplayeross.utils

import android.content.Context
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class B51LegacySdRegressionTest {
    private fun resolve(uuid: String?, mountedUuid: String?, directory: String = "/storage/1234-5678/Android/data/app.polylute/files"): File? {
        val context = mockk<Context>()
        val manager = mockk<StorageManager>()
        val volume = mockk<StorageVolume>()
        val mounted = mockk<StorageVolume>()
        every { volume.isPrimary } returns false
        every { mounted.isPrimary } returns false
        every { volume.uuid } returns uuid
        every { mounted.uuid } returns mountedUuid
        every { context.packageName } returns "app.polylute"
        every { context.getExternalFilesDirs(null) } returns arrayOf(File(directory), null)
        every { manager.getStorageVolume(any<File>()) } returns mounted
        val method = StorageUtils::class.java.getDeclaredMethod("getVolumePath", Context::class.java, StorageManager::class.java, StorageVolume::class.java).apply { isAccessible = true }
        return method.invoke(StorageUtils, context, manager, volume) as File?
    }
    @Test fun `Android 10 derives the removable root from the matching app files directory`() {
        assertThat(resolve("1234-5678", "1234-5678")).isEqualTo(File("/storage/1234-5678"))
        assertThat(resolve("1234-5678", "OTHER")).isNull()
        assertThat(resolve("1234-5678", "1234-5678", "/storage/1234-5678/Android/data/another.app/files")).isNull()
    }
    @Test fun `two unknown UUIDs must not alias unrelated removable volumes`() {
        assertThat(resolve(null, null)).isNull()
    }
}
