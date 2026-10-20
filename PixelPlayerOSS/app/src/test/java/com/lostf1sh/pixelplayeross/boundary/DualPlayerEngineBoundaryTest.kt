package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Collectors started from `DualPlayerEngine`'s `init` may only observe constructor parameters, and
 * may not touch engine state until they have hopped to the main thread.
 *
 * ## Why this is a source-level property
 *
 * Kotlin runs property initializers and `init` blocks in **textual order**, so a coroutine launched
 * from `init` can be executing while the fields declared below it are still unset. On this device
 * that is not theoretical: the process died roughly one second after every start, twelve times,
 * because a collector here observed `audioRouteState` — a field declared further down the class —
 * and read `null`:
 *
 * ```
 * java.lang.NullPointerException: Attempt to invoke interface method
 *     'java.lang.Object kotlinx.coroutines.flow.Flow.collect(FlowCollector, Continuation)'
 *     on a null object reference
 *   at …DualPlayerEngine$3$invokeSuspend$$inlined$map$1.collect(SafeCollector.common.kt:109)
 *   at kotlinx.coroutines.flow.DistinctFlowImpl.collect(Distinct.kt:68)
 *   at …DualPlayerEngine$3.invokeSuspend(DualPlayerEngine.kt:246)
 * ```
 *
 * Note what the stack is *not*: `SafeCollector` appears only because that is where the inlined
 * `collect` lands. This was never a flow-context invariant violation — it was a null receiver.
 *
 * Nothing a running JVM can observe without a real `ExoPlayer` and `Context` distinguishes the
 * safe arrangement from the fatal one, so it is checked at the source, in the same spirit as the
 * rest of this package: a collector that reintroduces the trap fails here rather than on the
 * device a second into playback.
 */
class DualPlayerEngineBoundaryTest {

    private val engine = File(
        "src/main/java/com/lostf1sh/pixelplayeross/data/service/player/DualPlayerEngine.kt"
    )

    /** The `init { … }` block, brace-matched, or null when there isn't one. */
    private fun initBlock(text: String): String? {
        val start = text.indexOf("\n    init {")
        if (start < 0) return null
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        return null
    }

    /** Constructor parameter names, from the primary constructor. */
    private fun constructorParameters(text: String): Set<String> {
        val header = text.substringAfter("class DualPlayerEngine @Inject constructor(", "")
        val body = header.substringBefore("\n) {")
        return Regex("""(?:^|,)\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:private |internal |)(?:val |var )?(\w+)\s*:""")
            .findAll(body)
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `the engine source was found, so this test is not vacuous`() {
        assertThat(engine.isFile).isTrue()
    }

    @Test
    fun `the init block still starts collectors, so the rules below are being applied`() {
        val init = initBlock(engine.readText())
        assertThat(init).isNotNull()
        val collectors = Regex("""\.collect\s*\{""").findAll(init!!).count()
        assertThat(collectors).isAtLeast(2)
    }

    @Test
    fun `every collector started from init observes a constructor parameter`() {
        assertThat(collectorReceivers()).isEmpty()
    }

    /**
     * The whole expression each `.collect {` is called on, per collector in `init`.
     *
     * Deliberately the entire chain rather than the identifier touching `.collect`: a receiver
     * written as `audioRouteState.map { it }.distinctUntilChanged()` ends in `)`, so matching only
     * identifier chains would let the exact expression that crashed through unnoticed. Returning
     * the raw chain means the caller can insist it is *bare*.
     */
    private fun collectorChains(): List<String> {
        val init = initBlock(engine.readText())!!
        return Regex("""\.collect\s*\{""").findAll(init).map { match ->
            val lineStart = init.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
            init.substring(lineStart, match.range.first).trim()
        }.toList()
    }

    /** Chains that are not a bare dotted path rooted at a constructor parameter. */
    private fun collectorReceivers(): List<String> {
        val parameters = constructorParameters(engine.readText())
        return collectorChains().filter { chain ->
            val bare = Regex("""^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$""").matches(chain)
            !bare || chain.substringBefore('.') !in parameters
        }
    }

    @Test
    fun `no flow operator runs ahead of the collect inside init`() {
        // The crash chain was `<field>.map { }.distinctUntilChanged().collect { }`. Every operator
        // in front of the collector executes on `appScope`'s dispatcher — IO — the moment the
        // flow emits, which can be mid-construction. Operators are therefore not allowed here at
        // all; the receivers are StateFlows and need none.
        val init = initBlock(engine.readText())!!
        val operators = listOf("map", "filter", "distinctUntilChanged", "onEach", "transform", "runningFold")
        val found = operators.filter { operator ->
            Regex("""\.$operator\s*[({]""").containsMatchIn(init)
        }
        assertThat(found).isEmpty()
    }

    @Test
    fun `every collector body hops to the main thread before doing anything else`() {
        // `appScope` is IO and the engine is built on the main thread, so this hop cannot run
        // until the constructor has returned and every field exists. It is the guarantee that
        // lets the bodies below reach engine state at all.
        val init = initBlock(engine.readText())!!
        val bodies = Regex("""\.collect\s*\{\s*\n\s*([^\n]+)""").findAll(init)
            .map { it.groupValues[1].trim() }
            .toList()

        assertThat(bodies).isNotEmpty()
        val offenders = bodies.filterNot { it.startsWith("withContext(Dispatchers.Main)") }
        assertThat(offenders).isEmpty()
    }
}
