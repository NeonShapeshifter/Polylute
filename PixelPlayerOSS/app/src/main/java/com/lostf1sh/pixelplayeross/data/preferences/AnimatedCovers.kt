package com.lostf1sh.pixelplayeross.data.preferences

/**
 * B56 phase 7.2 (#48): "Animated covers". One switch, stored only once the listener touches it ([ThemePreferencesRepository.animatedCoversStoredFlow]); until then it is **off** (B57: in both modes; B56 had it on in app
 * mode). A DAP is a single-purpose player whose CPU and battery are for the music (and the USB writer), so what moves on its screen is something the listener asks for.
 */
object AnimatedCovers {
    /**
     * The default, in **both** modes: off (B57 5, #52). B56 left it on in app mode; on Android 13+ a `cover.mp4` / `cover.webp` / `cover.gif` can only be read through a folder the
     * listener chose, and that is asked for **only when they turn the switch on**, so nothing is on (and nothing is asked) until they do.
     */
    @Suppress("UNUSED_PARAMETER")
    fun defaultEnabled(dapMode: Boolean): Boolean = false

    /** Android 13 (API 33) and later: the audio permission does not cover videos or images, so the covers are read through one folder the listener picks. Before that the storage permission reads them. */
    fun needsFolderGrant(sdk: Int): Boolean = sdk >= 33

    /** What turning the switch on does: switch on at once, or first ask for the folder (the system picker, `ACTION_OPEN_DOCUMENT_TREE`). */
    enum class TurnOn { ENABLE, ASK_FOR_FOLDER }

    fun turnOn(sdk: Int, hasFolder: Boolean): TurnOn = turnOn(needsFolderGrant(sdk), hasFolder)

    /** The same decision for a caller that already knows whether this Android needs the folder. */
    fun turnOn(needsFolder: Boolean, hasFolder: Boolean): TurnOn = if (needsFolder && !hasFolder) TurnOn.ASK_FOR_FOLDER else TurnOn.ENABLE

    /** The picker's answer: only a chosen folder turns the feature on; cancelling leaves it off and changes nothing else. */
    fun enabledAfterPicker(folderChosen: Boolean): Boolean = folderChosen

    /**
     * Where an album folder is inside the chosen [treeRootPath]: its path segments below the root (empty for the root itself), or null when the folder is outside it, which then simply
     * shows the still cover. Paths are compared by whole segments, so `/Music2` is not inside `/Music`.
     */
    fun segmentsInTree(albumFolderPath: String, treeRootPath: String): List<String>? {
        fun parts(path: String) = path.split('/').filter { it.isNotEmpty() }
        val root = parts(treeRootPath)
        val folder = parts(albumFolderPath)
        if (root.isEmpty() || folder.size < root.size) return null
        if (folder.take(root.size) != root) return null
        val below = folder.drop(root.size)
        return below.takeIf { rest -> rest.none { it == ".." || it == "." } }
    }

    /** What the switch shows and the player follows: the listener's choice, else the mode's default. */
    fun effective(stored: Boolean?, dapMode: Boolean): Boolean = stored ?: defaultEnabled(dapMode)
}
