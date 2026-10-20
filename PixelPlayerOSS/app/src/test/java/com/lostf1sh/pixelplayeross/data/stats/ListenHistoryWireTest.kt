package com.lostf1sh.pixelplayeross.data.stats

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.lostf1sh.pixelplayeross.data.backup.model.toBackupEntry
import com.lostf1sh.pixelplayeross.data.backup.model.toPlaybackEvent
import com.lostf1sh.pixelplayeross.data.backup.module.PlaybackHistoryModuleHandler
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * B28a: `playback_history.json` is a wire format. The new fields are additive and nullable, an event written before them reads as it
 * always did and is never rewritten, and what is recorded survives a write, a read, a backup and a restore.
 */
class ListenHistoryWireTest {

    // The backup gson as the app builds it (BackupModule): pretty-printed, nulls written.
    private val backupGson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    /** The three kinds of event the history now holds. */
    private val directPurity = PlaybackStatsRepository.PlaybackEvent(
        songId = "1412",
        timestamp = 1_792_259_509_512L,
        durationMs = 215_000L,
        startTimestamp = 1_792_259_294_512L,
        endTimestamp = 1_792_259_509_512L,
        libraryItemId = "3f2a9c1e-7b4d-4e0a-9c55-1d2e6a8b7f10",
        occurrenceId = "1kq8z3b9x4m2a",
        outcome = PlaybackOutcome.COMPLETED,
        endReason = TrackEndReason.NATURAL_END,
        itemDurationMs = 216_000L,
        audio = ListenAudioFacts(
            route = ListenRoute.USB_DIRECT,
            signal = ListenSignal.PURITY,
            bitPerfect = ListenBitPerfect.UNKNOWN,
            sourceRateHz = 96_000,
            sourceBits = 24,
            sourceCodec = "audio/flac",
            transportRateHz = 96_000,
            transportBits = 24,
            deviceName = "SNOWSKY Melody",
        ),
        context = ListenContext(kind = null, shuffle = false),
    )

    private val androidBluetooth = PlaybackStatsRepository.PlaybackEvent(
        songId = "2210",
        timestamp = 1_792_260_100_000L,
        durationMs = 95_000L,
        startTimestamp = 1_792_260_005_000L,
        endTimestamp = 1_792_260_100_000L,
        libraryItemId = "a81c04d2-33f0-4f7b-8e9a-6b5d0c7e2f44",
        occurrenceId = "3w9n0p2r5t7v1",
        outcome = PlaybackOutcome.SKIPPED,
        endReason = TrackEndReason.USER_NEXT,
        itemDurationMs = 241_000L,
        audio = ListenAudioFacts(
            route = ListenRoute.BLUETOOTH,
            bitPerfect = ListenBitPerfect.NO,
            sourceRateHz = 44_100,
            sourceCodec = "audio/mpeg",
        ),
        context = ListenContext(kind = null, shuffle = true),
    )

    private val externalView = PlaybackStatsRepository.PlaybackEvent(
        songId = "external:content://com.example.files/doc/9",
        timestamp = 1_792_261_000_000L,
        durationMs = 180_000L,
        startTimestamp = 1_792_260_820_000L,
        endTimestamp = 1_792_261_000_000L,
        occurrenceId = "7a1c3e5g9i2k4",
        outcome = PlaybackOutcome.PLAYED,
        endReason = TrackEndReason.INTERRUPTED,
        itemDurationMs = 260_000L,
        audio = ListenAudioFacts(route = ListenRoute.SPEAKER, bitPerfect = ListenBitPerfect.NO, sourceRateHz = 48_000, sourceCodec = "audio/mp4a-latm"),
        context = ListenContext(kind = ListenSourceKind.EXTERNAL_VIEW, shuffle = false),
    )

    private val oldEvent = PlaybackStatsRepository.PlaybackEvent(
        songId = "1412",
        timestamp = 1_792_259_509_512L,
        durationMs = 101_165L,
        startTimestamp = 1_792_259_408_347L,
        endTimestamp = 1_792_259_509_512L,
    )

