package com.lostf1sh.pixelplayeross.utils

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Shared helper for computing allowed parent directories by applying blocked rules.
 */
object DirectoryFilterUtils {
    /** Folder choices can arrive before the first scan. Keep their projection tied to Room. */
    fun observeAllowedParentDirs(
        allowedDirs: Set<String>,
        blockedDirs: Set<String>,
        parentDirectories: () -> kotlinx.coroutines.flow.Flow<List<String>>,
        normalizePath: (String) -> String,
    ): kotlinx.coroutines.flow.Flow<Pair<List<String>, Boolean>> {
        if (blockedDirs.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList<String>() to false)
        return parentDirectories().distinctUntilChanged().map { parents ->
            computeAllowedParentDirs(allowedDirs, blockedDirs, { parents }, normalizePath)
        }.distinctUntilChanged()
    }

    suspend fun computeAllowedParentDirs(
        allowedDirs: Set<String>,
        blockedDirs: Set<String>,
        getAllParentDirs: suspend () -> List<String>,
        normalizePath: (String) -> String
    ): Pair<List<String>, Boolean> {
        if (blockedDirs.isEmpty()) return Pair(emptyList(), false)

        val resolver = DirectoryRuleResolver(
            allowedDirs.map(normalizePath).toSet(),
            blockedDirs.map(normalizePath).toSet()
        )
        val allParentDirs = getAllParentDirs()
        val allowedParentDirs = allParentDirs.filter { parentDir ->
            !resolver.isBlocked(normalizePath(parentDir))
        }
        return Pair(allowedParentDirs, true)
    }
}
