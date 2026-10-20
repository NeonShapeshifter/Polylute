package com.lostf1sh.pixelplayeross.dap.admin

import android.content.Context
import android.content.Intent
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqBackupResult
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqDebugSteps
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqPreGainSenseSwitch
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqService
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqStateFormat
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteAvailability
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteResult
import com.lostf1sh.pixelplayeross.data.dac.fiio.DacEqWriteSwitch
import com.lostf1sh.pixelplayeross.data.dac.fiio.FiioWriteOptions
import com.lostf1sh.pixelplayeross.data.dac.fiio.PreGainSense
import com.lostf1sh.pixelplayeross.data.dac.fiio.WriteInitiator
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking

/**
 * B30-P1.5 / P2: the reviewer's commands for the DAC's own EQ. Debug builds only; every command logs every TX and RX frame and the
 * whole parsed state under the `DAC_HID` tag (and into `diagnostics/dac-hid.txt`, through [sink]).
 *
 * | Command | What | Writes to the DAC |
 * |---|---|---|
 * | `DAC_EQ_READ` | the whole EQ state, parsed | no (GETs) |
 * | `DAC_EQ_BACKUP [--ez replace true]` | reads all and stores it; never overwrites silently | no |
 * | `DAC_EQ_DISCOVER_SLOT` | selects the app's slot (USER 3), reads what it holds, adds it to the backup, puts the selection back | yes, one select and back: **audible at whatever the slot holds**, so with playback paused (refused otherwise) |
 * | `DAC_EQ_INVENTORY` | reads every preset the profile lists (select, read, back) into the backup | pointer moves only (selects); **audible**, playback paused (refused otherwise) |
 * | `DAC_EQ_TAKE_OVER` | USER 3's factory curve into the backup, then the slot saved flat with the pre-gain at 0, selection back | yes, through the gate; **audible**, playback paused (refused otherwise) |
 * | `DAC_EQ_LISTEN_CHECK --ez low_level true` | on the taken-over slot, the pre-gain at −6, 0, +6, 0 dB live (unsaved), 5 s each: which is quieter? | yes, **louder than the limit on purpose**, DAC at a low level, music playing |
 * | `DAC_EQ_ROUNDTRIP` | writes the app slot's stored state back, reads it back, compares | yes, through the gate |
 * | `DAC_EQ_SET_BAND --ei band N --ef gain G` | one band of the app slot, \|G\| <= 3 dB, pre-gain by the gate, read back | yes, through the gate |
 * | `DAC_EQ_RESTORE` | writes the backup, reads it back, compares (the app's own slot is left alone) | yes, through the gate |
 * | `DAC_EQ_RESTORE_FACTORY` | gives USER 3 its factory curve back and lets go of it | yes, through the gate; **audible**, playback paused (refused otherwise) |
 * | `SET_DAC_EQ_WRITES_ALLOWED --ez allowed true\|false` | the persisted override that lifts "verified" | no |
 * | `SET_DAC_EQ_PRE_GAIN_SENSE --es sense boost\|attenuation\|unconfirmed` | the persisted answer to "what does a positive global gain do", after the listening check | no |
 *
 * The write commands also take `--es crc maxim|zero` and `--es save slot|empty|none`: the two things the sources disagree on, as a way out
 * if the DAC proves to want the other form (`none` sends no save at all, for the unplug experiment). Extras are required where they say so; a missing or misspelled one changes nothing.
 */
internal object DacEqDebugCommands {
    const val ACTION_READ = "com.lostf1sh.pixelplayeross.debug.DAC_EQ_READ"
    const val ACTION_BACKUP = "com.lostf1sh.pixelplayeross.debug.DAC_EQ_BACKUP"
    const val ACTION_DISCOVER_SLOT = "com.lostf1sh.pixelplayeross.debug.DAC_EQ_DISCOVER_SLOT"
    const val ACTION_ROUNDTRIP = "com.lostf1sh.pixelplayeross.debug.DAC_EQ_ROUNDTRIP"
    const val ACTION_SET_BAND = "com.lostf1sh.pixelplayeross.debug.DAC_EQ_SET_BAND"
    const val ACTION_RESTORE = "com.lostf1sh.pixelplayeross.debug.DAC_EQ_RESTORE"
    const val ACTION_SET_WRITES_ALLOWED = "com.lostf1sh.pixelplayeross.debug.SET_DAC_EQ_WRITES_ALLOWED"
    const val ACTION_INVENTORY = "com.lostf1sh.pixelplayeross.debug.DAC_EQ_INVENTORY"
    const val ACTION_TAKE_OVER = "com.lostf1sh.pixelplayeross.debug.DAC_EQ_TAKE_OVER"
    const val ACTION_LISTEN_CHECK = "com.lostf1sh.pixelplayeross.debug.DAC_EQ_LISTEN_CHECK"
    const val ACTION_RESTORE_FACTORY = "com.lostf1sh.pixelplayeross.debug.DAC_EQ_RESTORE_FACTORY"
    const val ACTION_SET_PRE_GAIN_SENSE = "com.lostf1sh.pixelplayeross.debug.SET_DAC_EQ_PRE_GAIN_SENSE"

