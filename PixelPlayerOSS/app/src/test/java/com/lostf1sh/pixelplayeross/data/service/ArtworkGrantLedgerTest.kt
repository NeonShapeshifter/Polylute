package com.lostf1sh.pixelplayeross.data.service

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * B23-P4: `grantUriPermission` ran for every connected controller and SystemUI on every song change (~9 ms of a ~17 ms transition).
 * The same URI to the same package is granted once.
 */
class ArtworkGrantLedgerTest {
    private val systemUi = "com.android.systemui"
    private val art1 = "content://com.lostf1sh.pixelplayeross.artwork/1"
    private val art2 = "content://com.lostf1sh.pixelplayeross.artwork/2"

    /** What the service does around one grant, against a counter instead of the platform. */
    private class Granter(val ledger: ArtworkGrantLedger, val platformAccepts: Boolean = true) {
        var platformCalls = 0
        fun grant(target: String, uri: String) {
            if (!ledger.needsGrant(target, uri)) return
            platformCalls++
            if (platformAccepts) ledger.recordGranted(target, uri)
        }
    }

    @Test
    fun `the same URI and package grant once`() {
        val granter = Granter(ArtworkGrantLedger())
        repeat(10) { granter.grant(systemUi, art1) }
        assertThat(granter.platformCalls).isEqualTo(1)
        assertThat(granter.ledger.skipped).isEqualTo(9L)
    }

    @Test
    fun `a new URI, or a new package, is granted`() {
        val granter = Granter(ArtworkGrantLedger())
        granter.grant(systemUi, art1)
        granter.grant(systemUi, art2)                      // the next song's artwork
        granter.grant("com.example.controller", art2)       // a controller that connected since
        granter.grant("com.example.controller", art2)
        assertThat(granter.platformCalls).isEqualTo(3)
    }

    @Test
    fun `a refused grant is not remembered, so the next song tries again`() {
        val refusing = Granter(ArtworkGrantLedger(), platformAccepts = false)
        refusing.grant(systemUi, art1)
        refusing.grant(systemUi, art1)
        assertThat(refusing.platformCalls).isEqualTo(2)
        assertThat(refusing.ledger.size).isEqualTo(0)
    }

    @Test
    fun `the ledger is bounded and forgets the least recently used, which only costs one more grant`() {
        val ledger = ArtworkGrantLedger(capacity = 3)
        val granter = Granter(ledger)
        listOf("a", "b", "c").forEach { granter.grant(systemUi, "content://x/$it") }
        granter.grant(systemUi, "content://x/a")               // touched: now the most recent
        granter.grant(systemUi, "content://x/d")               // evicts b
        assertThat(ledger.size).isEqualTo(3)
        val before = granter.platformCalls
        granter.grant(systemUi, "content://x/a")               // still held
        assertThat(granter.platformCalls).isEqualTo(before)
        granter.grant(systemUi, "content://x/b")               // forgotten: granted again
        assertThat(granter.platformCalls).isEqualTo(before + 1)
    }
}
