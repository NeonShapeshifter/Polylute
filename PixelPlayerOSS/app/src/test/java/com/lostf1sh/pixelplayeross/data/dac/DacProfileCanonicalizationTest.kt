package com.lostf1sh.pixelplayeross.data.dac

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * One stable DAC identity is one profile.
 *
 * The defect these guard: two persisted records sharing a `deviceIdentityKey` produce the same
 * `profileId`, because `profileIdFor` is a pure function of the identity. A `LazyColumn` keyed by
 * `profileId` then throws `IllegalArgumentException: Key ... was already used` during the measure
 * pass and takes the whole screen down. The repair is canonicalisation at the decode boundary, so
 * no reader can observe the duplicate in the first place.
 */
class DacProfileCanonicalizationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val vendorId = 0x262A
    private val productId = 0x1234

    private fun identityKey(serial: String) = "dac-" + "a".repeat(24) + serial

    private fun profile(
        identityKey: String,
        displayName: String,
        createdAt: Long,
        lastSeen: Long,
        product: String? = "USB DAC",
    ) = DacProfile(
        profileId = DacIdentity.profileIdFor(identityKey),
        deviceIdentityKey = identityKey,
        identityScope = DacIdentityScope.UNIT_SPECIFIC,
        displayName = displayName,
        detectedManufacturer = "Vendor",
        detectedProduct = product,
        vendorId = vendorId,
        productId = productId,
        serialNumber = "SN",
        createdAtEpochMs = createdAt,
        lastSeenEpochMs = lastSeen,
    )

    // --- the invariant, at the data boundary ---------------------------------------------------

    @Test
    fun `two records for one identity decode to a single profile`() {
        val identity = identityKey("1")
        val duplicated = listOf(
            profile(identity, "USB DAC", createdAt = 1_000, lastSeen = 2_000),
            profile(identity, "USB DAC", createdAt = 1_500, lastSeen = 3_000),
        )
        // Sanity: the two records really do collide on the key the UI uses.
        assertThat(duplicated.map { it.profileId }.distinct()).hasSize(1)

        val decoded = DacProfilesCodec.decode(json, DacProfilesCodec.encode(json, duplicated))

        assertThat(decoded).hasSize(1)
        assertThat(decoded.map { it.profileId }.distinct()).hasSize(1)
    }

    @Test
    fun `unrelated profiles survive canonicalization untouched`() {
        val first = profile(identityKey("1"), "Desk DAC", 1_000, 5_000)
        val second = profile(identityKey("2"), "Travel DAC", 2_000, 6_000)
        val third = profile(identityKey("3"), "Bedside DAC", 3_000, 7_000)

        val result = DacProfileCanonicalizer.canonicalize(listOf(first, second, third))

        assertThat(result).containsExactly(first, second, third).inOrder()
    }

    @Test
    fun `normal unique data is returned unchanged`() {
        val profiles = listOf(
            profile(identityKey("1"), "A", 1_000, 5_000),
            profile(identityKey("2"), "B", 2_000, 6_000),
        )

        // Identity, not just equality: the healthy path must not allocate a new list.
        assertThat(DacProfileCanonicalizer.canonicalize(profiles)).isSameInstanceAs(profiles)
    }

    // --- the reconciliation rule ---------------------------------------------------------------

    @Test
    fun `the most recently seen record wins and the oldest creation time is kept`() {
        val identity = identityKey("1")
        val result = DacProfileCanonicalizer.canonicalize(
            listOf(
                profile(identity, "USB DAC", createdAt = 1_000, lastSeen = 9_000),
                profile(identity, "USB DAC", createdAt = 5_000, lastSeen = 3_000),
            )
        )

        assertThat(result).hasSize(1)
        // lastSeen carries the device's current truth, so the newest record supplies the fields…
        assertThat(result.single().lastSeenEpochMs).isEqualTo(9_000)
        // …while the profile's real age survives from the oldest copy.
        assertThat(result.single().createdAtEpochMs).isEqualTo(1_000)
    }

    @Test
    fun `a renamed duplicate keeps its user-authored name`() {
        val identity = identityKey("1")
        val result = DacProfileCanonicalizer.canonicalize(
            listOf(
                // Newest, but still carrying the name the device was first given.
                profile(identity, "USB DAC", createdAt = 1_000, lastSeen = 9_000),
                // Older, but the user renamed it — the only user-authored field on a profile.
                profile(identity, "Living room", createdAt = 2_000, lastSeen = 5_000),
            )
        )

        assertThat(result.single().displayName).isEqualTo("Living room")
        assertThat(result.single().lastSeenEpochMs).isEqualTo(9_000)
    }

    @Test
    fun `the merged record keeps the winner's technical metadata`() {
        val identity = identityKey("1")
        val result = DacProfileCanonicalizer.canonicalize(
            listOf(
                profile(identity, "USB DAC", createdAt = 1_000, lastSeen = 1_000, product = "Old Name"),
                profile(identity, "USB DAC", createdAt = 2_000, lastSeen = 8_000, product = "New Name"),
            )
        )

        assertThat(result.single().detectedProduct).isEqualTo("New Name")
    }

    // --- self-healing --------------------------------------------------------------------------

    @Test
    fun `the canonical form is stable across a second round trip`() {
        val identity = identityKey("1")
        val raw = DacProfilesCodec.encode(
            json,
            listOf(
                profile(identity, "USB DAC", createdAt = 1_000, lastSeen = 2_000),
                profile(identity, "USB DAC", createdAt = 1_500, lastSeen = 3_000),
            ),
        )

        // First load canonicalises…
        val repaired = DacProfilesCodec.encode(json, DacProfilesCodec.decode(json, raw))
        // …and the repaired payload is already canonical, so the repair is idempotent and the
        // rewritten state is byte-identical on the next launch.
        assertThat(DacProfilesCodec.encode(json, DacProfilesCodec.decode(json, repaired)))
            .isEqualTo(repaired)
        assertThat(DacProfilesCodec.decode(json, repaired)).hasSize(1)
    }

    @Test
    fun `a payload with no profiles is left alone`() {
        assertThat(DacProfilesCodec.decode(json, null)).isEmpty()
        assertThat(DacProfilesCodec.decode(json, "")).isEmpty()
    }

    // --- the last-line UI guard ----------------------------------------------------------------

    @Test
    fun `collision safe keys leave unique ids exactly as they are`() {
        val profiles = listOf(
            profile(identityKey("1"), "A", 1_000, 1_000),
            profile(identityKey("2"), "B", 2_000, 2_000),
        )

        val keyed = profiles.collisionSafeKeys { it.profileId }

        assertThat(keyed.map { it.second }).containsExactlyElementsIn(profiles.map { it.profileId })
            .inOrder()
    }

    @Test
    fun `collision safe keys disambiguate a duplicate id without dropping a row`() {
        val identity = identityKey("1")
        val duplicated = listOf(
            profile(identity, "A", 1_000, 1_000),
            profile(identity, "B", 2_000, 2_000),
        )

        val keys = duplicated.collisionSafeKeys { it.profileId }.map { it.second }

        assertThat(keys).hasSize(2)
        assertThat(keys.toSet()).hasSize(2)
        // The first occurrence keeps the plain id, so normal rows are unaffected.
        assertThat(keys.first()).isEqualTo(duplicated.first().profileId)
    }
}
