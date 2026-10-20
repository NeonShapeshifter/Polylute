package com.lostf1sh.pixelplayeross.presentation.model

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.ui.graphics.vector.ImageVector
import com.lostf1sh.pixelplayeross.R

/**
 * Who owns a settings category, and therefore who may see it.
 *
 * A category is not a UI grouping; it is an ownership statement. Normal Settings exists so a
 * listener can play music, so anything a listener cannot act on does not belong in it, no matter
 * how ordinary its card looks.
 */
enum class SettingsVisibility {
    /** Normal product functionality. Every build, every mode. */
    PUBLIC,

    /** Appliance administration, recovery and diagnostics. Reached through Maintenance. */
    MAINTENANCE,
}

/**
 * The public Settings information architecture.
 *
 * Ten categories, in declaration order, each owning one subject:
 *
 * - **Audio** — what the sound is: devices, processing, transitions.
 * - **Library** — the music collection and how it is read.
 * - **Lyrics** — persistent lyrics behaviour, wherever it renders.
 * - **Appearance** — what the product looks like.
 * - **Behavior** — how the player and the app behave around the listener.
 * - **Device** — integration with the device itself.
 * - **Home and DAP mode** — the home app and, when Polylute is home, Launcher / Pinned / Dedicated (B34).
 * - **System** — Android's own settings, repairing the library, and leaving the DAP (B32-Q6; it was Maintenance behind a PIN).
 * - **Backup & Restore** — the user's own data, out and back in.
 * - **About** — provenance and legal notices.
 *
 * The list was previously nine categories named after where a control used to live
 * (Wireless, Display, Storage, Playback) rather than what owns it. Those were locations, not
 * subjects: "Playback" held both audio processing and app behaviour, "Display" held a duplicate
 * brightness control and a screen timeout, and persistent lyrics preferences were split across
 * Library, Appearance and a debug-only Experimental screen. Brightness itself has exactly one home,
 * the Settings root, because it is a live device control rather than a preference.
 *
 * The order is the product's; tests assert it rather than a count.
 */
enum class SettingsCategory(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val visibility: SettingsVisibility,
    val icon: ImageVector? = null,
    val iconRes: Int? = null
) {
    AUDIO(
        id = "audio",
        titleRes = R.string.settings_category_audio_title,
        subtitleRes = R.string.settings_category_audio_subtitle,
        visibility = SettingsVisibility.PUBLIC,
        icon = Icons.Rounded.Headphones
    ),
    LIBRARY(
        id = "library",
        titleRes = R.string.settings_category_library_title,
        subtitleRes = R.string.settings_category_library_subtitle,
        visibility = SettingsVisibility.PUBLIC,
        icon = Icons.Rounded.LibraryMusic
    ),
    LYRICS(
        id = "lyrics",
        titleRes = R.string.settings_category_lyrics_title,
        subtitleRes = R.string.settings_category_lyrics_subtitle,
        visibility = SettingsVisibility.PUBLIC,
        icon = Icons.Rounded.Lyrics
    ),
    APPEARANCE(
        id = "appearance",
        titleRes = R.string.settings_category_appearance_title,
        subtitleRes = R.string.settings_category_appearance_subtitle,
        visibility = SettingsVisibility.PUBLIC,
        icon = Icons.Rounded.Palette
    ),
    BEHAVIOR(
        id = "behavior",
        titleRes = R.string.settings_category_behavior_title,
        subtitleRes = R.string.settings_category_behavior_subtitle,
        visibility = SettingsVisibility.PUBLIC,
        iconRes = R.drawable.rounded_touch_app_24
    ),
    DEVICE(
        id = "device",
        titleRes = R.string.settings_category_device_title,
        subtitleRes = R.string.settings_category_device_subtitle,
        visibility = SettingsVisibility.PUBLIC,
        icon = Icons.Rounded.PhoneAndroid
    ),
    HOME_MODE(
        id = "home_mode",
        titleRes = R.string.settings_category_home_mode_title,
        subtitleRes = R.string.settings_category_home_mode_subtitle,
        visibility = SettingsVisibility.PUBLIC,
        icon = Icons.Rounded.Home
    ),
    SYSTEM(
        id = "system",
        titleRes = R.string.settings_category_system_title,
        subtitleRes = R.string.settings_category_system_subtitle,
        visibility = SettingsVisibility.PUBLIC,
        icon = Icons.Rounded.Settings
    ),
    BACKUP_RESTORE(
        id = "backup_restore",
        titleRes = R.string.settings_category_backup_title,
        subtitleRes = R.string.settings_category_backup_subtitle,
        visibility = SettingsVisibility.PUBLIC,
        icon = Icons.Rounded.UploadFile
    ),
    ABOUT(
        id = "about",
        titleRes = R.string.settings_category_about_title,
        subtitleRes = R.string.settings_category_about_subtitle,
        visibility = SettingsVisibility.PUBLIC,
        icon = Icons.Rounded.Info
    ),
    /**
     * Appliance diagnostics. Not a product category in any build: Maintenance (a page of the System category) opens
     * [com.lostf1sh.pixelplayeross.presentation.navigation.Screen.DeviceCapabilities] directly.
     * It keeps a [SettingsVisibility.MAINTENANCE] declaration so the shell's chrome policy reads
     * ownership from here rather than from a second list.
     */
    DEVICE_CAPABILITIES(
        id = "device_capabilities",
        titleRes = R.string.settings_category_device_capabilities_title,
        subtitleRes = R.string.settings_category_device_capabilities_subtitle,
        visibility = SettingsVisibility.MAINTENANCE,
        icon = Icons.Rounded.DeveloperBoard
    );

    companion object {
        fun fromId(id: String): SettingsCategory? = entries.find { it.id == id }
    }
}
