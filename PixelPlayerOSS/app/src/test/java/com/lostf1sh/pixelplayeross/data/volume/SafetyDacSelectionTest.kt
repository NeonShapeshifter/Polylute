package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.dac.DacProfile
import com.lostf1sh.pixelplayeross.data.dac.DacProfileCatalog
import com.lostf1sh.pixelplayeross.data.dac.UsbDacDescriptor
import org.junit.jupiter.api.Test

/**
 * AUD-075: with two DACs attached (or one behind a hub carrying another audio device) there is no
 * single connected DAC, and Safety Max, the headphone association and the open guard all went
 * inactive. The DAC that actually opened Direct is known, and is the one being heard.
 */
class SafetyDacSelectionTest {

    private fun descriptor(serial: String, path: String) = UsbDacDescriptor(
        vendorId = 0x262A,
        productId = 0x1234,
        manufacturer = "Vendor",
        product = "USB DAC",
        serialNumber = serial,
        connectionPath = path,
    )

    private val a = descriptor("A", "/dev/bus/usb/001/002")
    private val b = descriptor("B", "/dev/bus/usb/001/003")

    private fun profileFor(d: UsbDacDescriptor, name: String) = DacProfile(
        profileId = "id-$name",
        deviceIdentityKey = d.identityKey,
        identityScope = d.identityScope,
        displayName = name,
        detectedManufacturer = d.manufacturer,
        detectedProduct = d.product,
        vendorId = d.vendorId,
        productId = d.productId,
        serialNumber = d.serialNumber,
        createdAtEpochMs = 1L,
        lastSeenEpochMs = 1L,
    )

    private val profiles = listOf(profileFor(a, "A"), profileFor(b, "B"))

    @Test
    fun `two DACs with Direct open on one resolves to that one`() {
        assertThat(DacProfileCatalog.findForDescriptor(profiles, b)?.displayName).isEqualTo("B")

        val selected = selectSafetyDac(profiles, listOf(a, b), openedDevicePath = b.connectionPath)

        assertThat(selected?.displayName).isEqualTo("B")
    }

    @Test
    fun `two DACs without a Direct stream stay ambiguous`() {
        assertThat(selectSafetyDac(profiles, listOf(a, b), openedDevicePath = null)).isNull()
    }

    @Test
    fun `a single DAC resolves as before`() {
        assertThat(selectSafetyDac(profiles, listOf(a), openedDevicePath = null)?.displayName).isEqualTo("A")
    }
}
