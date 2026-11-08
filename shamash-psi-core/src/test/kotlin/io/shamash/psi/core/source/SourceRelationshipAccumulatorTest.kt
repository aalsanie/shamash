/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceRelationshipAccumulatorTest {
    private val from = SourceEntityId("module-a", "file:///a", "file:///a/A.java", SourceEntityKind.CLASS, "p.A")
    private val target = SourceEntityId("module-b", "file:///b", "file:///b/B.kt", SourceEntityKind.CLASS, "q.B")

    @Test
    fun `repeat references preserve occurrence totals while witnesses are bounded`() {
        val reducer = SourceRelationshipAccumulator(SourceAnalysisProfile(maxWitnesses = 20))
        repeat(125) { i ->
            reducer.add(reference(SourceTarget.Internal(target), i))
        }
        val only = reducer.relationships().single()
        assertEquals(125L, only.occurrences)
        assertEquals(20, only.witnesses.size)
        assertEquals(20, reducer.evidence().size)
        assertEquals(125L, reducer.resolvedOccurrences)
        assertFalse(reducer.truncated)
    }

    @Test
    fun `different modules with same FQN remain independent graph entities`() {
        val other = target.copy(moduleId = "module-c", sourceRoot = "file:///c", fileUrl = "file:///c/B.kt")
        val reducer = SourceRelationshipAccumulator(SourceAnalysisProfile())
        reducer.add(reference(SourceTarget.Internal(target), 1))
        reducer.add(reference(SourceTarget.Internal(other), 2))
        assertEquals(2, reducer.relationships().size)
        assertEquals(setOf("module-b", "module-c"), reducer.relationships().map { it.target.moduleId }.toSet())
    }

    @Test
    fun `external and unresolved targets cannot masquerade as internal edges`() {
        val reducer = SourceRelationshipAccumulator(SourceAnalysisProfile())
        reducer.add(reference(SourceTarget.External("java.util.List"), 1))
        reducer.add(reference(SourceTarget.Unresolved("Missing"), 2))
        assertTrue(reducer.relationships().isEmpty())
        assertEquals(1L, reducer.resolvedOccurrences)
        assertEquals(1L, reducer.unresolvedOccurrences)
        assertEquals(2, reducer.evidence().size)
    }

    @Test
    fun `internal relationship limit retains evidence without claiming completeness`() {
        val reducer = SourceRelationshipAccumulator(SourceAnalysisProfile(maxRelationships = 1))
        reducer.add(reference(SourceTarget.Internal(target), 1))
        reducer.add(reference(SourceTarget.Internal(target.copy(signature = "q.C")), 2))
        reducer.add(reference(SourceTarget.Internal(target), 3))
        assertTrue(reducer.truncated)
        assertEquals(1, reducer.relationships().size)
        assertEquals(2L, reducer.relationships().single().occurrences)
        assertEquals(3L, reducer.resolvedOccurrences)
    }

    @Test
    fun `external references cannot consume the internal graph budget`() {
        val reducer = SourceRelationshipAccumulator(SourceAnalysisProfile(maxRelationships = 1))
        reducer.add(reference(SourceTarget.External("java.util.Map"), 1))
        reducer.add(reference(SourceTarget.Unresolved("Missing"), 2))
        reducer.add(reference(SourceTarget.Internal(target), 3))
        reducer.add(reference(SourceTarget.Internal(target), 4))

        assertTrue(reducer.truncated)
        assertEquals(1, reducer.relationships().size)
        assertEquals(2L, reducer.relationships().single().occurrences)
        assertEquals(2, reducer.evidence().size)
        assertEquals(3L, reducer.resolvedOccurrences)
        assertEquals(1L, reducer.unresolvedOccurrences)
    }

    @Test
    fun `different reference kinds aggregate one distinct relationship`() {
        val reducer = SourceRelationshipAccumulator(SourceAnalysisProfile())
        reducer.add(reference(SourceTarget.Internal(target), 1))
        reducer.add(reference(SourceTarget.Internal(target), 2).copy(kind = SourceDependencyKind.CALL))
        val relationship = reducer.relationships().single()
        assertEquals(2L, relationship.occurrences)
        assertEquals(setOf(SourceDependencyKind.TYPE, SourceDependencyKind.CALL), relationship.kinds)
    }

    private fun reference(
        target: SourceTarget,
        index: Int,
    ) = SourceDependency(
        from,
        target,
        SourceDependencyKind.TYPE,
        SourceReference(from.fileUrl, index, 3, index.toLong()),
    )
}
