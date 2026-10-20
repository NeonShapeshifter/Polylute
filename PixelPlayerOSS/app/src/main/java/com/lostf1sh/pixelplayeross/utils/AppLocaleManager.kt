package com.lostf1sh.pixelplayeross.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.lostf1sh.pixelplayeross.data.preferences.AppLanguage
import java.util.Locale

object AppLocaleManager {
    private const val PREFERENCES_NAME = "app_locale_preferences"
    private const val KEY_LANGUAGE_TAG = "app_language_tag"

    fun currentLanguageTag(context: Context): String =
        AppLanguage.normalize(
            context
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE_TAG, AppLanguage.SYSTEM.tag)
                .orEmpty()
        )

    /**
     * Stores the language and hands it to the platform (Android 13+: the per-app locale).
     *
     * This does **not** recreate anything: the running activity notices the change itself, once ([LanguageRecreateGate]),
     * through [observeLanguage] and the configuration event the platform sends (B16-G3).
     *
     * @return whether the stored language changed.
     */
    fun applyLanguage(context: Context, languageTag: String): Boolean {
        val normalized = AppLanguage.normalize(languageTag)
        val changed = currentLanguageTag(context) != normalized
        context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_TAG, normalized)
            .apply()

        val locales = if (normalized.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(normalized)
        }

        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != locales.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
        return changed
    }

    /**
     * Calls [onChange], on the main thread, whenever the stored app language is written; closing stops it. The listener is
     * held by the returned handle (a `SharedPreferences` keeps listeners weakly).
     */
    fun observeLanguage(context: Context, onChange: () -> Unit): AutoCloseable {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LANGUAGE_TAG) onChange()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun wrapContext(base: Context): Context {
        val languageTag = currentLanguageTag(base)
        if (languageTag.isBlank()) return base

        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)

        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
            setLocales(LocaleList(locale))
        }

        return base.createConfigurationContext(configuration)
    }
}
