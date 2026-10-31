package com.lostf1sh.pixelplayeross.dap.admin

import android.Manifest
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalPermissionsApi::class)
open class B44Fix1MusicAccessResumeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `the MainActivity permission state refreshes a grant on resume without a process restart`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        Shadows.shadowOf(app).denyPermissions(permission)
        val controller = DapDeviceOwnerController(app)
        val policy = app.getSystemService(DevicePolicyManager::class.java)
        Shadows.shadowOf(policy).setActiveAdmin(controller.adminComponent)
        Shadows.shadowOf(policy).setDeviceOwner(controller.adminComponent)
        compose.setContent {
            // MainActivity reads this exact Accompanist state, not a one-time permission snapshot.
            val state = rememberMultiplePermissionsState(listOf(permission))
            val granted = state.permissions.first { it.permission == permission }.status.isGranted
            Text(if (granted) "Library" else "Allow music access")
        }
        compose.onNodeWithText("Allow music access").assertExists()
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
            val lifecycle = (activity as androidx.lifecycle.LifecycleOwner).lifecycle as LifecycleRegistry
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            controller.restoreMusicAccessOnce()
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Library").assertExists()

    }
}

@Config(sdk = [31], application = Application::class)
class B44Fix1MusicAccessResumeAndroid12Test : B44Fix1MusicAccessResumeTest()
