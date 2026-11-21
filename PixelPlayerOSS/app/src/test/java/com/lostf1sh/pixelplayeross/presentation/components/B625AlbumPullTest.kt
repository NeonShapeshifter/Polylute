package com.lostf1sh.pixelplayeross.presentation.components
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
class B625AlbumPullTest {
    @Test fun `pull at top enlarges artwork to page width while list offset stays zero`() {
        var height = 300f
        var listOffset = 0f
        val c = AlbumHeaderScrollConnection({ height }, { height = it }, { listOffset == 0f }, 64f, 300f, 400f)
        val consumed = c.onPreScroll(Offset(0f, 80f), NestedScrollSource.UserInput)
        listOffset += 80f - consumed.y
        assertThat(height).isEqualTo(380f)
        assertThat(listOffset).isEqualTo(0f)
        assertThat(c.onPreScroll(Offset(0f, 100f), NestedScrollSource.UserInput).y).isEqualTo(20f)
        assertThat(height).isEqualTo(400f)
    }
    @Test fun `normal list scroll is untouched after header collapses and when returning from below`() {
        var height = 64f
        var offset = 400f
        val c = AlbumHeaderScrollConnection({ height }, { height = it }, { offset == 0f }, 64f, 300f, 400f)
        assertThat(c.onPreScroll(Offset(0f, -40f), NestedScrollSource.UserInput)).isEqualTo(Offset.Zero)
        assertThat(c.onPreScroll(Offset(0f, 40f), NestedScrollSource.UserInput)).isEqualTo(Offset.Zero)
        offset = 0f
        assertThat(c.onPreScroll(Offset(0f, 40f), NestedScrollSource.UserInput).y).isEqualTo(40f)
        assertThat(height).isEqualTo(104f)
    }
}
