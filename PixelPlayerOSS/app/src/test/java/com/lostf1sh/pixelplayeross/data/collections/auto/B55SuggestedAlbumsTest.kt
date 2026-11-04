package com.lostf1sh.pixelplayeross.data.collections.auto

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.stats.*
import org.junit.jupiter.api.Test

class B55SuggestedAlbumsTest {
    private val now=Kit.at(2026,11,2,20)
    private val regularIds=listOf(1L,10L,11L,12L,20L,21L,22L,23L,30L,31L,32L)
    private val songs=(regularIds+99L).flatMap { album -> (1..if(album==99L) 2 else 4).map { track ->
        val id=album*10+track
        Kit.song(id,artist=when(album) { 1L,10L,11L,12L -> "Atlas"; in 30L..32L -> "Past $album"; else -> "New $album" },album="Synthetic album $album",albumId=album)
            .copy(dateAdded=album*1000+track)
    } }
    private val events=(0..7).map { Kit.play(11,now-it*Kit.DAY) } + (30L..32L).flatMap { album ->
        (0..2).map { Kit.play(album*10+1,now-(120+it)*Kit.DAY) }
    }
    private val repeat=setOf("item-11","item-12","item-13")
    @Suppress("UNCHECKED_CAST")
    private fun build(ledger:ListeningLedger,day:Long=42,repeatIds:Set<String> = if(ledger.isEmpty) emptySet() else repeat):Pair<List<Album>,Map<String,Int>> {
        val cls=try { Class.forName("com.lostf1sh.pixelplayeross.data.collections.auto.SuggestedAlbums") } catch(_:ClassNotFoundException) { null }
        org.junit.jupiter.api.Assertions.assertNotNull(cls,"Suggested albums must be built from the actual ledger")
        val result=cls!!.getMethod("build",ListeningLedger::class.java,Long::class.javaPrimitiveType,Long::class.javaPrimitiveType,Set::class.java)
            .invoke(cls.getField("INSTANCE").get(null),ledger,now,day,repeatIds)
        return (result.javaClass.getMethod("getAlbums").invoke(result) as List<Album>) to (result.javaClass.getMethod("getSourceCounts").invoke(result) as Map<String,Int>)
    }
    @Test fun `Suggested albums is stable during the day and changes next day`():Unit {
        val ledger=Kit.ledger(events,Kit.catalog(songs));val first=build(ledger)
        assertThat(build(ledger)).isEqualTo(first)
        assertThat(build(ledger,43).first.map { it.id }).isNotEqualTo(first.first.map { it.id })
    }
    @Test fun `the three sources fill ten albums without mostly repeated albums or short releases`():Unit {
        val result=build(Kit.ledger(events,Kit.catalog(songs)))
        assertThat(result.first).hasSize(10)
        assertThat(result.first.map { it.id }).containsNoneOf(1L,99L)
        assertThat(result.second).containsExactly("familiar_artist",3,"never_played",4,"rediscover",3)
        println("B55 Suggested albums synthetic sourceCounts=${result.second}")
    }
    @Test fun `short releases only fill holes after all full albums`():Unit {
        val subset=songs.filter { it.albumId in listOf(1L,10L,20L,30L,99L) }
        val result=build(Kit.ledger(events,Kit.catalog(subset))).first
        assertThat(result.last().id).isEqualTo(99L)
        assertThat(result.dropLast(1).all { it.songCount >= 3 }).isTrue()
    }
    @Test fun `without history the newest full albums fill first`():Unit {
        val result=build(Kit.ledger(emptyList(),Kit.catalog(songs)))
        assertThat(result.first.map { it.id }).containsExactlyElementsIn(regularIds.sortedDescending().take(10)).inOrder()
        assertThat(result.second).containsExactly("newest",10)
    }
    @Test fun `mature eight hundred song library records its album source counts`():Unit {
        val tracks=SyntheticHistory.songs(800).mapIndexed { index,s -> s.copy(artist="Synthetic artist ${index%16}",
            artists=listOf(com.lostf1sh.pixelplayeross.data.model.ArtistRef((index%16).toLong(),"Synthetic artist ${index%16}",true))) }
        val ledger=Kit.ledger(SyntheticHistory.events(3000,800,now,70),Kit.catalog(tracks,firstSeen=tracks.associate { it.libraryItemId to now-300*Kit.DAY }))
        val sources=AutoCollectionEngine.compute(ledger,now,Kit.zone)
        val result=build(ledger,repeatIds=sources.getValue(AutoCollectionId.ON_REPEAT).items.mapTo(hashSetOf()) { it.identity })
        assertThat(result.first).hasSize(10)
        println("B55 Suggested albums mature800 listens3000 sourceCounts=${result.second}")
    }

}
