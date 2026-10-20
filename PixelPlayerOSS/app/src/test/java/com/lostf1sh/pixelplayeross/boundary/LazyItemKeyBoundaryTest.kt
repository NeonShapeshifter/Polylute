package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A `LazyColumn` item key must not change **type** when a paged row resolves.
 *
 * Every paged list in this app keys on the loaded entity's id and falls back to something for the
 * not-yet-loaded case:
 *
 * ```kotlin
 * key = { index -> songs.peek(index)?.id ?: <fallback> }
 * ```
 *
 * When the fallback was the `Int` index, one logical slot produced an `Int` while its page was in
 * flight and a `String` once it arrived. Compose treats a key of a different type as a different
 * key, so the row is disposed and composed again — a full row composition, including a fresh Coil
 * painter, at exactly the moment the user is scrolling fast enough to outrun paging. The rest of
 * the lists already used a `String` fallback; two did not.
 *
 * This is a source-level property: it is about how the key expression is *written*, and no JVM
 * assertion distinguishes the two forms without a running `LazyColumn`. Checked at the source in
 * the same spirit as the rest of this package.
 *
 * **Scope, stated so this test is not over-read:** it pins the key *type*. It does not make the key
 * *value* stable across resolution — a placeholder has no id, so Paging necessarily replaces it
 * with one. That transition is inherent to `enablePlaceholders = true` and is not what this guards.
 */
class LazyItemKeyBoundaryTest {

    private val mainSources = File("src/main/java")

    @Test
    fun `the source tree was found, so this test is not vacuous`() {
        assertThat(mainSources.isDirectory).isTrue()
    }

    @Test
    fun `paged list keys still fall back rather than only keying loaded items`() {
        // Keeps the rule below honest: if the `?:` fallbacks disappear this test should be revisited
        // rather than quietly passing over nothing.
        val fallbacks = keyExpressions().filter { it.contains("?:") }
        assertThat(fallbacks.size).isAtLeast(4)
    }

    @Test
    fun `no lazy item key falls back to a bare index`() {
        val offenders = keyExpressions()
            .filter { it.contains("?:") }
            .filter { fallbackIsBareIndex(it) }
            .toList()

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `every paged key falls back to a string`() {
        val offenders = keyExpressions()
            .filter { it.contains("?:") }
            .filterNot { it.contains("?: \"") }
            .toList()

        assertThat(offenders).isEmpty()
    }

    /** Every `key = { … }` expression appearing in a lazy list builder. */
    private fun keyExpressions(): List<String> = mainSources.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { file ->
            Regex("""key\s*=\s*\{[^\n]*}""").findAll(file.readText())
                .map { it.value.trim() }
                .toList()
        }
        .toList()

    /** True when the `?:` fallback is the `index` parameter itself rather than a string. */
    private fun fallbackIsBareIndex(expression: String): Boolean {
        val fallback = expression.substringAfter("?:").trim().trimEnd('}', ' ', ',')
        return fallback == "index" || fallback.matches(Regex("""\w*[Ii]ndex"""))
    }
}
