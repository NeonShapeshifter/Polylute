package com.lostf1sh.pixelplayeross.data.volume

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.decent.usbaudio.UsbAudioDevice
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.dac.DacProfileCatalog
import com.lostf1sh.pixelplayeross.data.dac.DacProfileRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.service.player.AudioBackend
import com.lostf1sh.pixelplayeross.data.service.player.DualPlayerEngine
import com.lostf1sh.pixelplayeross.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** A DAC without a volume control the app can use, by the key its profile has (or its USB identity before it has one). */
data class FixedOutputDac(val key: String, val name: String)

/** Where the question is: first "does it have its own control?", then (if it does) "which way should it play?", then, if that is a rise, the confirmation. */
enum class VolumeQuestionStage { ASK_CONTROL, CHOOSE_MODE, CONFIRM_RISE }

/**
 * The question on screen (B40-P3, replacing B39-S1's notice). [holdsPlayback] is true when it stopped a play request: answering it then
 * plays; false when the listener asked to see it again from the Audio page, where closing it changes nothing.
 * [riseDb] is set at [VolumeQuestionStage.CONFIRM_RISE]: how much louder Purity would be than what plays now.
 *
 * [question] is the question's **identity** (B41-fix2): minted by the coordinator, on the main thread, when a question is opened, and carried
 * unchanged by every copy of it (a Pause that clears [holdsPlayback], the next stage, the rise to confirm). Another question for the same DAC
 * at the same stage is another identity, so a Purity activation started for the first cannot answer the second: the stage alone does not say
 * which question it was asked from. It is not the attachment generation (that is the coordinator's `epoch`: one DAC attached, however many
 * questions it is asked), and 0 is the identity of a notice that was not opened by the coordinator.
 */
data class FixedOutputNotice(
    val dac: FixedOutputDac,
    val holdsPlayback: Boolean,
    val stage: VolumeQuestionStage = VolumeQuestionStage.ASK_CONTROL,
    val riseDb: Float? = null,
    val question: Long = 0L,
)

/** What the question does next. */
sealed interface QuestionStep {
    /** Another page of the question. */
    data class Show(val notice: FixedOutputNotice) : QuestionStep

    /** The question is over with this answer. */
    data class Done(val answer: DeviceVolumeAnswer) : QuestionStep

    /** Purity could not be turned on: the question ends and nothing is recorded (Resonance stays as it was). */
    data object PurityFailed : QuestionStep
}

/** How turning Purity on went (the Pure Direct lock's own activation decides; the question only reads it). */
sealed interface PurityOutcome {
    data object Enabled : PurityOutcome
    data class NeedsConfirmation(val riseDb: Float) : PurityOutcome
    data object Failed : PurityOutcome
}

/** The order of the question (B40-P3), apart from the screen and the platform. */
object VolumeQuestion {
    /** "Does it have its own volume knob or buttons?" answered. No: Resonance only, done. Yes: on to the choice. */
    fun afterControlAnswer(notice: FixedOutputNotice, hasOwnControl: Boolean): QuestionStep =
        if (hasOwnControl) QuestionStep.Show(notice.copy(stage = VolumeQuestionStage.CHOOSE_MODE))
        else QuestionStep.Done(DeviceVolumeAnswer.noControl)

    /**
     * Resonance (the recommended, default choice) or Purity. Resonance is done. Purity goes through the lock's own activation: done once it
     * is on, a rise to confirm first (the level never rises on its own), or not at all if it could not be turned on.
     */
    fun afterModeChoice(notice: FixedOutputNotice, mode: DeviceVolumeMode, purity: () -> PurityOutcome): QuestionStep = when (mode) {
        DeviceVolumeMode.RESONANCE -> QuestionStep.Done(DeviceVolumeAnswer(hasOwnControl = true, mode = DeviceVolumeMode.RESONANCE))
        DeviceVolumeMode.PURITY -> when (val outcome = purity()) {
            PurityOutcome.Enabled -> QuestionStep.Done(DeviceVolumeAnswer(hasOwnControl = true, mode = DeviceVolumeMode.PURITY))
            is PurityOutcome.NeedsConfirmation ->
                QuestionStep.Show(notice.copy(stage = VolumeQuestionStage.CONFIRM_RISE, riseDb = outcome.riseDb))
            PurityOutcome.Failed -> QuestionStep.PurityFailed
        }
    }
}

