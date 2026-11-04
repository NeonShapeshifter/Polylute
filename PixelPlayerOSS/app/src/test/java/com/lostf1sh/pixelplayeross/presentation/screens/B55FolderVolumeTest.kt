package com.lostf1sh.pixelplayeross.presentation.screens

import android.app.Application
import android.content.Context
import android.os.UserHandle
import android.os.storage.StorageManager
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.lostf1sh.pixelplayeross.data.model.FolderSource
import com.lostf1sh.pixelplayeross.ui.ds.DapTheme
import org.junit.Rule
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder

// Match the existing Library paging fixtures' Compose rule and native renderer.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=Application::class)
class B55FolderVolumeTest {
    @get:Rule val compose=createComposeRule()
    private fun seed():Context {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root="/storage/B555-0001"
        val storage=shadowOf(context.getSystemService(StorageManager::class.java))
        storage.resetStorageVolumeList()
        storage.addStorageVolume(StorageVolumeBuilder("synthetic",File(root),"Synthetic Music Card",UserHandle.getUserHandleForUid(0),"mounted")
            .setIsRemovable(true).setFsUuid("B555-0001").build())
        return context
    }
    @Test fun `F05 removable volume label uses Android description before the mount UUID`() {
        val context=seed()
        val root="/storage/B555-0001"
        val method=Class.forName("com.lostf1sh.pixelplayeross.presentation.screens.FolderTabControlsKt").methods.firstOrNull { it.name=="folderVolumeLabel" }
        val label=method?.invoke(null,context,root,"SD card · B555-0001") ?: "SD card · B555-0001"
        assertThat(label).isEqualTo("Synthetic Music Card")
    }
    @Test fun `F05 the SD card selector displays Android volume description`() {
        seed()
        compose.setContent { DapTheme { FolderTabControls(false,{},true,FolderSource.SD_CARD,{},"",{},
            listOf("/storage/B555-0001"),"/storage/B555-0001","/storage/emulated/0",{}) } }
        compose.onNodeWithText("Synthetic Music Card").assertIsDisplayed()
        compose.onAllNodesWithText("B555-0001",substring=true).assertCountEquals(0)
    }

}
