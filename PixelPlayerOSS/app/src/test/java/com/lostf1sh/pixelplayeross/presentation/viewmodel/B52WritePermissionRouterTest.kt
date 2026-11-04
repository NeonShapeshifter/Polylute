package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class B52WritePermissionRouterTest {
    @Test fun `lyrics cannot replace any pending metadata request or consume its result`() {
        for (owner in WritePermissionOwner.entries.filter { it != WritePermissionOwner.LYRICS }) {
            val router = WritePermissionRouter()
            assertThat(router.claim(owner)).isTrue()
            assertThat(router.claim(WritePermissionOwner.LYRICS)).isFalse()
            assertThat(router.take()).isEqualTo(owner)
            assertThat(router.take()).isNull()
        }
    }

    @Test fun `metadata cannot replace pending lyrics and a second save tap cannot request again`() {
        val router = WritePermissionRouter()
        assertThat(router.claim(WritePermissionOwner.LYRICS)).isTrue()
        assertThat(router.isPending).isTrue()
        WritePermissionOwner.entries.forEach { assertThat(router.claim(it)).isFalse() }
        assertThat(router.take()).isEqualTo(WritePermissionOwner.LYRICS)
        assertThat(router.isPending).isFalse()
        assertThat(router.claim(WritePermissionOwner.METADATA)).isTrue()
        assertThat(router.take()).isEqualTo(WritePermissionOwner.METADATA)
    }
}
