package com.lostf1sh.pixelplayeross.data.dac.fiio

import kotlinx.coroutines.CancellationException

/**
 * B59 (#53): what turning the DAC's EQ on needs from the player: whether it plays, a pause that is over when the player says so, and the resume.
 * The real one is wired in `DacEqModule` (the engine's master player, on the main thread); the tests hand in a fake.
 */
interface DacEqPlayback {
    fun isPlaying(): Boolean

    /** Pauses the music and waits until the player reports it is not playing. True when it is not playing afterwards. */
    suspend fun pauseAndWait(): Boolean

    /** Starts the music again. Called only for a pause this app made. */
    fun resume()
}

/** One user slot as the choice sees it: its name, the level of what it holds (worse reading of the pre-gain) and whether it holds nothing at all. */
data class DacEqSlotOption(val slotId: Int, val name: String, val netPeakDb: Double, val holdsNothing: Boolean)

/** How the app's slot on a DAC is decided from what the user slots hold. */
sealed interface DacEqSlotPlan {
    /** A slot whose curve is flat or harmless: used without asking. */
    data class Use(val option: DacEqSlotOption) : DacEqSlotPlan

    /** Every user slot holds a curve that boosts: the listener says which one may be taken, [preselected] first. */
    data class Ask(val options: List<DacEqSlotOption>, val preselected: Int) : DacEqSlotPlan
}

/**
 * B59 (#53): **the right slot.** Before, the app's slot was fixed at USER 3 (`appSlotId = 0xA2`); it holds whatever the listener put there, and on the owner's
 * Melody that was a curve of +10.7 dB net, which is what made the take-over so awkward. Now the app reads the user slots once and uses **one whose curve is
 * flat or harmless** (net boost at most 0 dB on the worse reading of the pre-gain) without asking; only when every user slot boosts it asks which one it may
 * take, with the profile's default (USER 3) preselected.
 */
object DacEqSlotChoice {
    /** The user slots as the backup read them; a slot the backup has not read is left out (it is not known to be harmless). */
    fun options(profile: FiioHidProfile, backup: DacEqBackup, sense: PreGainSense): List<DacEqSlotOption> =
        profile.writableSlots.mapNotNull { slot ->
            val held = backup.slots[slot.id] ?: return@mapNotNull null
            DacEqSlotOption(
                slotId = slot.id,
                name = slot.name,
                netPeakDb = DacEqLive.of(held, sense).netPeakDb,
                holdsNothing = held.preGainTenthsDb == 0 && held.bands.all { it.isInert },
            )
        }

    /**
     * The decision. A harmless slot wins without a question: first one that **holds nothing** (nothing is lost by taking it), else the lowest-numbered harmless
     * one. When no user slot is harmless (or none could be read) the listener is asked, USER 3 preselected.
     */
    fun plan(profile: FiioHidProfile, backup: DacEqBackup, sense: PreGainSense): DacEqSlotPlan {
        val options = options(profile, backup, sense)
        val harmless = options.filter { it.netPeakDb <= 0.0 }
        if (harmless.isNotEmpty()) {
            return DacEqSlotPlan.Use(harmless.sortedWith(compareByDescending<DacEqSlotOption> { it.holdsNothing }.thenBy { it.slotId }).first())
        }
        val all = profile.writableSlots.map { slot -> options.firstOrNull { it.slotId == slot.id } ?: DacEqSlotOption(slot.id, slot.name, Double.POSITIVE_INFINITY, false) }
        return DacEqSlotPlan.Ask(all, preselected = profile.appSlotId?.takeIf { id -> all.any { it.slotId == id } } ?: all.last().slotId)
    }
}

/** What the one-tap turn-on came to. [result] is null only when the listener cancelled the choice of the slot (nothing was written). */
data class DacEqTurnOnOutcome(
    val result: DacEqWriteResult?,
    /** The app paused the music for this. */
    val pausedMusic: Boolean,
    /** The app started it again (only after a success, only for a pause it made). */
    val resumedMusic: Boolean,
)

/**
 * B59 (#53): **turning the DAC's EQ on is one action.** Before: turn on → refused ("USER 3 holds a curve that boosts…") → a separate "Take over USER 3" button →
 * refused until the music was paused → pause by hand → press again → then the curve. Now the listener confirms once, and the app does it in order:
 *
 * 1. if the app's slot is not the app's yet, **pause the music** (only if it plays): selecting a slot nobody has vetted is audible at whatever level it holds;
 * 2. if no slot is chosen for this DAC, **read the user slots** (each is selected to be read, the DAC's own selection put back after) and choose one
 *    ([DacEqSlotChoice]); the listener is asked only when every user slot boosts;
 * 3. **take the slot over** (its curve goes in the backup, a flat curve with the pre-gain at 0 is saved);
 * 4. **apply the curve** (select the slot, write the plan, save, read back);
 * 5. **resume** the music, only if it was playing and the app paused it.
 *
 * On any failure each step has already restored as it always did, and **the music stays paused**: the result carries the reason, which the screen shows. The
 * safety rules are unchanged: every step goes through the gate, selecting a slot nobody vetted still only happens with the music paused, the plans, the backups
 * and the restore-factory action stay as they were. When the slot is the app's already, no pause is needed (writing a curve into it is what it always was).
 */
