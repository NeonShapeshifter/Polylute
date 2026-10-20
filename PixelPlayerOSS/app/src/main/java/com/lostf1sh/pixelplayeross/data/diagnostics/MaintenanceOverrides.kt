package com.lostf1sh.pixelplayeross.data.diagnostics

/**
 * What an override is *for*. The distinction is load-bearing and is the whole point of the model.
 *
 * A [FEATURE_KILL] disables a product feature that is known to be broken on some OEM build. It is
 * a supported product configuration: turning it off is a legitimate end state a user may keep.
 *
 * A [DIAGNOSTIC_OVERRIDE] forces a behaviour off so a fault can be isolated. It is not a
 * preference — it is a temporary instrument, and the diagnostic surface must present it as such.
 *
 * Neither kind may ever be a normal Settings preference, and neither may gate core playback.
 */
enum class MaintenanceOverrideKind {
    FEATURE_KILL,
    DIAGNOSTIC_OVERRIDE,
}

/**
 * One switchable behaviour, with the honest description of what turning it off costs.
 *
 * [clearedBy] tells the reader what restores normal behaviour. Every override here is removable
 * by removing it, which is the invariant the store is written to guarantee.
 */
enum class MaintenanceOverride(
    val id: String,
    val kind: MaintenanceOverrideKind,
    /** Key into a short human label for the switch itself. */
    val labelKey: String,
    /** Key into a sentence naming the consequence of enabling the override. */
    val consequenceKey: String,
) {
    /**
     * Stops the app from reconciling the Lock Task feature mask and from owning the wake surface.
     * Compensates for OEM builds whose Keyguard/Lock Task interaction misbehaves; leaving it on
     * costs the appliance wake experience and nothing else.
     *
     * This is the only feature kill here, deliberately. The wake surface *is* the OEM-specific
     * integration — there is no narrower "OEM wake path" to disable independently, so a second
     * switch claiming to do that would be a control with no effect behind it. A switch that does
     * nothing is worse than an absent one: a diagnostic reading it would draw a false conclusion.
     */
    DAP_LOCK_SCREEN_INTEGRATION(
        id = "dap_lock_screen_integration",
        kind = MaintenanceOverrideKind.FEATURE_KILL,
        labelKey = "maintenance_override_lock_screen_label",
        consequenceKey = "maintenance_override_lock_screen_consequence",
    ),

    /**
     * Forces the Android audio path for every source, regardless of what the route policy decides.
     * This is the primary instrument for separating a Direct-path fault from a playback fault.
     */
    FORCE_ANDROID_AUDIO(
        id = "force_android_audio",
        kind = MaintenanceOverrideKind.DIAGNOSTIC_OVERRIDE,
        labelKey = "maintenance_override_force_android_label",
        consequenceKey = "maintenance_override_force_android_consequence",
    ),

    /**
     * Suppresses the automatic Android-to-Direct transition while leaving Direct reachable.
     * Isolates "the transition itself is the fault" from "the Direct sink is the fault".
     */
    DISABLE_DIRECT_TRANSITION(
        id = "disable_direct_transition",
        kind = MaintenanceOverrideKind.DIAGNOSTIC_OVERRIDE,
        labelKey = "maintenance_override_disable_transition_label",
        consequenceKey = "maintenance_override_disable_transition_consequence",
    ),

    /**
     * Disables the headphone DSP graph on the Android path, so its contribution to a fault can be
     * removed without editing the user's equalizer profile.
     */
    DISABLE_DSP_GRAPH(
        id = "disable_dsp_graph",
        kind = MaintenanceOverrideKind.DIAGNOSTIC_OVERRIDE,
        labelKey = "maintenance_override_disable_dsp_label",
        consequenceKey = "maintenance_override_disable_dsp_consequence",
    ),
    ;

    companion object {
        fun fromId(id: String): MaintenanceOverride? = entries.firstOrNull { it.id == id }
    }
}

/** A snapshot of what is currently overridden, for the diagnostic surface and for the engine. */
data class MaintenanceOverrides(
    val activeIds: Set<String> = emptySet(),
) {
    val active: List<MaintenanceOverride>
        get() = MaintenanceOverride.entries.filter { it.id in activeIds }

    val count: Int get() = active.size

    val isEmpty: Boolean get() = active.isEmpty()

    /**
     * The number of overrides that are *diagnostic*, as opposed to a supported feature kill. The
     * diagnostic surface reports these separately because a diagnostic override left armed is a
     * measurement error waiting to happen.
     */
    val diagnosticCount: Int
        get() = active.count { it.kind == MaintenanceOverrideKind.DIAGNOSTIC_OVERRIDE }

    fun isActive(override: MaintenanceOverride): Boolean = override.id in activeIds

    fun forcedAndroidAudio(): Boolean = MaintenanceOverride.FORCE_ANDROID_AUDIO.id in activeIds

    fun directTransitionDisabled(): Boolean =
        MaintenanceOverride.DISABLE_DIRECT_TRANSITION.id in activeIds

    fun dspGraphDisabled(): Boolean = MaintenanceOverride.DISABLE_DSP_GRAPH.id in activeIds

    fun lockScreenIntegrationDisabled(): Boolean =
        MaintenanceOverride.DAP_LOCK_SCREEN_INTEGRATION.id in activeIds

    /** The override set after toggling one id. Idempotent, and cannot invent an unknown id. */
    fun applied(override: MaintenanceOverride, active: Boolean): MaintenanceOverrides =
        MaintenanceOverrides(
            activeIds = if (active) activeIds + override.id else activeIds - override.id
        )

    companion object {
        /**
         * Reads a persisted id set, dropping anything this build no longer defines.
         *
         * An override removed from [MaintenanceOverride] in a later build must not linger as an
         * armed switch that no surface can show and no user can clear.
         */
        fun fromStoredIds(stored: Set<String>?): MaintenanceOverrides =
            MaintenanceOverrides(
                activeIds = stored.orEmpty().filterTo(mutableSetOf()) { id ->
                    MaintenanceOverride.fromId(id) != null
                }
            )
    }
}
