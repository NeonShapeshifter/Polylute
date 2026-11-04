package com.lostf1sh.pixelplayeross.presentation.lockscreen

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSettings

@Implements(Settings.System::class)
class B55WritableSettingsShadow:ShadowSettings.ShadowSystem() {
    companion object {
        var writable=false
        @Implementation @JvmStatic fun canWrite(context:Context):Boolean=writable
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,shadows=[B55WritableSettingsShadow::class])
class B55LockSleepTest {
    private val policy=mockk<DevicePolicyManager>(relaxed=true)
    private var active=false
    private val context=object:ContextWrapper(ApplicationProvider.getApplicationContext()) {
        override fun getSystemService(name:String):Any?=if(name==Context.DEVICE_POLICY_SERVICE) policy else super.getSystemService(name)
    }
    private fun sleep():Any=Class.forName("com.lostf1sh.pixelplayeross.presentation.lockscreen.DapLockScreenSleep").getConstructor(Context::class.java).newInstance(context)
    private fun call(instance:Any,name:String)=instance.javaClass.getMethod(name).invoke(instance)
    private fun timeout()=Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_OFF_TIMEOUT)
    @Before fun prepare() {
        context.getSharedPreferences("dap_lock_display_restore",Context.MODE_PRIVATE).edit().clear().commit()
        every { policy.isAdminActive(any()) } answers { active }
        B55WritableSettingsShadow.writable=false
        Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_OFF_TIMEOUT,Int.MAX_VALUE)
    }
    @After fun cleanup() { unmockkAll() }
    @Test fun `active admin forces sleep with keyguard disabled and does not need Device Owner or a system timeout write`() {
        active=true
        val sleep=sleep();call(sleep,"begin")
        assertThat(call(sleep,"sleep").toString()).isEqualTo("FORCED")
        verify(exactly=1) { policy.lockNow() }
        assertThat(timeout()).isEqualTo(Int.MAX_VALUE)
        call(sleep,"end")
    }
    @Test fun `without admin the writable system timeout is thirty seconds and is restored on leaving`() {
        B55WritableSettingsShadow.writable=true
        val sleep=sleep();call(sleep,"begin");call(sleep,"begin")
        assertThat(timeout()).isEqualTo(30000)
        assertThat(call(sleep,"sleep").toString()).isEqualTo("SYSTEM_TIMEOUT")
        verify(exactly=0) { policy.lockNow() }
        call(sleep,"end")
        assertThat(timeout()).isEqualTo(Int.MAX_VALUE)
    }
    @Test fun `a fresh host restores a temporary timeout left by process death`() {
        B55WritableSettingsShadow.writable=true
        val first=sleep();call(first,"begin")
        assertThat(timeout()).isEqualTo(30000)
        // A new instance has none of the first host's fields, while Android and private recovery data survive.
        val reopened=sleep();call(reopened,"end")
        assertThat(timeout()).isEqualTo(Int.MAX_VALUE)
    }
    @Test fun `a system timeout changed externally wins over restoration`() {
        B55WritableSettingsShadow.writable=true
        val sleep=sleep();call(sleep,"begin")
        Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_OFF_TIMEOUT,120000)
        call(sleep,"end")
        assertThat(timeout()).isEqualTo(120000)
    }
    @Test fun `without admin or write authority no forced sleep is claimed`() {
        val sleep=sleep();call(sleep,"begin")
        assertThat(call(sleep,"sleep").toString()).isEqualTo("UNAVAILABLE")
        assertThat(timeout()).isEqualTo(Int.MAX_VALUE)
        verify(exactly=0) { policy.lockNow() }
        call(sleep,"end")
    }
}
