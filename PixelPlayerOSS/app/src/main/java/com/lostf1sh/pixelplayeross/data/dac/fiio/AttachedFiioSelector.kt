package com.lostf1sh.pixelplayeross.data.dac.fiio

import com.lostf1sh.pixelplayeross.data.dac.DacProfile
import com.lostf1sh.pixelplayeross.data.dac.DacProfileCatalog
import com.lostf1sh.pixelplayeross.data.dac.UsbDacDescriptor

/**
 * B30-P1: which attached DAC the EQ is about.
 *
 * The one Direct opened, if it is a FiiO/Snowsky the table knows; else the only such DAC attached. With two of them and no way to
 * tell which one the stream goes to, nothing is chosen: a read of the wrong DAC would label the wrong output. The same rule Safety
 * uses for its DAC (AUD-075).
 */
object AttachedFiioSelector {
    fun select(profiles: List<DacProfile>, connected: List<UsbDacDescriptor>, openedDevicePath: String?): AttachedFiio? {
        val askable = connected.mapNotNull { descriptor ->
            FiioHidProfiles.match(descriptor.vendorId, descriptor.productId, descriptor.product)?.let { descriptor to it }
        }
        if (askable.isEmpty()) return null
        val (descriptor, profile) = openedDevicePath?.let { path -> askable.firstOrNull { it.first.connectionPath == path } }
            ?: askable.singleOrNull()
            ?: return null
        val dac = DacProfileCatalog.findForDescriptor(profiles, descriptor)
        return AttachedFiio(
            attachKey = descriptor.connectionPath ?: descriptor.identityKey,
            identityKey = descriptor.identityKey,
            dacProfileId = dac?.profileId,
            displayName = dac?.displayName ?: descriptor.detectedName,
            connectionPath = descriptor.connectionPath,
            profile = profile,
        )
    }
}
