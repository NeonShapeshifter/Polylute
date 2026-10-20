package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lostf1sh.pixelplayeross.presentation.SpecialThanksFixtures
import java.io.File
import org.junit.jupiter.api.Test

/**
 * B38 (E1): the thanks are the same words in the three languages' strings (the README carries only the footer), and the notices state
 * that Polylute is a modified version (GPL section 5(a)).
 *
 * Plain JVM test: it reads the resource XML, the README and the notices asset as files. The tests run from `app/`, so the README (the
 * repository's, one level above `PixelPlayerOSS/`) is `../../README.md`.
 */
class SpecialThanksTextTest {

    private val readme get() = File("../../README.md").readText()
    private val notices get() = File("src/main/assets/licenses/THIRD_PARTY_NOTICES.md").readText()

    @Test
    fun `the string resources of each language hold the thanks word for word`() {
        listOf(
            Triple("values", SpecialThanksFixtures.englishTitle, SpecialThanksFixtures.english),
            Triple("values-es", SpecialThanksFixtures.spanishTitle, SpecialThanksFixtures.spanish),
            Triple("values-sv", SpecialThanksFixtures.swedishTitle, SpecialThanksFixtures.swedish),
        ).forEach { (dir, title, lines) ->
            val strings = SpecialThanksFixtures.stringValues(dir)
            assertWithMessage("$dir title").that(strings["about_special_thanks_title"]).isEqualTo(title)
            SpecialThanksFixtures.resourceNames.forEachIndexed { index, name ->
                assertWithMessage("$dir $name").that(strings[name]).isEqualTo(lines[index])
            }
            assertWithMessage("$dir subtitle").that(strings["about_special_thanks_subtitle"]).isNotEmpty()
        }
    }

    @Test
    fun `the Swedish thanks keep every project name as it is`() {
        val names = listOf(
            "PixelPlayer", "decent-player", "Media3", "FFmpeg", "Jellyfin", "libFLAC", "TagLib", "JAudiotagger", "WolfEQ", "DevicePEQ",
            "Pragmatic Audio", "LRCLIB", "MusicBrainz", "Wavy Slider", "Reorderable", "Coil", "Roderick", "Polylute", "FiiO",
        )
        val swedish = SpecialThanksFixtures.swedish.joinToString("\n")
        val english = SpecialThanksFixtures.english.joinToString("\n")
        names.filter { it in english }.forEach { name ->
            assertWithMessage(name).that(swedish).contains(name)
        }
        // translated, not copied: no Swedish line is the English one
        SpecialThanksFixtures.swedish.zip(SpecialThanksFixtures.english).forEach { (sv, en) -> assertThat(sv).isNotEqualTo(en) }
    }

    @Test
    fun `the old origin row text is gone from all three languages`() {
        listOf("values", "values-es", "values-sv").forEach { dir ->
            val strings = SpecialThanksFixtures.stringValues(dir)
            assertWithMessage(dir).that(strings.keys.filter { it.startsWith("about_attribution_origin") }).isEmpty()
        }
    }

    @Test
    fun `the README ends with the footer word for word and leaves the thanks to the app`() {
        // the owner's call after B38: the thanks live in About only
        val footer = "\n\n© 2026 Polylute contributors · GPL-3.0-or-later.  \n" +
            "A fan nod to Risk of Rain 2; not affiliated with Gearbox or Hopoo Games.\n" +
            "\n" +
            "All Freedoms Granted Until Gearbox Says Otherwise.\n" +
            "\n" +
            "Please don't send Mithrix.\n"
        assertThat(readme).endsWith(footer)
        assertThat(readme).doesNotContain("## Special thanks")
        assertThat(readme).doesNotContain("Roderick")
    }

    @Test
    fun `the notices say Polylute is a modified version of both projects and when`() {
        val origins = notices.substringAfter("# Origins\n").substringBefore("\n# ")
        val sentences = origins.lines().filter { it.startsWith("Polylute is a modified version") }
        assertThat(sentences).hasSize(1)
        val sentence = sentences.single()
        assertThat(sentence).contains("modified version of PixelPlayerOSS and of decent-player")
        assertThat(sentence).contains("modified in 2026")
        assertThat(sentence).endsWith(".")
        // one sentence: a single full stop, the last character
        assertThat(sentence.count { it == '.' }).isEqualTo(1)
        // both copyright lines are as they were
        assertThat(origins).contains("Copyright (C) 2026 Theo Vilardo, maintained by lostf1sh")
        assertThat(origins).contains("Copyright (c) 2026 Marcelo Silva")
    }
}
