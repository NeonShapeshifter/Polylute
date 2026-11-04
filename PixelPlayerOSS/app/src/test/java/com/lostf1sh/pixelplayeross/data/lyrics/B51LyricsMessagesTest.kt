package com.lostf1sh.pixelplayeross.data.lyrics

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B51LyricsMessagesTest {
    @Test fun `fallback messages in English Spanish and Swedish say where lyrics are and why the sidecar failed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = mapOf(
            "en" to "Saved in Polylute (couldn’t write the .lrc file: no space left)",
            "es" to "Guardado en Polylute (no se pudo escribir el archivo .lrc: no queda espacio)",
            "sv" to "Sparad i Polylute (kunde inte skriva .lrc-filen: utrymmet är slut)"
        )
        for ((language, message) in expected) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
            assertThat(localized.getString(R.string.lyrics_saved_in_app, localized.getString(LyricsWriteReason.NO_SPACE.messageRes()))).isEqualTo(message)
            for (reason in LyricsWriteReason.entries) assertThat(localized.getString(reason.messageRes())).isNotEmpty()
        }
    }
}
