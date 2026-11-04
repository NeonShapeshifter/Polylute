package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

class B55HomeCostTest {
    private val root=File("src/main/java/com/lostf1sh/pixelplayeross")
    @Test fun `H10 Home has no settings model full preview subscription or songs in saved instance state`():Unit {
        val home=File(root,"presentation/screens/HomeScreen.kt").readText()
        assertThat(home).doesNotContain("SettingsViewModel")
        assertThat(home).doesNotContain("homeMixPreviewSongs.collectAsStateWithLifecycle")
        assertThat(home).doesNotContain("rememberSaveable { mutableStateOf(latestRecentlyPlayedSongs)")
        assertThat(File(root,"presentation/navigation/DapRootShell.kt").readText()).doesNotContain("dap-root-home-settings")
    }
    @Test fun `H10 the Home playlist model is created inside the add to playlist sheet`():Unit {
        val daily=File(root,"presentation/components/DailyMixSection.kt").readText()
        assertThat(daily.indexOf("val playlistViewModel: PlaylistViewModel")).isGreaterThan(daily.indexOf("if (showPlaylistBottomSheet"))
    }
}