    val ACTIONS = setOf(
        ACTION_READ, ACTION_BACKUP, ACTION_DISCOVER_SLOT, ACTION_ROUNDTRIP, ACTION_SET_BAND, ACTION_RESTORE, ACTION_SET_WRITES_ALLOWED,
        ACTION_INVENTORY, ACTION_TAKE_OVER, ACTION_LISTEN_CHECK, ACTION_RESTORE_FACTORY, ACTION_SET_PRE_GAIN_SENSE,
    )

    fun run(context: Context, intent: Intent, sink: (String) -> Unit) {
        val service = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, DualPlayerEngineDebugEntryPoint::class.java).dacEqService()
        }.getOrNull() ?: return sink("DAC_HID|DAC_EQ: the app's object graph is not up yet")
        val action = intent.action ?: return

        if (action == ACTION_SET_WRITES_ALLOWED) {
            if (!intent.hasExtra(EXTRA_ALLOWED)) {
                sink("DAC_HID|SET_DAC_EQ_WRITES_ALLOWED needs --ez allowed true|false; unchanged")
            } else {
                DacEqWriteSwitch.shared(context).set(intent.getBooleanExtra(EXTRA_ALLOWED, false))
            }
            sink("DAC_HID|DAC_EQ_WRITES_ALLOWED override=${service.writeSwitch.isAllowed()} (a debug build's persisted switch; a release build ignores it)")
            return
        }

        if (action == ACTION_SET_PRE_GAIN_SENSE) {
            val sense = PreGainSense.parse(intent.getStringExtra(EXTRA_SENSE))
            val switch = DacEqPreGainSenseSwitch.shared(context)
            if (sense == null) {
                sink("DAC_HID|SET_DAC_EQ_PRE_GAIN_SENSE needs --es sense boost|attenuation|unconfirmed; unchanged")
            } else {
                switch.set(sense)
            }
            sink("DAC_HID|DAC_EQ_PRE_GAIN_SENSE override=${switch.override() ?: "none"} (a debug build's persisted answer to the listening check; a release build ignores it)")
            return
        }

        val att = service.attached()
            ?: return sink("DAC_HID|no FiiO/Snowsky DAC the app can ask is attached (the DAC must be connected; Direct need not be up for a debug command)")
        sink("DAC_HID|${action.substringAfterLast('.')} dac=${att.displayName} profile=${att.profile.id} verified=${att.profile.verified} " +
            "override=${service.writeSwitch.isAllowed()} preGainSense=${service.sense(att)} playing=${service.isPlaying()} availability=${service.availability(att)}")

        val crc = DacEqDebugSteps.parseCrc(intent.getStringExtra(EXTRA_CRC)).getOrElse { return sink("DAC_HID|${it.message}; nothing sent") }
        val save = DacEqDebugSteps.parseSave(intent.getStringExtra(EXTRA_SAVE)).getOrElse { return sink("DAC_HID|${it.message}; nothing sent") }
        val options = FiioWriteOptions(crc ?: FiioWriteOptions().crcMode, save ?: FiioWriteOptions().saveForm)
        if (crc != null || save != null) sink("DAC_HID|write options: crc=${options.crcMode} save=${options.saveForm}")
        val writer = service.writer(options, sink)

        when (action) {
            ACTION_READ -> runBlocking { service.read(att, sink) }
            ACTION_BACKUP -> {
                val replace = intent.getBooleanExtra(EXTRA_REPLACE, false)
                when (val result = runBlocking { writer.backup(att, replace) }) {
                    is DacEqBackupResult.Created -> sink("DAC_HID|BACKUP stored: key=${att.backupKey} active=0x%02X slots=${result.backup.slots.keys.map { "0x%02X".format(it) }}".format(result.backup.activePresetId))
                    is DacEqBackupResult.Exists -> sink("DAC_HID|BACKUP exists (taken ${result.backup.takenAtEpochMs}) and was KEPT; use --ez replace true to take a new one (the old one is archived)")
                    is DacEqBackupResult.NotReadable -> sink("DAC_HID|BACKUP not stored: the state could not be read completely")
                    is DacEqBackupResult.StoreFailed -> sink("DAC_HID|BACKUP not stored: the file could not be written and read back")
                    is DacEqBackupResult.Unavailable -> sink("DAC_HID|BACKUP unavailable: ${result.why}")
                }
            }
            ACTION_DISCOVER_SLOT -> report(sink, runBlocking { writer.discoverSlot(att) })
            ACTION_INVENTORY -> report(sink, runBlocking { writer.inventory(att, WriteInitiator.REVIEWER_COMMAND) })
            ACTION_TAKE_OVER -> report(sink, runBlocking { writer.takeOver(att, WriteInitiator.REVIEWER_COMMAND) })
            ACTION_RESTORE_FACTORY -> report(sink, runBlocking { writer.restoreFactory(att, WriteInitiator.REVIEWER_COMMAND) })
            ACTION_LISTEN_CHECK -> {
                if (!intent.getBooleanExtra(EXTRA_LOW_LEVEL, false)) {
                    sink("DAC_HID|DAC_EQ_LISTEN_CHECK puts the output 6 dB above where it was for 5 s: set the DAC's own level low by hand and send --ez low_level true; nothing sent")
                } else {
                    report(sink, runBlocking { writer.listeningCheck(att, lowLevel = true) })
                }
            }
            ACTION_ROUNDTRIP -> report(sink, runBlocking { writer.roundtrip(att, WriteInitiator.REVIEWER_COMMAND) })
            ACTION_RESTORE -> report(sink, runBlocking { writer.restore(att, WriteInitiator.REVIEWER_COMMAND) })
            ACTION_SET_BAND -> {
                val band = if (intent.hasExtra(EXTRA_BAND)) intent.getIntExtra(EXTRA_BAND, -1) else null
                val gain = if (intent.hasExtra(EXTRA_GAIN)) intent.getFloatExtra(EXTRA_GAIN, Float.NaN) else null
                when (val step = DacEqDebugSteps.parseSetBand(band, gain, att.profile.bandCount)) {
                    is DacEqDebugSteps.SetBand.Refused -> sink("DAC_HID|DAC_EQ_SET_BAND ${step.why}; nothing sent")
                    is DacEqDebugSteps.SetBand.Ok ->
                        report(sink, runBlocking { writer.setBand(att, step.bandIndex, step.gainDb, WriteInitiator.REVIEWER_COMMAND) })
                }
            }
        }
        if (service.availability(att) == DacEqWriteAvailability.NEEDS_VERIFICATION && action in WRITING) {
            sink("DAC_HID|(writes are refused while the profile is unverified: send SET_DAC_EQ_WRITES_ALLOWED --ez allowed true first)")
        }
    }

    private val WRITING = setOf(
        ACTION_DISCOVER_SLOT, ACTION_ROUNDTRIP, ACTION_SET_BAND, ACTION_RESTORE, ACTION_INVENTORY, ACTION_TAKE_OVER, ACTION_LISTEN_CHECK, ACTION_RESTORE_FACTORY,
    )

    private fun report(sink: (String) -> Unit, result: DacEqWriteResult) {
        sink("DAC_HID|RESULT $result")
        if (result is DacEqWriteResult.Done) DacEqStateFormat.lines(result.state, "DAC_HID|FINAL").forEach(sink)
    }

    const val EXTRA_ALLOWED = "allowed"
    const val EXTRA_SENSE = "sense"
    const val EXTRA_LOW_LEVEL = "low_level"
    const val EXTRA_REPLACE = "replace"
    const val EXTRA_BAND = "band"
    const val EXTRA_GAIN = "gain"
    const val EXTRA_CRC = "crc"
    const val EXTRA_SAVE = "save"
}
