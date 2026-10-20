package com.lostf1sh.pixelplayeross.presentation.navigation

import com.lostf1sh.pixelplayeross.presentation.model.SettingsCategory
import com.lostf1sh.pixelplayeross.presentation.model.SettingsVisibility

/**
 * What chrome a route may carry.
 *
 * The shell asks this once per route instead of twenty screens each deciding for themselves.
 */
enum class DapSurfaceKind {
    /** Home, Search, Library, Settings root: the browsing roots. */
    Root,

    /** A consumer music surface: album, artist, playlist, genre, Daily Mix, statistics, bookmarks. */
    MusicDetail,

    /** A Settings page below the root, including the profile and EQ surfaces. */
    SettingsPage,

    /** Appliance administration and diagnostics. */
    Administrative,
}

/**
 * Resolves the chrome for a navigation route.
 *
 * **The shelf is an allow-list, not a deny-list.** That is the point of this object. The previous
 * revision classified routes by an exclusion set, so every route it had not been told about —
 * `headphone_profile`, `dac_profile`, `equalizer`, `safety_max_volume`, `known_dacs`,
 * `known_headphones` — fell through to "music detail" and silently regained the player shelf. On
 * the device that is exactly what happened: the shelf sat over the headphone profile and the EQ.
 *
 * A route carries the shelf only if it is named as a music surface below. A route nobody has
 * classified carries **no** shelf, which is the safe failure: an unmapped screen loses a bar it
 * might have wanted, rather than gaining one over its own controls.
 */
object DapSurfacePolicy {

    /**
     * Consumer music surfaces that keep the shelf: the pages a listener browses *music* on, where
     * reaching for the transport should not mean leaving the page.
     */
    private val musicDetailRoutes = setOf(
        Screen.AlbumDetail.route,
        Screen.ArtistDetail.route,
        Screen.PlaylistDetail.route,
        Screen.GenreDetail.route,
        Screen.AutoCollection.route,
        Screen.SmartCollections.route,
        Screen.RecentlyPlayed.route,
        Screen.Stats.route,
        Screen.AudioBookmarks.route,
        Screen.AudioBookmarkFolder.route,
    )

    private val rootRoutes = setOf(
        Screen.Home.route,
        Screen.Search.route,
        Screen.Library.route,
        Screen.Settings.route,
    )

    private val administrativeRoutes = setOf(
        Screen.Maintenance.route,
        Screen.DeviceCapabilities.route,
    )

    /**
     * Settings-owned routes that the category list does not link to directly.
     *
     * `equalizer`, `known_dacs`, `known_headphones`, `dac_profile`, `headphone_profile` and
     * `safety_max_volume` are the profile and EQ surfaces: configuration pages with their own
     * controls in the lower third of the screen. They belong to Settings even though nothing on the
     * Settings root navigates to them by name.
     */
    private val settingsOwnedRoutes = setOf(
        Screen.SettingsCategory.route,
        Screen.ArtistSettings.route,
        Screen.PaletteStyle.route,
        Screen.DelimiterConfig.route,
        Screen.WordDelimiterConfig.route,
        Screen.About.route,
        Screen.Licenses.route,
        Screen.SpecialThanks.route,
        Screen.Duplicates.route,
        Screen.EditTransition.route,
        Screen.Equalizer.route,
        Screen.KnownDacs.route,
        Screen.DacProfile.route,
        Screen.KnownHeadphones.route,
        Screen.HeadphoneProfile.route,
        Screen.SafetyMaxVolume.route,
    )

    /**
     * Settings categories that are appliance administration rather than a user preference.
     *
     * Read from the ownership statement [SettingsCategory] already carries, so a category cannot be
     * public in the settings list and administrative to the shell at the same time.
     */
    private val administrativeSettingsCategoryIds: Set<String> =
        SettingsCategory.entries
            .filter { it.visibility != SettingsVisibility.PUBLIC }
            .map { it.id }
            .toSet()

    /**
     * The path part of a route, without its query string.
     *
     * Navigation Compose matches a destination on the pattern including its query, but the chrome a
     * screen carries is a property of the screen, not of the arguments it was opened with. Settings
     * Search used to append `?highlightKey=…` to destinations, and a pattern carrying a query
     * defeated [prefixOf] — `device_capabilities?highlightKey={highlightKey}` never matched the
     * route `device_capabilities`, so that screen was classified as an unmapped page. Search is
     * gone and no route carries a query any more; this strips one anyway, so the same defect cannot
     * return the next time a route grows an argument.
     */
    private fun pathOf(route: String): String = route.substringBefore("?")

    /** The prefix a parameterised route matches on, e.g. `album_detail/`. */
    private fun prefixOf(route: String): String = pathOf(route).substringBefore("/{") + "/"

    /** The path a declared route's family shares, e.g. `album_detail` for `album_detail/{id}`. */
    private fun basePathOf(declared: String): String = pathOf(declared).substringBefore("/{")

    private fun matches(route: String, declared: String): Boolean {
        val path = pathOf(route)
        if (!declared.contains("{")) return path == declared
        if (path.startsWith(prefixOf(declared))) return true
        // A declared route whose only argument is a query — `edit_transition?playlistId={id}` —
        // has no path parameter, so `prefixOf` produces `edit_transition/` and can never match the
        // same screen opened with the query omitted. That form is a real destination: it is how the
        // global transitions editor is reached. Without this the route still classified correctly,
        // but only because the unmapped fallback happens to agree, which is not a reason to rely on.
        return path == basePathOf(declared)
    }

    private fun matchesAny(route: String, declared: Set<String>): Boolean =
        declared.any { matches(route, it) }

    fun kindFor(route: String?): DapSurfaceKind {
        // An unmapped route carries the least chrome. See the class comment.
        if (route == null) return DapSurfaceKind.SettingsPage

        if (route in rootRoutes) return DapSurfaceKind.Root
        // B32-Q2: the Library showing a song for another page is the Library: its chrome, its shelf and its bottom bar.
        if (isLibraryLocateRoute(route)) return DapSurfaceKind.Root
        if (matchesAny(route, administrativeRoutes)) return DapSurfaceKind.Administrative

        if (matchesAny(route, settingsOwnedRoutes)) {
            if (matches(route, Screen.SettingsCategory.route)) {
                val categoryId = pathOf(route).substringAfterLast('/')
                if (categoryId in administrativeSettingsCategoryIds) {
                    return DapSurfaceKind.Administrative
                }
            }
            return DapSurfaceKind.SettingsPage
        }

        if (matchesAny(route, musicDetailRoutes)) return DapSurfaceKind.MusicDetail

        return DapSurfaceKind.SettingsPage
    }

    /** The player shelf shows on the roots and on the music surfaces. Nowhere else. */
    fun showsPlayerShelf(route: String?): Boolean = when (kindFor(route)) {
        DapSurfaceKind.Root, DapSurfaceKind.MusicDetail -> true
        DapSurfaceKind.SettingsPage, DapSurfaceKind.Administrative -> false
    }

    /**
     * The bottom navigation is for the browsing roots.
     *
     * Settings root is deliberately excluded. It is a root by ownership, but it is reached by
     * drilling *out* of the app rather than along it and its own top bar carries the way back; the
     * device has shipped with the bar hidden there since before this policy existed. Flipping that
     * is a product decision rather than a bug fix, so it is stated here instead of changed
     * silently.
     */
    fun showsBottomNavigation(route: String?): Boolean =
        kindFor(route) == DapSurfaceKind.Root && route != Screen.Settings.route
}
