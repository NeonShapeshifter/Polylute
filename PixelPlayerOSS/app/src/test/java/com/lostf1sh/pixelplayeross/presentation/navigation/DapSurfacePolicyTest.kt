package com.lostf1sh.pixelplayeross.presentation.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The chrome contract, route by route.
 *
 * The policy is an allow-list, so the failure that matters is a screen silently gaining chrome it
 * should not have. The previous revision was a deny-list defaulting to "consumer detail", and the
 * profile and EQ surfaces — which nobody had listed — spent a release with the player shelf sitting
 * over their own controls. These tests fix the classification of every declared route.
 */
class DapSurfacePolicyTest {

    // --- Roots -----------------------------------------------------------------------------------

    @Test
    fun `the browsing roots carry both bars`() {
        assertThat(DapSurfacePolicy.kindFor(Screen.Home.route)).isEqualTo(DapSurfaceKind.Root)
        assertThat(DapSurfacePolicy.kindFor(Screen.Search.route)).isEqualTo(DapSurfaceKind.Root)
        assertThat(DapSurfacePolicy.kindFor(Screen.Library.route)).isEqualTo(DapSurfaceKind.Root)
        assertThat(DapSurfacePolicy.kindFor(Screen.Settings.route)).isEqualTo(DapSurfaceKind.Root)

        listOf(Screen.Home.route, Screen.Search.route, Screen.Library.route).forEach { route ->
            assertThat(DapSurfacePolicy.showsBottomNavigation(route)).isTrue()
            assertThat(DapSurfacePolicy.showsPlayerShelf(route)).isTrue()
        }
    }

    @Test
    fun `the Settings root keeps the shelf and drops the bottom bar`() {
        // Settings is a root by ownership, but it is reached by drilling out of the app rather than
        // along it, and its own top bar carries the way back. This is a stated product decision,
        // not an accident of the deny-list, so it is pinned here.
        assertThat(DapSurfacePolicy.showsPlayerShelf(Screen.Settings.route)).isTrue()
        assertThat(DapSurfacePolicy.showsBottomNavigation(Screen.Settings.route)).isFalse()
    }

    // --- The music surfaces that keep the shelf --------------------------------------------------

    @Test
    fun `music detail surfaces keep the shelf and drop the bottom bar`() {
        listOf(
            Screen.AlbumDetail.createRoute(1L),
            Screen.ArtistDetail.createRoute(1L),
            Screen.PlaylistDetail.createRoute("p1"),
            Screen.GenreDetail.createRoute("Rock"),
            // The generated views are browsable music pages like any other. They were reachable
            // from the Library before they had been classified here, which is the exact failure
            // this test exists to catch: a route that exists and is navigated to but that the
            // policy has never heard of silently loses its shelf.
            Screen.AutoCollection.createRoute("daily_mix"),
            Screen.SmartCollections.route,
            Screen.RecentlyPlayed.route,
            Screen.Stats.route,
            Screen.AudioBookmarks.route,
        ).forEach { route ->
            assertThat(DapSurfacePolicy.kindFor(route)).isEqualTo(DapSurfaceKind.MusicDetail)
            assertThat(DapSurfacePolicy.showsPlayerShelf(route)).isTrue()
            assertThat(DapSurfacePolicy.showsBottomNavigation(route)).isFalse()
        }
    }

    // --- Settings pages: the case that regressed --------------------------------------------------

    @Test
    fun `every Settings page carries neither bar`() {
        listOf(
            Screen.SettingsCategory.createRoute("library"),
            Screen.PaletteStyle.route,
            Screen.Duplicates.route,
            Screen.ArtistSettings.route,
            Screen.Equalizer.createRoute("hp1"),
            Screen.KnownDacs.route,
            Screen.DacProfile.createRoute("dac1"),
            Screen.KnownHeadphones.route,
            Screen.HeadphoneProfile.createRoute("hp1"),
            Screen.SafetyMaxVolume.createRoute("hp1"),
            Screen.About.route,
            Screen.Licenses.route,
            Screen.SpecialThanks.route,
            Screen.DelimiterConfig.route,
            Screen.WordDelimiterConfig.route,
            Screen.EditTransition.createRoute(null),
        ).forEach { route ->
            assertThat(DapSurfacePolicy.kindFor(route)).isEqualTo(DapSurfaceKind.SettingsPage)
            assertThat(DapSurfacePolicy.showsPlayerShelf(route)).isFalse()
            assertThat(DapSurfacePolicy.showsBottomNavigation(route)).isFalse()
        }
    }

