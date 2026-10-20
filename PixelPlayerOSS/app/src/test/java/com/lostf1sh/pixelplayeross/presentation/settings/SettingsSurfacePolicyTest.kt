package com.lostf1sh.pixelplayeross.presentation.settings

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.dap.admin.MaintenanceSessionController
import com.lostf1sh.pixelplayeross.dap.admin.MaintenanceSessionState
import com.lostf1sh.pixelplayeross.presentation.model.SettingsCategory
import com.lostf1sh.pixelplayeross.presentation.model.SettingsVisibility
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import org.junit.jupiter.api.Test

/**
 * The product contracts behind Settings ownership: what a listener may reach and what is reserved for Maintenance (a page of the
 * System category; B32-Q6: no PIN, no Developer category, the same list in every build).
 *
 * These assert ownership and the shape of the public information architecture, not layout. Nothing
 * here inspects a pixel or a colour, so a legitimate visual change cannot break them, and a change
 * that quietly re-exposes an internal tool, or drops a category out of the public list, cannot pass
 * them.
 */
class SettingsSurfacePolicyTest {

    // --- Contract 1: the shipped Settings surface is the product, and only the product -----------

    /** The public information architecture, in the order the Settings root must present it. */
    private val publicIa = listOf(
        "audio", "library", "lyrics", "appearance", "behavior", "device", "home_mode", "system", "backup_restore", "about",
    )

    @Test
    fun `the shipped Settings surface is exactly the ten public categories in order`() {
        val visible = SettingsSurfacePolicy.visibleCategories()

        assertThat(visible.filter { it.visibility != SettingsVisibility.PUBLIC }).isEmpty()
        assertThat(visible.map { it.id }).containsExactlyElementsIn(publicIa).inOrder()
    }

    @Test
    fun `there is no Developer category in any build, and the System category took its place`() {
        assertThat(SettingsCategory.fromId("developer")).isNull()
        assertThat(SettingsCategory.entries.map { it.id }).doesNotContain("developer")
        assertThat(SettingsVisibility.entries.map { it.name }).containsExactly("PUBLIC", "MAINTENANCE").inOrder()
        assertThat(SettingsCategory.fromId("system")).isEqualTo(SettingsCategory.SYSTEM)
        assertThat(SettingsCategory.SYSTEM.visibility).isEqualTo(SettingsVisibility.PUBLIC)
        assertThat(Screen.SettingsCategory.createRoute(SettingsCategory.SYSTEM.id)).isEqualTo("settings_category/system")
    }

    @Test
    fun `every category a listener can see still resolves back to itself`() {
        // The Settings root turns a row into a route by id. If a visible category's id did not
        // round-trip, the row would open nothing.
        SettingsSurfacePolicy.visibleCategories().forEach { category ->
            assertThat(SettingsCategory.fromId(category.id)).isEqualTo(category)
        }
    }

    // --- Contract 2: appliance tools are never product surface -----------------------------------

    @Test
    fun `device diagnostics is never offered by Settings as a category`() {
        assertThat(SettingsSurfacePolicy.visibleCategories()).doesNotContain(SettingsCategory.DEVICE_CAPABILITIES)
    }

    @Test
    fun `appliance diagnostics are not a category of Settings`() {
        // Maintenance is a page the System category links to; it is not a category, and its ownership class is never listed.
        assertThat(SettingsSurfacePolicy.isVisible(SettingsVisibility.MAINTENANCE)).isFalse()
    }

    @Test
    fun `device diagnostics moved owner rather than disappearing`() {
        assertThat(SettingsCategory.DEVICE_CAPABILITIES.visibility).isEqualTo(SettingsVisibility.MAINTENANCE)
        assertThat(Screen.DeviceCapabilities.route).isEqualTo("device_capabilities")
    }

    // --- Contract 3: the categories the old IA split subjects across are gone ---------------------

    @Test
    fun `the retired location-named categories are not public categories any more`() {
        // Wireless, Display, Storage and Playback were named after where a control used to live.
        // Their contents moved to the category that owns the subject — Device, Audio, Behavior —
        // so a row cannot reappear under a name that answers "where" instead of "what".
        listOf("wireless", "display", "storage", "playback").forEach { retired ->
            assertThat(SettingsCategory.fromId(retired)).isNull()
            assertThat(SettingsSurfacePolicy.visibleCategories().map { it.id })
                .doesNotContain(retired)
        }
    }

    @Test
    fun `no public category is a duplicate of another subject`() {
        // "Equalizer" is reached through the headphone profile that owns the curve, so a top-level
        // Equalizer category would be a second owner of the same state.
        assertThat(SettingsSurfacePolicy.visibleCategories().map { it.id })
            .doesNotContain("equalizer")
    }

