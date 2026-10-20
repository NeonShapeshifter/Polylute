package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.volume.PristineModeController
import com.lostf1sh.pixelplayeross.data.volume.PristineModePolicy
import com.lostf1sh.pixelplayeross.data.volume.PristineToggleResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.dap.admin.DapDeviceOwnerController
import com.lostf1sh.pixelplayeross.dap.compat.CapabilityStatus
import com.lostf1sh.pixelplayeross.dap.compat.DeviceCapability
import com.lostf1sh.pixelplayeross.dap.compat.DeviceCapabilityRepository
import com.lostf1sh.pixelplayeross.dap.compat.DeviceCompatibilitySnapshot
import com.decent.usbaudio.media3.UsbDirectSinkState
import com.lostf1sh.pixelplayeross.data.preferences.DAP_BRIGHTNESS_FOLLOW_SYSTEM
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM
import com.lostf1sh.pixelplayeross.data.preferences.DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteReason
import com.lostf1sh.pixelplayeross.data.volume.SafetyVolumeController
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import kotlin.math.roundToInt
import com.lostf1sh.pixelplayeross.utils.StorageInfo
import com.lostf1sh.pixelplayeross.utils.StorageUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class DapAudioRouteKind {
    USB_DIRECT,
    BLUETOOTH,
    USB_ANDROID,
    WIRED,
    SPEAKER,
    ANDROID_AUDIO,
}

enum class DapBatteryPlug {
    NONE,
    AC,
    USB,
    WIRELESS,
    OTHER,
}

enum class DapManagedRadio {
    WIFI,
    BLUETOOTH,
}

data class DapStorageState(
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val mountedSources: List<StorageInfo> = emptyList(),
)