    // --- Administrative ------------------------------------------------------------------------

    @Test
    fun `appliance administration carries neither bar`() {
        listOf(
            Screen.Maintenance.route,
            Screen.DeviceCapabilities.route,
        ).forEach { route ->
            assertThat(DapSurfacePolicy.kindFor(route)).isEqualTo(DapSurfaceKind.Administrative)
            assertThat(DapSurfacePolicy.showsPlayerShelf(route)).isFalse()
            assertThat(DapSurfacePolicy.showsBottomNavigation(route)).isFalse()
        }
    }

    @Test
    fun `a non-public settings category is administrative to the shell too`() {
        // The kind is read from the category's own visibility, so a category cannot be hidden from
        // the Settings list and still be treated as an ordinary page by the shell.
        assertThat(DapSurfacePolicy.kindFor(Screen.SettingsCategory.createRoute("device_capabilities")))
            .isEqualTo(DapSurfaceKind.Administrative)
        assertThat(DapSurfacePolicy.kindFor(Screen.SettingsCategory.createRoute("system")))
            .isEqualTo(DapSurfaceKind.SettingsPage)
        assertThat(DapSurfacePolicy.kindFor(Screen.SettingsCategory.createRoute("audio")))
            .isEqualTo(DapSurfaceKind.SettingsPage)
        assertThat(DapSurfacePolicy.kindFor(Screen.SettingsCategory.createRoute("lyrics")))
            .isEqualTo(DapSurfaceKind.SettingsPage)
    }

    // --- The safe default ----------------------------------------------------------------------

    @Test
    fun `an unclassified route gets no chrome`() {
        // The whole point of the allow-list. A route nobody has classified must not fall through to
        // "consumer detail" and acquire a shelf over whatever it draws in the lower third.
        listOf("something_new", "future_screen/{id}", null).forEach { route ->
            assertThat(DapSurfacePolicy.showsPlayerShelf(route)).isFalse()
            assertThat(DapSurfacePolicy.showsBottomNavigation(route)).isFalse()
        }
    }

    @Test
    fun `the global transitions editor is a settings page in both of its forms`() {
        // The editor is reached from Audio with no playlist argument, which means the bare path is
        // now a real destination rather than a form only a test used. Both spellings must classify
        // the same way; the bare one previously matched nothing and landed on the safe default.
        listOf(
            Screen.EditTransition.createRoute(null),
            Screen.EditTransition.createRoute("playlist-1"),
            Screen.EditTransition.route,
        ).forEach { route ->
            assertThat(DapSurfacePolicy.kindFor(route)).isEqualTo(DapSurfaceKind.SettingsPage)
            assertThat(DapSurfacePolicy.showsPlayerShelf(route)).isFalse()
            assertThat(DapSurfacePolicy.showsBottomNavigation(route)).isFalse()
        }

        assertThat(Screen.EditTransition.createRoute(null)).isEqualTo("edit_transition")
    }

    @Test
    fun `a query string does not change a route's chrome`() {
        // Settings Search used to append `?highlightKey=…` to destinations, which defeated the
        // prefix match and misclassified `device_capabilities`. Search is gone; the matcher strips
        // a query anyway so the defect cannot come back with the next route argument.
        assertThat(DapSurfacePolicy.kindFor("device_capabilities?highlightKey=item_x"))
            .isEqualTo(DapSurfaceKind.Administrative)
        assertThat(DapSurfacePolicy.kindFor("settings_category/library?highlightKey=item_x"))
            .isEqualTo(DapSurfaceKind.SettingsPage)
        assertThat(DapSurfacePolicy.kindFor("album_detail/12?from=search"))
            .isEqualTo(DapSurfaceKind.MusicDetail)
    }
}
