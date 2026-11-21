package com.lostf1sh.pixelplayeross.data.diagnostics

import com.decent.usbaudio.UsbDiagnosticJournal
import java.io.File

/** The native private journal is included in the existing, explicitly shared text attachment. */
object UsbIncidentJournal {
    fun read(filesDirectory: File): String = UsbDiagnosticJournal.export(filesDirectory)

    fun attach(text: String, journal: String): String = buildString(text.length + journal.length + 80) {
        append(text)
        if (!text.endsWith("\n")) appendLine()
        appendLine()
        appendLine("-- automatic USB incident journal (no media names, paths or PCM) --")
        append(journal)
        if (!journal.endsWith("\n")) appendLine()
    }
}
