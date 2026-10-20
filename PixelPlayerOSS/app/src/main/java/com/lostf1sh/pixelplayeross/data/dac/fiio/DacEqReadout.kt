package com.lostf1sh.pixelplayeross.data.dac.fiio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the labels read: the DAC's EQ as a label, live. Implemented by [DacEqController]; the tests use a fixed one. */
interface DacEqLabelSource {
    val label: StateFlow<DacEqLabel>
}

/** No DAC EQ at all: every label says what it said before B30. */
object NoDacEqLabels : DacEqLabelSource {
    override val label: StateFlow<DacEqLabel> = MutableStateFlow(DacEqLabel.NOT_APPLICABLE)
}

/**
 * A FiiO/Snowsky DAC the app can ask, as attached right now.
 *
 * @property attachKey one value per attachment (the identity plus the USB device path, which changes on every re-enumeration):
 *   the read happens once per key.
 * @property identityKey the DAC profile's `deviceIdentityKey`: per serial when the DAC exposes one, else per model. Backups are
 *   keyed by it.
 * @property dacProfileId the DAC profile's id, the DAC half of a headphone + DAC pair; null before the catalog has registered it.
 */
data class AttachedFiio(
    val attachKey: String,
    val identityKey: String,
    val dacProfileId: String?,
    val displayName: String,
    val connectionPath: String?,
    val profile: FiioHidProfile,
) {
    /**
     * What a backup of this DAC is filed under: the DAC profile's id, which is per serial when the DAC exposes one and per
     * model when it does not, and stays the same when a model-scoped profile is promoted to a unit once the serial can be read.
     */
    val backupKey: String get() = dacProfileId ?: identityKey
}

/**
 * B30-fix1 H2: which attachment of which DAC an edit, a read or a write belongs to. The [generation] moves every time the controller
 * sees the DAC go away or another one come, so an unplug and a plug of the same unit, even on the same USB path, is a different
 * attachment from the one an edit was accepted on.
 */
data class AttachmentToken(val attachKey: String, val generation: Long)

/** What the app knows of the attached DAC's EQ. */
sealed interface DacEqReadout {
    val label: DacEqLabel

    /** No FiiO/Snowsky DAC the app can ask is attached. */
    data object NotApplicable : DacEqReadout {
        override val label = DacEqLabel.NOT_APPLICABLE
    }

    /** One is attached; its EQ has not been read yet (waiting for Direct, or reading). Not Purity. */
    data class Pending(val attached: AttachedFiio) : DacEqReadout {
        override val label = DacEqLabel.UNKNOWN
    }

    /** The device's own answer, read at [readAtMs] (`SystemClock.elapsedRealtime`). */
    data class Known(val attached: AttachedFiio, val state: DacEqState, val readAtMs: Long) : DacEqReadout {
        override val label = DacEqLabel.of(state.verdict)
    }

    /** The interface could not be opened or claimed, or there was no permission: nothing was read. Not Purity. */
    data class Unreadable(val attached: AttachedFiio, val why: String) : DacEqReadout {
        override val label = DacEqLabel.UNKNOWN
    }

    val attachedOrNull: AttachedFiio?
        get() = when (this) {
            NotApplicable -> null
            is Pending -> attached
            is Known -> attached
            is Unreadable -> attached
        }
}
