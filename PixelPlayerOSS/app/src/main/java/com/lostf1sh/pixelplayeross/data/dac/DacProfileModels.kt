package com.lostf1sh.pixelplayeross.data.dac

import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
enum class DacIdentityScope {
    UNIT_SPECIFIC,
    MODEL_SCOPED,
}

@Serializable
data class DacProfile(
    val profileId: String,
    val deviceIdentityKey: String,
    val identityScope: DacIdentityScope,
    val displayName: String,
    val detectedManufacturer: String? = null,
    val detectedProduct: String? = null,
    val vendorId: Int,
    val productId: Int,
    val serialNumber: String? = null,
    val createdAtEpochMs: Long,
    val lastSeenEpochMs: Long,
)

/** A live USB attachment. [connectionPath] is deliberately never persisted as identity. */
data class UsbDacDescriptor(
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val product: String?,
    val serialNumber: String?,
    val connectionPath: String?,
) {
    val identityScope: DacIdentityScope
        get() = if (serialNumber.normalizedIdentityPart() != null) {
            DacIdentityScope.UNIT_SPECIFIC
        } else {
            DacIdentityScope.MODEL_SCOPED
        }

    val identityKey: String
        get() = DacIdentity.keyFor(this)

    val detectedName: String
        get() = detectedDisplayNameFor(vendorId, productId, manufacturer, product)

    /**
     * A short hardware-descriptive label for diagnostics: `2972:0126 SNOWSKY Melody`. Deliberately
     * built from the USB descriptor and never from the stored profile, so a hardware event can be
     * reported truthfully before any profile exists, and after one has been forgotten.
     *
     * The serial is appended only when the device actually exposes one, so the label never implies
     * a per-unit identity that the hardware did not provide.
     */
    fun usbIdLabel(): String {
        val id = "%04X:%04X".format(vendorId, productId)
        val name = detectedName
        return serialNumber.normalizedDisplayPart()
            ?.let { "$id $name [$it]" }
            ?: "$id $name"
    }
}

data class UsbDacCandidateFacts(
    val hasAudioStreamingInterface: Boolean,
    val hasIsochronousOutputEndpoint: Boolean,
)

object UsbDacClassifier {
    fun isEligible(facts: UsbDacCandidateFacts): Boolean =
        facts.hasAudioStreamingInterface && facts.hasIsochronousOutputEndpoint
}

object DacIdentity {
    fun keyFor(descriptor: UsbDacDescriptor): String {
        val serial = descriptor.serialNumber.normalizedIdentityPart()
        val canonical = if (serial != null) {
            "unit|${descriptor.vendorId}|${descriptor.productId}|$serial"
        } else {
            "model|${descriptor.vendorId}|${descriptor.productId}|" +
                "${descriptor.manufacturer.normalizedIdentityPart().orEmpty()}|" +
                descriptor.product.normalizedIdentityPart().orEmpty()
        }
        return "dac-${canonical.sha256().take(32)}"
    }

    fun profileIdFor(identityKey: String): String = "profile-${identityKey.removePrefix("dac-")}"
}

data class DacCatalogUpdate(
    val profiles: List<DacProfile>,
    val profile: DacProfile,
    val created: Boolean,
    val promotedFromModelScope: Boolean,
)

