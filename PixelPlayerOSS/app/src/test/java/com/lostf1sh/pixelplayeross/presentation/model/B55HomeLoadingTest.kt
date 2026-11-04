package com.lostf1sh.pixelplayeross.presentation.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class B55HomeLoadingTest {
    private fun loaded(generated:Boolean,history:Boolean=true,needsFallback:Boolean=false,fallbackLoaded:Boolean=true):Boolean {
        val method=runCatching { Class.forName("com.lostf1sh.pixelplayeross.presentation.model.HomeSuggestionsPolicyKt").methods.single { it.name=="homeContentLoaded" } }.getOrNull()
        // The old timer hid the skeleton after 1.2 seconds even when the mixes had not finished.
        return method?.invoke(null,generated,history,needsFallback,fallbackLoaded) as? Boolean ?: true
    }
    @Test fun `H11 unfinished mixes keep the skeleton even after the former loading timer elapsed`():Unit {
        assertThat(loaded(false)).isFalse()
        assertThat(loaded(true,false)).isFalse()
        assertThat(loaded(true,true,true,false)).isFalse()
        assertThat(loaded(true,true,true,true)).isTrue()
        assertThat(loaded(true)).isTrue()
    }
}
