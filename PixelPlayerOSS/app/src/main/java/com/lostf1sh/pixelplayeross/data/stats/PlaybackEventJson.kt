package com.lostf1sh.pixelplayeross.data.stats

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository.PlaybackEvent
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * The reader of `playback_history.json` (B28).
 *
 * The file is a JSON array of [PlaybackEvent]s, written by Gson from the data class (so the key names are the property
 * names, which the R8 keep rules of B28a pin). It is **read** here, one event at a time, by hand: a history of two years is
 * 60 000 events and ~27 MB, and building a tree of it first (what the repository did) or going through reflection per
 * event costs more than the Stats screen is allowed (D9). Reading is also where the file's two promises are kept:
 *
 *  - **An old event is read as it is.** Every key but `songId`, `timestamp` and `durationMs` may be missing; an event written
 *    before B28a has none of the later ones and they come out `null`. A key this build does not know is skipped, and an enum
 *    name it does not know reads as `null` (an event written by a newer build is still an event).
 *  - **A torn file is read up to the tear.** An append (see `PlaybackStatsRepository`) rewrites the closing bracket in place,
 *    so a process that dies in the middle of one leaves an array that never closes. Every complete event before the tear is
 *    returned and [Result.intact] says the file needs rewriting; nothing that was written is lost to the one that was not.
 */
internal object PlaybackEventJson {

    /** The events a file held, in file order, and whether the file ended the way a whole one does. */
    class Result(val events: ArrayList<PlaybackEvent>, val intact: Boolean)

    fun read(input: InputStream): Result {
        val events = ArrayList<PlaybackEvent>()
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8).buffered(READ_BUFFER))
        try {
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                // Not an array: nothing in it is a history, and rewriting it is the repair.
                return Result(events, intact = false)
            }
            reader.beginArray()
            while (reader.hasNext()) {
                readEvent(reader)?.let(events::add)
            }
            reader.endArray()
            return Result(events, intact = true)
        } catch (_: EOFException) {
            return Result(events, intact = false)
        } catch (_: java.io.IOException) {
            // `MalformedJsonException` and a failed read are both IOExceptions: the tear is somewhere after the last event.
            return Result(events, intact = false)
        } catch (_: IllegalStateException) {
            return Result(events, intact = false)
        } catch (_: NumberFormatException) {
            return Result(events, intact = false)
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun readEvent(reader: JsonReader): PlaybackEvent? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var songId: String? = null
        var timestamp = 0L
        var durationMs = 0L
        var startTimestamp: Long? = null
        var endTimestamp: Long? = null
        var libraryItemId: String? = null
        var occurrenceId: String? = null
        var outcome: PlaybackOutcome? = null
        var endReason: TrackEndReason? = null
        var itemDurationMs: Long? = null
        var audio: ListenAudioFacts? = null
        var context: ListenContext? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "songId" -> songId = reader.stringOrNull()
                "timestamp" -> timestamp = reader.longOrNull() ?: 0L
                "durationMs" -> durationMs = reader.longOrNull() ?: 0L
                "startTimestamp" -> startTimestamp = reader.longOrNull()
                "endTimestamp" -> endTimestamp = reader.longOrNull()
                "libraryItemId" -> libraryItemId = reader.stringOrNull()
                "occurrenceId" -> occurrenceId = reader.stringOrNull()
                "outcome" -> outcome = reader.enumOrNull<PlaybackOutcome>()
                "endReason" -> endReason = reader.enumOrNull<TrackEndReason>()
                "itemDurationMs" -> itemDurationMs = reader.longOrNull()
                "audio" -> audio = readAudio(reader)
                "context" -> context = readContext(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val id = songId?.takeIf { it.isNotBlank() } ?: return null
        return PlaybackEvent(
            songId = id,
            timestamp = timestamp,
            durationMs = durationMs,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            libraryItemId = libraryItemId,
            occurrenceId = occurrenceId,
            outcome = outcome,
            endReason = endReason,
            itemDurationMs = itemDurationMs,
            audio = audio,
            context = context,
        )
    }

    private fun readAudio(reader: JsonReader): ListenAudioFacts? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var facts = ListenAudioFacts()
        reader.beginObject()
        while (reader.hasNext()) {
            facts = when (reader.nextName()) {
                "route" -> facts.copy(route = reader.enumOrNull<ListenRoute>())
                "signal" -> facts.copy(signal = reader.enumOrNull<ListenSignal>())
                "bitPerfect" -> facts.copy(bitPerfect = reader.enumOrNull<ListenBitPerfect>())
                "sourceRateHz" -> facts.copy(sourceRateHz = reader.longOrNull()?.toInt())
                "sourceBits" -> facts.copy(sourceBits = reader.longOrNull()?.toInt())
                "sourceCodec" -> facts.copy(sourceCodec = reader.stringOrNull())
                "transportRateHz" -> facts.copy(transportRateHz = reader.longOrNull()?.toInt())
                "transportBits" -> facts.copy(transportBits = reader.longOrNull()?.toInt())
                "transportCodec" -> facts.copy(transportCodec = reader.stringOrNull())
                "deviceName" -> facts.copy(deviceName = reader.stringOrNull())
                "share" -> facts.copy(share = reader.longOrNull()?.toInt())
                else -> {
                    reader.skipValue()
                    facts
                }
            }
        }
        reader.endObject()
        return facts
    }

    private fun readContext(reader: JsonReader): ListenContext? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var context = ListenContext()
        reader.beginObject()
        while (reader.hasNext()) {
            context = when (reader.nextName()) {
                "kind" -> context.copy(kind = reader.enumOrNull<ListenSourceKind>())
                "ref" -> context.copy(ref = reader.stringOrNull())
                "shuffle" -> context.copy(shuffle = reader.booleanOrNull())
                else -> {
                    reader.skipValue()
                    context
                }
            }
        }
        reader.endObject()
        return context
    }

    private fun JsonReader.stringOrNull(): String? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.longOrNull(): Long? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.NUMBER -> {
            // A number written as 1.7922595095E12 (a double) is still a time; take its value.
            val raw = nextString()
            raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
        }
        JsonToken.STRING -> nextString().toLongOrNull()
        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.booleanOrNull(): Boolean? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.BOOLEAN -> nextBoolean()
        else -> {
            skipValue()
            null
        }
    }

    private inline fun <reified E : Enum<E>> JsonReader.enumOrNull(): E? {
        val name = stringOrNull() ?: return null
        return enumValues<E>().firstOrNull { it.name == name }
    }

    private const val READ_BUFFER = 64 * 1024
}