    /** What a history file from before B28a holds: five fields per event, no more. */
    private val oldFile = """[{"songId":"215","timestamp":1788151772011,"durationMs":9507,"startTimestamp":1788151762504,"endTimestamp":1788151772011},""" +
        """{"songId":"1412","timestamp":1792259509512,"durationMs":101165,"startTimestamp":1792259408347,"endTimestamp":1792259509512}]"""

    private fun historyFile(dir: File) = File(dir, "playback_history.json")

    @AfterEach
    fun releaseFiles() = releaseHistoryFiles()

    private fun jsonKeys(file: File): List<Set<String>> =
        JsonParser.parseString(file.readText()).asJsonArray.map { it.asJsonObject.keySet() }

    @Test
    fun `an event written before the new fields reads unchanged and without them`() {
        val (repository, dir) = newHistoryRepository()
        historyFile(dir).writeText(oldFile)

        val events = runBlocking { repository.exportEventsForBackup() }

        assertThat(events).hasSize(2)
        assertThat(events[0]).isEqualTo(
            PlaybackStatsRepository.PlaybackEvent("215", 1_788_151_772_011L, 9_507L, 1_788_151_762_504L, 1_788_151_772_011L),
        )
        assertThat(events[1]).isEqualTo(oldEvent.copy(durationMs = 101_165L))
        assertThat(events.all { it.occurrenceId == null && it.outcome == null && it.endReason == null }).isTrue()
        assertThat(events.all { it.itemDurationMs == null && it.audio == null && it.context == null }).isTrue()
    }

    @Test
    fun `recording an event leaves the old ones in the file as they were`() {
        val (repository, dir) = newHistoryRepository()
        historyFile(dir).writeText(oldFile)

        runBlocking {
            repository.recordPlayback(
                songId = directPurity.songId,
                durationMs = directPurity.durationMs,
                timestamp = directPurity.timestamp,
                libraryItemId = directPurity.libraryItemId,
                occurrenceId = directPurity.occurrenceId,
                outcome = directPurity.outcome,
                endReason = directPurity.endReason,
                itemDurationMs = directPurity.itemDurationMs,
                audio = directPurity.audio,
                context = directPurity.context,
            )
        }

        val keys = jsonKeys(historyFile(dir))
        val oldKeys = setOf("songId", "timestamp", "durationMs", "startTimestamp", "endTimestamp")
        // Not rewritten to fill the old events in: they still hold exactly their five fields.
        assertThat(keys[0]).isEqualTo(oldKeys)
        assertThat(keys[1]).isEqualTo(oldKeys)
        assertThat(keys[2]).containsAtLeast("libraryItemId", "occurrenceId", "outcome", "endReason", "itemDurationMs", "audio", "context")
    }

    @Test
    fun `a new event round-trips through the write and the read`() {
        val (repository, _) = newHistoryRepository()

        listOf(directPurity, androidBluetooth, externalView).forEach { event ->
            runBlocking {
                repository.recordPlayback(
                    songId = event.songId,
                    durationMs = event.durationMs,
                    timestamp = event.timestamp,
                    libraryItemId = event.libraryItemId,
                    occurrenceId = event.occurrenceId,
                    outcome = event.outcome,
                    endReason = event.endReason,
                    itemDurationMs = event.itemDurationMs,
                    audio = event.audio,
                    context = event.context,
                )
            }
        }

        assertThat(runBlocking { repository.exportEventsForBackup() })
            .containsExactly(directPurity, androidBluetooth, externalView).inOrder()
    }

