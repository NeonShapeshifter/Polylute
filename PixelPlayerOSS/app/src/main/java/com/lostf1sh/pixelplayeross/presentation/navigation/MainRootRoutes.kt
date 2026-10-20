package com.lostf1sh.pixelplayeross.presentation.navigation

internal fun isMainRootRoute(route: String?): Boolean = when (route) {
    Screen.Home.route,
    Screen.Search.route,
    Screen.Library.route -> true
    else -> false
}

/** The Library showing a song for another page (Stats, a collection): a destination that draws nothing, with the Library root in front. */
internal fun isLibraryLocateRoute(route: String?): Boolean = route?.startsWith(Screen.LibraryLocate.PREFIX) == true

/**
 * The root that is in front (B32-Q2): the one the listener chose, or the Library while it is showing a song for another page. The choice
 * itself is never overwritten, so Back from Stats lands where the listener was.
 */
internal fun shownRootRoute(detailRoute: String?, chosenRoot: String?): String? =
    if (isLibraryLocateRoute(detailRoute)) Screen.Library.route else chosenRoot
