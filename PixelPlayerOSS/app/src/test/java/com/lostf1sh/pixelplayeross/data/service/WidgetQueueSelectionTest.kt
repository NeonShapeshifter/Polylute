package com.lostf1sh.pixelplayeross.data.service

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** AUD-103: the widget's PLAY_FROM_QUEUE matched only numeric ids; `external:` items never played. */
class WidgetQueueSelectionTest {
    private val queue = listOf("101", "external:abc/def", "303")

    @Test
    fun `a media id is matched exactly, numeric or not`() {
        assertThat(WidgetQueueSelection.indexOf(queue, "external:abc/def", -1L)).isEqualTo(1)
        assertThat(WidgetQueueSelection.indexOf(queue, "303", -1L)).isEqualTo(2)
    }

    @Test
    fun `an older widget sending only the numeric id still works`() {
        assertThat(WidgetQueueSelection.indexOf(queue, null, 101L)).isEqualTo(0)
    }

    @Test
    fun `nothing matching is -1`() {
        assertThat(WidgetQueueSelection.indexOf(queue, "external:zzz", -1L)).isEqualTo(-1)
        assertThat(WidgetQueueSelection.indexOf(queue, null, -1L)).isEqualTo(-1)
    }
}
