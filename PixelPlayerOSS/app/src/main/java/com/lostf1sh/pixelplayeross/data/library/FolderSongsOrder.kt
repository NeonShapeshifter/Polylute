package com.lostf1sh.pixelplayeross.data.library

import com.lostf1sh.pixelplayeross.data.model.MusicFolder
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SortOption
import com.lostf1sh.pixelplayeross.utils.TextFold
import java.io.File
import java.util.Locale

internal fun folderRelativePath(folder: MusicFolder): String {
    val root = folder.musicRootPath.trimEnd('/')
    return if (folder.path == root) File(root).name
    else folder.path.removePrefix("$root/").ifBlank { folder.name }
}

internal fun sortMusicFoldersByOption(folders: List<MusicFolder>, sortOption: SortOption): List<MusicFolder> {
    return when (sortOption) {
        SortOption.FolderPathAZ -> folders.sortedBy { folderRelativePath(it).lowercase(Locale.ROOT) }
        SortOption.FolderPathZA -> folders.sortedByDescending { folderRelativePath(it).lowercase(Locale.ROOT) }
        SortOption.FolderNameAZ -> folders.sortedWith(
            compareBy<MusicFolder> { it.name.lowercase() }
                .thenBy { it.path }
        )
        SortOption.FolderNameZA -> folders.sortedWith(
            compareByDescending<MusicFolder> { it.name.lowercase() }
                .thenBy { it.path }
        )
        SortOption.FolderSongCountAsc -> folders.sortedWith(
            compareBy<MusicFolder> { it.totalSongCount }
                .thenBy { it.name.lowercase() }
                .thenBy { it.path }
        )
        SortOption.FolderSongCountDesc -> folders.sortedWith(
            compareByDescending<MusicFolder> { it.totalSongCount }
                .thenBy { it.name.lowercase() }
                .thenBy { it.path }
        )
        SortOption.FolderSubdirCountAsc -> folders.sortedWith(
            compareBy<MusicFolder> { it.totalSubFolderCount }
                .thenBy { it.name.lowercase() }
                .thenBy { it.path }
        )
        SortOption.FolderSubdirCountDesc -> folders.sortedWith(
            compareByDescending<MusicFolder> { it.totalSubFolderCount }
                .thenBy { it.name.lowercase() }
                .thenBy { it.path }
        )
        else -> folders.sortedWith(
            compareBy<MusicFolder> { it.name.lowercase() }
                .thenBy { it.path }
        )
    }
}

private fun songFileName(song: Song): String {
    val path = song.path.trim()
    if (path.isEmpty()) return song.title
    val lastSlash = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    val name = if (lastSlash >= 0) path.substring(lastSlash + 1) else path
    return name.ifEmpty { song.title }
}

/**
 * True when [songs] belongs to a single non-blank album (same album name, and matching positive
 * `albumId`s when present).
 */
internal fun isOneAlbumFolder(songs: List<Song>): Boolean {
    if (songs.size <= 1) return false
    val first = songs.first()
    val firstAlbum = first.album.trim()
    if (firstAlbum.isEmpty()) return false
    val firstAlbumLower = firstAlbum.lowercase(Locale.ROOT)
    val firstAlbumId = first.albumId
    return songs.all { song ->
        val albumMatch = song.album.trim().lowercase(Locale.ROOT) == firstAlbumLower
        val idMatch = firstAlbumId <= 0L || song.albumId <= 0L || song.albumId == firstAlbumId
        albumMatch && idMatch
    }
}

/**
 * One-album folder order: numbered tracks first by disc, track number, title; untagged tracks
 * (0) after by title.
 */
private val albumFolderSongOrder: Comparator<Song> =
    compareBy<Song> { if (it.trackNumber > 0) 0 else 1 }
        .thenBy { it.discNumber ?: 0 }
        .thenBy { it.trackNumber }
        .thenBy { it.title.lowercase(Locale.ROOT) }
        .thenBy { it.artist.lowercase(Locale.ROOT) }
        .thenBy { it.id }

/**
 * Mixed folder order: by file name (case-insensitive), falling back to full path when names match.
 */
private val mixedFolderSongOrder: Comparator<Song> =
    compareBy<Song> { songFileName(it).lowercase(Locale.ROOT) }
        .thenBy { it.path.lowercase(Locale.ROOT) }
        .thenBy { it.path }
        .thenBy { it.title.lowercase(Locale.ROOT) }
        .thenBy { it.id }

