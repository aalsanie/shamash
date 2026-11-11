/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import java.util.TreeSet

internal class SourceTypeWitnessIndex(
    references: Iterable<SourceDependency>,
) {
    private val offsets = HashMap<Pair<SourceEntityId, SourceTarget>, TreeSet<Int>>()

    init {
        references.forEach(::add)
    }

    fun add(reference: SourceDependency) {
        if (reference.kind != SourceDependencyKind.TYPE) return
        offsets.getOrPut(reference.from to reference.target, ::TreeSet).add(reference.evidence.offset)
    }

    fun contains(
        owner: SourceEntityId,
        target: SourceTarget,
        start: Int,
        end: Int,
    ): Boolean {
        val next = offsets[owner to target]?.ceiling(start) ?: return false
        return next <= end
    }
}
