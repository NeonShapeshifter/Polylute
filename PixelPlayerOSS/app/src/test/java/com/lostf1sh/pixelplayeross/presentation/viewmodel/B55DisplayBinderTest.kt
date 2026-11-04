package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.app.Activity
import android.app.Application
import android.view.Window
import android.view.WindowManager.LayoutParams
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class B55DisplayBinderTest {
    private fun binder(window:Window):Any = Class.forName("com.lostf1sh.pixelplayeross.presentation.viewmodel.KeepScreenOnBinder")
        .getConstructor(Window::class.java).newInstance(window)
    private fun apply(binder:Any,dap:Boolean,playing:Boolean,lock:Boolean,mode:Long=-1) {
        binder.javaClass.getMethod("apply",Boolean::class.javaPrimitiveType,Long::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
            .invoke(binder,dap,mode,playing,lock)
    }
    @Test fun `any DAP page holds the real window flag while playing and the lock surface releases it`() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val window=controller.get().window
            val bound=binder(window)
            for(page in listOf("Home","Library","Settings","Folders")) {
                apply(bound,true,true,false)
                assertThat(window.attributes.flags and LayoutParams.FLAG_KEEP_SCREEN_ON).isNotEqualTo(0)
            }
            apply(bound,true,true,true)
            assertThat(window.attributes.flags and LayoutParams.FLAG_KEEP_SCREEN_ON).isEqualTo(0)
            apply(bound,true,false,false)
            assertThat(window.attributes.flags and LayoutParams.FLAG_KEEP_SCREEN_ON).isEqualTo(0)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun `app mode never holds a flag even with stored minus one and switching out of DAP clears it`() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val window=controller.get().window
            val bound=binder(window)
            apply(bound,true,true,false)
            apply(bound,false,true,false)
            assertThat(window.attributes.flags and LayoutParams.FLAG_KEEP_SCREEN_ON).isEqualTo(0)
            apply(bound,false,true,true)
            assertThat(window.attributes.flags and LayoutParams.FLAG_KEEP_SCREEN_ON).isEqualTo(0)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun `app mode window brightness follows Android despite a stored DAP percentage`() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val window=controller.get().window
            val type=Class.forName("com.lostf1sh.pixelplayeross.presentation.viewmodel.DapWindowBrightness")
            val instance=type.getField("INSTANCE").get(null)
            val apply=type.getMethod("apply",Window::class.java,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
            apply.invoke(instance,window,80,true)
            assertThat(window.attributes.screenBrightness).isWithin(.001f).of(.8f)
            apply.invoke(instance,window,80,false)
            assertThat(window.attributes.screenBrightness).isEqualTo(LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        } finally { controller.pause().stop().destroy() }
    }
}
