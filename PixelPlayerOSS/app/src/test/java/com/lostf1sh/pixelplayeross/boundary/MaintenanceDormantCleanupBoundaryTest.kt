package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * AUD-106 (owner decision): no automatic purge; a manual Maintenance action that shows how many
 * items without a file would be removed and asks before removing them. Plus, after AUD-009, the
 * rebuild warning must stop claiming favourites and lyrics are deleted — they no longer are.
 *
 * Source-level: Compose screens and a Hilt ViewModel cannot run on this JVM.
 */
class MaintenanceDormantCleanupBoundaryTest {

    // B32-Q6: the action moved from Maintenance (behind a PIN) to the System page of Settings; the page asks, the wiring calls the view model.
    private val screen = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/settings/SystemSettings.kt").readText()
    private val wiring = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/settings/SettingsActionsWiring.kt").readText()
    private val viewModel = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/SettingsViewModel.kt").readText()

    private fun string(locale: String, file: String, name: String): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values$locale/$file"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .first { it.getAttribute("name") == name }.textContent
    }

    @Test
    fun `the view model counts and removes through the one selection that also protects the listening history`() {
        // The behaviour is proved by DormantItemCleanupTest (real Room, real history); this only pins that the screen reaches it, and
        // not the DAO directly, which cannot see the history (R28-01).
        assertThat(viewModel).contains("dormantItemCleanup.count()")
        assertThat(viewModel).contains("dormantItemCleanup.remove()")
        assertThat(viewModel).doesNotContain("libraryItemDao")
    }

    @Test
    fun `the screen shows the count and removes only from a confirmation dialog`() {
        assertThat(screen).contains("purgeableDormantCount")
        // the row only asks; the action is the dialog's "yes"
        val rows = screen.substringAfter("fun SystemSettingsScreen(").substringBefore("confirming?.let { which ->")
        val dialogs = screen.substringAfter("confirming?.let { which ->")
        assertThat(rows).contains("confirming = SystemConfirmation.CLEANUP")
        assertThat(rows).doesNotContain("onCleanUpItemsWithoutFile")
        assertThat(dialogs.substringAfter("SystemConfirmation.CLEANUP -> ConfirmDialog(").substringBefore("SystemConfirmation.RESTORE_HONOR"))
            .contains("run(actions::onCleanUpItemsWithoutFile)")
        // and the only caller of the view model's removal is the wiring behind that action
        assertThat(wiring).contains("vm.purgeDormantItems")
        assertThat(screen).doesNotContain("purgeDormantItems")
    }

    @Test
    fun `the rebuild warning no longer claims favourites and lyrics are removed`() {
        val en = string("", "strings_settings.xml", "dialog_rebuild_database_message").lowercase()
        val es = string("-es", "strings_settings.xml", "dialog_rebuild_database_message").lowercase()
        assertThat(en).doesNotContain("remove local imported lyrics, favorites")
        assertThat(es).doesNotContain("se perderán letras importadas, favoritos")
    }
}
