package com.lostf1sh.pixelplayeross.data.preferences

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** B57 5 (#52), the decisions that do not need a phone: off by default in both modes, the folder is asked for only when the switch is turned on (Android 13+), cancelling keeps it off, and an album outside the chosen folder shows its still cover. */
class B57AnimatedCoverFolderTest {
    @Test
    fun `off by default in app mode and in DAP mode`() {
        assertThat(AnimatedCovers.defaultEnabled(dapMode = false)).isFalse()
        assertThat(AnimatedCovers.defaultEnabled(dapMode = true)).isFalse()
        assertThat(AnimatedCovers.effective(null, dapMode = false)).isFalse()
        assertThat(AnimatedCovers.effective(null, dapMode = true)).isFalse()
    }

    @Test
    fun `turning it on asks for the folder only on Android 13 and later and only when there is none`() {
        assertThat(AnimatedCovers.turnOn(sdk = 35, hasFolder = false)).isEqualTo(AnimatedCovers.TurnOn.ASK_FOR_FOLDER) // the HONOR
        assertThat(AnimatedCovers.turnOn(sdk = 33, hasFolder = false)).isEqualTo(AnimatedCovers.TurnOn.ASK_FOR_FOLDER) // the S22
        assertThat(AnimatedCovers.turnOn(sdk = 35, hasFolder = true)).isEqualTo(AnimatedCovers.TurnOn.ENABLE)
        // API 29-32: no picker, the existing storage permission reads the files
        for (sdk in 29..32) assertThat(AnimatedCovers.turnOn(sdk, hasFolder = false)).isEqualTo(AnimatedCovers.TurnOn.ENABLE)
        assertThat(AnimatedCovers.needsFolderGrant(32)).isFalse()
        assertThat(AnimatedCovers.needsFolderGrant(33)).isTrue()
    }

    @Test
    fun `cancelling the picker leaves it off`() {
        assertThat(AnimatedCovers.enabledAfterPicker(folderChosen = false)).isFalse()
        assertThat(AnimatedCovers.enabledAfterPicker(folderChosen = true)).isTrue()
    }

    @Test
    fun `an album folder is inside the chosen folder by whole path segments, or it is outside and shows the still cover`() {
        val root = "/storage/emulated/0/DAP music"
        assertThat(AnimatedCovers.segmentsInTree("/storage/emulated/0/DAP music/Artist One/[2023] Album One", root)).containsExactly("Artist One", "[2023] Album One").inOrder()
        assertThat(AnimatedCovers.segmentsInTree(root, root)).isEmpty()
        assertThat(AnimatedCovers.segmentsInTree("/storage/emulated/0/DAP music 2/Artist One", root)).isNull()   // a sibling with the same prefix is not inside
        assertThat(AnimatedCovers.segmentsInTree("/storage/emulated/0/Download/Polylute portadas animadas", root)).isNull()
        assertThat(AnimatedCovers.segmentsInTree("/storage/emulated/0/DAP music/../Secret", root)).isNull()
        assertThat(AnimatedCovers.segmentsInTree("/anything", "")).isNull()
    }
}
