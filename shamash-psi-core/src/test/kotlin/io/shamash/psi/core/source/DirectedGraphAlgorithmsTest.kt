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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DirectedGraphAlgorithmsTest {
    @Test
    fun `empty disconnected and acyclic graphs contain no cyclic components`() {
        assertTrue(DirectedGraphAlgorithms.cyclicComponents(emptyList<Int>(), emptyMap()).isEmpty())
        assertTrue(DirectedGraphAlgorithms.cyclicComponents(listOf(1, 2, 3), emptyMap()).isEmpty())
        val edges = mapOf(1 to listOf(2, 3), 2 to listOf(3))
        assertTrue(DirectedGraphAlgorithms.cyclicComponents(listOf(1, 2, 3), edges).isEmpty())
    }

    @Test
    fun `self loops are not reported as multi-package cycles`() {
        val groups = DirectedGraphAlgorithms.cyclicComponents(
            listOf(1, 2, 3), mapOf(1 to listOf(1), 2 to listOf(3), 3 to listOf(2)),
        )
        assertEquals(listOf(listOf(2, 3)), groups)
    }

    @Test
    fun `intersecting cycles form one SCC and witness stays within actual edges`() {
        val edges = mapOf(1 to listOf(2), 2 to listOf(1, 3), 3 to listOf(2, 4))
        val groups = DirectedGraphAlgorithms.cyclicComponents(listOf(1, 2, 3, 4), edges)
        assertEquals(listOf(listOf(1, 2, 3)), groups)
        val path = DirectedGraphAlgorithms.representativeCycle(groups.single(), edges)
        assertEquals(listOf(1, 2, 1), path)
        assertTrue(path.zipWithNext().all { (from, to) -> to in edges[from].orEmpty() })
    }

    @Test
    fun `deterministic representative closes a long directed path`() {
        val edges = mapOf(1 to listOf(3, 2), 2 to listOf(4), 3 to listOf(4), 4 to listOf(1))
        val members = listOf(4, 2, 1, 3)
        val first = DirectedGraphAlgorithms.representativeCycle(members, edges)
        val second =
            DirectedGraphAlgorithms.representativeCycle(members.reversed(), edges.mapValues { it.value.reversed() })
        assertEquals(first, second)
        assertEquals(1, first.first())
        assertEquals(1, first.last())
        assertEquals(listOf(1, 2, 4, 1), first)
    }

    @Test
    fun `cyclic groups are order invariant even with duplicate incoming edges`() {
        val original = mapOf(3 to listOf(4, 4), 4 to listOf(3), 1 to listOf(2), 2 to listOf(1), 9 to listOf(9))
        val reversed = original.entries.reversed().associate { it.key to it.value.reversed() }
        assertEquals(
            DirectedGraphAlgorithms.cyclicComponents(original.keys.reversed(), original),
            DirectedGraphAlgorithms.cyclicComponents(original.keys.toList(), reversed),
        )
    }

    @Test
    fun `nontrivial group without a true directed cycle fails instead of inventing a path`() {
        assertFailsWith<IllegalStateException> {
            DirectedGraphAlgorithms.representativeCycle(listOf(1, 2), mapOf(1 to listOf(2)))
        }
        assertFailsWith<IllegalArgumentException> {
            DirectedGraphAlgorithms.representativeCycle(listOf(1), mapOf(1 to listOf(1)))
        }
    }

    @Test
    fun `cancellation interrupts both SCC and witness traversal`() {
        assertFailsWith<SentinelCancellation> {
            DirectedGraphAlgorithms.cyclicComponents((0..100).toList(), emptyMap<Int, List<Int>>()) {
                throw SentinelCancellation()
            }
        }
        assertFailsWith<SentinelCancellation> {
            DirectedGraphAlgorithms.representativeCycle(listOf(1, 2), mapOf(1 to listOf(2), 2 to listOf(1))) {
                throw SentinelCancellation()
            }
        }
    }

    @Test
    fun `deep graph does not overflow the JVM call stack`() {
        val n = 20_000
        val graph = (0 until n).associateWith { i -> if (i + 1 < n) listOf(i + 1) else listOf(0) }
        val components = DirectedGraphAlgorithms.cyclicComponents(graph.keys.toList(), graph)
        assertEquals(1, components.size)
        assertEquals(n, components.single().size)
        val witness = DirectedGraphAlgorithms.representativeCycle(components.single(), graph)
        assertEquals(n + 1, witness.size)
        assertEquals(witness.first(), witness.last())
    }

    private class SentinelCancellation : RuntimeException()
}
