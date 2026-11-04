package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class B52StringsContractTest {
    private val required = setOf("info_internet_title",
        "info_remove_from_queue", "info_clear_selection", "info_all_saved", "info_downloading_finishes", "info_saved_chosen",
        "info_on_phone", "info_shared_items", "info_cleared_undo",
        "music_info_prepare",
        "offline_download_title",
        "offline_download_all",
        "info_entry_settings_sub",
        "setcat_music_info",
        "music_info_delete",
        "info_scope_library",
        "info_scope_album",
        "info_scope_artist",
        "info_scope_selection",
        "info_step_find",
        "info_step_choose",
        "info_step_download",
        "info_find_title",
        "info_find_note",
        "info_find",
        "info_find_again",
        "info_wifi_only",
        "info_runs_in_background",
        "info_preparing",
        "info_preparing_note",
        "info_find_queued",
        "info_searching",
        "info_choose_while_searching",
        "info_checked_today",
        "info_checked_days_ago",
        "info_up_to_date",
        "info_result_available",
        "info_result_local",
        "info_result_missing",
        "info_result_failed",
        "info_could_not_check",
        "info_could_not_check_hint",
        "info_retry_n",
        "info_empty_title",
        "info_empty_body",
        "info_select_all",
        "info_kinds_selected",
        "info_bios",
        "offline_download_descriptions",
        "info_kind_available",
        "info_kind_chosen",
        "info_kind_saved",
        "info_kind_queued",
        "info_kind_to_save",
        "info_no_size",
        "info_no_size_note",
        "info_choose_by_group",
        "info_by_group_title",
        "info_back_to_summary",
        "info_artists_count",
        "info_group_chosen",
        "info_group_select_all",
        "info_nothing_available",
        "info_no_match",
        "info_no_match_hint",
        "info_clear_search",
        "info_item_photo",
        "info_item_bio",
        "info_item_cover",
        "info_item_album_desc",
        "info_item_lyrics",
        "info_unchecked",
        "info_saved",
        "info_local",
        "info_missing",
        "info_queued",
        "info_downloading_item",
        "info_will_retry",
        "info_deleted_by_you",
        "info_selected",
        "info_at_least",
        "info_new_and_saved",
        "info_delete_selected",
        "info_downloading",
        "info_paused",
        "info_paused_note",
        "info_waiting_wifi",
        "info_waiting_wifi_note",
        "info_offline",
        "info_offline_note",
        "info_source_wait",
        "info_source_wait_note",
        "offline_download_retry",
        "info_source_down",
        "info_queue_global",
        "info_done",
        "info_done_note",
        "info_ok",
        "info_error_find",
        "offline_download_failed",
        "info_error_select",
        "info_error_delete",
        "info_error_pause",
        "info_error_resume",
        "info_choice_kept",
        "info_retry",
        "music_info_refreshing",
        "music_info_refreshed",
        "music_info_refresh_failed",
        "music_info_delete_failed",
        "info_deleting",
        "info_deleted_undo",
        "music_info_deleted_page",
        "info_undo",
        "setcat_music_info_delete_title",
        "setcat_music_info_delete_subtitle",
        "setcat_music_info_nothing_saved",
        "setcat_delete_confirm_title",
        "setcat_delete_confirm_what",
        "setcat_delete_confirm_local",
        "setcat_delete_confirm_lasting",
        "common_cancel",
        "common_delete",
        "setcat_music_info_note",
        "setcat_music_info_auto",
        "setcat_music_info_covers_title",
        "setcat_music_info_covers_subtitle",
        "setcat_music_info_photos_subtitle",
        "setcat_music_info_descriptions_title",
        "setcat_music_info_descriptions_subtitle",
        "setcat_lyrics_pointer",
        "music_info_read_more",
        "music_info_source_line",
        "cd_show_metadata_info",
        "credits_group_text",
        "credits_group_photo",
        "credits_group_id",
        "credits_musicbrainz_id",
        "music_info_read_less",
        "info_download_n",
        "info_cancel",
        "info_cancel_note",
        "info_cancelled_undo",
        "info_error_cancel",
        "info_error_undo",
        "info_step_done",
        "info_step_current",
        "info_step_pending",
        "info_expanded",
        "info_collapsed",
        "info_available_pending",
        "info_so_far",
        "info_none_chosen",
        "info_retrying_count",
        "info_size_not_given",
        "info_find_waiting_wifi_note",
        "info_find_offline_note",
        "info_seconds",
        "info_minutes",
        "setcat_music_info_deleted",
        "info_scope_counts",
        "music_info_source_musicbrainz",
        "music_info_from_source",
        "info_deleting_all",
        "info_undone", "info_new_count", "info_available_count", "info_remaining_count", "info_albums_count", "info_songs_count", "info_saved_count")
    private fun resources(folder: String): Map<String,String> {
        val values=linkedMapOf<String,String>()
        File("src/main/res/$folder").listFiles()!!.filter { it.extension=="xml" }.forEach { file ->
            val children=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
            for(i in 0 until children.length) {
                val node=children.item(i)
                if(node.nodeName !in listOf("string","plurals")) continue
                val key=node.attributes.getNamedItem("name").nodeValue
                assertThat(values.keys).doesNotContain(key)
                if (node.nodeName == "plurals") {
                    val items = (0 until node.childNodes.length).map { node.childNodes.item(it) }
                        .filter { it.nodeName == "item" }.associate { it.attributes.getNamedItem("quantity").nodeValue to it.textContent }
                    assertThat(items.keys).containsAtLeast("one", "other")
                    if (folder == "values-es" && key in required) assertThat(items.keys).contains("many")
                    val arguments = Regex("%[0-9]+\\$[dsf]")
                    val expected = arguments.findAll(items.getValue("other")).map { it.value }.toList().sorted()
                    items.values.forEach { assertThat(arguments.findAll(it).map { match -> match.value }.toList().sorted()).containsExactlyElementsIn(expected) }
                    values[key] = items.getValue("other")
                } else values[key]=node.textContent
            }
        }
        return values
    }
    @Test fun `all design rows and cancel have explicit English Spanish and Swedish resources with matching arguments`() {
        val maps=listOf("values","values-es","values-sv").map(::resources)
        val pattern=Regex("%[0-9]+\\$[dsf]")
        for(key in required) {
            maps.forEach { map -> assertThat(map.keys).contains(key); assertThat(map[key]!!.trim()).isNotEmpty() }
            val formats=maps.map { pattern.findAll(it.getValue(key)).map { match -> match.value }.toList().sorted() }
            assertThat(formats[1]).containsExactlyElementsIn(formats[0]);assertThat(formats[2]).containsExactlyElementsIn(formats[0])
        }
        assertThat(maps[1]["info_internet_title"]).isEqualTo("Información musical")
        assertThat(maps[2]["info_clear_selection"]).isEqualTo("Rensa val")
        assertThat(maps[2]["info_shared_items"]).isEqualTo("%1\$d delas med andra")
        assertThat(maps[2]["info_on_phone"]).isEqualTo("%1\$d på telefonen")
        assertThat(maps[2]["info_choose_by_group"]).isEqualTo("Välj efter artist eller album")
        assertThat(maps[2]["info_cancelled_undo"]).isEqualTo("Avbröt %1\$d. Redan sparade objekt behålls.")
    }
    @Test fun `resources used only by the replaced screen and disclosure are removed in every language`() {
        val old=setOf("info_reported_size","info_checked_result","music_info_expand","music_info_collapse","offline_download_progress_items","info_refreshing_catalog")
        listOf("values","values-es","values-sv").map(::resources).forEach { assertThat(it.keys.intersect(old)).isEmpty() }
    }
}
