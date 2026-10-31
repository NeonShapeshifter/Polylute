package com.lostf1sh.pixelplayeross.dap.admin

import android.Manifest
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDevicePolicyManager
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
open class B44Fix1MusicAccessTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val folder = Files.createTempDirectory("b44-fix1-access").toFile()
    private val context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = folder
        override fun getSystemService(name: String): Any? =
            if (name == Context.DEVICE_POLICY_SERVICE) policy else super.getSystemService(name)
    }
    private val platform = app.getSystemService(DevicePolicyManager::class.java)
    private val policy = io.mockk.mockk<DevicePolicyManager>(relaxed = true)
    private val grants = mutableListOf<Pair<String, Int>>()
    private var fail = false
    private var throws = false
    private var failDefault = false
    private var throwDefault = false
    private val controller = DapDeviceOwnerController(context)
    private val permission get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private val marker get() = File(folder, "music-access-restore-b44.done")
    private val logs = mutableListOf<String>()
    private val tree = object : Timber.DebugTree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { logs += message }
    }
    @Before fun prepare() {
        Timber.plant(tree)
        io.mockk.every { policy.isDeviceOwnerApp(any()) } answers { platform.isDeviceOwnerApp(firstArg()) }
        io.mockk.every { policy.setPermissionGrantState(any(), any(), any(), any()) } answers {
            val state = arg<Int>(3)
            grants += arg<String>(2) to state
            if (throws || (throwDefault && state == DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)) {
                throw SecurityException("platform refused")
            }
            if (fail || (failDefault && state == DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)) {
                false
            } else {
                platform.setPermissionGrantState(arg(0), arg(1), arg(2), state)
            }
        }
        Shadows.shadowOf(app).denyPermissions(permission)
    }
    @After fun cleanup() {
        Timber.uproot(tree)
        folder.deleteRecursively()
    }
    private fun owner() {
        Shadows.shadowOf(platform).setActiveAdmin(controller.adminComponent)
        Shadows.shadowOf(platform).setDeviceOwner(controller.adminComponent)
    }
    @Test fun `an owner grants the correct music permission then releases policy only once`() {
        owner()
        controller.restoreMusicAccessOnce()
        assertThat(grants).containsExactly(permission to 1, permission to 0).inOrder()
        assertThat(context.checkSelfPermission(permission)).isEqualTo(PackageManager.PERMISSION_GRANTED)
        assertThat(platform.getPermissionGrantState(controller.adminComponent, context.packageName, permission)).isEqualTo(0)
        assertThat(marker.isFile).isTrue()
        Shadows.shadowOf(app).denyPermissions(permission)
        DapDeviceOwnerController(context).restoreMusicAccessOnce()
        assertThat(context.checkSelfPermission(permission)).isEqualTo(PackageManager.PERMISSION_DENIED)
        assertThat(grants).hasSize(2)
    }
    @Test fun `an existing grant is left alone and marked complete`() {
        owner()
        Shadows.shadowOf(app).grantPermissions(permission)
        controller.restoreMusicAccessOnce()
        assertThat(grants).isEmpty()
        assertThat(marker.isFile).isTrue()
    }
    @Test fun `a regular installation keeps the normal permission prompt`() {
        controller.restoreMusicAccessOnce()
        assertThat(grants).isEmpty()
        assertThat(context.checkSelfPermission(permission)).isEqualTo(PackageManager.PERMISSION_DENIED)
        assertThat(marker.isFile).isTrue()
    }
    @Test fun `a refused grant logs and remains retryable on a later start`() {
        owner()
        fail = true
        controller.restoreMusicAccessOnce()
        assertThat(marker.exists()).isFalse()
        assertThat(logs).isNotEmpty()
        fail = false
        controller.restoreMusicAccessOnce()
        assertThat(marker.isFile).isTrue()
        assertThat(context.checkSelfPermission(permission)).isEqualTo(PackageManager.PERMISSION_GRANTED)
    }
    @Test fun `a throwing policy never crashes and does not mark restoration complete`() {
        owner()
        throws = true
        val result = runCatching { controller.restoreMusicAccessOnce() }
        assertThat(result.exceptionOrNull()).isNull()
        assertThat(marker.exists()).isFalse()
        assertThat(logs).isNotEmpty()
        throws = false
        controller.restoreMusicAccessOnce()
        assertThat(marker.isFile).isTrue()
    }
    @Test fun `a throwing policy release remains release only after a listener revoke`() {
        owner()
        throwDefault = true
        controller.restoreMusicAccessOnce()
        Shadows.shadowOf(app).denyPermissions(permission)
        controller.restoreMusicAccessOnce()
        throwDefault = false
        controller.restoreMusicAccessOnce()
        assertThat(grants.map { it.second }).containsExactly(1, 0, 0, 0).inOrder()
        assertThat(context.checkSelfPermission(permission)).isEqualTo(PackageManager.PERMISSION_DENIED)
        assertThat(marker.isFile).isTrue()
    }
    @Test fun `a failed policy release retries DEFAULT without regranting after a listener revokes`() {
        owner()
        failDefault = true
        controller.restoreMusicAccessOnce()
        assertThat(marker.exists()).isFalse()
        Shadows.shadowOf(app).denyPermissions(permission)
        failDefault = false
        DapDeviceOwnerController(context).restoreMusicAccessOnce()
        assertThat(grants.map { it.second }).containsExactly(1, 0, 0).inOrder()
        assertThat(context.checkSelfPermission(permission)).isEqualTo(PackageManager.PERMISSION_DENIED)
        assertThat(marker.isFile).isTrue()
    }
}

@Config(sdk = [31], application = Application::class)
class B44Fix1MusicAccessAndroid12Test : B44Fix1MusicAccessTest()
