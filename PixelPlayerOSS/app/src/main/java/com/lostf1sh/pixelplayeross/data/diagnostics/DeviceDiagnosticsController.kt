package com.lostf1sh.pixelplayeross.data.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.lostf1sh.pixelplayeross.dap.admin.DapContainmentStore
import com.lostf1sh.pixelplayeross.dap.admin.DapMaintenanceSession
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Everything the hardware diagnostic surface renders, assembled at one point in time. */
data class DeviceDiagnosticSnapshot(
    val device: HardwareDiagnosticGroup? = null,
    val usb: HardwareDiagnosticGroup? = null,
    val audio: HardwareDiagnosticGroup? = null,
    val power: HardwareDiagnosticGroup? = null,
    val appliance: HardwareDiagnosticGroup? = null,
    val events: List<HardwareEvent> = emptyList(),
    val droppedEventCount: Int = 0,
) {
    val groups: List<HardwareDiagnosticGroup>
        get() = listOfNotNull(device, usb, audio, power, appliance)
}

/**
 * Collects the platform facts the hardware diagnostic reports, and nothing else.
 *
 * Every value here is read from the platform or from the component that already owns it. Nothing
 * is derived from a user setting, so the surface cannot agree with the user's belief instead of
 * checking it — which is the entire purpose of a device diagnostic.
 *
 * Event-driven rather than polled: it reacts to the DAC list, the route authority and the sticky
 * battery broadcast. There is no timer, because a diagnostic screen that wakes the device once a
 * second to re-read hardware it has not been told about is itself a hardware problem.
 */
@Singleton
class DeviceDiagnosticsController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dacProfiles: DacProfileRepository,
    private val dualPlayerEngine: DualPlayerEngine,
    private val maintenanceOverrides: MaintenanceOverrideStore,
    @AppScope private val appScope: CoroutineScope,
) {
    private val _snapshot = MutableStateFlow(DeviceDiagnosticSnapshot())
    val snapshot: StateFlow<DeviceDiagnosticSnapshot> = _snapshot.asStateFlow()

    private val containment = DapContainmentStore(context)
    private var started = false

    /** Last battery reading, from the sticky broadcast. `null` until the platform answers once. */
    @Volatile private var batteryPercent: Int? = null
    @Volatile private var batteryCharging: Boolean? = null
    @Volatile private var batteryPlugLabel: String? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryPercent = level.takeIf { it >= 0 && scale > 0 }
                ?.let { (it * 100) / scale }
            batteryCharging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let { status ->
                when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING,
                    BatteryManager.BATTERY_STATUS_FULL -> true
                    BatteryManager.BATTERY_STATUS_DISCHARGING,
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                    else -> null
                }
            }
            batteryPlugLabel = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> null
            }
            rebuild()
        }
    }

    fun start() {
        if (started) return
        started = true
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        appScope.launch {
            combine(
                dacProfiles.connected,
                dualPlayerEngine.audioRouteState,
                dualPlayerEngine.usbAudioDiagnostics,
                maintenanceOverrides.state,
            ) { _, _, _, _ -> Unit }.collect { rebuild() }
        }
    }

    /** Recomputes the snapshot from current runtime state. Cheap: no platform queries beyond energy. */
    fun refresh() = rebuild()

    private fun rebuild() {
        val descriptors = dacProfiles.connected.value
        val route = dualPlayerEngine.audioRouteState.value
        val usb = dualPlayerEngine.usbAudioDiagnostics.value
        val overrides = maintenanceOverrides.current()
        val permission = runCatching {
            val manager = context.getSystemService(UsbManager::class.java)
            manager?.deviceList?.values?.firstOrNull()?.let { manager.hasPermission(it) }
        }.getOrNull()
        val power = context.getSystemService(PowerManager::class.java)

        _snapshot.value = DeviceDiagnosticSnapshot(
            device = HardwareDiagnostics.deviceGroup(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                buildDisplay = Build.DISPLAY,
                buildIncremental = Build.VERSION.INCREMENTAL,
            ),
            usb = HardwareDiagnostics.usbGroup(
                hostDeclared = true,
                descriptors = descriptors,
                apiPermissionGranted = permission,
            ),
            audio = HardwareDiagnostics.audioGroup(
                route = route,
                usb = usb,
                processing = dualPlayerEngine.currentProcessingRequirements(),
                forceAndroidOverride = overrides.forcedAndroidAudio(),
                directTransitionOverride = overrides.directTransitionDisabled(),
            ),
            power = HardwareDiagnostics.powerGroup(
                batteryPercent = batteryPercent,
                charging = batteryCharging,
                pluggedLabel = batteryPlugLabel,
                screenInteractive = power?.isInteractive,
            ),
            appliance = HardwareDiagnostics.applianceGroup(
                dedicatedMode = containment.isDedicatedModeEnabled(),
                lockTaskState = lockTaskLabel(),
                dapLockScreenEnabled = containment.isDapLockScreenEnabled(),
                maintenanceActive = DapMaintenanceSession.isActive,
                overrides = overrides,
            ),
            events = HardwareEventLog.snapshot(),
            droppedEventCount = HardwareEventLog.droppedCount(),
        )
    }

    private fun lockTaskLabel(): String {
        val manager = context.getSystemService(android.app.ActivityManager::class.java) ?: return "unknown"
        return when (manager.lockTaskModeState) {
            android.app.ActivityManager.LOCK_TASK_MODE_NONE -> "not locked"
            android.app.ActivityManager.LOCK_TASK_MODE_LOCKED -> "locked"
            android.app.ActivityManager.LOCK_TASK_MODE_PINNED -> "pinned"
            else -> "unknown"
        }
    }
}
