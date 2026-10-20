package com.lostf1sh.pixelplayeross.data.dac

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lostf1sh.pixelplayeross.data.diagnostics.HardwareEventLog
import com.lostf1sh.pixelplayeross.data.diagnostics.HardwareEventType
import com.lostf1sh.pixelplayeross.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class DacProfilesPayload(
    val schemaVersion: Int = 1,
    val profiles: List<DacProfile> = emptyList(),
)

internal object DacProfilesCodec {
    fun decode(json: Json, raw: String?): List<DacProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<DacProfilesPayload>(raw).profiles }
            .getOrDefault(emptyList())
            .filter { it.profileId.isNotBlank() && it.deviceIdentityKey.isNotBlank() }
            // Decoding is the boundary, so canonicalising here covers every reader and every
            // writer at once — the `profiles` flow, and the read-modify-write inside rename,
            // forget and register, which each decode before re-encoding.
            .let(DacProfileCanonicalizer::canonicalize)
    }

    fun encode(json: Json, profiles: List<DacProfile>): String =
        json.encodeToString(DacProfilesPayload(profiles = profiles))
}

@Singleton
class DacProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    @AppScope private val appScope: CoroutineScope,
) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val profilesKey = stringPreferencesKey("usb_dac_profiles_v1")
    private val _connected = MutableStateFlow<List<UsbDacDescriptor>>(emptyList())
    val connected: StateFlow<List<UsbDacDescriptor>> = _connected

    val profiles: StateFlow<List<DacProfile>> = dataStore.data
        .map { preferences -> DacProfilesCodec.decode(json, preferences[profilesKey]) }
        .distinctUntilChanged()
        .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    private var started = false
    private val suppressedUntilDetach = mutableSetOf<String>()
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED
            ) {
                reconcile()
            }
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(usbReceiver, filter)
        }
        reconcile()
        appScope.launch { repairPersistedProfiles() }
    }

    /**
     * Rewrites the stored list in canonical form when it is not already canonical.
     *
     * Decoding already hides duplicates from every reader, so the app is correct without this —
     * but the corrupt records would survive on disk and be re-canonicalised on every launch. This
     * is the self-healing half, and it is the only reason the repair is durable rather than
     * cosmetic. It runs once from [start] and is a no-op on healthy data.
     */
    private suspend fun repairPersistedProfiles() {
        dataStore.edit { preferences ->
            val raw = preferences[profilesKey] ?: return@edit
            val canonical = DacProfilesCodec.encode(json, DacProfilesCodec.decode(json, raw))
            if (canonical != raw) {
                preferences[profilesKey] = canonical
            }
        }
    }

    fun reconcile() {
        val descriptors = usbManager?.deviceList?.values.orEmpty()
            .mapNotNull(::descriptorFor)
            .sortedWith(compareBy({ it.vendorId }, { it.productId }, { it.identityKey }))
        val attachedKeys = descriptors.mapTo(mutableSetOf()) { it.identityKey }
        synchronized(suppressedUntilDetach) { suppressedUntilDetach.retainAll(attachedKeys) }
        recordAttachmentChanges(previous = _connected.value, current = descriptors)
        _connected.value = descriptors
        descriptors.forEach { descriptor ->
            val suppressed = synchronized(suppressedUntilDetach) {
                descriptor.identityKey in suppressedUntilDetach
            }
            if (!suppressed) appScope.launch { register(descriptor) }
        }
    }

    /**
     * Records arrivals and departures into the bounded hardware log, described by USB ID rather
     * than by profile, so the log stays truthful even if the profile store is empty or corrupt.
     * A device that merely changed its descriptor is neither an arrival nor a departure.
     */
    private fun recordAttachmentChanges(
        previous: List<UsbDacDescriptor>,
        current: List<UsbDacDescriptor>,
    ) {
        val before = previous.mapTo(mutableSetOf()) { it.identityKey }
        val after = current.mapTo(mutableSetOf()) { it.identityKey }
        current.filter { it.identityKey !in before }.forEach { descriptor ->
            HardwareEventLog.record(HardwareEventType.USB_ATTACHED, descriptor.usbIdLabel())
        }
        previous.filter { it.identityKey !in after }.forEach { descriptor ->
            HardwareEventLog.record(HardwareEventType.USB_DETACHED, descriptor.usbIdLabel())
        }
    }

    suspend fun rename(profileId: String, displayName: String) {
        dataStore.edit { preferences ->
            val updated = DacProfileCatalog.rename(
                DacProfilesCodec.decode(json, preferences[profilesKey]),
                profileId,
                displayName,
            )
            preferences[profilesKey] = DacProfilesCodec.encode(json, updated)
        }
    }

    suspend fun forget(profileId: String) {
        val profile = profiles.value.firstOrNull { it.profileId == profileId }
        if (profile != null && connected.value.any { descriptor ->
                DacProfileCatalog.findForDescriptor(listOf(profile), descriptor) != null
            }
        ) {
            synchronized(suppressedUntilDetach) { suppressedUntilDetach += profile.deviceIdentityKey }
        }
        dataStore.edit { preferences ->
            preferences[profilesKey] = DacProfilesCodec.encode(
                json,
                DacProfileCatalog.forget(
                    DacProfilesCodec.decode(json, preferences[profilesKey]),
                    profileId,
                ),
            )
        }
    }

    private suspend fun register(descriptor: UsbDacDescriptor) {
        dataStore.edit { preferences ->
            val existing = DacProfilesCodec.decode(json, preferences[profilesKey])
            val updated = DacProfileCatalog.register(existing, descriptor, System.currentTimeMillis())
            preferences[profilesKey] = DacProfilesCodec.encode(json, updated.profiles)
        }
    }

    private fun descriptorFor(device: UsbDevice): UsbDacDescriptor? {
        var hasStreaming = false
        var hasIsochronousOut = false
        for (interfaceIndex in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(interfaceIndex)
            if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_AUDIO ||
                usbInterface.interfaceSubclass != USB_SUBCLASS_AUDIOSTREAMING
            ) continue
            hasStreaming = true
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (endpoint.direction == UsbConstants.USB_DIR_OUT &&
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                ) {
                    hasIsochronousOut = true
                }
            }
        }
        if (!UsbDacClassifier.isEligible(UsbDacCandidateFacts(hasStreaming, hasIsochronousOut))) {
            return null
        }
        val hasPermission = usbManager?.hasPermission(device) == true
        return UsbDacDescriptor(
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturer = runCatching { device.manufacturerName }.getOrNull(),
            product = runCatching { device.productName }.getOrNull(),
            serialNumber = if (hasPermission) runCatching { device.serialNumber }.getOrNull() else null,
            connectionPath = device.deviceName,
        )
    }

    companion object {
        private const val USB_SUBCLASS_AUDIOSTREAMING = 2
    }
}
