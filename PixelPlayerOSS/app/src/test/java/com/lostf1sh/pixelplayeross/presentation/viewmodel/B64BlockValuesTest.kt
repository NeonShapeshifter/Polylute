package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import org.junit.jupiter.api.Test

class B64BlockValuesTest {
    @Test fun `an old Library destination cannot clear a newer request or its mark`() = kotlinx.coroutines.test.runTest {
        val locator = LibraryLocator(this, resolveIndex = { 2 }, onNotFound = {})
        locator.request("old")
        val old = locator.request.value!!
        locator.request("new")
        val current = locator.request.value!!
        locator.clear(old.token)
        assertThat(locator.request.value).isEqualTo(current)
        locator.take(current)
        locator.complete(current)
        locator.clear(old.token)
        assertThat(locator.located.value).isEqualTo("new")
        locator.clear(current.token)
        assertThat(locator.located.value).isNull()
    }

    @Test fun `a missing placeholder never shifts the following block source`() {
        val songs = (1..5).map { Song.emptySong().copy(id = it.toString()) }
        assertThat(retainBlockValues(songs, listOf(songs[1],songs[2],songs[4]), listOf("A","A","B","B","C")))
            .containsExactly("A","B","C").inOrder()
    }
    @Test fun `repeated songs keep a separate source per occurrence`() {
        val song = Song.emptySong().copy(id = "42")
        assertThat(retainBlockValues(listOf(song,song),listOf(song,song),listOf("artist1","artist2")))
            .containsExactly("artist1","artist2").inOrder()
    }
}
