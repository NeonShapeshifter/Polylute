package com.lostf1sh.pixelplayeross.data.collections

/**
 * How every saved collection answers for one track.
 *
 * ## Why this exists
 *
 * Phase 12.4 shipped the rule engine and its Collections surface, and left `explain()` reachable
 * only from tests. The consequence was that a user whose collection did not contain what they
 * expected had no way to find out why: the surface showed the tracks that matched and said nothing
 * about the ones that did not, and "my rules are wrong" and "my metadata is missing" look identical
 * from the outside.
 *
 * ## It does not decide anything
 *
 * Membership is asked of [SmartCollectionEngine.evaluate] — the same call the Collections surface
 * makes — and the per-rule verdicts come from [SmartCollectionEngine.explain], which runs through
 * that function's own comparison logic. Nothing here re-derives what "matches" means. A diagnostic
 * that computes its answer independently of the thing it is diagnosing will eventually disagree
 * with it, and the diagnostic is the one that gets believed.
 */
data class CollectionDiagnostic(
    /**
     * Whether the track is an active library candidate at all.
     *
     * `false` means the track has no live projection — it is dormant, or its locator moved — so no
     * collection can contain it, and the honest answer is that rather than an empty list that reads
     * like "your rules excluded it".
     */
    val isActiveCandidate: Boolean,
    val explanations: List<CollectionExplanation>,
)

/** One collection's answer for the track being diagnosed. */
data class CollectionExplanation(
    val collectionId: Long,
    val name: String,
    val isBuiltIn: Boolean,
    val matchMode: SmartCollectionMatchMode,
    val ruleCount: Int,
    /** Whether the engine puts this track in the collection. */
    val isMember: Boolean,
    /** True when the collection's own rules cannot all be read, so it returns nothing by design. */
    val needsAttention: Boolean,
    val evaluations: List<RuleEvaluation>,
)