    @Test
    fun `the field names and the enum names in the file are the wire format`() {
        val json = Gson().toJson(listOf(directPurity))
        val event = JsonParser.parseString(json).asJsonArray[0].asJsonObject

        assertThat(event.keySet()).containsExactly(
            "songId", "timestamp", "durationMs", "startTimestamp", "endTimestamp",
            "libraryItemId", "occurrenceId", "outcome", "endReason", "itemDurationMs", "audio", "context",
        )
        assertThat(event["outcome"].asString).isEqualTo("COMPLETED")
        assertThat(event["endReason"].asString).isEqualTo("NATURAL_END")
        val audio = event["audio"].asJsonObject
        assertThat(audio.keySet()).containsExactly(
            "route", "signal", "bitPerfect", "sourceRateHz", "sourceBits", "sourceCodec", "transportRateHz", "transportBits", "deviceName",
        )
        assertThat(audio["route"].asString).isEqualTo("USB_DIRECT")
        assertThat(audio["signal"].asString).isEqualTo("PURITY")
        assertThat(audio["bitPerfect"].asString).isEqualTo("UNKNOWN")
        assertThat(event["context"].asJsonObject.keySet()).containsExactly("shuffle")
    }

    @Test
    fun `a null is not written, so an event only costs what it knows`() {
        val json = Gson().toJson(externalView)
        assertThat(json).doesNotContain("libraryItemId")
        assertThat(json).doesNotContain("null")
    }

    @Test
    fun `a value this build does not know reads as unrecorded and the event still reads`() {
        val (repository, dir) = newHistoryRepository()
        historyFile(dir).writeText(
            """[{"songId":"9","timestamp":2000,"durationMs":1000,"startTimestamp":1000,"endTimestamp":2000,""" +
                """"occurrenceId":"x1","outcome":"ABANDONED","endReason":"NATURAL_END","futureField":{"a":1},""" +
                """"audio":{"route":"SATELLITE","signal":"PURITY","bitPerfect":"MAYBE","sourceRateHz":44100,"codecFuture":7}}]""",
        )

        val event = runBlocking { repository.exportEventsForBackup() }.single()

        assertThat(event.outcome).isNull()
        assertThat(event.endReason).isEqualTo(TrackEndReason.NATURAL_END)
        assertThat(event.audio).isEqualTo(ListenAudioFacts(route = null, signal = ListenSignal.PURITY, bitPerfect = null, sourceRateHz = 44_100))
        assertThat(event.occurrenceId).isEqualTo("x1")
    }

    @Test
    fun `a sanitize pass keeps every new field and only bounds the damaged ones`() {
        val (repository, _) = newHistoryRepository()
        runBlocking {
            repository.importEventsFromBackup(listOf(directPurity, androidBluetooth, externalView), clearExisting = true)
        }
        val sanitised = runBlocking { repository.exportEventsForBackup() }
        assertThat(sanitised).containsExactly(directPurity, androidBluetooth, externalView).inOrder()
        sanitised.forEach { event ->
            assertThat(event.occurrenceId).isNotNull()
            assertThat(event.outcome).isNotNull()
            assertThat(event.endReason).isNotNull()
            assertThat(event.itemDurationMs).isNotNull()
            assertThat(event.audio).isNotNull()
            assertThat(event.context).isNotNull()
        }

        val (damagedRepository, _) = newHistoryRepository()
        runBlocking {
            damagedRepository.importEventsFromBackup(
                listOf(
                    directPurity.copy(
                        occurrenceId = " " + "z".repeat(200),
                        itemDurationMs = -5L,
                        audio = directPurity.audio!!.copy(
                            sourceRateHz = -1,
                            sourceCodec = "c".repeat(500),
                            share = 100,
                            deviceName = "   ",
                        ),
                    ),
                ),
                clearExisting = true,
            )
        }
        val bounded = runBlocking { damagedRepository.exportEventsForBackup() }.single()
        assertThat(bounded.occurrenceId).hasLength(64)
        assertThat(bounded.itemDurationMs).isNull()
        val boundedAudio = bounded.audio!!
        assertThat(boundedAudio.sourceRateHz).isNull()
        assertThat(boundedAudio.sourceCodec).hasLength(128)
        assertThat(boundedAudio.share).isNull()
        assertThat(boundedAudio.deviceName).isNull()
        // Everything else of the audio facts is as recorded.
        assertThat(boundedAudio.route).isEqualTo(ListenRoute.USB_DIRECT)
        assertThat(boundedAudio.transportBits).isEqualTo(24)
    }

