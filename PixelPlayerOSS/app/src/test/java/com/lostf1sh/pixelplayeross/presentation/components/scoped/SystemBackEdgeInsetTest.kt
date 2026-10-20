package com.lostf1sh.pixelplayeross.presentation.components.scoped

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The reserved system back-gesture strip is the single definition of "this gesture belongs to
 * Back". Everything that has to stay out of the platform's way asks this question, so it is worth
 * pinning down independently of any Compose tree.
 */
class SystemBackEdgeInsetTest {

    private val width = 720f
    private val edge = SystemBackEdgeInset(leftPx = 48f, rightPx = 48f)

    @Test
    fun `a down inside the left strip belongs to back`() {
        assertThat(edge.owns(0f, width)).isTrue()
        assertThat(edge.owns(47f, width)).isTrue()
    }

    @Test
    fun `a down inside the right strip belongs to back`() {
        assertThat(edge.owns(720f, width)).isTrue()
        assertThat(edge.owns(673f, width)).isTrue()
    }

    @Test
    fun `a down in the middle belongs to the surface, not back`() {
        assertThat(edge.owns(360f, width)).isFalse()
        assertThat(edge.owns(100f, width)).isFalse()
        assertThat(edge.owns(620f, width)).isFalse()
    }

    @Test
    fun `the strip is inclusive at its boundary, matching the original carousel behaviour`() {
        // 48 px is the last pixel *inside* the left strip and 672 the first inside the right one.
        // The comparison is `<=`/`>=` on purpose: this is the semantics the album carousel has
        // always used, and changing it here would silently move the boundary on that surface.
        assertThat(edge.owns(48f, width)).isTrue()
        assertThat(edge.owns(672f, width)).isTrue()
        assertThat(edge.owns(49f, width)).isFalse()
        assertThat(edge.owns(671f, width)).isFalse()
    }

    @Test
    fun `the strips are measured from the live width, so a rotated surface still excludes them`() {
        // Same inset, narrower surface: the right strip must move with the width, not be a constant.
        assertThat(edge.owns(600f, 640f)).isTrue()
        assertThat(edge.owns(300f, 640f)).isFalse()
    }
}
