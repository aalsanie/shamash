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
        endExclusive: Int,
    ): Boolean {
        if (endExclusive <= start) return false
        val next = offsets[owner to target]?.ceiling(start) ?: return false
        return next < endExclusive
    }

    fun addMissing(
        references: MutableList<SourceDependency>,
        owner: SourceEntityId,
        target: SourceTarget,
        typeText: String,
        evidenceOffset: Int,
        token: String,
        documentStamp: Long,
    ): Boolean {
        val relativeOffset = findUniqueTypeTokenOffset(typeText, token) ?: return false
        val witness =
            SourceDependency(
                owner,
                target,
                SourceDependencyKind.TYPE,
                SourceReference(owner.fileUrl, evidenceOffset + relativeOffset, token.length, documentStamp),
            )
        references += witness
        add(witness)
        return true
    }
}
