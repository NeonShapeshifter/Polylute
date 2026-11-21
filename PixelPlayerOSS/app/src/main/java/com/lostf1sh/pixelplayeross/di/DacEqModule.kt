package com.lostf1sh.pixelplayeross.di

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.decent.usbaudio.UsbAudioDevice
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.dac.fiio.AttachedFiioSelector
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqController
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqHidAccess
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBackupStore
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqLabelSource
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqPlayback
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqPreGainSenseSwitch
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqService
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteConsent
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteSwitch
import com.lostf1sh.pixelplayeross.data.dac.fiio.PurityDacEq
import com.lostf1sh.pixelplayeross.data.dac.fiio.PurityDacEqMemoryStore
import com.lostf1sh.pixelplayeross.data.dac.fiio.PurityDacEqNotes
import com.lostf1sh.pixelplayeross.data.dac.fiio.PurityDacEqObserver
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.dac.fiio.UsbFiioHidOpener
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * B30: the DAC's EQ controller, built from the real flows. The HID thread is its own (`DacHid`, background priority): a read or a
 * write blocks on USB transfers for up to a few hundred ms and must never share a thread with the UI or with playback.
 */
@Module
@InstallIn(SingletonComponent::class)
object DacEqModule {
    @Provides
    @Singleton
    fun dacEqController(
        @ApplicationContext context: Context,
        engine: DualPlayerEngine,
        dacs: DacProfileRepository,
    ): DacEqController {
        val usb = UsbAudioDevice.getInstance(context)
        // The same DAC Safety resolves: the one Direct opened, else the only one attached. The route is in the combine so a Direct
        // open re-evaluates it.
        val attached = combine(dacs.profiles, dacs.connected, engine.audioRouteState) { profiles, connected, _ ->
            AttachedFiioSelector.select(profiles, connected, usb.openedDevicePath)
        }.distinctUntilChanged()
        val directActive = engine.audioRouteState.map { it.isDirectActive }.distinctUntilChanged()
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "DacHid")
        }.asCoroutineDispatcher()
        return DacEqController(
            attached = attached,
            directActive = directActive,
            access = DacEqHidAccess(UsbFiioHidOpener(context), dispatcher, usb.owner) { usb.openGeneration },
            clock = SystemClock::elapsedRealtime,
            log = { line -> Timber.tag("DAC_HID").i(line) },
            attachedNow = { AttachedFiioSelector.select(dacs.profiles.value, dacs.connected.value, usb.openedDevicePath) },
        )
    }

    @Provides
    fun dacEqLabelSource(controller: DacEqController): DacEqLabelSource = controller

    /** B61 (item 3): Purity closes the DAC's EQ and puts it back; what was selected is remembered beside that DAC's EQ backup. */
    @Provides
    @Singleton
    fun purityDacEq(@ApplicationContext context: Context, service: DacEqService, notes: PurityDacEqNotes): PurityDacEq = PurityDacEq(
        service = service,
        memories = PurityDacEqMemoryStore(File(context.filesDir, "dac-eq-backups")),
        notes = notes,
        log = { line -> Timber.tag("DAC_HID").i(line) },
    )

    @Provides
    @Singleton
    fun purityDacEqObserver(coordinator: PurityDacEq, controller: DacEqController, preferences: UserPreferencesRepository): PurityDacEqObserver =
        PurityDacEqObserver(coordinator, preferences.pristineLockFlow, controller)

    /** The controller, the backups on disk, the debug override and the one gate's writer: what the screen and the reviewer's commands use. */
    @Provides
    @Singleton
    fun dacEqService(
        @ApplicationContext context: Context,
        controller: DacEqController,
        engine: DualPlayerEngine,
    ): DacEqService = DacEqService(
        controller = controller,
        backups = DacEqBackupStore(File(context.filesDir, "dac-eq-backups")),
        writeSwitch = DacEqWriteSwitch.shared(context),
        consent = DacEqWriteConsent.shared(context),
        elapsedMs = SystemClock::elapsedRealtime,
        wallClockMs = System::currentTimeMillis,
        log = { line -> Timber.tag("DAC_HID").i(line) },
        senseSwitch = DacEqPreGainSenseSwitch.shared(context),
        // The player's own answer to "is anything playing", a state flow: readable from the HID thread.
        playbackActive = { engine.isPlayingFlow.value },
        playback = EnginePlayback(engine),
    )
}

/**
 * B59 (#53): the one-tap turn-on pauses the music through the engine's master player (the player's own thread, the main one) and waits for the engine to say it
 * stopped, because the gate asks the same flag; and it starts it again only after a pause the app made.
 */
private class EnginePlayback(private val engine: DualPlayerEngine) : DacEqPlayback {
    private val main = Handler(Looper.getMainLooper())

    override fun isPlaying(): Boolean = engine.isPlayingFlow.value

    override suspend fun pauseAndWait(): Boolean {
        if (!engine.isPlayingFlow.value) return true
        withContext(Dispatchers.Main.immediate) { engine.masterPlayer.pause() }
        return withTimeoutOrNull(PAUSE_WAIT_MS) { engine.isPlayingFlow.first { !it } } != null
    }

    override fun resume() {
        main.post { engine.masterPlayer.play() }
    }

    private companion object {
        const val PAUSE_WAIT_MS = 3_000L
    }
}