/**
 * Asks the listener, once per device and before it plays, what to do with a DAC whose volume the app cannot set (B40-P3; B39-S1 only told
 * them). The play waits for the answer ([allowPlay]).
 *
 * It sits in front of the session's player ([com.lostf1sh.pixelplayeross.data.service.player.MappingPlayer]), so every play (the app, the
 * notification, a headset button, the lock screen) goes through it. The only thing it can do to playback is not start it yet. The
 * automatic Direct handoff already holds on a fixed output ([HandoffResumeSafety]), so nothing resumes on its own either.
 *
 * What an answer does ([DeviceVolumeAnswer]): a device that holds Purity off ("no control" or "knob, Resonance") has the Pure Direct lock
 * not applied while it is attached ([UserPreferencesRepository.purityHeldOffForDevice]), so Polylute's own volume works, and that volume
 * starts at [DeviceVolumePolicy.safeStartGain] at every connection (it only ever lowers). Purity adds nothing: choosing it keeps the
 * lock, whose own activation says when the level would rise ([PristineModeController.enable]).
 *
 * Which DAC has no volume is read from its descriptors, before anything is claimed ([UsbAudioDevice.hasHardwareVolumeControl]),
 * once per attachment.
 *
 * **A held play starts only once the answer's protection is in effect** (B40-fix1), and the play is one thing with one owner
 * (B40-fix3). Everything about it (the question, the play the listener wants, the settling of the protection, the confirmation) lives on the
 * **main thread**, where the plays, the pauses and stops, the attachments and the answers arrive, and is touched nowhere else: no
 * atomics, no `compareAndSet` to lose a race with. Three things make it correct by construction:
 *  - an **attachment generation** ([epoch]): every change of the attached DAC (a DAC arrives, leaves, or is replaced by another, and
 *    A, nothing, A is three) starts a new one. The settle job belongs to a generation and is **cancelled** when it ends; the
 *    confirmation ("the safe start was seen in effect") and the wanted play carry the generation they were made in, and one from an older
 *    generation is dropped: it can neither confirm nor play;
 *  - **the protection is per generation**: a generation applies its own lock and level, directly and in order, whatever an older settle
 *    is doing; nothing can skip it;
 *  - a **play intent that is checked when it runs** ([wanted], [postResume]): the listener's play creates it, any pause or stop from any
 *    controller clears it, and the Runnable that finally starts the playback checks it (and the generation) **at the moment it runs**,
 *    not when it was posted. A pause that comes between the confirmation and the Runnable is the newer intent and wins.
 * The protection itself always goes ahead; only the play is cancelled. Not confirmed within [DeviceVolumePolicy.PROTECTION_BOUND_MS]:
 * no play, one line in the log, and the listener is told only if the play was still wanted.
 */