data class DeviceControlUiState(
    val brightnessPercent: Int = 50,
    val brightnessFollowsSystem: Boolean = true,
    val screenTimeoutMode: Long = DAP_SCREEN_TIMEOUT_FOLLOW_SYSTEM,
    val effectiveScreenTimeoutMillis: Long = 60_000L,
    val canWriteSystemSettings: Boolean = false,
    val mediaVolume: Int = 0,
    val mediaVolumeMax: Int = 15,
    val mediaVolumeDisplayPercent: Int = 0,
    val safetyVolumeActive: Boolean = false,
    val safetyVolumeOverLimit: Boolean = false,
    /** The volume slider drives the DAC's own hardware volume (USB Direct with a Feature Unit). */
    val volumeIsDacHardware: Boolean = false,
    val volumeControllable: Boolean = true,
    val dacVolumeDecibels: Float? = null,
    val digitalGainPercent: Int = 100,
    /**
     * B21: what is applied right now (the Pure Direct switch, the route and why, the DAC, Resonance). The one
     * [AudioStatus] every screen reads; the properties below keep their old names and answer from it.
     */
    val audio: AudioStatus = AudioStatus(),
    /** F-02: a rise (dB) enabling the lock would cause, awaiting the listener's confirmation. */
    val pristineRiseToConfirm: Float? = null,
    val pristineRiseReason: PristineModePolicy.RiseReason? = null,
    val audioRouteKind: DapAudioRouteKind = DapAudioRouteKind.ANDROID_AUDIO,
    val audioOutputName: String? = null,
    val usbDirectState: UsbDirectSinkState = UsbDirectSinkState.ANDROID_AUDIO,
    val usbDirectRate: Int? = null,
    val usbDirectBitDepth: Int? = null,
    val usbDirectError: String? = null,
    val batteryPercent: Int = 0,
    val batteryCharging: Boolean = false,
    val batteryPlug: DapBatteryPlug = DapBatteryPlug.NONE,
    val wifiRadioEnabled: Boolean = false,
    val wifiConnected: Boolean = false,
    val wifiName: String? = null,
    val bluetoothEnabled: Boolean = false,
    val bluetoothAudioDevice: String? = null,
    val storage: DapStorageState = DapStorageState(),
    val isDeviceOwner: Boolean = false,
    val isDeviceAdminActive: Boolean = false,
    val isPixelPlayerHome: Boolean = false,
    val isLockTaskPermitted: Boolean = false,
    val capabilities: DeviceCompatibilitySnapshot = DeviceCompatibilitySnapshot.empty(),
) {
    val isUsbDirectActive: Boolean
        get() = usbDirectState == UsbDirectSinkState.USB_DIRECT_ACTIVE

    /** F-02: the Pure Direct lock (the preference). */
    val pristineLocked: Boolean
        get() = audio.purityPreference

    /** The route's own answer (`AudioRouteState`), apart from the sink's: Direct is carrying the stream. */
    val routeDirectActive: Boolean
        get() = audio.directActive

    /** USB Direct in PROCESSED: the app's digital level is a second, separate volume. */
    val directProcessed: Boolean
        get() = audio.resonance

    /** Why the route is where it is: said next to the lock's status when it is not on Direct. */
    val routeReason: AudioRouteReason
        get() = audio.route.reason

    /**
     * B16-G4: the lock is a preference, and it is *in force* only while a USB DAC is connected. Without one the controls it
     * would freeze (crossfade, speed) are as available as with the lock off, and the preference waits for the DAC.
     */
    val pristineInForce: Boolean
        get() = audio.purityInForce

    /** B16-G5: a USB DAC is connected, so crossfade and speed (which USB Direct cannot carry yet) cost the Direct route. */
    val usbDacConnected: Boolean
        get() = audio.dacConnected

    /**
     * What the Pure Direct label may claim (B15-F2): the lock is a preference, and the label used to follow the sink's
     * state alone. It follows the route as well now: ACTIVE only while the route really is Direct (and the sink says so)
     * and the signal is PRISTINE.
     */
    val pristineStatus: PristineModePolicy.Status
        get() = audio.purity

    /**
     * FIX-2: what the list should say about the stored choice not matching the system's real value.
     * The re-apply itself happens elsewhere (`ScreenTimeoutDriftGuard`, on resume and at boot); this
     * is what the screen shows in the meantime, and always on a phone.
     */
    val screenTimeoutDrift: ScreenTimeoutPolicy.Drift
        get() = ScreenTimeoutPolicy.drift(
            storedMode = screenTimeoutMode,
            systemTimeoutMs = effectiveScreenTimeoutMillis,
            dapMode = isDeviceOwner && isPixelPlayerHome,
            canWrite = canWriteSystemSettings,
        )

    /** F-05: the system timeout is "never", with or without the app's own mode. */
    val screenTimeoutOffer: ScreenTimeoutPolicy.Offer
        get() = ScreenTimeoutPolicy.offer(effectiveScreenTimeoutMillis, dapMode = isDeviceOwner)

    /** AUD-120: the digital level alone keeps Direct off PRISTINE. */
    val digitalBlocksPristine: Boolean
        get() = DigitalGainSlider.pristineBlockedByDigital(isUsbDirectActive, digitalGainPercent, pristineLocked)

    val mediaVolumePercent: Int
        get() = mediaVolumeDisplayPercent
}

sealed interface DeviceControlEvent {
    data object RequestWriteSettingsAccess : DeviceControlEvent
    data object RequestBluetoothEnableConfirmation : DeviceControlEvent
    data class ManagedRadioRejected(val radio: DapManagedRadio) : DeviceControlEvent
}