/** Pure catalog rules keep reconnect and serial-promotion behavior deterministic and testable. */
object DacProfileCatalog {
    fun register(
        profiles: List<DacProfile>,
        descriptor: UsbDacDescriptor,
        nowEpochMs: Long,
    ): DacCatalogUpdate {
        val exact = profiles.firstOrNull { it.deviceIdentityKey == descriptor.identityKey }
        if (exact != null) {
            val updated = exact.withDetection(descriptor, nowEpochMs)
            return DacCatalogUpdate(
                profiles = profiles.replace(exact.profileId, updated),
                profile = updated,
                created = false,
                promotedFromModelScope = false,
            )
        }

        val promotable = if (descriptor.identityScope == DacIdentityScope.UNIT_SPECIFIC) {
            profiles.firstOrNull { profile ->
                profile.identityScope == DacIdentityScope.MODEL_SCOPED &&
                    profile.vendorId == descriptor.vendorId &&
                    profile.productId == descriptor.productId &&
                    profile.detectedManufacturer.normalizedIdentityPart() ==
                        descriptor.manufacturer.normalizedIdentityPart() &&
                    profile.detectedProduct.normalizedIdentityPart() ==
                        descriptor.product.normalizedIdentityPart()
            }
        } else {
            null
        }
        if (promotable != null) {
            val promoted = promotable.copy(
                deviceIdentityKey = descriptor.identityKey,
                identityScope = DacIdentityScope.UNIT_SPECIFIC,
                detectedManufacturer = descriptor.manufacturer.normalizedDisplayPart(),
                detectedProduct = descriptor.product.normalizedDisplayPart(),
                serialNumber = descriptor.serialNumber.normalizedDisplayPart(),
                lastSeenEpochMs = nowEpochMs,
            )
            return DacCatalogUpdate(
                profiles = profiles.replace(promotable.profileId, promoted),
                profile = promoted,
                created = false,
                promotedFromModelScope = true,
            )
        }

        // A record that was promoted model -> unit keeps the profileId it was created with, so a
        // later attach that cannot read the serial derives that same id again while its
        // identityKey no longer matches anything stored. Without this lookup the branch below
        // creates a second record carrying the first one's profileId — two entries, one id, and
        // a duplicate-key crash in the list that renders them.
        //
        // A weaker sighting never demotes a stronger record: the unit-scoped identity and the
        // serial it carries survive, and this attach is recorded as a sighting. That mirrors the
        // promotion rule, which only ever moves model -> unit for the same reason.
        val derivedProfileId = DacIdentity.profileIdFor(descriptor.identityKey)
        val sameIdentityNarrowerScope = profiles.firstOrNull { it.profileId == derivedProfileId }
        if (sameIdentityNarrowerScope != null) {
            val refreshed = sameIdentityNarrowerScope.withSighting(nowEpochMs)
            return DacCatalogUpdate(
                profiles = profiles.replace(sameIdentityNarrowerScope.profileId, refreshed),
                profile = refreshed,
                created = false,
                promotedFromModelScope = false,
            )
        }

        val created = DacProfile(
            profileId = DacIdentity.profileIdFor(descriptor.identityKey),
            deviceIdentityKey = descriptor.identityKey,
            identityScope = descriptor.identityScope,
            displayName = descriptor.detectedName,
            detectedManufacturer = descriptor.manufacturer.normalizedDisplayPart(),
            detectedProduct = descriptor.product.normalizedDisplayPart(),
            vendorId = descriptor.vendorId,
            productId = descriptor.productId,
            serialNumber = descriptor.serialNumber.normalizedDisplayPart(),
            createdAtEpochMs = nowEpochMs,
            lastSeenEpochMs = nowEpochMs,
        )
        return DacCatalogUpdate(
            profiles = profiles + created,
            profile = created,
            created = true,
            promotedFromModelScope = false,
        )
    }

    fun rename(profiles: List<DacProfile>, profileId: String, requestedName: String): List<DacProfile> {
        val name = requestedName.trim().takeIf { it.isNotEmpty() } ?: return profiles
        return profiles.map { if (it.profileId == profileId) it.copy(displayName = name) else it }
    }

    fun forget(profiles: List<DacProfile>, profileId: String): List<DacProfile> =
        profiles.filterNot { it.profileId == profileId }

    fun findForDescriptor(profiles: List<DacProfile>, descriptor: UsbDacDescriptor): DacProfile? =
        profiles.firstOrNull { it.deviceIdentityKey == descriptor.identityKey }
            ?: profiles.firstOrNull {
                it.identityScope == DacIdentityScope.MODEL_SCOPED &&
                    it.vendorId == descriptor.vendorId &&
                    it.productId == descriptor.productId &&
                    it.detectedManufacturer.normalizedIdentityPart() ==
                        descriptor.manufacturer.normalizedIdentityPart() &&
                    it.detectedProduct.normalizedIdentityPart() == descriptor.product.normalizedIdentityPart()
            }

    /**
     * Records that the device was seen again, without learning anything new about it.
     *
     * Deliberately not [withDetection]: a model-scoped sighting carries no serial, and
     * `withDetection` would copy that absence over the serial a promoted record already holds.
     * A sighting that knows less must not erase what a previous sighting knew.
     */
    private fun DacProfile.withSighting(nowEpochMs: Long) = copy(lastSeenEpochMs = nowEpochMs)

    private fun DacProfile.withDetection(descriptor: UsbDacDescriptor, nowEpochMs: Long) = copy(
        detectedManufacturer = descriptor.manufacturer.normalizedDisplayPart(),
        detectedProduct = descriptor.product.normalizedDisplayPart(),
        serialNumber = descriptor.serialNumber.normalizedDisplayPart(),
        lastSeenEpochMs = nowEpochMs,
    )

    private fun List<DacProfile>.replace(profileId: String, replacement: DacProfile) =
        map { if (it.profileId == profileId) replacement else it }
}

