/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import java.util.ArrayDeque

/**
 * Deterministic iterative graph algorithms. No recursive DFS or enumeration of simple cycles.
 * Sorting costs are separate from the linear graph traversals.
 */
object DirectedGraphAlgorithms {
    fun <N : Comparable<N>> cyclicComponents(
        nodes: Collection<N>,
        edges: Map<N, Collection<N>>,
        checkCancelled: () -> Unit = {},
    ): List<List<N>> {
        val vertices = nodes.toSortedSet().toList()
        val allowed = vertices.toHashSet()
        val outgoing =
            vertices.associateWith { node ->
                edges[node].orEmpty().filter { it in allowed }.distinct().sorted()
            }
        val incoming = vertices.associateWith { mutableListOf<N>() }
        for ((from, targets) in outgoing) {
            checkCancelled()
            for (to in targets) incoming.getValue(to).add(from)
        }
        incoming.values.forEach { it.sort() }

        data class Frame<N>(val node: N, var next: Int = 0)

        val visited = HashSet<N>()
        val finished = ArrayList<N>(vertices.size)
        for (start in vertices) {
            checkCancelled()
            if (!visited.add(start)) continue
            val stack = ArrayDeque<Frame<N>>()
            stack.addLast(Frame(start))
            while (stack.isNotEmpty()) {
                checkCancelled()
                val top = stack.last()
                val targets = outgoing.getValue(top.node)
                if (top.next == targets.size) {
                    finished.add(top.node)
                    stack.removeLast()
                } else {
                    val next = targets[top.next++]
                    if (visited.add(next)) stack.addLast(Frame(next))
                }
            }
        }

        visited.clear()
        val components = ArrayList<List<N>>()
        for (start in finished.asReversed()) {
            checkCancelled()
            if (!visited.add(start)) continue
            val stack = ArrayDeque<N>()
            stack.addLast(start)
            val members = ArrayList<N>()
            while (stack.isNotEmpty()) {
                checkCancelled()
                val node = stack.removeLast()
                members.add(node)
                for (predecessor in incoming.getValue(node)) {
                    if (visited.add(predecessor)) stack.addLast(predecessor)
                }
            }
            if (members.size > 1) components.add(members.sorted())
        }
        return components.sortedWith(compareByDescending<List<N>> { it.size }.thenBy { it.first() })
    }

    /**
     * Returns the nodes of one actual closed directed cycle (first == last).
     * For a nontrivial SCC, the smallest node has a neighbor with a path back to it.
     */
    fun <N : Comparable<N>> representativeCycle(
        component: Collection<N>,
        edges: Map<N, Collection<N>>,
        checkCancelled: () -> Unit = {},
    ): List<N> {
        require(component.size > 1) { "A package cycle requires at least two nodes" }
        val group = component.toHashSet()
        val start = component.minOrNull() ?: error("Empty component")
        for (next in edges[start].orEmpty().filter { it in group && it != start }.distinct().sorted()) {
            checkCancelled()
            val queue = ArrayDeque<N>()
            val previous = HashMap<N, N?>()
            previous[next] = null
            queue.addLast(next)
            while (queue.isNotEmpty() && start !in previous) {
                checkCancelled()
                val node = queue.removeFirst()
                for (neighbor in edges[node].orEmpty().filter { it in group }.distinct().sorted()) {
                    if (neighbor in previous) continue
                    previous[neighbor] = node
                    queue.addLast(neighbor)
                }
            }
            if (start !in previous) continue
            val reverse = ArrayList<N>()
            var node: N? = start
            while (node != null) {
                reverse.add(node)
                node = previous[node]
            }
            reverse.reverse() // next ... start
            return listOf(start) + reverse
        }
        error("Nontrivial SCC had no directed witness")
    }
}