@HiltViewModel
class DeviceControlViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val dualPlayerEngine: DualPlayerEngine,
    private val connectivityStateHolder: ConnectivityStateHolder,
    private val capabilityRepository: DeviceCapabilityRepository,
    private val safetyVolumeController: SafetyVolumeController,
    private val pristineModeController: PristineModeController,
    audioStatusSource: AudioStatusSource,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val deviceOwnerController = DapDeviceOwnerController(context)

    private val _uiState = MutableStateFlow(DeviceControlUiState())
    val uiState: StateFlow<DeviceControlUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<DeviceControlEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var pendingScreenTimeoutMillis: Long? = null
    private var brightnessPersistJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            updateBattery(intent)
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshAudioState()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshAudioState()
    }

    init {
        connectivityStateHolder.initialize()
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let(::updateBattery)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        viewModelScope.launch {
            userPreferencesRepository.dapBrightnessPercentFlow.collect { persisted ->
                val followsSystem = persisted == DAP_BRIGHTNESS_FOLLOW_SYSTEM
                _uiState.update {
                    it.copy(
                        brightnessPercent = if (followsSystem) readSystemBrightnessPercent() else persisted,
                        brightnessFollowsSystem = followsSystem,
                    )
                }
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.dapScreenTimeoutModeFlow.collect { mode ->
                _uiState.update {
                    it.copy(
                        screenTimeoutMode = mode,
                        effectiveScreenTimeoutMillis = readSystemScreenTimeout(),
                        canWriteSystemSettings = Settings.System.canWrite(context),
                    )
                }
            }
        }
        viewModelScope.launch {
            connectivityStateHolder.isWifiRadioOn.collect { enabled ->
                _uiState.update { it.copy(wifiRadioEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            connectivityStateHolder.isWifiEnabled.collect { connected ->
                _uiState.update { it.copy(wifiConnected = connected) }
            }
        }
        viewModelScope.launch {
            connectivityStateHolder.wifiName.collect { name ->
                _uiState.update { it.copy(wifiName = name) }
            }
        }
        viewModelScope.launch {
            connectivityStateHolder.isBluetoothEnabled.collect { enabled ->
                _uiState.update { it.copy(bluetoothEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            connectivityStateHolder.bluetoothName.collect { name ->
                _uiState.update { it.copy(bluetoothAudioDevice = name) }
                refreshAudioState()
            }
        }
        viewModelScope.launch {
            combine(
                dualPlayerEngine.usbAudioDiagnostics,
                dualPlayerEngine.audioRouteState,
            ) { diagnostics, route -> diagnostics to route }.collect { (diagnostics, route) ->
                _uiState.update {
                    it.copy(
                        usbDirectState = diagnostics.state,
                        usbDirectRate = diagnostics.sourceSampleRate,
                        usbDirectBitDepth = diagnostics.sourceBitDepth,
                        usbDirectError = diagnostics.errorReason,
                    )
                }
                refreshAudioState(route.actualBackend)
            }
        }
        viewModelScope.launch {
            audioStatusSource.status.collect { status ->
                _uiState.update { it.copy(audio = status) }
            }
        }
        viewModelScope.launch {
            dualPlayerEngine.gainModel.collect { gain ->
                _uiState.update { it.copy(digitalGainPercent = (gain.userDigitalGain * 100f).roundToInt()) }
            }
        }
        viewModelScope.launch {
            safetyVolumeController.state.collect { volume ->
                _uiState.update {
                    it.copy(
                        mediaVolume = volume.backend.rawLevel,
                        mediaVolumeMax = volume.backend.rawMax.coerceAtLeast(1),
                        mediaVolumeDisplayPercent = volume.displayPercent,
                        safetyVolumeActive = volume.protectionActive,
                        safetyVolumeOverLimit = volume.displayPercent > 100,
                        volumeIsDacHardware = volume.backend.kind == SafetyVolumeBackendKind.USB_HARDWARE_VOLUME,
                        volumeControllable = volume.backend.controllable,
                        dacVolumeDecibels = volume.hardwareDecibels,
                    )
                }
            }
        }

        refresh()
    }

    fun refresh() {
        connectivityStateHolder.reconcileExternalState()
        dualPlayerEngine.refreshUsbAudioDiagnostics()
        refreshVolume()
        refreshAudioState()
        refreshStorage()
        refreshDeviceOwnerState()
        _uiState.update {
            it.copy(
                canWriteSystemSettings = Settings.System.canWrite(context),
                effectiveScreenTimeoutMillis = readSystemScreenTimeout(),
            )
        }
    }

    fun setWifiEnabledByOwner(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (
                capabilityRepository.snapshot()[DeviceCapability.WIFI_DIRECT_TOGGLE].status !=
                CapabilityStatus.SUPPORTED
            ) {
                eventChannel.trySend(DeviceControlEvent.ManagedRadioRejected(DapManagedRadio.WIFI))
                return@launch
            }
            val accepted = runCatching {
                deviceOwnerController.setWifiEnabled(enabled)
            }.getOrDefault(false)
            if (!accepted) eventChannel.trySend(DeviceControlEvent.ManagedRadioRejected(DapManagedRadio.WIFI))
            delay(1_000L)
            refreshDeviceOwnerState()
        }
    }

    fun setBluetoothEnabledByOwner(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val directToggle = capabilityRepository.snapshot()[
                DeviceCapability.BLUETOOTH_DIRECT_TOGGLE
            ].status
            if (directToggle != CapabilityStatus.SUPPORTED) {
                eventChannel.trySend(
                    if (enabled) DeviceControlEvent.RequestBluetoothEnableConfirmation
                    else DeviceControlEvent.ManagedRadioRejected(DapManagedRadio.BLUETOOTH)
                )
                return@launch
            }
            val accepted = runCatching {
                deviceOwnerController.setBluetoothEnabled(enabled)
            }.getOrDefault(false)
            if (!accepted) {
                eventChannel.trySend(
                    if (enabled) DeviceControlEvent.RequestBluetoothEnableConfirmation
                    else DeviceControlEvent.ManagedRadioRejected(DapManagedRadio.BLUETOOTH)
                )
            }
            delay(1_000L)
            refreshDeviceOwnerState()
        }
    }

    fun setBrightnessPercent(percent: Int) {
        val sanitized = DapDeviceControlPolicy.sanitizeBrightnessPercent(percent)
        _uiState.update { it.copy(brightnessPercent = sanitized, brightnessFollowsSystem = false) }
        brightnessPersistJob?.cancel()
        brightnessPersistJob = viewModelScope.launch {
            delay(120L)
            userPreferencesRepository.setDapBrightnessPercent(sanitized)
        }
    }

    fun setMediaVolume(volume: Int) {
        if (!DapDeviceControlPolicy.canAdjustVolume(_uiState.value.usbDirectState, _uiState.value.volumeControllable)) return
        safetyVolumeController.setRawLevel(volume)
    }

    fun setDigitalGainPercent(percent: Int) {
        dualPlayerEngine.setUserDigitalGain(percent.coerceIn(0, 100) / 100f)
    }

    /**
     * F-02. Runs off the main thread: entering reads back the DAC's volume (a USB control transfer)
     * and leaving waits for the processed mode to be back before raising it.
     */
    fun setPristineMode(enabled: Boolean, confirmRise: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = if (enabled) pristineModeController.enable(confirmRise) else pristineModeController.disable()
            val ask = result as? PristineToggleResult.NeedsConfirmation
            _uiState.update { it.copy(pristineRiseToConfirm = ask?.riseDb, pristineRiseReason = ask?.reason) }
        }
    }

    fun confirmPristineRise() = setPristineMode(enabled = true, confirmRise = true)

    fun dismissPristineRise() {
        _uiState.update { it.copy(pristineRiseToConfirm = null, pristineRiseReason = null) }
    }

    fun selectScreenTimeout(mode: Long) {
        if (mode == DAP_SCREEN_TIMEOUT_NEVER_WHILE_PLAYING) {
            pendingScreenTimeoutMillis = null
            viewModelScope.launch { userPreferencesRepository.setDapScreenTimeoutMode(mode) }
            return
        }

        if (!Settings.System.canWrite(context)) {
            pendingScreenTimeoutMillis = mode
            eventChannel.trySend(DeviceControlEvent.RequestWriteSettingsAccess)
            return
        }

        applySystemScreenTimeout(mode)
    }

    fun onWriteSettingsAccessResult() {
        val canWrite = Settings.System.canWrite(context)
        _uiState.update { it.copy(canWriteSystemSettings = canWrite) }
        if (!canWrite) return
        pendingScreenTimeoutMillis?.let { mode ->
            pendingScreenTimeoutMillis = null
            applySystemScreenTimeout(mode)
        }
    }

    private fun applySystemScreenTimeout(mode: Long) {
        // F-05: only a preset is ever written. "Never" (Int.MAX_VALUE, 0, -1, anything an hour or
        // more) is not something this app puts in the system setting.
        if (!ScreenTimeoutPolicy.isAppWritable(mode)) return
        val applied = SystemScreenTimeout.write(context, mode)
        if (!applied) return
        _uiState.update {
            it.copy(
                screenTimeoutMode = mode,
                effectiveScreenTimeoutMillis = mode,
                canWriteSystemSettings = true,
            )
        }
        viewModelScope.launch { userPreferencesRepository.setDapScreenTimeoutMode(mode) }
    }

    private fun refreshVolume() {
        safetyVolumeController.reconcile()
    }

    private fun refreshAudioState(
        actualBackend: AudioBackend = dualPlayerEngine.audioRouteState.value.actualBackend,
    ) {
        val direct = dualPlayerEngine.usbAudioDiagnostics.value
        if (actualBackend == AudioBackend.USB_DIRECT) {
            _uiState.update {
                it.copy(
                    audioRouteKind = DapAudioRouteKind.USB_DIRECT,
                    audioOutputName = direct.deviceName,
                )
            }
            return
        }
        val bluetoothName = connectivityStateHolder.bluetoothName.value
        if (!bluetoothName.isNullOrBlank()) {
            _uiState.update {
                it.copy(audioRouteKind = DapAudioRouteKind.BLUETOOTH, audioOutputName = bluetoothName)
            }
            return
        }

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val selected = outputs.firstOrNull { it.type in USB_AUDIO_DEVICE_TYPES }
            ?: outputs.firstOrNull { it.type in WIRED_AUDIO_DEVICE_TYPES }
            ?: outputs.firstOrNull { it.type in BUILT_IN_SPEAKER_TYPES }

        val kind = when {
            selected == null -> DapAudioRouteKind.ANDROID_AUDIO
            selected.type in USB_AUDIO_DEVICE_TYPES -> DapAudioRouteKind.USB_ANDROID
            selected.type in WIRED_AUDIO_DEVICE_TYPES -> DapAudioRouteKind.WIRED
            selected.type in BUILT_IN_SPEAKER_TYPES -> DapAudioRouteKind.SPEAKER
            else -> DapAudioRouteKind.ANDROID_AUDIO
        }
        _uiState.update {
            it.copy(
                audioRouteKind = kind,
                audioOutputName = selected?.productName?.toString()?.takeIf(String::isNotBlank),
            )
        }
    }

    private fun refreshStorage() {
        val root = Environment.getDataDirectory()
        val stat = StatFs(root.absolutePath)
        _uiState.update {
            it.copy(
                storage = DapStorageState(
                    totalBytes = stat.totalBytes,
                    freeBytes = stat.availableBytes,
                    mountedSources = StorageUtils.getAvailableStorages(context),
                )
            )
        }
    }

    private fun refreshDeviceOwnerState() {
        val state = deviceOwnerController.currentState()
        val capabilities = capabilityRepository.snapshot()
        _uiState.update {
            it.copy(
                isDeviceOwner = state.isDeviceOwner,
                isDeviceAdminActive = state.isAdminActive,
                isPixelPlayerHome = state.isPixelPlayerHome,
                isLockTaskPermitted = state.isLockTaskPermitted,
                capabilities = capabilities,
            )
        }
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        _uiState.update {
            it.copy(
                batteryPercent = (level * 100 / scale).coerceIn(0, 100),
                batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL,
                batteryPlug = when (plugged) {
                    0 -> DapBatteryPlug.NONE
                    BatteryManager.BATTERY_PLUGGED_AC -> DapBatteryPlug.AC
                    BatteryManager.BATTERY_PLUGGED_USB -> DapBatteryPlug.USB
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> DapBatteryPlug.WIRELESS
                    else -> DapBatteryPlug.OTHER
                },
            )
        }
    }

    private fun readSystemBrightnessPercent(): Int {
        val raw = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (raw * 100 / 255).coerceIn(5, 100)
    }

    private fun readSystemScreenTimeout(): Long = SystemScreenTimeout.read(context) ?: 60_000L

    override fun onCleared() {
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        super.onCleared()
    }

    companion object {
        private val USB_AUDIO_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
        private val WIRED_AUDIO_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
        )
        private val BUILT_IN_SPEAKER_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        )
    }
}