internal fun String?.normalizedDisplayPart(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

internal fun String?.normalizedIdentityPart(): String? =
    normalizedDisplayPart()?.lowercase()

/** The name a device would have been given on first sight, before any user rename. */
internal fun detectedDisplayNameFor(
    vendorId: Int,
    productId: Int,
    manufacturer: String?,
    product: String?,
): String = product.normalizedDisplayPart()
    ?: manufacturer.normalizedDisplayPart()?.let { "$it USB DAC" }
    ?: "USB DAC %04X:%04X".format(vendorId, productId)

/**
 * One stable DAC identity is one profile.
 *
 * [DacProfile.profileId] is a pure function of [DacProfile.deviceIdentityKey], and every catalog
 * operation — [DacProfileCatalog.register], `rename`, `forget`, `findForDescriptor` — assumes at
 * most one record per identity. Two persisted records carrying the same identity are therefore
 * corruption, not a supported state: they produce the same `profileId`, and a `LazyColumn` keyed
 * by it throws `IllegalArgumentException: Key ... was already used` during the measure pass, which
 * takes the whole screen down.
 *
 * Canonicalising at the decode boundary means no caller can observe the duplicate in the first
 * place, so the UI key is safe by construction rather than by a guard.
 */
/**
 * Lazy-list keys for a list whose stable identity is [idOf].
 *
 * The id is the key for every normal row. Only a row whose id has already been seen gets a
 * disambiguated key, and only then — so this changes nothing on healthy data.
 *
 * This is a guard, not the fix: the repository canonicalises at its decode boundary, so a
 * duplicate should never reach a list. It exists because a duplicate key throws inside Compose's
 * measure pass and takes the whole screen down, which is a worse failure than a guarded key.
 */
internal fun <T> List<T>.collisionSafeKeys(idOf: (T) -> String): List<Pair<T, Any>> {
    val counts = HashMap<String, Int>()
    return map { item ->
        val id = idOf(item)
        val occurrence = counts.getOrDefault(id, 0)
        counts[id] = occurrence + 1
        item to if (occurrence == 0) id else "$id#$occurrence"
    }
}

object DacProfileCanonicalizer {

    /**
     * Collapses records that share a `profileId` into one, preserving the position of the first
     * occurrence. Returns the input unchanged when it is already canonical, which is the normal
     * case and allocates nothing.
     */
    fun canonicalize(profiles: List<DacProfile>): List<DacProfile> {
        if (profiles.size < 2) return profiles

        val groups = LinkedHashMap<String, MutableList<DacProfile>>()
        var sawDuplicate = false
        for (profile in profiles) {
            val bucket = groups.getOrPut(profile.profileId) { mutableListOf() }
            if (bucket.isNotEmpty()) sawDuplicate = true
            bucket += profile
        }
        if (!sawDuplicate) return profiles

        return groups.values.map { group ->
            if (group.size == 1) group.single() else merge(group)
        }
    }

    /**
     * Picks the record that wins and folds the group's user data into it.
     *
     * The winner is the most recently seen record: `lastSeen` is the only field that tracks the
     * device's current truth, and a serial promotion rewrites the identity in place, so the newest
     * record is the one holding the most accurate technical metadata. Ties fall back to the newest
     * `createdAt`, then the display name, purely so the result is deterministic when nothing else
     * distinguishes the copies.
     *
     * What is preserved from the rest of the group:
     *  - a renamed profile keeps its name. `displayName` is the only user-authored field on a
     *    profile, and a name that differs from what the device would have been called on first
     *    sight can only have come from the user, so it outranks the winner's.
     *  - `createdAt` is the oldest in the group, so the profile's real age survives.
     */
    private fun merge(group: List<DacProfile>): DacProfile {
        val winner = group.maxWithOrNull(
            compareBy<DacProfile> { it.lastSeenEpochMs }
                .thenBy { it.createdAtEpochMs }
                .thenBy { it.displayName }
        ) ?: group.first()

        val renamed = group.firstOrNull { profile ->
            profile.displayName.isNotBlank() && profile.displayName != profile.detectedDisplayName()
        }

        return winner.copy(
            displayName = renamed?.displayName ?: winner.displayName,
            createdAtEpochMs = group.minOf { it.createdAtEpochMs },
            lastSeenEpochMs = group.maxOf { it.lastSeenEpochMs },
        )
    }

    private fun DacProfile.detectedDisplayName(): String =
        detectedDisplayNameFor(vendorId, productId, detectedManufacturer, detectedProduct)
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
