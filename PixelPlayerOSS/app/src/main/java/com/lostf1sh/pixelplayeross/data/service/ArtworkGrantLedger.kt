package com.lostf1sh.pixelplayeross.data.service

/**
 * Which artwork URIs have already been granted to which package (B23-P4).
 *
 * `grantUriPermission` is a Binder call into the activity manager, and the service made one per connected controller *and one
 * for SystemUI* on every song change, on the main thread, for a URI each of them already held: ~9 ms of a ~17 ms transition on the
 * HONOR. A grant given with `grantUriPermission` stays until it is revoked or the device restarts (the app revokes none), and it
 * belongs to the package, not to the controller connection, so a second grant of the same URI to the same package changes nothing.
 *
 * So the service grants on **connection** (the controller's own `onConnect` grants the current item) and on **a URI the package
 * has not been given**, and only records a grant once the platform accepted it, so a refusal is tried again at the next song.
 * Bounded: after [capacity] entries the least recently used is forgotten, which only means it is granted once more.
 *
 * Main thread only, like the transition listener that uses it.
 */
internal class ArtworkGrantLedger(private val capacity: Int = DEFAULT_CAPACITY) {
    private val granted = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean = size > capacity
    }

    /** Grants asked of the platform through this ledger, and grants it was spared (for the tests and the trace). */
    var recorded = 0L
        private set
    var skipped = 0L
        private set

    /** True when [uri] still has to be granted to [targetPackage]. Touching an entry keeps it recent. */
    fun needsGrant(targetPackage: String, uri: String): Boolean {
        // `get`, not `containsKey`: only a get counts as a use for the access order.
        val present = granted[key(targetPackage, uri)] != null
        if (present) skipped++
        return !present
    }

    /** The platform accepted the grant. */
    fun recordGranted(targetPackage: String, uri: String) {
        granted[key(targetPackage, uri)] = Unit
        recorded++
    }

    val size: Int get() = granted.size

    private fun key(targetPackage: String, uri: String) = "$targetPackage|$uri"

    companion object {
        const val DEFAULT_CAPACITY = 256
    }
}
