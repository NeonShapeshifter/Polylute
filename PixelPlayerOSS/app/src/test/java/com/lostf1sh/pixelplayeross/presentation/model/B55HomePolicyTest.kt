package com.lostf1sh.pixelplayeross.presentation.model

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.stats.*
import org.junit.jupiter.api.Test

/** Adapters reproduce the cited pre-B55 branches when the new pure function does not exist in the before copy. */
class B55HomePolicyTest {
    private val now=Kit.at(2026,11,2,20)
    private fun type(name:String):Class<*>? = try { Class.forName(name) } catch(_:ClassNotFoundException) { null }
    @Suppress("UNCHECKED_CAST")
    private fun source(curated:List<String>,daily:List<String>,preview:List<String>):List<String> {
        val cls=type("com.lostf1sh.pixelplayeross.presentation.model.HomeSuggestionsPolicyKt")
            ?: return curated.ifEmpty { daily.ifEmpty { preview } }
        return cls.getMethod("homeMixSource",List::class.java,List::class.java,List::class.java).invoke(null,curated,daily,preview) as List<String>
    }
    @Suppress("UNCHECKED_CAST")
    private fun preview(items:List<String>,saved:List<String>,dap:Boolean,seed:Long,previous:List<String> = emptyList()):List<String> {
        val cls=type("com.lostf1sh.pixelplayeross.presentation.model.HomeSuggestionsPolicyKt") ?: return items.take(3)
        return cls.getMethod("homeSuggestionPreview",List::class.java,List::class.java,Boolean::class.javaPrimitiveType,Long::class.javaPrimitiveType,List::class.java)
            .invoke(null,items,saved,dap,seed,previous) as List<String>
    }
    private fun counts(ms:Long,length:Long):Boolean {
        val cls=type("com.lostf1sh.pixelplayeross.data.stats.RecentListenRule") ?: return ms >= 5000
        return cls.getMethod("counts",Long::class.javaPrimitiveType,Long::class.javaPrimitiveType).invoke(cls.getField("INSTANCE").get(null),ms,length) as Boolean
    }
    @Suppress("UNCHECKED_CAST")
    private fun recent(ledger:ListeningLedger):List<RecentlyPlayedSongUiModel> {
        val cls=type("com.lostf1sh.pixelplayeross.data.stats.RecentListeningKt")
        if(cls != null) return cls.getMethod("recentListening",ListeningLedger::class.java,Long::class.javaPrimitiveType).invoke(null,ledger,now) as List<RecentlyPlayedSongUiModel>
        val history=ledger.occurrences.asReversed().filter { it.listenedMs >= 5000 }.take(500).mapNotNull { o ->
            o.item?.let { PlaybackStatsRepository.PlaybackHistoryEntry(it.locator,o.endMs,libraryItemId=it.identity) }
        }
        return mapRecentlyPlayedSongs(history,ledger.catalog.items.map { it.song },nowMillis=now)
    }
    @Test fun `H01 empty Suggestions never borrows Daily Mix`():Unit {
        assertThat(source(emptyList(),listOf("daily"),listOf("new"))).isEmpty()
        assertThat(source(emptyList(),emptyList(),listOf("new"))).containsExactly("new")
    }
    @Test fun `three Suggestions change on an app open and DAP keeps its saved sample until refresh`():Unit {
        val items=(0 until 60).map { "$it" };val saved=listOf("4","10","20")
        val first=preview(items,saved,false,1)
        val second=preview(items,saved,false,2,first)
        assertThat(second).isNotEqualTo(first)
        assertThat(preview(items,saved,true,1)).isEqualTo(preview(items,saved,true,2))
        assertThat(preview(items,saved,true,2)).isEqualTo(saved)
        assertThat(preview(items,listOf("5","11","21"),true,2)).isNotEqualTo(saved)
        assertThat(first.map(items::indexOf)).isInOrder()
    }
    @Test fun `recent listening requires thirty seconds or the whole shorter track`():Unit {
        assertThat(counts(29_000,240_000)).isFalse()
        assertThat(counts(30_000,240_000)).isTrue()
        assertThat(counts(19_000,20_000)).isFalse()
        assertThat(counts(20_000,20_000)).isTrue()
        assertThat(counts(4000,4000)).isTrue()
    }
    @Test fun `month history sees six hundred entries and retains events after a clock rollback`():Unit {
        val songs=(1L..600L).map { Kit.song(it) }
        val events=songs.mapIndexed { i,s -> Kit.event(s.id.toLong(),now-i*90_000L,60_000,identity=s.libraryItemId,itemMs=s.duration) }
        val all=recent(Kit.ledger(events,Kit.catalog(songs)))
        // All are in this month; the before adapter is the screen's 500-entry source.
        assertThat(all).hasSize(600)
        val rangeMethod=Class.forName("com.lostf1sh.pixelplayeross.presentation.model.RecentlyPlayedSongUiKt")
            .methods.firstOrNull { it.name=="filterRecentListening" }
        val month=if(rangeMethod == null) all else rangeMethod.invoke(null,all,StatsTimeRange.MONTH,now,Kit.zone) as List<*>
        assertThat(month).hasSize(600)
        val future=Kit.ledger(listOf(Kit.play(1,now+Kit.DAY)),Kit.catalog(songs))
        assertThat(recent(future)).hasSize(1)
        assertThat(recent(future).single().lastPlayedTimestamp).isAtMost(now)
    }
    @Test fun `Recent artists is newest first unique and splits collaborations like Artists`():Unit {
        val a=Kit.song(1).copy(artist="Alba, Boreal",artists=emptyList())
        val b=Kit.song(2).copy(artist="Boreal, Cobre",artists=listOf(ArtistRef(2,"Boreal",true),ArtistRef(3,"Cobre")))
        val recent=listOf(RecentlyPlayedSongUiModel(a,now),RecentlyPlayedSongUiModel(b,now-1000),RecentlyPlayedSongUiModel(a,now-2000))
        val cls=type("com.lostf1sh.pixelplayeross.data.stats.RecentListeningKt")
        @Suppress("UNCHECKED_CAST")
        fun credits(items:List<RecentlyPlayedSongUiModel>):List<ArtistRef> = if(cls == null) emptyList() else
            cls.getMethod("recentArtistCredits",List::class.java,List::class.java).invoke(null,items,UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS) as List<ArtistRef>
        assertThat(credits(recent).map { it.name }).containsExactly("Alba","Boreal","Cobre").inOrder()
        assertThat(credits(emptyList())).isEmpty()
    }
}
