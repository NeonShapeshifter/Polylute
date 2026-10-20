package com.lostf1sh.pixelplayeross.data.collections.auto

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.google.gson.GsonBuilder
import com.lostf1sh.pixelplayeross.data.backup.module.GlobalSettingsModuleHandler
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.Kit
import com.lostf1sh.pixelplayeross.data.stats.Kit.DAY
import com.lostf1sh.pixelplayeross.data.stats.Kit.at
import com.lostf1sh.pixelplayeross.data.stats.LedgerProvider
import com.lostf1sh.pixelplayeross.data.stats.LibraryChangeSignal
import com.lostf1sh.pixelplayeross.data.stats.ListeningLedger
import com.lostf1sh.pixelplayeross.presentation.viewmodel.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * What the repository promises on top of the rules (B28): the mix of the day stays the same all day and changes the next, a swipe on
 * Home takes a song out for the rest of the day, and hiding a collection is a preference that survives a restart and a backup.
 * Everything here is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoCollectionRepositoryTest {

    private class FakeLedgers(var ledger: ListeningLedger) : LedgerProvider {
        override val changes: Flow<Long> = emptyFlow()
        override suspend fun ledger(): ListeningLedger = ledger
    }

    private val day1 = at(2026, 10, 15, 20)
    private val day2 = day1 + DAY

    /** Sixty tracks that arrived ten days ago and were never played: enough for the mix to have something to choose from. */
    private fun library(extra: Int = 0) = Kit.catalog(
        (1..60 + extra).map { Kit.song(it.toLong(), artist = "Artist ${it % 12}") },
        firstSeen = (1..60 + extra).associate { "item-$it" to day1 - 10 * DAY },
    )

    private fun ledger(extra: Int = 0) = ListeningLedger.build(emptyList(), library(extra))

    /** A library of exactly [count] tracks, none played, all new. */
    private fun ledgerOfSize(count: Int): ListeningLedger = ListeningLedger.build(
        emptyList(),
        Kit.catalog((1..count).map { Kit.song(it.toLong()) }, firstSeen = (1..count).associate { "item-$it" to day1 - 10 * DAY }),
    )

    private class Rig(
        val repository: AutoCollectionRepository,
        val preferences: UserPreferencesRepository,
        val ledgers: FakeLedgers,
    )

    private fun TestScope.preferencesOf(file: java.io.File) = UserPreferencesRepository(
        dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }),
        json = Json,
    )

    /** A repository over [preferences]: building another over the same preferences is what a restart of the app does. */
    private fun TestScope.repositoryOver(preferences: UserPreferencesRepository, ledgers: FakeLedgers, now: () -> Long): Rig {
        val music = mockk<MusicRepository>(relaxed = true)
        coEvery { music.getSongIdsSorted(any(), any()) } returns emptyList()
        val repository = AutoCollectionRepository(
            ledgers = ledgers,
            preferences = preferences,
            musicRepository = music,
            librarySignal = LibraryChangeSignal(),
            dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler)),
            scope = backgroundScope,
        )
        repository.clock = now
        repository.zone = { Kit.zone }
        return Rig(repository, preferences, ledgers)
    }

    private fun TestScope.rig(
        ledgers: FakeLedgers,
        file: java.io.File,
        now: () -> Long,
    ): Rig = repositoryOver(preferencesOf(file), ledgers, now)

    /** The state flow is collected on the test scheduler, so what was just written is read after it has run. */
    private fun TestScope.state(rig: Rig): AutoCollectionsState {
        runCurrent()
        return rig.repository.state.value
    }

    private fun TestScope.dailyItems(rig: Rig) = state(rig).collection(AutoCollectionId.DAILY_MIX)!!.items

    private fun TestScope.daily(rig: Rig) = dailyItems(rig).map { it.identity }

    // --- the mix of the day -----------------------------------------------------------------------------------

    @Test
    fun `the mix of the day is the same all day even when the library changes and another the next day`() = runTest {
        val dir = Files.createTempDirectory("auto-mix-test")
        try {
            var now = day1
            val rig = rig(FakeLedgers(ledger()), dir.resolve("settings.preferences_pb").toFile()) { now }
            rig.repository.refresh("test")
            val first = daily(rig)
            assertThat(first).isNotEmpty()

            // Ten more tracks arrive and the library is rebuilt on the same day: the list given today does not move.
            rig.ledgers.ledger = ledger(extra = 10)
            now = day1 + 3 * 60 * 60 * 1000L
            rig.repository.refresh("library changed")
            assertThat(daily(rig)).isEqualTo(first)

            // The next day is another mix, made from that day's seed.
            now = day2
            rig.repository.refreshIfDayChanged()
            val second = daily(rig)
            assertThat(second).isNotEmpty()
            assertThat(second).isNotEqualTo(first)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a song swiped out of the mix stays out for the day and is not hidden from the next one`() = runTest {
        val dir = Files.createTempDirectory("auto-mix-swipe-test")
        try {
            var now = day1
            val rig = rig(FakeLedgers(ledger()), dir.resolve("settings.preferences_pb").toFile()) { now }
            rig.repository.refresh("test")
            val before = dailyItems(rig)
            val removed = before.first()

            rig.repository.removeFromDailyMix(removed.locator)
            assertThat(daily(rig)).doesNotContain(removed.identity)
            assertThat(daily(rig)).hasSize(before.size - 1)

            // A rebuild the same day gives the saved list back, without the swiped song.
            rig.repository.refresh("library changed")
            assertThat(daily(rig)).doesNotContain(removed.identity)

            // Nothing is remembered against the song for another day.
            now = day2
            rig.repository.refreshIfDayChanged()
            assertThat(dailyItems(rig)).isNotEmpty()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // --- a mix emptied by the listener stays empty for the day (R28-05) ---------------------------------------------

    @Test
    fun `a library of one song whose only song was swiped out keeps an empty mix through a refresh and a restart`() = runTest {
        val dir = Files.createTempDirectory("auto-mix-one-song-test")
        try {
            var now = day1
            val onlySong = FakeLedgers(ledgerOfSize(1))
            val rig = rig(onlySong, dir.resolve("settings.preferences_pb").toFile()) { now }
            rig.repository.refresh("test")
            val before = dailyItems(rig)
            assertThat(before).hasSize(1)

            rig.repository.removeFromDailyMix(before.single().locator)
            assertThat(daily(rig)).isEmpty()

            // A rebuild the same day, a library change, and a new repository over the same store: the song does not come back.
            rig.repository.refresh("library changed")
            assertThat(daily(rig)).isEmpty()
            assertThat(rig.repository.homeMixes.value.daily).isEmpty()
            val restarted = repositoryOver(rig.preferences, onlySong) { now + 60 * 60 * 1000L }
            restarted.repository.refresh("restart")
            assertThat(daily(restarted)).isEmpty()
            assertThat(restarted.repository.homeMixes.value.daily).isEmpty()

            // The next day is another mix, made of what the library holds.
            now = day2
            restarted.repository.refreshIfDayChanged()
            assertThat(daily(restarted)).hasSize(1)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a full mix swiped out one song at a time stays empty for the day and a new one is made the next`() = runTest {
        val dir = Files.createTempDirectory("auto-mix-all-test")
        try {
            var now = day1
            val library = FakeLedgers(ledger())
            val rig = rig(library, dir.resolve("settings.preferences_pb").toFile()) { now }
            rig.repository.refresh("test")
            val before = dailyItems(rig)
            assertThat(before).hasSize(AutoCollectionEngine.DAILY_SIZE)

            before.forEachIndexed { index, item ->
                rig.repository.removeFromDailyMix(item.locator)
                assertThat(daily(rig)).hasSize(before.size - index - 1)
            }
            assertThat(daily(rig)).isEmpty()

            rig.repository.refresh("library changed")
            assertThat(daily(rig)).isEmpty()
            val restarted = repositoryOver(rig.preferences, library) { now + 3 * 60 * 60 * 1000L }
            restarted.repository.refresh("restart")
            assertThat(daily(restarted)).isEmpty()
            // Asking for it to be fresh does not make another one either: it is the same day.
            restarted.repository.ensureFresh(maxAgeMs = 0L)
            assertThat(daily(restarted)).isEmpty()

            now = day2
            restarted.repository.refreshIfDayChanged()
            assertThat(daily(restarted)).hasSize(AutoCollectionEngine.DAILY_SIZE)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a mix whose tracks have all left the library is not replaced by another that day and comes back with them`() = runTest {
        val dir = Files.createTempDirectory("auto-mix-left-test")
        try {
            val now = day1
            val library = FakeLedgers(ledger())
            val rig = rig(library, dir.resolve("settings.preferences_pb").toFile()) { now }
            rig.repository.refresh("test")
            val first = daily(rig)
            assertThat(first).isNotEmpty()

            // A rescan that, for a moment, holds none of them (another library with the same size and other identities).
            library.ledger = ListeningLedger.build(
                emptyList(),
                Kit.catalog(
                    (1..60).map { Kit.song(1000L + it, artist = "Artist ${it % 12}") },
                    firstSeen = (1..60).associate { "item-${1000 + it}" to day1 - 10 * DAY },
                ),
            )
            rig.repository.refresh("library changed")
            assertThat(daily(rig)).isEmpty()

            library.ledger = ledger()
            rig.repository.refresh("library back")
            assertThat(daily(rig)).isEqualTo(first)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a day that has not changed does not rebuild and one that has does`() = runTest {
        val dir = Files.createTempDirectory("auto-mix-day-test")
        try {
            var now = day1
            val rig = rig(FakeLedgers(ledger()), dir.resolve("settings.preferences_pb").toFile()) { now }
            rig.repository.refresh("test")
            val builtAt = rig.repository.builtAtMs
            assertThat(builtAt).isEqualTo(day1)

            now = day1 + 60_000L
            rig.repository.refreshIfDayChanged()
            assertThat(rig.repository.builtAtMs).isEqualTo(builtAt)

            now = day2
            rig.repository.refreshIfDayChanged()
            assertThat(rig.repository.builtAtMs).isEqualTo(day2)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // --- hiding -----------------------------------------------------------------------------------------------

    @Test
    fun `hiding is a preference that a new repository on the same file still reads, and showing brings it back`() = runTest {
        val dir = Files.createTempDirectory("auto-hide-test")
        try {
            val file = dir.resolve("settings.preferences_pb").toFile()
            val first = rig(FakeLedgers(ledger()), file) { day1 }
            first.repository.refresh("test")
            first.preferences.setAutoCollectionHidden(AutoCollectionId.NIGHT.key, true)
            first.preferences.setAutoCollectionHidden(AutoCollectionId.NIGHT.key, true)
            first.preferences.setAutoCollectionHidden(AutoCollectionId.A_YEAR_AGO.key, true)

            val state = state(first)
            assertThat(state.hidden).containsExactly(AutoCollectionId.NIGHT, AutoCollectionId.A_YEAR_AGO)
            assertThat(state.visible.map { it.id }).containsNoneOf(AutoCollectionId.NIGHT, AutoCollectionId.A_YEAR_AGO)
            // Hidden is only hidden: the collection is still built, still has its page, and is not renamed or removed.
            assertThat(state.collections.map { it.id }).containsExactlyElementsIn(AutoCollectionId.entries)

            first.preferences.setAutoCollectionHidden(AutoCollectionId.NIGHT.key, false)
            assertThat(first.preferences.hiddenAutoCollectionsFlow.first()).containsExactly(AutoCollectionId.A_YEAR_AGO.key)

            first.preferences.showAllAutoCollections()
            assertThat(first.preferences.hiddenAutoCollectionsFlow.first()).isEmpty()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a key this build does not know is ignored and the rest still hide`() = runTest {
        val dir = Files.createTempDirectory("auto-hide-unknown-test")
        try {
            val rig = rig(FakeLedgers(ledger()), dir.resolve("settings.preferences_pb").toFile()) { day1 }
            rig.repository.refresh("test")
            rig.preferences.setAutoCollectionHidden("a_collection_from_the_future", true)
            rig.preferences.setAutoCollectionHidden(AutoCollectionId.DEEP_CUTS.key, true)

            assertThat(state(rig).hidden).containsExactly(AutoCollectionId.DEEP_CUTS)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the hidden collections travel with a backup of the settings and come back on restore`() = runTest {
        val dirA = Files.createTempDirectory("auto-backup-a")
        val dirB = Files.createTempDirectory("auto-backup-b")
        try {
            val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
            val source = rig(FakeLedgers(ledger()), dirA.resolve("settings.preferences_pb").toFile()) { day1 }
            source.preferences.setAutoCollectionHidden(AutoCollectionId.HIGH_RESOLUTION.key, true)
            source.preferences.setAutoCollectionHidden(AutoCollectionId.NEVER_PLAYED.key, true)
            val payload = GlobalSettingsModuleHandler(source.preferences, gson).export()
            assertThat(payload).contains("hidden_auto_collections")

            // A phone that never hid anything: restoring the backup hides the same two, and no others.
            val target = rig(FakeLedgers(ledger()), dirB.resolve("settings.preferences_pb").toFile()) { day1 }
            assertThat(target.preferences.hiddenAutoCollectionsFlow.first()).isEmpty()
            GlobalSettingsModuleHandler(target.preferences, gson).restore(payload)
            assertThat(target.preferences.hiddenAutoCollectionsFlow.first())
                .containsExactly(AutoCollectionId.HIGH_RESOLUTION.key, AutoCollectionId.NEVER_PLAYED.key)

            // And a restore of a backup made before collections could be hidden shows them all again.
            val older = payload.replace("hidden_auto_collections", "some_other_setting")
            GlobalSettingsModuleHandler(target.preferences, gson).restore(older)
            assertThat(target.preferences.hiddenAutoCollectionsFlow.first()).isEmpty()
        } finally {
            dirA.toFile().deleteRecursively()
            dirB.toFile().deleteRecursively()
        }
    }
}