    // --- Contract 4: About and its legal notices stay reachable ----------------------------------

    @Test
    fun `about remains a product surface in every build`() {
        assertThat(SettingsSurfacePolicy.visibleCategories()).contains(SettingsCategory.ABOUT)

        // Attribution and licence notices are not optional, so the About entry must survive into a
        // release build, and its route must still be built from the category id.
        assertThat(Screen.SettingsCategory.createRoute(SettingsCategory.ABOUT.id))
            .isEqualTo("settings_category/about")
    }

    // --- Contract 5: the Maintenance entry is not gated on the thing it repairs -------------------

    @Test
    fun `maintenance asks nothing of the session and the session is only the window in which Lock Task is lifted`() {
        val controller = MaintenanceSessionController()

        // nothing opens a window by itself, and a window that was never opened is not authenticated
        assertThat(controller.snapshot.value.isAuthenticated).isFalse()
        assertThat(controller.snapshot.value.state).isEqualTo(MaintenanceSessionState.NOT_AUTHENTICATED)

        controller.begin(nowElapsedMs = 0L)
        assertThat(controller.snapshot.value.isAuthenticated).isTrue()
        // no state exists for "an entry was authorized with a PIN"
        assertThat(MaintenanceSessionState.entries.map { it.name })
            .containsExactly("NOT_AUTHENTICATED", "AUTHENTICATED_ACTIVE", "AUTHENTICATED_EXTERNAL_FLOW", "EXPIRED")
    }

    @Test
    fun `the maintenance session still expires on idle and in absolute terms`() {
        // Reorganising Maintenance content must not lengthen its life. These are the two limits the
        // architecture is defined by.
        assertThat(MaintenanceSessionController.IDLE_TIMEOUT_MS).isEqualTo(5 * 60 * 1_000L)
        assertThat(MaintenanceSessionController.ABSOLUTE_TIMEOUT_MS).isEqualTo(15 * 60 * 1_000L)

        val idle = MaintenanceSessionController()
        idle.begin(nowElapsedMs = 0L)
        idle.expireIfNeeded(MaintenanceSessionController.IDLE_TIMEOUT_MS)
        assertThat(idle.snapshot.value.isAuthenticated).isFalse()

        val absolute = MaintenanceSessionController()
        absolute.begin(nowElapsedMs = 0L)
        // Kept warm by interaction, so only the absolute limit can end it.
        repeat(30) { step ->
            absolute.recordInteraction(nowElapsedMs = step * 60_000L)
        }
        absolute.expireIfNeeded(nowElapsedMs = 20 * 60_000L)
        assertThat(absolute.snapshot.value.isAuthenticated).isFalse()
    }

    @Test
    fun `the maintenance entry exists in every product mode`() {
        // It used to be `isDedicatedActive`, which offered recovery only to a device that had
        // already finished the provisioning being recovered. Every mode must now expose the entry
        // (there is no PIN; B32-Q6).
        val modes = com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode.entries.map { mode ->
            com.lostf1sh.pixelplayeross.dap.mode.ProductModeState(
                requestedMode = com.lostf1sh.pixelplayeross.dap.mode.ProductModeRequest.APP,
                effectiveMode = mode,
                isDeviceOwner = mode == com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode.DEDICATED_ACTIVE,
                hasDedicatedProvisioningEvidence =
                    mode == com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode.DEDICATED_ACTIVE,
            )
        }

        assertThat(modes.map { it.effectiveMode }).hasSize(5)
        modes.forEach { state ->
            assertThat(state.isMaintenanceAvailable).isTrue()
        }
    }

    @Test
    fun `app mode and setup-required are not dedicated, so containment is not attempted`() {
        // The mode still decides whether there is a Lock Task to suspend. Only the entry stopped
        // depending on it.
        val app = com.lostf1sh.pixelplayeross.dap.mode.ProductModeResolver.resolve(
            requestedMode = null,
            evidence = com.lostf1sh.pixelplayeross.dap.mode.DedicatedProvisioningEvidence(
                isDeviceOwner = false,
                isPixelPlayerHome = false,
                isDedicatedModeEnabled = false,
                isLockTaskPermitted = false,
            ),
        )
        assertThat(app.effectiveMode)
            .isEqualTo(com.lostf1sh.pixelplayeross.dap.mode.EffectiveProductMode.APP)
        assertThat(app.isDedicatedActive).isFalse()
        assertThat(app.isMaintenanceAvailable).isTrue()
    }
}