    @Test
    fun `an event goes through the backup and back with every field`() {
        val (source, _) = newHistoryRepository()
        runBlocking {
            source.importEventsFromBackup(listOf(oldEvent, directPurity, androidBluetooth, externalView), clearExisting = true)
        }
        val exported = PlaybackHistoryModuleHandler(source, backupGson).let { runBlocking { it.export() } }

        val (restored, _) = newHistoryRepository()
        runBlocking { PlaybackHistoryModuleHandler(restored, backupGson).restore(exported) }

        assertThat(runBlocking { restored.exportEventsForBackup() })
            .containsExactlyElementsIn(runBlocking { source.exportEventsForBackup() }).inOrder()
        assertThat(runBlocking { restored.exportEventsForBackup() }).containsAtLeast(directPurity, androidBluetooth, externalView)
    }

    @Test
    fun `the backup entry and the history event convert into each other without loss`() {
        listOf(oldEvent, directPurity, androidBluetooth, externalView).forEach { event ->
            assertThat(event.toBackupEntry().toPlaybackEvent()).isEqualTo(event)
        }
    }

    @Test
    fun `a backup made before the new fields restores as it always did`() {
        val oldBackup = """
            [
              {"songId":"215","timestamp":1788151772011,"durationMs":9507,"startTimestamp":1788151762504,"endTimestamp":1788151772011},
              {"songId":"1412","timestamp":1792259509512,"durationMs":101165,"startTimestamp":null,"endTimestamp":null}
            ]
        """.trimIndent()
        val (repository, dir) = newHistoryRepository()

        runBlocking { PlaybackHistoryModuleHandler(repository, backupGson).restore(oldBackup) }

        val events = runBlocking { repository.exportEventsForBackup() }
        assertThat(events.map { it.songId }).containsExactly("215", "1412").inOrder()
        assertThat(events.all { it.audio == null && it.occurrenceId == null && it.outcome == null }).isTrue()
        assertThat(historyFile(dir).readText()).doesNotContain("occurrenceId")
    }

    @Test
    fun `the library identity the backup used to drop is kept now`() {
        val (source, _) = newHistoryRepository()
        runBlocking { source.importEventsFromBackup(listOf(directPurity), clearExisting = true) }
        val exported = runBlocking { PlaybackHistoryModuleHandler(source, backupGson).export() }

        assertThat(exported).contains(directPurity.libraryItemId)
        val array: JsonArray = JsonParser.parseString(exported).asJsonArray
        assertThat(array[0].asJsonObject["libraryItemId"].asString).isEqualTo(directPurity.libraryItemId)
    }

    // --- Size ---------------------------------------------------------------------------------------------------------------------

    private fun bytes(event: PlaybackStatsRepository.PlaybackEvent): Int = Gson().toJson(event).toByteArray(Charsets.UTF_8).size

    @Test
    fun `size per event before and after, and what two years of listening weigh`() {
        val before = bytes(oldEvent)
        val direct = bytes(directPurity)
        val android = bytes(androidBluetooth)
        val external = bytes(externalView)
        // The array adds one comma per event.
        val weighted = (direct + android + external) / 3.0 + 1
        val sixtyThousand = (weighted * 60_000 / 1_000_000.0)
        println(
            "B28a size: before=$before B, directPurity=$direct B, androidBluetooth=$android B, externalView=$external B, " +
                "mean=${"%.0f".format(weighted)} B, 60000 events = ${"%.1f".format(sixtyThousand)} MB (before: ${"%.1f".format((before + 1) * 60_000 / 1_000_000.0)} MB)",
        )

        println("B28a example, Direct Purity: " + Gson().toJson(directPurity))
        println("B28a example, Android route: " + Gson().toJson(androidBluetooth))
        println("B28a example, external VIEW: " + Gson().toJson(externalView))
        println("B28a example, before B28a: " + Gson().toJson(oldEvent))

        // A budget, not a measurement of anything else: the history is rewritten whole on every event, so growth is a cost to watch.
        assertThat(direct).isLessThan(600)
        assertThat(android).isLessThan(500)
        assertThat(external).isLessThan(500)
        assertThat(direct).isGreaterThan(before)
    }
}
