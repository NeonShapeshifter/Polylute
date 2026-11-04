package com.lostf1sh.pixelplayeross.utils

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class B55HomeDurationTest {
    @Test fun `H13 compact Home duration comes from resources in every supported language`() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        for((lang,unit) in listOf("en" to "m","es" to "min","sv" to "min")) {
            val config=Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
            val resources=context.createConfigurationContext(config).resources
            val method=Class.forName("com.lostf1sh.pixelplayeross.utils.FormatsKt").methods.firstOrNull {
                it.name=="formatListeningDurationCompact" && it.parameterTypes.size==2
            }
            assertThat(method).isNotNull()
            fun formatted(ms:Long)=method!!.invoke(null,ms,resources) as String
            assertThat(formatted(14*3600000L+37*60000L)).isEqualTo("14h 37$unit")
            assertThat(formatted(3600000L)).isEqualTo("1h")
            assertThat(formatted(60000L)).isEqualTo("1$unit")
            assertThat(formatted(29000L)).isEqualTo("29s")
        }
    }
}
