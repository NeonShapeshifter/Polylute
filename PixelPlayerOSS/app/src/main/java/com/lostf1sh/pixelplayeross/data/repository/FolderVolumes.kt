package com.lostf1sh.pixelplayeross.data.repository

import com.lostf1sh.pixelplayeross.data.database.FolderSongRow

internal data class FolderVolumes(val hasInternal: Boolean, val sdRoots: Set<String>, val internalRoot: String) {
    val roots: List<String> get() = (if (hasInternal) listOf(internalRoot) else emptyList()) + sdRoots.sorted()
    val showSwitch: Boolean get() = roots.size > 1

    companion object {
        fun resolve(paths: List<String>, internalRoot: String, platformSdRoot: String?): FolderVolumes {
            val rows = paths.mapIndexed { index, path -> FolderSongRow(index.toLong(), path, "", null) }
            val roots = FolderTreeBuilder().inferRemovableStorageRoots(rows, internalRoot, setOfNotNull(platformSdRoot))
            return FolderVolumes(paths.any { it == internalRoot || it.startsWith(internalRoot.trimEnd('/') + "/") }, roots, internalRoot)
        }
    }
}
