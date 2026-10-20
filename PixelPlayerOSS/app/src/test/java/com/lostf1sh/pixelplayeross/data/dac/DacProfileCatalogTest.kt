package com.lostf1sh.pixelplayeross.data.dac

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class DacProfileCatalogTest {
    private val ds2 = UsbDacDescriptor(
        vendorId = 0x262A,
        productId = 0x0001,
        manufacturer = "Speed Dragon",
        product = "Fosi Audio DS2",
        serialNumber = "SERIAL-A",
        connectionPath = "/dev/bus/usb/001/002",
    )

    @Test
    fun `same serial reconnect reuses profile despite path change`() {
        val first = DacProfileCatalog.register(emptyList(), ds2, 100L)
        val second = DacProfileCatalog.register(
            first.profiles,
            ds2.copy(connectionPath = "/dev/bus/usb/002/009"),
            200L,
        )

        assertThat(second.created).isFalse()
        assertThat(second.profiles).hasSize(1)
        assertThat(second.profile.profileId).isEqualTo(first.profile.profileId)
        assertThat(second.profile.lastSeenEpochMs).isEqualTo(200L)
    }

    @Test
    fun `no serial creates deterministic model scoped identity`() {
        val noSerial = ds2.copy(serialNumber = null)
        val first = DacProfileCatalog.register(emptyList(), noSerial, 100L)
        val second = DacProfileCatalog.register(first.profiles, noSerial.copy(connectionPath = "changed"), 200L)

        assertThat(first.profile.identityScope).isEqualTo(DacIdentityScope.MODEL_SCOPED)
        assertThat(second.profiles).hasSize(1)
        assertThat(second.profile.profileId).isEqualTo(first.profile.profileId)
    }

    @Test
    fun `identical no serial units do not receive false unit identities`() {
        val firstUnit = ds2.copy(serialNumber = null, connectionPath = "path-one")
        val secondUnit = ds2.copy(serialNumber = null, connectionPath = "path-two")
        val first = DacProfileCatalog.register(emptyList(), firstUnit, 100L)
        val second = DacProfileCatalog.register(first.profiles, secondUnit, 200L)

        assertThat(second.profiles).hasSize(1)
        assertThat(second.profile.identityScope).isEqualTo(DacIdentityScope.MODEL_SCOPED)
    }

    @Test
    fun `different VID PID creates different profile`() {
        val first = DacProfileCatalog.register(emptyList(), ds2, 100L)
        val second = DacProfileCatalog.register(
            first.profiles,
            ds2.copy(vendorId = 0x1234, productId = 0x5678),
            200L,
        )

        assertThat(second.profiles).hasSize(2)
        assertThat(second.profile.profileId).isNotEqualTo(first.profile.profileId)
    }

    @Test
    fun `same model with different serial creates different unit profiles`() {
        val first = DacProfileCatalog.register(emptyList(), ds2, 100L)
        val second = DacProfileCatalog.register(first.profiles, ds2.copy(serialNumber = "SERIAL-B"), 200L)

        assertThat(second.profiles).hasSize(2)
        assertThat(second.profile.profileId).isNotEqualTo(first.profile.profileId)
    }

    @Test
    fun `model profile promotes when serial becomes available and keeps user identity`() {
        val model = DacProfileCatalog.register(emptyList(), ds2.copy(serialNumber = null), 100L)
        val renamed = DacProfileCatalog.rename(model.profiles, model.profile.profileId, "Desk DAC")
        val promoted = DacProfileCatalog.register(renamed, ds2, 200L)

        assertThat(promoted.promotedFromModelScope).isTrue()
        assertThat(promoted.profiles).hasSize(1)
        assertThat(promoted.profile.profileId).isEqualTo(model.profile.profileId)
        assertThat(promoted.profile.displayName).isEqualTo("Desk DAC")
        assertThat(promoted.profile.identityScope).isEqualTo(DacIdentityScope.UNIT_SPECIFIC)
    }

    @Test
    fun `rename persists in encoded payload and reconnect preserves it`() {
        val first = DacProfileCatalog.register(emptyList(), ds2, 100L)
        val renamed = DacProfileCatalog.rename(first.profiles, first.profile.profileId, "Portable Rig")
        val json = Json { ignoreUnknownKeys = true }
        val reloaded = DacProfilesCodec.decode(json, DacProfilesCodec.encode(json, renamed))
        val reconnect = DacProfileCatalog.register(reloaded, ds2.copy(connectionPath = "new"), 300L)

        assertThat(reconnect.profile.displayName).isEqualTo("Portable Rig")
        assertThat(reconnect.profiles).hasSize(1)
    }

    @Test
    fun `forget removes only requested profile`() {
        val first = DacProfileCatalog.register(emptyList(), ds2, 100L)
        val second = DacProfileCatalog.register(first.profiles, ds2.copy(serialNumber = "SERIAL-B"), 200L)

        val forgotten = DacProfileCatalog.forget(second.profiles, first.profile.profileId)

        assertThat(forgotten).containsExactly(second.profile)
    }

    @Test
    fun `corrupt or incomplete payload fails to safe empty state`() {
        val json = Json { ignoreUnknownKeys = true }

        assertThat(DacProfilesCodec.decode(json, "not-json")).isEmpty()
        assertThat(DacProfilesCodec.decode(json, "{\"schemaVersion\":1,\"profiles\":[{}]}")).isEmpty()
    }

    @Test
    fun `classifier requires audio streaming and isochronous output`() {
        assertThat(UsbDacClassifier.isEligible(UsbDacCandidateFacts(true, true))).isTrue()
        assertThat(UsbDacClassifier.isEligible(UsbDacCandidateFacts(true, false))).isFalse()
        assertThat(UsbDacClassifier.isEligible(UsbDacCandidateFacts(false, true))).isFalse()
        assertThat(UsbDacClassifier.isEligible(UsbDacCandidateFacts(false, false))).isFalse()
    }

    @Test
    fun `a flaky serial read does not create a twin of the promoted profile`() {
        // The identity key has two canonical forms: `model|…` when the device reports no serial
        // and `unit|…` when it does. A profile is created model-scoped, promoted when a serial
        // appears, and then the device drops the serial again — which cheap DACs do routinely.
        val model = DacProfileCatalog.register(emptyList(), ds2.copy(serialNumber = null), 100L)
        val promoted = DacProfileCatalog.register(model.profiles, ds2, 200L)
        assertThat(promoted.profiles).hasSize(1)

        val backToModelScope = DacProfileCatalog.register(
            promoted.profiles,
            ds2.copy(serialNumber = null),
            300L,
        )

        // One physical DAC is one profile, whatever the serial did this second.
        assertThat(backToModelScope.profiles).hasSize(1)
        assertThat(backToModelScope.profiles.map { it.profileId }).containsNoDuplicates()

        // And the weaker sighting must not erase what the stronger one learned.
        assertThat(backToModelScope.profile.serialNumber).isEqualTo("SERIAL-A")
        assertThat(backToModelScope.profile.identityScope)
            .isEqualTo(DacIdentityScope.UNIT_SPECIFIC)
        assertThat(backToModelScope.profile.lastSeenEpochMs).isEqualTo(300L)
    }
}
