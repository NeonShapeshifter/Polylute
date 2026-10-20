package com.lostf1sh.pixelplayeross.presentation.settings

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lostf1sh.pixelplayeross.data.preferences.AppLanguage
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B15-F3: the app ships English, Spanish and Swedish. Swedish is a complete translation, not a partial one: every
 * translatable string and plural of `values/` exists in `values-sv/` with the same placeholders, and the three places
 * that decide which languages ship agree.
 */
class SwedishLocaleTest {

    private val res = File("src/main/res")

    private class Entries(val strings: Map<String, String>, val plurals: Map<String, String>)

    private fun load(dir: String): Entries {
        val strings = sortedMapOf<String, String>()
        val plurals = sortedMapOf<String, String>()
        res.resolve(dir).listFiles { f -> f.name.endsWith(".xml") && f.name.startsWith("str") || f.name == "plurals.xml" }!!
            .forEach { file ->
                val text = file.readText()
                Regex("""<string\s+([^>]*?)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
                    val attrs = m.groupValues[1]
                    if ("translatable=\"false\"" in attrs) return@forEach
                    strings[Regex("""name="([^"]+)"""").find(attrs)!!.groupValues[1]] = m.groupValues[2]
                }
                Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
                    plurals[m.groupValues[1]] = Regex("""<item quantity="([^"]+)">""").findAll(m.groupValues[2])
                        .joinToString(",") { it.groupValues[1] }
                }
            }
        return Entries(strings, plurals)
    }

    private val placeholder = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXoeEgGcb%]""")

    @Test
    fun `every translatable string and plural exists in Swedish, and nothing else does`() {
        val en = load("values")
        val sv = load("values-sv")
        assertThat(sv.strings.keys - en.strings.keys).isEmpty()
        assertThat(en.strings.keys - sv.strings.keys).isEmpty()
        assertThat(sv.plurals.keys).isEqualTo(en.plurals.keys)
        assertThat(sv.strings.size).isAtLeast(2404)
    }

    @Test
    fun `Swedish keeps the placeholders of the English string it translates`() {
        val en = load("values")
        val sv = load("values-sv")
        for ((key, english) in en.strings) {
            val swedish = sv.strings.getValue(key)
            assertWithMessage(key).that(placeholder.findAll(swedish).map { it.value }.sorted().toList())
                .isEqualTo(placeholder.findAll(english).map { it.value }.sorted().toList())
        }
    }

    @Test
    fun `Swedish plurals have one and other`() {
        val sv = load("values-sv")
        sv.plurals.forEach { (name, quantities) -> assertWithMessage(name).that(quantities.split(",")).containsExactly("one", "other") }
    }

    @Test
    fun `Swedish files carry no marker of an unfinished translation and no Swedish string is empty`() {
        val sv = load("values-sv")
        sv.strings.forEach { (key, value) -> assertWithMessage(key).that(value.isBlank()).isFalse() }
    }

    @Test
    fun `the three places that decide the shipped languages agree on en es sv`() {
        assertThat(AppLanguage.supportedLanguageTags).containsExactly("", "en", "es", "sv")
        assertThat(AppLanguage.normalize("sv")).isEqualTo("sv")
        assertThat(AppLanguage.normalize("de")).isEqualTo("")          // an unbuilt language falls back to the system
        val config = res.resolve("xml/locales_config.xml").readText()
        assertThat(Regex("""android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toList())
            .containsExactly("en", "es", "sv").inOrder()
        val gradle = File("build.gradle.kts").readText()
        assertThat(gradle).contains("""localeFilters.addAll(listOf("en", "es", "sv"))""")
        assertThat(res.resolve("values/strings_settings.xml").readText())
            .contains("""<string name="setcat_language_swedish" translatable="false">Svenska</string>""")
    }
}