class DacEqTurnOn(
    private val writer: DacEqWriter,
    private val backups: DacEqBackupStore,
    private val playback: DacEqPlayback?,
    private val senseOf: (FiioHidProfile) -> PreGainSense,
    private val log: (String) -> Unit = {},
) {
    suspend fun turnOn(
        att: AttachedFiio,
        bands: List<DacEqBand>,
        initiator: WriteInitiator,
        stale: () -> String? = { null },
        /** Asked only when every user slot boosts; null answer = the listener cancelled. */
        chooseSlot: suspend (DacEqSlotPlan.Ask) -> Int? = { it.preselected },
    ): DacEqTurnOnOutcome {
        val profile = att.profile
        val before = backups.load(att.backupKey)
        val appSlot = before?.appSlotFor(profile)
        val owned = appSlot != null && before.owns(appSlot)
        val slotKnown = before?.hasAppSlot(profile) == true
        val wasPlaying = playback?.isPlaying() == true
        log("DAC_HID|TURN_ON start dac=${att.displayName} slotOwned=$owned slotChosen=$slotKnown playing=$wasPlaying")

        var paused = false
        if (!owned) {
            if (wasPlaying) {
                log("DAC_HID|TURN_ON pausing the music: the next steps select a slot that may be audible at any level")
                val stopped = try {
                    playback.pauseAndWait()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    log("DAC_HID|ERROR TURN_ON the music could not be paused: ${error.javaClass.simpleName}: ${error.message}")
                    false
                }
                paused = true
                if (!stopped) {
                    log("DAC_HID|TURN_ON the music did not stop; nothing was sent to the DAC")
                    return DacEqTurnOnOutcome(
                        DacEqWriteResult.Failed("the music could not be paused", restored = null, state = null), pausedMusic = true, resumedMusic = false,
                    )
                }
            }
            if (!slotKnown) {
                val read = writer.readUserSlots(att, initiator, stale)
                if (read !is DacEqWriteResult.Done) return failed("reading the user slots", read, paused)
                val backup = backups.load(att.backupKey)
                    ?: return failed("reading the user slots", DacEqWriteResult.Failed("the backup disappeared", restored = null, state = null), paused)
                val chosen = when (val plan = DacEqSlotChoice.plan(profile, backup, senseOf(profile))) {
                    is DacEqSlotPlan.Use -> {
                        log(
                            "DAC_HID|SLOT ${plan.option.name} (0x%02X) is used without asking: it holds ${if (plan.option.holdsNothing) "nothing" else "a curve that does not boost"}, net %.1f dB"
                                .format(plan.option.slotId, plan.option.netPeakDb)
                        )
                        plan.option.slotId
                    }
                    is DacEqSlotPlan.Ask -> {
                        log("DAC_HID|SLOT every user slot holds a curve that boosts: asking which one the app may take (preselected 0x%02X)".format(plan.preselected))
                        chooseSlot(plan) ?: run {
                            log("DAC_HID|SLOT the listener did not choose a slot; nothing was written")
                            if (paused) resume()
                            return DacEqTurnOnOutcome(null, pausedMusic = paused, resumedMusic = paused)
                        }
                    }
                }
                if (!writer.chooseSlot(att, chosen)) {
                    return failed("choosing the slot", DacEqWriteResult.Failed("the chosen slot could not be recorded", restored = null, state = null), paused)
                }
            }
            val taken = writer.takeOver(att, initiator, stale)
            if (taken !is DacEqWriteResult.Done) return failed("the take-over", taken, paused)
        }
        val applied = writer.applyCurve(att, bands, initiator, stale)
        if (applied !is DacEqWriteResult.Done) return failed("applying the curve", applied, paused)
        if (paused) resume()
        log("DAC_HID|TURN_ON done: the DAC's EQ is on${if (paused) "; the music was started again" else ""}")
        return DacEqTurnOnOutcome(applied, pausedMusic = paused, resumedMusic = paused)
    }

    private fun resume() {
        log("DAC_HID|TURN_ON starting the music again (it was playing and the app paused it)")
        playback?.resume()
    }

    private fun failed(step: String, result: DacEqWriteResult, paused: Boolean): DacEqTurnOnOutcome {
        log("DAC_HID|TURN_ON ended at $step: ${describeWriteResult(result)}${if (paused) "; the music stays paused" else ""}")
        return DacEqTurnOnOutcome(result, pausedMusic = paused, resumedMusic = false)
    }
}
