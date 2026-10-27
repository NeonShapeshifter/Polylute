package com.lostf1sh.pixelplayeross.data.library

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.library.LibraryFolders.Rules
import com.lostf1sh.pixelplayeross.utils.DirectoryRuleResolver
import org.junit.jupiter.api.Test

/** B39-A9: the rules of "nothing unless included", as pure functions over paths. */
class LibraryFoldersTest {
    private val storage = "/storage/emulated/0"
    private val dap = "$storage/Music/DAP music"
    private val whatsapp = "$storage/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"

    /** The owner's library: one root with artist/album folders inside it. */
    private val ownerSongFolders = listOf(
        "$dap/Artist A/Album 1",
        "$dap/Artist A/Album 2",
        "$dap/Artist B/Album 3",
        "$dap/Artist C",
    )

    @Test
    fun `the blocked root turns every path out until a folder is included`() {
        val rules = Rules(allowed = emptySet(), blocked = setOf(LibraryFolders.NOTHING_BY_DEFAULT))
        assertThat(rules.includeOnly).isTrue()
        assertThat(rules.includes("$storage/Music/anything")).isFalse()
        assertThat(rules.includes("$storage/DCIM")).isFalse()
        val withDap = LibraryFolders.including(rules, listOf(dap))
        assertThat(withDap.includes("$dap/Artist A/Album 1")).isTrue()
        assertThat(withDap.includes("$storage/Music/Other folder")).isFalse()
    }

    @Test
    fun `an exclusion inside an included folder still wins and an exception inside that wins again`() {
        val rules = LibraryFolders.including(Rules(emptySet(), setOf("/")), listOf(dap))
            .let { it.copy(blocked = it.blocked + "$dap/Artist B") }
            .let { it.copy(allowed = it.allowed + "$dap/Artist B/Album 3") }
        assertThat(rules.includes("$dap/Artist A/Album 1")).isTrue()
        assertThat(rules.includes("$dap/Artist B/Album 4")).isFalse()
        assertThat(rules.includes("$dap/Artist B/Album 3")).isTrue()
    }

    @Test
    fun `the old rules read as they always did and the root block is only what makes them include-only`() {
        val old = Rules(allowed = emptySet(), blocked = setOf(whatsapp))
        assertThat(old.includeOnly).isFalse()
        assertThat(old.includes("$storage/Music/new folder")).isTrue()
        assertThat(old.includes(whatsapp)).isFalse()
        assertThat(DirectoryRuleResolver(emptySet(), setOf("/")).isBlocked("/storage/emulated/0/x")).isTrue()
        assertThat(DirectoryRuleResolver(setOf("/storage/emulated/0/x"), setOf("/")).isBlocked("/storage/emulated/0/x/y")).isFalse()
    }

    @Test
    fun `roots for the owner's library are the one folder that holds it, never the storage`() {
        val roots = LibraryFolders.rootsFor(ownerSongFolders, listOf(storage))
        assertThat(roots).containsExactly(dap)
    }

    @Test
    fun `a song right in the storage root is held only by the storage and separate top folders stay separate`() {
        assertThat(LibraryFolders.rootsFor(listOf(storage), listOf(storage))).containsExactly(storage)
        val roots = LibraryFolders.rootsFor(
            listOf("$storage/Music/A/x", "$storage/Music/A/y", "$storage/Download/Albums", "/storage/1234-ABCD/Music/B"),
            listOf(storage, "/storage/1234-ABCD"),
        )
        assertThat(roots).containsExactly("$storage/Music/A", "$storage/Download/Albums", "/storage/1234-ABCD/Music/B")
    }

    @Test
    fun `migrating the owner's library keeps every song in and the stray excluded folder out`() {
        val old = Rules(allowed = emptySet(), blocked = setOf(whatsapp))
        val migrated = LibraryFolders.migrate(old, ownerSongFolders, listOf(storage))!!
        assertThat(migrated.includeOnly).isTrue()
        assertThat(migrated.allowed).containsExactly(dap)
        // the old exclusion is kept
        assertThat(migrated.blocked).containsExactly(whatsapp, "/")
        ownerSongFolders.forEach { assertThat(migrated.includes(it)).isTrue() }
        assertThat(migrated.includes(whatsapp)).isFalse()
        // and what the old rules took in by default, because nobody had named it, is out now
        assertThat(migrated.includes("$storage/Music/A new folder")).isFalse()
        // nothing removed from the old sets
        assertThat(migrated.allowed).containsAtLeastElementsIn(old.allowed)
        assertThat(migrated.blocked).containsAtLeastElementsIn(old.blocked)
    }

