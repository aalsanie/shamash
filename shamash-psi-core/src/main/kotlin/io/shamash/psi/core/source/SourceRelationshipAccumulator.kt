/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

/**
 * Aggregates outside PSI read locks. Counts all occurrences; retains bounded evidence per
 * distinct relationship, with no silently evicted internal edge in a COMPLETE result.
 */
class SourceRelationshipAccumulator(private val profile: SourceAnalysisProfile) {
    init {
        require(profile.maxFiles > 0 && profile.maxRelationships > 0 && profile.maxWitnesses > 0)
    }

    private class Counter {
        val kinds = LinkedHashSet<SourceDependencyKind>()
        val witnesses = ArrayList<SourceDependency>()
        var total = 0L
    }

    private val internal = LinkedHashMap<Pair<SourceEntityId, SourceEntityId>, Counter>()
    private val other = LinkedHashMap<Pair<SourceEntityId, SourceTarget>, Counter>()

    var resolvedOccurrences: Long = 0
        private set
    var unresolvedOccurrences: Long = 0
        private set
    var truncated: Boolean = false
        private set

    fun add(dependency: SourceDependency) {
        when (val target = dependency.target) {
            is SourceTarget.Internal -> {
                resolvedOccurrences++
                val key = dependency.from to target.entity
                val counter =
                    internal[key] ?: run {
                        if (internal.size == profile.maxRelationships) {
                            truncated = true
                            return
                        }
                        Counter().also { internal[key] = it }
                    }
                record(counter, dependency)
            }
            is SourceTarget.External -> {
                resolvedOccurrences++
                recordOther(dependency)
            }
            is SourceTarget.Unresolved -> {
                unresolvedOccurrences++
                recordOther(dependency)
            }
        }
    }

    private fun recordOther(reference: SourceDependency) {
        val key = reference.from to reference.target
        val counter =
            other[key] ?: run {
                if (other.size >= profile.maxRelationships) {
                    truncated = true
                    return
                }
                Counter().also { other[key] = it }
            }
        record(counter, reference)
    }

    private fun record(counter: Counter, reference: SourceDependency) {
        counter.total++
        counter.kinds += reference.kind
        if (counter.witnesses.size < profile.maxWitnesses) counter.witnesses += reference
    }

    fun relationships(): List<SourceRelationship> =
        internal.map { (key, value) ->
            SourceRelationship(
                key.first,
                key.second,
                value.kinds.toSet(),
                value.total,
                value.witnesses.map { it.evidence },
            )
        }.sortedWith(compareBy({ it.from.moduleId }, { it.from.fileUrl }, { it.target.moduleId }, { it.target.signature }))

    /** Retained evidence only; occurrence totals are in relationships and coverage. */
    fun evidence(): List<SourceDependency> =
        (internal.values.asSequence() + other.values.asSequence())
            .flatMap { it.witnesses.asSequence() }
            .sortedWith(compareBy({ it.from.fileUrl }, { it.evidence.offset }, { it.target.toString() }))
            .toList()
}
