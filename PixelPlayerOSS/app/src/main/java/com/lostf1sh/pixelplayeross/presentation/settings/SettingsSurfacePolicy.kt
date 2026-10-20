package com.lostf1sh.pixelplayeross.presentation.settings

import com.lostf1sh.pixelplayeross.presentation.model.SettingsCategory
import com.lostf1sh.pixelplayeross.presentation.model.SettingsVisibility

/**
 * The one place that decides which ownership classes may appear in the product's Settings surface.
 *
 * Settings is reached through the category list on the Settings root, and nothing else. This object
 * is the one place that decides which ownership classes may appear there, so a surface cannot be
 * hidden from the list and still reachable some other way.
 *
 * B32-Q6: the Developer category is gone (its tools are Maintenance's) and with it the build-type
 * parameter: every build shows the same list.
 */
object SettingsSurfacePolicy {

    /** Categories a listener may see, in the order they are declared. */
    fun visibleCategories(): List<SettingsCategory> =
        SettingsCategory.entries.filter { isVisible(it.visibility) }

    fun isVisible(visibility: SettingsVisibility): Boolean = when (visibility) {
        // Normal product functionality: always.
        SettingsVisibility.PUBLIC -> true
        // Appliance diagnostics are Maintenance's, which is a page the System category links to; they are not a category of their
        // own in any build.
        SettingsVisibility.MAINTENANCE -> false
    }
}