/**
 * The single song order used by the folder view, floating Play/Shuffle discs, and whole-folder
 * sheet playback:
 * - One-album folder (every song has the same album): disc, track number, title.
 * - Mixed folder: file name case-insensitive, falling back to path.
 * - [SortOption.FolderNameZA] is the exact reverse of either order.
 */
internal fun sortSongsForFolderView(songs: List<Song>, sortOption: SortOption): List<Song> {
    if (songs.size <= 1) return songs.toList()
    val base = if (isOneAlbumFolder(songs)) albumFolderSongOrder else mixedFolderSongOrder
    return when (sortOption) {
        SortOption.FolderNameZA -> songs.sortedWith(base.reversed())
        else -> songs.sortedWith(base)
    }
}

private fun matchesFolderQuery(needle: String, vararg fields: String): Boolean =
    needle.isEmpty() || fields.any { needle in TextFold.fold(it) }

internal fun songMatchesFolderQuery(song: Song, needle: String): Boolean =
    matchesFolderQuery(needle, song.title, song.displayArtist, song.album, song.path)

/**
 * Whole-tree folder order matching what the screen shows (used by the folder long-press sheet,
 * folder playlist view, and folder card preview): in each folder, its subfolders come first in
 * [sortMusicFoldersByOption] order recursively, then its own songs in [sortSongsForFolderView]
 * order, each song at most once.
 */
fun folderSongsInOrder(folder: MusicFolder, sortOption: SortOption): List<Song> {
    val result = ArrayList<Song>()
    val seenIds = HashSet<String>()
    fun visit(current: MusicFolder) {
        for (subFolder in sortMusicFoldersByOption(current.subFolders, sortOption)) {
            visit(subFolder)
        }
        for (song in sortSongsForFolderView(current.songs, sortOption)) {
            if (seenIds.add(song.id)) {
                result.add(song)
            }
        }
    }
    visit(folder)
    return result
}

/**
 * Songs played by the floating Play and Shuffle discs inside [folder]:
 * - When [folder] has songs of its own, plays those songs in [sortSongsForFolderView] order,
 *   filtered by [query] (matching the list on screen; subfolders are excluded).
 * - When [folder] has no songs of its own (only subfolders), plays the visible subfolders' trees
 *   in [folderSongsInOrder] order so the discs remain useful.
 */
fun folderDiscSongsInOrder(
    folder: MusicFolder,
    sortOption: SortOption,
    query: String = "",
): List<Song> {
    val needle = TextFold.fold(query)
    if (folder.songs.isNotEmpty()) {
        val sorted = sortSongsForFolderView(folder.songs, sortOption)
        return if (needle.isEmpty()) sorted else sorted.filter { songMatchesFolderQuery(it, needle) }
    }
    val visibleSubFolders = sortMusicFoldersByOption(folder.subFolders, sortOption).let { subs ->
        if (needle.isEmpty()) subs else subs.filter { matchesFolderQuery(needle, it.name, folderRelativePath(it)) }
    }
    val result = ArrayList<Song>()
    val seenIds = HashSet<String>()
    for (sub in visibleSubFolders) {
        for (song in folderSongsInOrder(sub, sortOption)) {
            if (seenIds.add(song.id)) {
                result.add(song)
            }
        }
    }
    return result
}

/**
 * Reviewer 11-24: whether [folderDiscSongsInOrder] would give anything, without sorting the tree (the screen asks on every query or
 * folder change, on the main thread; a root with only subfolders used to sort the whole library for a yes).
 */
fun folderDiscHasSongs(folder: MusicFolder, query: String = ""): Boolean {
    val needle = TextFold.fold(query)
    if (folder.songs.isNotEmpty()) {
        return needle.isEmpty() || folder.songs.any { songMatchesFolderQuery(it, needle) }
    }
    return folder.subFolders.any { sub ->
        (needle.isEmpty() || matchesFolderQuery(needle, sub.name, folderRelativePath(sub))) && folderHasPlayableSongs(sub)
    }
}

fun folderHasPlayableSongs(folder: MusicFolder): Boolean {
    if (folder.songs.isNotEmpty()) return true
    return folder.subFolders.any(::folderHasPlayableSongs)
}
