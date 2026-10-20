package com.lostf1sh.pixelplayeross.data.preferences

import android.content.Context
import androidx.annotation.StringRes
import com.lostf1sh.pixelplayeross.R

/**
 * The languages the app ships. Only the ones that are fully maintained (B14-4, B15-F3): English, Spanish and Swedish. The other ten
 * were inherited at about two thirds of today's strings and are kept, unbuilt, in `translations-unbuilt/`; a
 * stored tag of one of them normalises to [SYSTEM], so an install that had picked one falls back to the device
 * language rather than failing.
 */
enum class AppLanguage(val tag: String, @StringRes val labelRes: Int) {
    SYSTEM("", R.string.setcat_language_system),
    ENGLISH("en", R.string.setcat_language_english),
    SPANISH("es", R.string.setcat_language_spanish),
    SWEDISH("sv", R.string.setcat_language_swedish);

    companion object {
        val supportedLanguageTags: Set<String> = entries.map { it.tag }.toSet()

        fun getLanguageOptions(context: Context): Map<String, String> {
            val systemOption = SYSTEM.tag to context.getString(SYSTEM.labelRes)
            val otherOptions = entries
                .filter { it != SYSTEM }
                .map { it.tag to context.getString(it.labelRes) }
                .sortedBy { it.second.lowercase() }

            val result = LinkedHashMap<String, String>()
            result[systemOption.first] = systemOption.second
            for (option in otherOptions) {
                result[option.first] = option.second
            }
            return result
        }

        fun normalize(languageTag: String?): String {
            val normalized = languageTag?.trim() ?: return SYSTEM.tag
            return entries.find { it.tag.equals(normalized, ignoreCase = true) }?.tag ?: SYSTEM.tag
        }
    }
}
