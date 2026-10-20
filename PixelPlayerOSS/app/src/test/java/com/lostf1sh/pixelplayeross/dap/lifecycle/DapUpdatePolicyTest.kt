package com.lostf1sh.pixelplayeross.dap.lifecycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DapUpdatePolicyTest {
    private val installed = DapPackageIdentity("app.id", 10, "ABC123")

    @Test
    fun `matching signature and higher version is a compatible upgrade`() {
        assertEquals(
            DapUpdateCompatibility.COMPATIBLE_UPGRADE,
            DapUpdatePolicy.compatibility(installed, installed.copy(versionCode = 11)),
        )
    }

    @Test
    fun `same version replacement is development-compatible`() {
        assertEquals(
            DapUpdateCompatibility.COMPATIBLE_SAME_VERSION,
            DapUpdatePolicy.compatibility(installed, installed),
        )
    }

    @Test
    fun `package and signing mismatches are rejected`() {
        assertEquals(
            DapUpdateCompatibility.PACKAGE_MISMATCH,
            DapUpdatePolicy.compatibility(installed, installed.copy(packageName = "other.id")),
        )
        assertEquals(
            DapUpdateCompatibility.SIGNATURE_MISMATCH,
            DapUpdatePolicy.compatibility(installed, installed.copy(signingCertificateSha256 = "DEF456")),
        )
    }

    @Test
    fun `downgrade is not mislabeled as rollback`() {
        assertEquals(
            DapUpdateCompatibility.DOWNGRADE_UNSUPPORTED,
            DapUpdatePolicy.compatibility(installed, installed.copy(versionCode = 9)),
        )
    }

    @Test
    fun `rejected install leaves identical previous package authoritative`() {
        assertEquals(
            DapUpdateOutcome.PREVIOUS_INSTALL_REMAINS_AUTHORITATIVE,
            DapUpdatePolicy.classifyOutcome(
                DapInstallObservation.COMMAND_REJECTED,
                installed,
                installed.copy(versionCode = 11),
                installed,
            ),
        )
    }

    @Test
    fun `unknown result always requires platform reinspection`() {
        assertEquals(
            DapUpdateOutcome.REINSPECTION_REQUIRED,
            DapUpdatePolicy.classifyOutcome(
                DapInstallObservation.RESULT_UNKNOWN,
                installed,
                installed.copy(versionCode = 11),
                installed.copy(versionCode = 11),
            ),
        )
    }

    @Test
    fun `successful command is not enough when installed identity is still old`() {
        assertEquals(
            DapUpdateOutcome.REINSPECTION_REQUIRED,
            DapUpdatePolicy.classifyOutcome(
                DapInstallObservation.COMMAND_SUCCEEDED,
                installed,
                installed.copy(versionCode = 11),
                installed,
            ),
        )
    }

    @Test
    fun `successful command plus candidate identity is verified`() {
        val candidate = installed.copy(versionCode = 11)
        assertEquals(
            DapUpdateOutcome.INSTALLED_AND_VERIFIED,
            DapUpdatePolicy.classifyOutcome(
                DapInstallObservation.COMMAND_SUCCEEDED,
                installed,
                candidate,
                candidate,
            ),
        )
    }
}
