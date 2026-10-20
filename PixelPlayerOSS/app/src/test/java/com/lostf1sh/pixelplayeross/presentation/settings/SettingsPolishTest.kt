package com.lostf1sh.pixelplayeross.presentation.settings

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.presentation.model.SettingsCategory
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The presentation contracts of the Settings polish pass: what the copy says, which slot it is
 * rendered through, and that the pass left no half-migrated surfaces behind.
 *
 * The string assertions read the resource files rather than resolving them, because a JVM unit test
 * has no `Resources`. That is enough for what is being pinned: the *value* behind a named key, and
 * its presence in both languages.
 */
class SettingsPolishTest {

    /**
     * Resolves a path that may be written from the repository root or from the module directory,
     * because the unit-test working directory is the module (`app/`) and a path assertion that
     * silently found nothing would pass vacuously.
     */
    private fun resourceFile(path: String): File {
        val relative = path.removePrefix("app/")
        return listOf(File(path), File(relative), File("app", relative))
            .firstOrNull { it.exists() }
            ?: error("Could not locate $path from ${File(".").absolutePath}")
    }

    private fun stringValue(valuesPath: String, key: String): String {
        val text = resourceFile(valuesPath).readText()
        val match = Regex("""<string name="${Regex.escape(key)}">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(text)
        requireNotNull(match) { "$key is missing from $valuesPath" }
        return match.groupValues[1]
    }

    private fun sourceUnder(relativeDir: String): List<File> =
        resourceFile(relativeDir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val enValues = "app/src/main/res/values/strings_settings.xml"
    private val esValues = "app/src/main/res/values-es/strings_settings.xml"

    // --- A: the Library category is named "Library" / "Biblioteca" -------------------------------

    @Test
    fun `the Library category is named Biblioteca in Spanish and Library in English`() {
        // The category is a collection, not a management console: "Music Management" and "Gestión
        // de música" named the work the user could do in it rather than the thing it holds.
        assertThat(stringValue(enValues, "settings_category_library_title")).isEqualTo("Library")
        assertThat(stringValue(esValues, "settings_category_library_title")).isEqualTo("Biblioteca")

        // The actions inside it keep their own descriptive names. These live in the shared strings
        // file rather than the Settings one.
        assertThat(stringValue("app/src/main/res/values/strings.xml", "setcat_find_duplicates_title"))
            .isEqualTo("Duplicate tracks")
        assertThat(stringValue("app/src/main/res/values-es/strings.xml", "setcat_find_duplicates_title"))
            .isEqualTo("Pistas duplicadas")
    }

    /**
     * Category id → resource-key suffix. They are not always the same word: the `backup_restore`
     * id predates the copy, whose key is `settings_category_backup_*`. Kept explicit so a rename
     * cannot silently make this test look at a key that does not exist.
     */
    private val subtitleKeys = mapOf(
        "audio" to "settings_category_audio_subtitle",
        "library" to "settings_category_library_subtitle",
        "lyrics" to "settings_category_lyrics_subtitle",
        "appearance" to "settings_category_appearance_subtitle",
        "behavior" to "settings_category_behavior_subtitle",
        "device" to "settings_category_device_subtitle",
        "home_mode" to "settings_category_home_mode_subtitle",
        "system" to "settings_category_system_subtitle",
        "backup_restore" to "settings_category_backup_subtitle",
        "about" to "settings_category_about_subtitle",
    )

    @Test
    fun `every public category has a short subtitle in both languages`() {
        val public = SettingsCategory.entries
            .filter { it.visibility == com.lostf1sh.pixelplayeross.presentation.model.SettingsVisibility.PUBLIC }
        // Guard against a vacuous pass: every public category must have a subtitle this test knows
        // how to look up.
        assertThat(public.map { it.id }).containsExactlyElementsIn(subtitleKeys.keys)

        public.forEach { category ->
                val key = subtitleKeys.getValue(category.id)
                val en = stringValue(enValues, key)
                val es = stringValue(esValues, key)
                assertThat(en).isNotEmpty()
                assertThat(es).isNotEmpty()
                // Short enough to be one line at 13 sp on the device's 360 dp width. The old
                // subtitles ran to 50+ characters and wrapped.
                assertThat(en.length).isAtMost(40)
                assertThat(es.length).isAtMost(40)
            }
    }

    // --- B: subtitles go through the description slot, not the value slot ------------------------

    @Test
    fun `the Settings root renders category subtitles as a description, not as a value`() {
        val source = resourceFile(
            "app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/SettingsScreen.kt"
        ).readText()

        // Guard against a vacuous pass: the rows must still be built.
        assertThat(source).contains("SettingsSurfacePolicy.visibleCategories()")

        // The subtitle is prose, so it is passed as `description` — which the row sets in Manrope.
        // Passing it as `value` put explanatory copy in JetBrains Mono.
        assertThat(source).contains("description = stringResource(category.subtitleRes)")
        assertThat(source).contains("description = stringResource(SettingsCategory.ABOUT.subtitleRes)")
        assertThat(source).doesNotContain("value = stringResource(category.subtitleRes)")
        assertThat(source).doesNotContain("value = stringResource(SettingsCategory.ABOUT.subtitleRes)")
    }

    @Test
    fun `the row primitive distinguishes a description from a value`() {
        val primitives = resourceFile(
            "app/src/main/java/com/lostf1sh/pixelplayeross/ui/ds/DapPrimitives.kt"
        ).readText()
        val row = primitives.substringAfter("fun DapSettingRow(").substringBefore("\n}")

        // The two slots must exist and must not share a style: description is prose (Manrope),
        // value is a measurement (mono). A primitive with only `value` forces prose into mono.
        assertThat(row).contains("description: String? = null")
        assertThat(row).contains("style = DapType.Caption")
        assertThat(row).contains("style = valueStyle")
    }

    // --- C: the removed root cards are gone ------------------------------------------------------

    @Test
    fun `no Settings surface uses the retired root DAC or headphone cards`() {
        val sources = sourceUnder("app/src/main/java")
        require(sources.isNotEmpty()) { "Source tree not found; the assertion would be vacuous." }
        val all = sources.joinToString("\n") { it.readText() }

        assertThat(all).doesNotContain("DacSettingsCard")
        assertThat(all).doesNotContain("HeadphoneSettingsCard")
    }

    // --- D: the backup surface's copy is resource-backed in both languages -----------------------

    @Test
    fun `every backup section is named by a resource, present in both languages`() {
        BackupSection.entries.forEach { section ->
            assertThat(section.labelRes).isNotEqualTo(0)
            assertThat(section.descriptionRes).isNotEqualTo(0)
        }

        // None of the old English literals survive as data.
        val sectionSource = resourceFile(
            "app/src/main/java/com/lostf1sh/pixelplayeross/data/backup/model/BackupSection.kt"
        ).readText()
        assertThat(sectionSource).doesNotContain("val label: String")
        assertThat(sectionSource).doesNotContain("val description: String")

        // And the section name set exists in both locales, so a Spanish build cannot fall back to
        // an English section name. Counted, so a locale that silently lost a key fails here.
        val en = resourceFile(enValues).readText()
        val es = resourceFile(esValues).readText()
        val keys = Regex("""<string name="(backup_section_[a-z_]+)">""").findAll(en)
            .map { it.groupValues[1] }.toSet()
        assertThat(keys).hasSize(BackupSection.entries.size * 2)
        keys.forEach { key ->
            assertThat(es).contains("""<string name="$key">""")
        }
    }

    @Test
    fun `backup progress and failure text is resource-backed in both languages`() {
        val en = resourceFile(enValues).readText()
        val es = resourceFile(esValues).readText()

        val expected = listOf(
            "backup_step_preparing_title",
            "backup_step_collecting_title",
            "backup_step_export_done_title",
            "backup_step_restore_done_title",
            "backup_warning_legacy_format",
            "backup_error_validation_failed",
            "backup_error_rolled_back",
            "backup_validation_checksum_mismatch",
            "backup_detail_raw",
        )
        expected.forEach { key ->
            assertThat(en).contains("""<string name="$key">""")
            assertThat(es).contains("""<string name="$key">""")
        }
    }

    // --- E: the deleted screen's resources are gone, and its live ones survived ------------------

    @Test
    fun `the deleted Experimental screen's resources are gone from every locale`() {
        val removed = listOf(
            "presentation_batch_f_exp_step1_delay_header",
            "presentation_batch_f_exp_delay_everything_title",
            "presentation_batch_f_exp_use_placeholders_title",
            "presentation_batch_f_exp_step3_trigger_header",
            "presentation_batch_f_exp_threshold_title",
            "presentation_batch_f_exp_drag_release_title",
            "presentation_batch_f_exp_expand_threshold_title",
            "presentation_batch_f_exp_content_appears_at",
            "presentation_batch_f_exp_close_threshold_title",
            "presentation_batch_f_exp_transparent_placeholders_title",
            "presentation_batch_f_exp_player_ui_tweaks_section",
            "presentation_batch_f_exp_visual_quality",
            "presentation_batch_f_exp_screen_title",
        )
        // The shipped languages (en, es, sv) are in the source set; the ten unbuilt ones (B14-4) are kept under
        // translations-unbuilt/ and must not carry the dead strings back in when one of them is brought back.
        val locales = listOf("src/main/res/values", "src/main/res/values-es", "src/main/res/values-sv") +
            listOf("ar", "de", "fr", "in", "it", "ko", "nb", "ru", "tr", "zh-rCN").map { "translations-unbuilt/values-$it" }
        locales.forEach { locale ->
            val text = resourceFile("app/$locale/strings_presentation_batch_f.xml").readText()
            removed.forEach { key ->
                assertThat(text).doesNotContain("""<string name="$key">""")
            }
        }
    }

    @Test
    fun `the Experimental resources that moved to Lyrics and Appearance survived`() {
        // These are referenced from the Settings category screen, which is where the four
        // product-facing rows went. Deleting them with the screen would have blanked live rows.
        val en = resourceFile("app/src/main/res/values/strings_presentation_batch_f.xml").readText()
        val es = resourceFile("app/src/main/res/values-es/strings_presentation_batch_f.xml").readText()
        listOf(
            "presentation_batch_f_exp_animated_lyrics_title",
            "presentation_batch_f_exp_animated_lyrics_subtitle",
            "presentation_batch_f_exp_lyric_blur_title",
            "presentation_batch_f_exp_lyric_blur_subtitle",
            "presentation_batch_f_exp_blur_strength",
            "presentation_batch_f_exp_album_art_resolution",
        ).forEach { key ->
            assertThat(en).contains("""<string name="$key">""")
            assertThat(es).contains("""<string name="$key">""")
        }
    }

    // --- A/B, continued: the mono boundary -------------------------------------------------------

    @Test
    fun `a choice row renders its selection as prose, not as a measurement`() {
        val rows = resourceFile(
            "app/src/main/java/com/lostf1sh/pixelplayeross/ui/ds/DapSettingsRows.kt"
        ).readText()
        val choice = rows.substringAfter("fun DapChoiceRow(").substringBefore("\n}")

        // A selector's selected label is a word the user picked — "Follow System", "Embedded
        // First". It went through the nav row's mono value slot, which set every selection on every
        // category screen in JetBrains Mono.
        assertThat(choice).contains("valueStyle = DapType.CaptionMedium")
    }

    @Test
    fun `the backup data layer holds no user-facing English literals`() {
        // The progress, warning and failure sentences are built as messages now, not as text.
        val sources = sourceUnder("app/src/main/java/com/lostf1sh/pixelplayeross/data/backup")
        require(sources.isNotEmpty()) { "Backup sources not found." }
        val offenders = sources
            .filterNot { it.name == "AppDataBackupManager.kt" }   // deprecated, unreachable
            .filter { file ->
                val text = file.readText()
                // A title/detail/message argument that is a bare literal rather than a message.
                Regex("""(title|detail|message)\s*=\s*"[A-Z][^"]*"""").containsMatchIn(text)
            }
        assertThat(offenders.map { it.name }).isEmpty()
    }
}
