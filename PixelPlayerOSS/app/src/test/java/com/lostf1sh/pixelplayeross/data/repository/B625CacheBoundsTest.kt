package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class B625CacheBoundsTest {
    @Test fun rejectedLyricsDiagnosticsHaveAFiniteBudget() {
        val policy = com.lostf1sh.pixelplayeross.data.lyrics.LyricsTextPolicy
        @Suppress("UNCHECKED_CAST")
        val entries = policy.javaClass.getDeclaredField("dropped").apply { isAccessible = true }.get(policy) as MutableSet<Long>
        repeat(20000) { entries.add(it.toLong()) }
        assertTrue(entries.size <= 1024)
        entries.clear()
    }

    @Test fun paletteGenerationsCannotGrowOrAuthorizeAnEvictedTicket() {
        val currency = com.lostf1sh.pixelplayeross.presentation.viewmodel.PaletteCacheCurrency()
        val old = currency.begin("synthetic-old", forced = true)
        repeat(20000) { currency.end(currency.begin("synthetic-$it", forced = false)) }
        @Suppress("UNCHECKED_CAST")
        val entries = currency.javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(currency) as Map<String, *>
        assertTrue(entries.size <= 8192)
        assertTrue(currency.mayWrite(old)) // Active writes keep one lock even under pressure.
        currency.end(old)
        repeat(20000) { currency.end(currency.begin("later-$it", forced = false)) }
        assertTrue(!currency.mayWrite(old))
        val latest = currency.begin("synthetic-old", forced = false)
        assertTrue(currency.mayWrite(latest))
    }

    @Test fun directoryListingsHaveAFiniteBudget() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() })
        val explorer = com.lostf1sh.pixelplayeross.presentation.viewmodel.FileExplorerStateHolder(mockk(relaxed = true), scope, ApplicationProvider.getApplicationContext<Context>())
        @Suppress("UNCHECKED_CAST")
        val entries = explorer.javaClass.getDeclaredField("directoryChildrenCache").apply { isAccessible = true }.get(explorer) as MutableMap<String, List<Any>>
        repeat(20000) { entries["/synthetic/$it"] = emptyList() }
        assertTrue(entries.size <= 256)
    }

    @Test fun lyricsNegativeResultsAndArtistFailuresHaveAFiniteSessionBudget() {
        val lyrics = LyricsRepositoryImpl(ApplicationProvider.getApplicationContext<Context>(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        for (name in listOf("remoteNotFound", "remoteNoFit")) {
            @Suppress("UNCHECKED_CAST")
            val set = lyrics.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(lyrics) as MutableSet<String>
            repeat(20000) { set.add("synthetic-$it") }
            assertTrue("$name retained ${set.size} keys", set.size <= 8192)
        }
        @Suppress("UNCHECKED_CAST")
        val failures = lyrics.javaClass.getDeclaredField("remoteFailedAtMs").apply { isAccessible = true }.get(lyrics) as MutableMap<String, Long>
        repeat(20000) { failures["synthetic-$it"] = it.toLong() }
        assertTrue(failures.size <= 8192)
        val artists = ArtistImageRepository(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        @Suppress("UNCHECKED_CAST")
        val failed = artists.javaClass.getDeclaredField("failedFetches").apply { isAccessible = true }.get(artists) as MutableSet<String>
        repeat(20000) { failed.add("synthetic-$it") }
        assertTrue(failed.size <= 8192)
    }
}
