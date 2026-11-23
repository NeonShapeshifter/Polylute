package com.lostf1sh.pixelplayeross.data.volume

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbDeviceOwner
import com.decent.usbaudio.UsbVolumeRange
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.DacProfile
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.dac.UsbDacDescriptor
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneEqState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfile
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileRepository
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneProfileState
import com.lostf1sh.pixelplayeross.data.headphones.HeadphoneSafetyPolicy
import com.lostf1sh.pixelplayeross.data.headphones.SafetyMaxCalibration
import com.lostf1sh.pixelplayeross.data.headphones.SafetyVolumeBackendKind
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteState
import com.lostf1sh.pixelplayeross.data.service.player.AudioRouteTransition
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.presentation.safety.SafetyOverrideConfirmActivity
import com.lostf1sh.pixelplayeross.presentation.safety.SafetyOverrideConfirmation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowToast

/**
 * B69 (owner, 2026-11-23): Volume Up past Safety Max with another app in front. The real [SafetyVolumeController] (the
 * SafetyVolumeWriteResultTest harness) at its ceiling on a DAC's hardware volume, keys on the remote path, and the service's wiring
 * (every refusal -> [SafetyMaxRefusalNotifier.onRefusal]). The same scenarios as `B69-gates/before-probes`, which pass on the tree from
 * before B69 with no toast, no action and nothing asked with the screen on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class B69SafetyMaxOutsideTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val range = UsbVolumeRange(minUnits = 0, maxUnits = 60 * 512, resolutionUnits = 256)
    private lateinit var controller: SafetyVolumeController
    private lateinit var usb: UsbAudioDevice
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val events = mutableListOf<SafetyMaxRefusal>()
    private val writes = mutableListOf<Int>()
    private val calibration = SafetyMaxCalibration(outputContextId = "dac:melody", backendKind = SafetyVolumeBackendKind.USB_HARDWARE_VOLUME,
        ceilingRaw = 20, backendMinRaw = 0, backendMaxRaw = 60, calibratedAtEpochMs = 1L)

    @Before fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        usb = mockk(relaxed = true)
        every { usb.owner } returns UsbDeviceOwner()
        every { usb.openGeneration } returns 1L
        every { usb.hasHardwareVolume } returns true
        every { usb.readHardwareVolumeRange() } returns range
        every { usb.openedDevicePath } returns null
        every { usb.writeHardwareVolume(any(), any(), any()) } answers { writes += firstArg<Int>(); firstArg() }
        UsbAudioDevice::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, usb)
        val engine = mockk<DualPlayerEngine>(relaxed = true)
        every { engine.gainModel } returns MutableStateFlow(DualPlayerEngine.GainModel(1f, null, 1f))
        every { engine.audioRouteState } returns MutableStateFlow(AudioRouteState(actualBackend = AudioBackend.USB_DIRECT, transition = AudioRouteTransition.DIRECT_ACTIVE))
        every { engine.isPlayingFlow } returns MutableStateFlow(true)
        val descriptor = UsbDacDescriptor(0x3302, 0x0101, "SNOWSKY", "Melody", "M1", "/dev/bus/usb/001/002")
        val dac = DacProfile(profileId = "melody", deviceIdentityKey = descriptor.identityKey, identityScope = descriptor.identityScope,
            displayName = "Melody", detectedManufacturer = descriptor.manufacturer, detectedProduct = descriptor.product,
            vendorId = descriptor.vendorId, productId = descriptor.productId, serialNumber = descriptor.serialNumber, createdAtEpochMs = 1L, lastSeenEpochMs = 1L)
        val dacs = mockk<DacProfileRepository>(relaxed = true)
        every { dacs.connected } returns MutableStateFlow(listOf(descriptor))
        every { dacs.profiles } returns MutableStateFlow(listOf(dac))
        val headphones = mockk<HeadphoneProfileRepository>(relaxed = true)
        every { headphones.state } returns MutableStateFlow(HeadphoneProfileState(profiles = listOf(HeadphoneProfile(profileId = "profile",
            displayName = "Headphones", equalizer = HeadphoneEqState(), createdAtEpochMs = 1L, updatedAtEpochMs = 1L,
            safetyMax = HeadphoneSafetyPolicy(enabled = true, calibrations = mapOf(calibration.storageKey to calibration)))), selectedProfileId = "profile"))
        controller = SafetyVolumeController(context, headphones, dacs, engine, CoroutineScope(Job().apply { cancel() }))
        publish(20)
        val notifier = SafetyMaxRefusalNotifier(context)
        // The service's wiring (MusicService: refusals.collect { safetyRefusalNotifier.onRefusal(it) }).
        scope.launch { controller.refusals.collect { events += it; notifier.onRefusal(it) } }
    }

    @After fun tearDown() {
        scope.cancel()
        UsbAudioDevice::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun field(name: String) = SafetyVolumeController::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun publish(raw: Int) {
        @Suppress("UNCHECKED_CAST")
        (field("_state").get(controller) as MutableStateFlow<SafetyVolumeState>).value = SafetyVolumeState(
            backend = VolumeBackendSnapshot(SafetyVolumeBackendKind.USB_HARDWARE_VOLUME, "dac:melody", "DAC", raw, 0, 60, true),
            selectedProfileId = "profile", selectedProfileName = "Headphones", safetyEnabled = true, calibration = calibration,
        )
    }

    private fun screen(on: Boolean, locked: Boolean) {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(on)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(locked)
    }

    private fun press(times: Int, gapMs: Long = 400) = repeat(times) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(gapMs))
        controller.remoteVolume.setLevel(controller.state.value.backend.rawLevel + 1)
        repeat(2) { UsbAudioDevice.volumeExecutor.submit {}.get(3, TimeUnit.SECONDS) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val notificationManager get() = context.getSystemService(NotificationManager::class.java)
    private fun notification(): Notification? = shadowOf(notificationManager).allNotifications.singleOrNull()
    private fun toasts() = ShadowToast.shownToastCount()

    private val inFront = object : OverrideConfirmationHost { override fun setOverLockScreen(enabled: Boolean) = Unit }

    // ── B1 + B2: screen on, another app in front ─────────────────────────────────────────────────────

    @Test fun anotherAppInFrontOneNoticeThenTheActionAfterThreePresses() {
        screen(on = true, locked = false)
        press(1)
        assertThat(toasts()).isEqualTo(1)
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("Safety Max: volume limit reached")
        assertThat(notification()!!.actions).isNull()                    // the F-07 notice, as before, until the ask
        press(2)                                                           // the third refused press: the ask
        val offered = notification()!!
        assertThat(offered.actions).hasLength(1)
        assertThat(offered.actions[0].title.toString()).isEqualTo("Raise the limit")
        assertThat(offered.channelId).isEqualTo(SafetyMaxRefusalNotifier.OFFER_CHANNEL_ID)
        assertThat(offered.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("Safety Max: volume limit reached")
        press(6)                                                           // more presses never stack anything
        assertThat(toasts()).isEqualTo(1)
        assertThat(shadowOf(notificationManager).allNotifications).hasSize(1)
        assertThat(notification()!!.actions).hasLength(1)                 // the episode keeps its action
        assertThat(events.count { it.visibleNotice }).isEqualTo(1)
        assertThat(events.count { it.offer == SafetyMaxOutsideNotice.OfferStyle.HEADS_UP }).isAtLeast(1)
        // Nothing was raised, nothing was written, nothing was granted.
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(20)
        assertThat(controller.state.value.temporaryOverride).isFalse()
        assertThat(writes).isEmpty()
        println("B69 after: screen on, other app, 9 refused presses: toasts=${toasts()} events=${events.map { "${it.visibleNotice}/${it.offer}" }} actions=${notification()!!.actions.size}")
    }

    @Test fun theHeadsUpChannelIsSilentAndTheActionNeedsAnUnlockAndOpensTheConfirmation() {
        screen(on = true, locked = false)
        press(3)
        val channel = notificationManager.getNotificationChannel(SafetyMaxRefusalNotifier.OFFER_CHANNEL_ID)
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
        assertThat(channel.sound).isNull()
        assertThat(channel.shouldVibrate()).isFalse()
        assertThat(channel.shouldShowLights()).isFalse()
        // The F-07 channel is untouched.
        assertThat(notificationManager.getNotificationChannel(SafetyMaxRefusalNotifier.CHANNEL_ID).importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        val action = notification()!!.actions.single()
        assertThat(action.isAuthenticationRequired).isTrue()
        val intent = shadowOf(action.actionIntent).savedIntent
        assertThat(intent.component!!.className).isEqualTo(SafetyOverrideConfirmActivity::class.java.name)
        assertThat(shadowOf(action.actionIntent).isActivityIntent).isTrue()
        assertThat(intent.flags and android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS).isNotEqualTo(0)
    }

    @Test fun heldKeyRepeatsAreOnePress() {
        screen(on = true, locked = false)
        press(10, gapMs = 50)                                              // a held key on the remote path: ~50 ms apart
        assertThat(toasts()).isEqualTo(1)
        assertThat(notification()!!.actions).isNull()
    }

    @Test fun aNewEpisodeAfterTheLevelWentDownOrAMinuteOfQuiet() {
        screen(on = true, locked = false)
        press(1)
        assertThat(toasts()).isEqualTo(1)
        controller.setRawLevel(19)                                          // lowered: the episode ends
        repeat(2) { UsbAudioDevice.volumeExecutor.submit {}.get(3, TimeUnit.SECONDS) }
        publish(20)
        press(1)
        assertThat(toasts()).isEqualTo(2)
        ShadowSystemClock.advanceBy(Duration.ofMillis(SafetyMaxOutsideNotice.EPISODE_IDLE_MS + 1))
        press(1)
        assertThat(toasts()).isEqualTo(3)
    }

    @Test fun withPolyluteInFrontNothingNewIsSaid() {
        screen(on = true, locked = false)
        controller.attachConfirmationHost(inFront)
        press(6)
        assertThat(toasts()).isEqualTo(0)
        assertThat(events.none { it.visibleNotice || it.offer != null }).isTrue()
        assertThat(notification()?.actions).isNull()
    }

    // ── the lock screen (F-07) ───────────────────────────────────────────────────────────────────

    @Test fun theLockScreenPathIsUnchangedUntilTheFifthPressThenTheActionIsBehindAnUnlock() {
        screen(on = true, locked = true)
        press(4)
        assertThat(toasts()).isEqualTo(0)
        val before = notification()!!
        assertThat(before.actions).isNull()
        assertThat(before.channelId).isEqualTo(SafetyMaxRefusalNotifier.CHANNEL_ID)
        assertThat(before.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("Safety Max · unlock to go higher")
        assertThat(field("pendingRequest").get(controller)).isNull()
        press(1)                                                           // the fifth: FIX-5's ask (ON_UNLOCK), as before
        assertThat(field("pendingRequest").get(controller)).isNotNull()
        val after = notification()!!
        assertThat(after.channelId).isEqualTo(SafetyMaxRefusalNotifier.CHANNEL_ID)          // still the quiet lock-screen notice
        assertThat(after.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("Safety Max · unlock to go higher")
        assertThat(after.visibility).isEqualTo(Notification.VISIBILITY_PUBLIC)
        assertThat(after.actions.single().isAuthenticationRequired).isTrue()
        assertThat(toasts()).isEqualTo(0)
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(20)
    }

    @Test fun theScreenOffPathIsUnchangedToo() {
        screen(on = false, locked = true)
        press(5)
        assertThat(toasts()).isEqualTo(0)
        assertThat(field("pendingRequest").get(controller)).isNotNull()
        assertThat(controller.state.value.showOverrideConfirmation).isFalse()
    }

    // ── the confirmation ─────────────────────────────────────────────────────────────────────────

    @Test fun openingTheConfirmationNeverChangesTheLevelAndConfirmingStartsTheOrdinaryOverride() {
        screen(on = true, locked = false)
        press(3)
        val confirmation = SafetyOverrideConfirmation(controller) { false }
        assertThat(confirmation.shouldShow()).isTrue()
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(20)
        assertThat(controller.state.value.temporaryOverride).isFalse()
        confirmation.confirm()
        assertThat(controller.state.value.temporaryOverride).isTrue()
        val session = field("overrideSession").get(controller)
        assertThat(session).isNotNull()                                     // the same session the in-app button starts
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(20)   // the level is still the listener's to raise
        assertThat(writes).isEmpty()
        assertThat(confirmation.shouldShow()).isFalse()                     // nothing left to confirm
    }

    @Test fun cancellingLeavesTheCeiling() {
        screen(on = true, locked = true)
        press(5)
        assertThat(field("pendingRequest").get(controller)).isNotNull()
        screen(on = true, locked = false)
        val confirmation = SafetyOverrideConfirmation(controller) { false }
        confirmation.cancel()
        assertThat(controller.state.value.temporaryOverride).isFalse()
        assertThat(field("overrideSession").get(controller)).isNull()
        assertThat(field("pendingRequest").get(controller)).isNull()       // opening the app later does not ask again
        assertThat(controller.state.value.showOverrideConfirmation).isFalse()
        press(1)
        assertThat(controller.state.value.backend.rawLevel).isEqualTo(20)   // still refused
        assertThat(writes).isEmpty()
    }

    @Test fun aStaleOrLockedConfirmationOpensNothing() {
        publish(18)
        assertThat(SafetyOverrideConfirmation(controller) { false }.shouldShow()).isFalse()
        publish(20)
        val locked = SafetyOverrideConfirmation(controller) { true }
        assertThat(locked.shouldShow()).isFalse()
        locked.confirm()
        assertThat(controller.state.value.temporaryOverride).isFalse()
    }

    // ── strings ──────────────────────────────────────────────────────────────────────────────────

    @Test fun theStringsInAllThreeLanguages() {
        val expected = mapOf(
            "en" to listOf("Safety Max: volume limit reached", "Raise the limit", "Volume limit: raise it"),
            "es" to listOf("Safety Max: límite de volumen alcanzado", "Subir el límite", "Límite de volumen: subirlo"),
            "sv" to listOf("Safety Max: volymgränsen är nådd", "Höj gränsen", "Volymgräns: höj den"),
        )
        for ((tag, texts) in expected) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            val got = listOf(R.string.safety_max_limit_reached, R.string.safety_max_raise_limit, R.string.safety_max_offer_channel).map(localized::getString)
            assertThat(got).isEqualTo(texts)
        }
        for (dir in listOf("values", "values-es", "values-sv")) {
            val xml = File("src/main/res/$dir/strings_safety_volume.xml").readText()
            listOf("safety_max_limit_reached", "safety_max_raise_limit", "safety_max_offer_channel").forEach { assertThat(xml).contains("name=\"$it\"") }
        }
    }

    // ── boundaries: the manifest and the activity ────────────────────────────────────────────────

    @Test fun theConfirmationWindowIsPrivateOutOfTheRecentsAndNotOverTheLockScreen() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val entry = manifest.substringAfter("android:name=\".presentation.safety.SafetyOverrideConfirmActivity\"").substringBefore("/>")
        assertThat(entry).contains("android:exported=\"false\"")
        assertThat(entry).contains("android:excludeFromRecents=\"true\"")
        assertThat(entry).contains("android:noHistory=\"true\"")
        assertThat(entry).contains("android:taskAffinity=\"\"")
        // owner 11-23: a small window over another app follows that app (a landscape video does not turn while it is open)
        assertThat(entry).contains("android:screenOrientation=\"behind\"")
        assertThat(entry).doesNotContain("showWhenLocked")
        assertThat(entry).doesNotContain("turnScreenOn")
        val activity = File("src/main/java/com/lostf1sh/pixelplayeross/presentation/safety/SafetyOverrideConfirmActivity.kt").readText()
        assertThat(activity.indexOf("if (!confirmation.shouldShow())")).isLessThan(activity.indexOf("setContent {"))
        for (forbidden in listOf("setRawLevel", "setLevel(", "setStreamVolume", "writeHardwareVolume", "adjustStreamVolume")) {
            assertThat(activity).doesNotContain(forbidden)
            assertThat(File("src/main/java/com/lostf1sh/pixelplayeross/presentation/safety/SafetyOverrideConfirmation.kt").readText()).doesNotContain(forbidden)
        }
    }

    @Test fun theNoticeIsNeverAVolumeOrAGrant() {
        val notifier = File("src/main/java/com/lostf1sh/pixelplayeross/data/volume/SafetyMaxRefusalNotifier.kt").readText()
        val policy = File("src/main/java/com/lostf1sh/pixelplayeross/data/volume/SafetyMaxOutsideNotice.kt").readText()
        for (forbidden in listOf("setRawLevel", "allowTemporaryOverride", "setStreamVolume", "writeHardwareVolume", "temporaryOverride = true")) {
            assertThat(notifier).doesNotContain(forbidden)
            assertThat(policy).doesNotContain(forbidden)
        }
        assertThat(notifier).doesNotContain("setFullScreenIntent")
        verify(exactly = 0) { usb.writeHardwareVolume(any(), any(), any()) }
    }
}