@Singleton
class FixedOutputNoticeCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dacProfiles: DacProfileRepository,
    private val engine: DualPlayerEngine,
    private val preferences: UserPreferencesRepository,
    private val pristineMode: dagger.Lazy<PristineModeController>,
    @AppScope private val appScope: CoroutineScope,
) {
    private val usb = UsbAudioDevice.getInstance(context)
    private val ledger = DeviceVolumeLedger.persistent(context)
    private val notifier = FixedOutputNoticeNotifier(context)

    /** What each attachment answered, so the descriptors are read once and not on every play. Keyed by device path and USB id. */
    private val answers = ConcurrentHashMap<String, Boolean>()

    private val _pending = MutableStateFlow<FixedOutputNotice?>(null)

    /** The question to show, or null. */
    val pending: StateFlow<FixedOutputNotice?> = _pending.asStateFlow()

    /**
     * Starts the session's playback. Installed by the service, which owns the session's player. **The coordinator calls it, on the main
     * thread, from the Runnable it posts ([postResume]) and only if the play is still wanted when that Runnable runs**: it is the action,
     * not a way to post one (a bare post here is what let a Play run after a Pause).
     */
    var resumePlayback: (() -> Unit)? = null

    /** Bumped when an answer is written, so [answer] is read again. */
    private val answerVersion = MutableStateFlow(0)

    // ── the held play: one owner, the main thread ────────────────────────────────────────────────
    // (The only other shared state of this class is `answers`, the descriptor cache that `resolve()` fills from the flow's IO thread
    // and from the gate's main thread, so it is a ConcurrentHashMap; and `_pending`/`answerVersion`, StateFlows the screens read.)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The attachment generation: bumped by every change of the attached DAC ([onDacChanged]). */
    private var epoch = 0L

    /** The DAC (by key) of the current generation; null when none is attached. */
    private var attachedKey: String? = null

    /** The generation in which the DAC's safe start was seen in effect; the first play of a generation passes only if this is it. A later volume change is the listener's. */
    private var confirmedEpoch = -1L

    /** The settling of the protection of one generation; its job is cancelled when the generation ends. */
    private class Settle(val epoch: Long, val job: Job)

    private var settle: Settle? = null

    /** The play the listener wants, made in generation [epoch]: valid only while that generation is the current one and nothing paused or stopped. */
    private class Wanted(val epoch: Long)

    private var wanted: Wanted? = null

    /** The DAC attached now if it is known to have no volume the app can set; null otherwise. Follows the attachments and the route. */
    val fixedOutputDac: StateFlow<FixedOutputDac?> = combine(
        dacProfiles.connected,
        dacProfiles.profiles,
        engine.audioRouteState.map { it.permissionGranted }.distinctUntilChanged(),
    ) { _, _, _ -> resolve() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
        .stateIn(appScope, SharingStarted.Eagerly, null)

    /** What the listener answered for the DAC attached now, or null (none attached, or not asked yet). */
    val answer: StateFlow<DeviceVolumeAnswer?> = combine(fixedOutputDac, answerVersion) { dac, _ ->
        dac?.let { ledger.answerFor(it.key) }
    }.distinctUntilChanged().stateIn(appScope, SharingStarted.Eagerly, null)

    init {
        // The DAC left: whatever was waiting on it is moot.
        appScope.launch(Dispatchers.Main.immediate) {
            fixedOutputDac.collect { dac -> onDacChanged(dac) }
        }
        // Purity is held off for a device that answered Resonance or "no control"; the engine and the screens read the lock with that.
        appScope.launch {
            answer.map { it?.holdsPurityOff == true }.distinctUntilChanged().collect { preferences.purityHeldOffForDevice.value = it }
        }
        // And Polylute's own volume starts low at every connection of such a device (never above what it was).
        appScope.launch {
            combine(fixedOutputDac.map { it?.key }, answer.map { it?.holdsPurityOff == true }) { key, held -> key.takeIf { held } }
                .distinctUntilChanged()
                .collect { key -> if (key != null) engine.lowerUserDigitalGainTo(DeviceVolumePolicy.safeStartGain) }
        }
    }

    private fun resolve(): FixedOutputDac? {
        val device = usb.findUsbAudioDevice() ?: return null
        val cacheKey = "${device.deviceName}|${device.vendorId}:${device.productId}"
        val hasControl = answers[cacheKey] ?: usb.hasHardwareVolumeControl(device)?.also { answers[cacheKey] = it }
        if (hasControl != false) return null
        val descriptor = dacProfiles.connected.value.firstOrNull { it.connectionPath == device.deviceName }
        val profile = descriptor?.let { DacProfileCatalog.findForDescriptor(dacProfiles.profiles.value, it) }
        val key = profile?.profileId ?: descriptor?.identityKey ?: cacheKey
        val name = profile?.displayName ?: descriptor?.detectedName ?: device.productName ?: "USB DAC"
        return FixedOutputDac(key, name)
    }

    /**
     * Asked before a play reaches the player. True lets it through. False holds it and puts the question up (on screen, and as a
     * notification for a play that came with the app out of sight): nothing is played, nothing is changed.
     */
    fun allowPlay(): Boolean {
        val route = engine.audioRouteState.value
        // Not on the Direct path the volume is Android's own and the app's digital level works: nothing to ask.
        if (route.desiredBackend != AudioBackend.USB_DIRECT || route.usbDacPresent != true || route.permissionGranted != true) return true
        // Read now, not from the flow: a play that comes the moment the DAC is permitted must not beat the collector.
        val dac = resolve() ?: return true
        return allowPlayFor(dac)
    }

    internal fun allowPlayFor(dac: FixedOutputDac): Boolean {
        // The gate reads the attachment now, not from the flow: this play is for this DAC, in whatever generation it is.
        onDacChanged(dac)
        // The protection of this generation is being put in effect: the play waits with it, and is wanted (once, however many times it is asked).
        if (settle?.epoch == epoch) {
            want()
            return false
        }
        if (!ledger.due(dac.key, hasVolumeControl = false)) return answeredPlayMayStart(dac)
        // The question is due. The play it holds lives in the question until it is answered (a pause clears it there too).
        if (_pending.value?.dac?.key == dac.key && _pending.value?.holdsPlayback == true) return false
        Timber.tag(TAG).i("Play held: %s has no volume the app can set; the question is due", dac.name)
        _pending.value = openQuestion(dac, holdsPlayback = true)
        notifier.post(dac.name)
        return false
    }

    /**
     * The DAC attached now changed (null: it left). A change of the attached DAC, and only that, starts a new generation: the settle of
     * the old one is cancelled and the play wanted in it, the confirmation it may have made and the question for another DAC are over.
     * Main thread.
     */
    internal fun onDacChanged(dac: FixedOutputDac?) {
        if (!onMainThread()) {
            mainHandler.post { onDacChanged(dac) }
            return
        }
        if (dac?.key == attachedKey) return
        epoch++
        attachedKey = dac?.key
        settle?.job?.cancel()
        settle = null
        wanted = null
        if (_pending.value?.dac?.key != dac?.key) clear()
    }

    /**
     * The listener paused or stopped (from the app, the notification, the lock screen, a headset or a DAC button: whatever reaches the
     * session's player). The play they wanted is no longer wanted, whether it is waiting for the protection, waiting in the question, or
     * already posted to the main thread and not yet run. The protection itself still applies.
     */
    fun cancelOwedPlay() {
        if (!onMainThread()) {
            mainHandler.post { cancelOwedPlay() }
            return
        }
        wanted = null
        _pending.value?.takeIf { it.holdsPlayback }?.let { _pending.value = it.copy(holdsPlayback = false) }
    }

    private fun onMainThread() = Looper.myLooper() == Looper.getMainLooper()

    /** The listener wants to play, in this generation: one intent, however many times they press. */
    private fun want() {
        if (wanted?.epoch != epoch) wanted = Wanted(epoch)
    }

    /**
     * Posts the start of the playback. **The check is made when the Runnable runs**: the intent must still be this one and its generation
     * the current one. A pause or stop in between clears the intent, a DAC change makes a new generation, and the Runnable then does
     * nothing. This is the only place [resumePlayback] is called.
     */
    private fun postResume(ticket: Wanted) {
        mainHandler.post {
            if (wanted === ticket && ticket.epoch == epoch) {
                wanted = null
                resumePlayback?.invoke()
            }
        }
    }

    /**
     * The device already has its answer. One that holds Purity off (Resonance, or no control) needs the lock lifted and the safe start
     * level in effect before the first play of a generation, which a connection's collectors may not have delivered yet: if they are
     * not, this generation applies them itself, directly, and the play waits for them. Later plays in the generation pass at once.
     */
    internal fun answeredPlayMayStart(dac: FixedOutputDac): Boolean {
        onDacChanged(dac)
        val answer = ledger.answerFor(dac.key)
        if (answer?.holdsPurityOff != true || confirmedEpoch == epoch) return true
        if (protectionInEffect()) {
            confirmedEpoch = epoch
            return true
        }
        Timber.tag(TAG).i("Play held: %s's answer (%s) is not in effect in the signal chain yet", dac.name, answer.encode())
        want()
        beginSettle(dac)
        return false
    }

    private fun protectionInEffect(): Boolean =
        DeviceVolumePolicy.protectionInEffect(engine.appliedSignal.value, engine.gainModel.value.userDigitalGain)

    /**
     * Applies what a Purity-off answer means for **this generation**, directly and in order (the lock, then the level), and settles: waits
     * (bounded) for the signal path to report both. Confirmed, the wanted play is posted; not, it is dropped with a log line and a toast.
     * An older generation's settle was cancelled with it ([onDacChanged]) and cannot be in the way: nothing here is a compare-and-set that
     * could skip this generation's protection. Main thread.
     */
    private fun beginSettle(dac: FixedOutputDac) {
        if (settle?.epoch == epoch) return
        val generation = epoch
        preferences.purityHeldOffForDevice.value = true
        engine.lowerUserDigitalGainTo(DeviceVolumePolicy.safeStartGain)
        val job = appScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            val confirmed = withTimeoutOrNull(DeviceVolumePolicy.PROTECTION_BOUND_MS) {
                combine(engine.appliedSignal, engine.gainModel) { signal, gain ->
                    DeviceVolumePolicy.protectionInEffect(signal, gain.userDigitalGain)
                }.first { it }
            } == true
            // Back on the main thread. A job of a generation that has ended was cancelled and does not get here; if it did, it would find the
            // generation changed and drop its result.
            if (epoch != generation) return@launch
            settle = null
            val ticket = wanted?.takeIf { it.epoch == generation }
            if (confirmed) {
                confirmedEpoch = generation
                if (ticket != null) postResume(ticket)
            } else {
                Timber.tag(TAG).w("%s: the lock and the safe start level were not in effect within %d ms; the play was not started", dac.name, DeviceVolumePolicy.PROTECTION_BOUND_MS)
                if (ticket != null) {
                    wanted = null
                    toast(R.string.fixed_output_toast_not_confirmed)
                }
            }
        }
        // assigned before the job can run (it may complete at once if the signal path already reports the protection)
        settle = Settle(generation, job)
        job.start()
    }

    /** "Does it have its own volume knob or buttons?" answered. No: Resonance only. Yes: on to the choice. */
    fun answerOwnControl(hasOwnControl: Boolean) {
        val notice = _pending.value?.takeIf { it.stage == VolumeQuestionStage.ASK_CONTROL } ?: return
        apply(notice, VolumeQuestion.afterControlAnswer(notice, hasOwnControl))
    }

    /** Resonance (recommended) or Purity for a device that has its own control. Purity may ask to confirm a rise first. */
    fun choose(mode: DeviceVolumeMode) {
        if (!onMainThread()) {
            mainHandler.post { choose(mode) }
            return
        }
        val notice = _pending.value?.takeIf { it.stage == VolumeQuestionStage.CHOOSE_MODE } ?: return
        if (mode == DeviceVolumeMode.PURITY) {
            beginPurity(notice, confirmRise = false)
        } else {
            apply(notice, VolumeQuestion.afterModeChoice(notice, mode) { PurityOutcome.Enabled })
        }
    }

    /** The rise Purity would bring was read and the listener went on: the lock is turned on with it. */
    fun confirmRise() {
        if (!onMainThread()) {
            mainHandler.post { confirmRise() }
            return
        }
        val notice = _pending.value?.takeIf { it.stage == VolumeQuestionStage.CONFIRM_RISE } ?: return
        beginPurity(notice, confirmRise = true)
    }

    /**
     * A Purity activation in flight (B41-fix1, the outside review of B40-fix3): the attachment generation it was asked in and the
     * question it was asked from. It owns nothing but that; what the question wants (the play, its stage) is read **when the activation
     * ends**, not copied from when it began.
     */
    private class PurityRequest(val epoch: Long, val dacKey: String, val stage: VolumeQuestionStage, val question: Long)

    /** The identities handed out to questions ([FixedOutputNotice.question]). Main thread, like the question itself. */
    private var lastQuestion = 0L

    /** A question being opened, with an identity no earlier one had. */
    private fun openQuestion(dac: FixedOutputDac, holdsPlayback: Boolean) =
        FixedOutputNotice(dac, holdsPlayback, question = ++lastQuestion)

    /**
     * Turns Purity on. The activation suspends ([PristineModeController.enable] reads the lock and persists), and the listener does not
     * stop living meanwhile: a Pause or Stop clears the play the question holds, a DAC that leaves ends the generation, a new Play
     * starts another question. So the question the activation was asked from is **not** kept: only the generation and the stage are
     * (a [PurityRequest]), and [finishPurity] decides, on the main thread and with what is true then, what the result may still do.
     * Main thread.
     */
    private fun beginPurity(notice: FixedOutputNotice, confirmRise: Boolean) {
        val request = PurityRequest(epoch, notice.dac.key, notice.stage, notice.question)
        appScope.launch { enterPurity(request, notice.dac.name, confirmRise) }
    }

    private suspend fun enterPurity(request: PurityRequest, dacName: String, confirmRise: Boolean) {
        // Nothing automatic raises the level: the rise is shown, and only a yes applies it.
        val outcome = when (val result = pristineMode.get().enable(confirmRise)) {
            is PristineToggleResult.NeedsConfirmation -> PurityOutcome.NeedsConfirmation(result.riseDb)
            is PristineToggleResult.Failed -> {
                Timber.tag(TAG).w("Purity could not be turned on for %s: %s", dacName, result.reason)
                PurityOutcome.Failed
            }
            else -> PurityOutcome.Enabled
        }
        withContext(Dispatchers.Main.immediate) { finishPurity(request, dacName, outcome) }
    }

    /**
     * The activation ended. Main thread. It publishes its result only into the question that asked for it, in the generation it was
     * asked in; anything older is dropped whole (a result, a failure toast and a confirmation alike), so it can neither create an owed
     * play nor bring back the intent a Pause or Stop cleared, nor close or restore the question of another DAC, **nor answer a question that
     * was closed and opened again** (B41-fix2: the same DAC at the same stage is not the same question; the identity is).
     * For the question that is still there, the result is applied to **its current state** (its [FixedOutputNotice.holdsPlayback] is the
     * live play intent, which [cancelOwedPlay] clears), so a Pause during the activation still means no play, and a confirmation asked
     * after it comes back with the intent as it is.
     */
    private fun finishPurity(request: PurityRequest, dacName: String, outcome: PurityOutcome) {
        val current = _pending.value
        if (request.epoch != epoch || current == null || current.dac.key != request.dacKey || current.stage != request.stage ||
            current.question != request.question
        ) {
            Timber.tag(TAG).i("Purity finished for %s after its question ended (%s): the result is dropped", dacName, if (request.epoch != epoch) "the attachment changed" else "the question moved on")
            return
        }
        apply(current, VolumeQuestion.afterModeChoice(current, DeviceVolumeMode.PURITY) { outcome })
    }

    private fun apply(notice: FixedOutputNotice, step: QuestionStep) {
        when (step) {
            is QuestionStep.Show -> _pending.value = step.notice
            is QuestionStep.Done -> {
                record(notice, step.answer)
                toast(
                    when {
                        !step.answer.hasOwnControl -> R.string.fixed_output_toast_no_control
                        step.answer.mode == DeviceVolumeMode.PURITY -> R.string.fixed_output_toast_purity
                        else -> R.string.fixed_output_toast_resonance
                    },
                )
                if (step.answer.holdsPurityOff) {
                    // The question is over; the play it held (if the listener has not paused since) waits for the lock and the level of
                    // this generation to be in effect (B40-fix1, fix3).
                    clear()
                    onDacChanged(notice.dac)
                    if (notice.holdsPlayback) want()
                    beginSettle(notice.dac)
                } else {
                    finish(notice)
                }
            }
            QuestionStep.PurityFailed -> {
                toast(R.string.fixed_output_toast_purity_failed)
                clear()
            }
        }
    }

    private fun record(notice: FixedOutputNotice, answer: DeviceVolumeAnswer) {
        ledger.record(notice.dac.key, answer)
        Timber.tag(TAG).i("%s answered: %s", notice.dac.name, answer.encode())
        answerVersion.update { it + 1 }
    }

    private fun finish(notice: FixedOutputNotice) {
        clear()
        onDacChanged(notice.dac)
        if (notice.holdsPlayback) {
            want()
            wanted?.let(::postResume)
        }
    }

    private fun toast(@androidx.annotation.StringRes text: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Closes the question without remembering anything: it is due again on the next play (or the old answer stands). */
    fun dismiss() = clear()

    /** The Audio page's "change it": the question for the DAC attached now, with nothing to resume. False if there is none. */
    fun showAgain(): Boolean {
        val dac = fixedOutputDac.value ?: return false
        _pending.value = openQuestion(dac, holdsPlayback = false)
        return true
    }

    private fun clear() {
        _pending.update { null }
        notifier.cancel()
    }

    private companion object {
        const val TAG = "FixedOutputNotice"
    }
}
