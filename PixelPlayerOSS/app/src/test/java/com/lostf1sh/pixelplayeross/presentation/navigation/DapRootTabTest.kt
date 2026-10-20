package com.lostf1sh.pixelplayeross.presentation.navigation

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.preferences.LaunchTab
import org.junit.Test

class DapRootTabTest {
    @Test
    fun `routes map to their exact root tab`() {
        assertThat(DapRootTab.fromRouteOrHome(Screen.Home.route)).isEqualTo(DapRootTab.HOME)
        assertThat(DapRootTab.fromRouteOrHome(Screen.Search.route)).isEqualTo(DapRootTab.SEARCH)
        assertThat(DapRootTab.fromRouteOrHome(Screen.Library.route)).isEqualTo(DapRootTab.LIBRARY)
    }

    @Test
    fun `invalid and missing restored routes fall back to home`() {
        assertThat(DapRootTab.fromRouteOrHome(null)).isEqualTo(DapRootTab.HOME)
        assertThat(DapRootTab.fromRouteOrHome("album/invalid")).isEqualTo(DapRootTab.HOME)
        assertThat(DapRootTab.fromRouteOrHome("")).isEqualTo(DapRootTab.HOME)
    }

    @Test
    fun `launch preferences map without creating navigation routes`() {
        assertThat(DapRootTab.fromLaunchTabOrHome(LaunchTab.HOME)).isEqualTo(DapRootTab.HOME)
        assertThat(DapRootTab.fromLaunchTabOrHome(LaunchTab.SEARCH)).isEqualTo(DapRootTab.SEARCH)
        assertThat(DapRootTab.fromLaunchTabOrHome(LaunchTab.LIBRARY)).isEqualTo(DapRootTab.LIBRARY)
        assertThat(DapRootTab.fromLaunchTabOrHome("unknown")).isEqualTo(DapRootTab.HOME)
    }
}
