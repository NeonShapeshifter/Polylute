package com.lostf1sh.pixelplayeross.dap.lifecycle

data class DapPackageIdentity(
    val packageName: String,
    val versionCode: Long,
    val signingCertificateSha256: String,
)

enum class DapUpdateCompatibility {
    COMPATIBLE_SAME_VERSION,
    COMPATIBLE_UPGRADE,
    PACKAGE_MISMATCH,
    SIGNATURE_MISMATCH,
    DOWNGRADE_UNSUPPORTED,
}

enum class DapInstallObservation {
    COMMAND_SUCCEEDED,
    COMMAND_REJECTED,
    RESULT_UNKNOWN,
}

enum class DapUpdateOutcome {
    INSTALLED_AND_VERIFIED,
    INSTALL_DID_NOT_OCCUR,
    PREVIOUS_INSTALL_REMAINS_AUTHORITATIVE,
    REINSPECTION_REQUIRED,
}

/** Pure artifact checks shared by future Maintenance update UI and development tooling. */
object DapUpdatePolicy {
    fun compatibility(
        installed: DapPackageIdentity,
        candidate: DapPackageIdentity,
    ): DapUpdateCompatibility = when {
        installed.packageName != candidate.packageName -> DapUpdateCompatibility.PACKAGE_MISMATCH
        !installed.signingCertificateSha256.equals(
            candidate.signingCertificateSha256,
            ignoreCase = true,
        ) -> DapUpdateCompatibility.SIGNATURE_MISMATCH
        candidate.versionCode < installed.versionCode -> DapUpdateCompatibility.DOWNGRADE_UNSUPPORTED
        candidate.versionCode == installed.versionCode ->
            DapUpdateCompatibility.COMPATIBLE_SAME_VERSION
        else -> DapUpdateCompatibility.COMPATIBLE_UPGRADE
    }

    fun classifyOutcome(
        observation: DapInstallObservation,
        before: DapPackageIdentity,
        candidate: DapPackageIdentity,
        after: DapPackageIdentity?,
    ): DapUpdateOutcome {
        if (observation == DapInstallObservation.RESULT_UNKNOWN || after == null) {
            return DapUpdateOutcome.REINSPECTION_REQUIRED
        }
        if (observation == DapInstallObservation.COMMAND_REJECTED) {
            return if (after == before) {
                DapUpdateOutcome.PREVIOUS_INSTALL_REMAINS_AUTHORITATIVE
            } else {
                DapUpdateOutcome.REINSPECTION_REQUIRED
            }
        }
        return if (
            after.packageName == candidate.packageName &&
            after.versionCode == candidate.versionCode &&
            after.signingCertificateSha256.equals(
                candidate.signingCertificateSha256,
                ignoreCase = true,
            )
        ) {
            DapUpdateOutcome.INSTALLED_AND_VERIFIED
        } else {
            DapUpdateOutcome.REINSPECTION_REQUIRED
        }
    }
}