    @Test
    fun `migrate gives every song folder the answer the old rules gave it`() {
        val old = Rules(allowed = setOf("$storage/Music/Podcasts/Keep"), blocked = setOf("$storage/Music/Podcasts", whatsapp))
        val dirs = ownerSongFolders + "$storage/Music/Podcasts/Keep/ep1"
        val migrated = LibraryFolders.migrate(old, dirs, listOf(storage))!!
        (dirs + whatsapp + "$storage/Music/Podcasts/Other").forEach { dir ->
            if (dir in dirs) assertThat(migrated.includes(dir)).isEqualTo(old.includes(dir))
        }
        assertThat(migrated.includes("$storage/Music/Podcasts/Other")).isFalse()
    }

    @Test
    fun `there is nothing to migrate from without songs and nothing to migrate twice`() {
        assertThat(LibraryFolders.migrate(Rules(emptySet(), emptySet()), emptyList(), listOf(storage))).isNull()
        assertThat(LibraryFolders.migrate(Rules(emptySet(), emptySet()), listOf("", " ".trim()), listOf(storage))).isNull()
        assertThat(LibraryFolders.migrate(Rules(setOf(dap), setOf("/")), ownerSongFolders, listOf(storage))).isNull()
    }

    @Test
    fun `including a folder lifts an exclusion of exactly that folder and keeps the ones inside it`() {
        val rules = Rules(allowed = emptySet(), blocked = setOf("/", whatsapp, "$whatsapp/Sent"))
        val next = LibraryFolders.including(rules, listOf(whatsapp))
        assertThat(next.allowed).containsExactly(whatsapp)
        assertThat(next.blocked).containsExactly("/", "$whatsapp/Sent")
        assertThat(next.includes(whatsapp)).isTrue()
        assertThat(next.includes("$whatsapp/Sent")).isFalse()
    }

    @Test
    fun `proposals are the folders with audio that are not in yet, gathered and counted`() {
        val audio = mapOf(
            "$dap/Artist A/Album 1" to 12,
            "$dap/Artist A/Album 2" to 10,
            whatsapp to 40,
            "$storage/Download/Albums" to 5,
            "$storage/Music/.hidden" to 3,
            "$storage/Music/Empty" to 0,
        )
        val rules = Rules(allowed = emptySet(), blocked = setOf("/", "$storage/Download"))
        val found = LibraryFolders.proposals(audio, rules, listOf(storage))
        // by name, so the list reads the same every time
        assertThat(found.map { it.path }).containsExactly(whatsapp, "$storage/Download/Albums", "$dap/Artist A").inOrder()
        assertThat(found.first { it.path == "$dap/Artist A" }.songCount).isEqualTo(22)
        assertThat(found.first { it.path == whatsapp }.songCount).isEqualTo(40)
    }

    @Test
    fun `included folders are not proposed again and old rules are judged as include-only`() {
        val audio = mapOf("$dap/Artist A/Album 1" to 12, "$storage/Music/New" to 4)
        val included = Rules(allowed = setOf(dap), blocked = setOf("/"))
        assertThat(LibraryFolders.proposals(audio, included, listOf(storage)).map { it.path }).containsExactly("$storage/Music/New")
        // rules that are not include-only yet: everything would count as in; the finder asks what is not chosen, so everything is offered,
        // gathered under the one folder that holds it
        val old = Rules(emptySet(), emptySet())
        val offered = LibraryFolders.proposals(audio, old, listOf(storage))
        assertThat(offered.map { it.path }).containsExactly("$storage/Music")
        assertThat(offered.single().songCount).isEqualTo(16)
    }
}
