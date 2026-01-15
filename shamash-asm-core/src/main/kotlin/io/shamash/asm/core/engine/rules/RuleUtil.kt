/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
 *
 * Author: @aalsanie
 *
 * Plugin: https://plugins.jetbrains.com/plugin/29504-shamash
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.shamash.asm.core.engine.rules

import io.shamash.artifacts.util.PathNormalizer
import io.shamash.artifacts.util.glob.GlobMatcher
import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleScope
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.model.TypeRef
import io.shamash.asm.core.facts.query.FactIndex
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Engine rule utilities.
 *
 * - Centralize shared concerns:
 *   - scope checks (roles/packages/globs)
 *   - filePath normalization for findings
 *   - graph building + SCC/cycle helpers
 *
 * - Relies only on:
 *   - shamash-asm-core (config + facts)
 *   - shamash-artifacts (PathNormalizer + GlobMatcher)
 */
internal object RuleUtil {
    /* -------------------------------------------------------------------------
     * Rule identity
     * ---------------------------------------------------------------------- */

    fun canonicalRuleId(
        rule: RuleDef,
        role: String? = null,
    ): String {
        val t = rule.type.trim()
        val n = rule.name.trim()
        val r = role?.trim()?.takeIf { it.isNotEmpty() }
        return if (r == null) "$t.$n" else "$t.$n.$r"
    }

    /* -------------------------------------------------------------------------
     * Scope compilation / evaluation
     * ---------------------------------------------------------------------- */

    data class CompiledScope(
        val includeRoles: Set<String>?,
        val excludeRoles: Set<String>?,
        val includePackages: List<Regex>,
        val excludePackages: List<Regex>,
        val includeGlobs: List<String>,
        val excludeGlobs: List<String>,
    )

    fun compileScope(scope: RuleScope?): CompiledScope {
        if (scope == null) {
            return CompiledScope(
                includeRoles = null,
                excludeRoles = null,
                includePackages = emptyList(),
                excludePackages = emptyList(),
                includeGlobs = emptyList(),
                excludeGlobs = emptyList(),
            )
        }

        fun roleSet(xs: List<String>?): Set<String>? =
            xs?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }?.toSet()?.takeIf { it.isNotEmpty() }

        fun rxList(xs: List<String>?): List<Regex> =
            xs?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }?.map { Regex(it) } ?: emptyList()

        fun globList(xs: List<String>?): List<String> = xs?.mapNotNull { it.trim().takeIf(String::isNotEmpty) } ?: emptyList()

        return CompiledScope(
            includeRoles = roleSet(scope.includeRoles),
            excludeRoles = roleSet(scope.excludeRoles),
            includePackages = rxList(scope.includePackages),
            excludePackages = rxList(scope.excludePackages),
            includeGlobs = globList(scope.includeGlobs),
            excludeGlobs = globList(scope.excludeGlobs),
        )
    }

    /**
     * Returns true if the given [roleId] is enabled by:
     * - rule.roles (top-level)
     * - scope includeRoles/excludeRoles (optional)
     */
    fun roleAllowed(
        rule: RuleDef,
        scope: CompiledScope,
        roleId: String?,
    ): Boolean {
        val r = roleId?.trim()?.takeIf { it.isNotEmpty() }

        // rule.roles: null = apply to all roles; else must match
        rule.roles?.let { allowed ->
            val set = allowed.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
            if (r == null || r !in set) return false
        }

        // scope role filters (optional)
        scope.includeRoles?.let { inc ->
            if (r == null || r !in inc) return false
        }
        scope.excludeRoles?.let { exc ->
            if (r != null && r in exc) return false
        }

        return true
    }

    /**
     * Returns true if [c] is in rule scope:
     * - package regex include/exclude
     * - path glob include/exclude (matched against normalized filePath)
     * - roles handled by [roleAllowed] (call separately if needed)
     */
    fun classInScope(
        c: ClassFact,
        scope: CompiledScope,
    ): Boolean {
        val pkg = c.packageName
        if (scope.includePackages.isNotEmpty() && scope.includePackages.none { it.containsMatchIn(pkg) }) return false
        if (scope.excludePackages.isNotEmpty() && scope.excludePackages.any { it.containsMatchIn(pkg) }) return false

        val fp = GlobMatcher.normalizePath(filePathOf(c.location))
        if (scope.includeGlobs.isNotEmpty() && scope.includeGlobs.none { GlobMatcher.matches(it, fp) }) return false
        if (scope.excludeGlobs.isNotEmpty() && scope.excludeGlobs.any { GlobMatcher.matches(it, fp) }) return false

        return true
    }

    /* -------------------------------------------------------------------------
     * File path helpers
     * ---------------------------------------------------------------------- */

    /**
     * Stable file path string for findings.
     * - DIR_CLASS: normalized class file path
     * - JAR_ENTRY: normalized jar path + "!/<entry>"
     */
    fun filePathOf(location: SourceLocation?): String {
        if (location == null) return ""
        val loc = location.normalized()

        return when (loc.originKind) {
            OriginKind.DIR_CLASS -> PathNormalizer.normalize(loc.originPath)
            OriginKind.JAR_ENTRY -> {
                val jar = loc.containerPath ?: loc.originPath
                val entry = loc.entryPath ?: ""
                val jarN = PathNormalizer.normalize(jar)
                val entryN = entry.replace('\\', '/').trimStart('/')
                if (entryN.isEmpty()) jarN else "$jarN!/$entryN"
            }
        }
    }

    /* -------------------------------------------------------------------------
     * Graph building
     * ---------------------------------------------------------------------- */

    data class DirectedGraph(
        val nodes: Set<String>,
        val outgoing: Map<String, Set<String>>,
        val edgeCount: Int,
    ) {
        fun successors(node: String): Set<String> = outgoing[node] ?: emptySet()
    }

    private const val EXTERNAL_PREFIX = "__external__"

    /**
     * Build a dependency graph from facts.edges using analysis.graphs granularity.
     *
     * External buckets:
     * - If includeExternalBuckets = false: edges to nodes not present in facts/classes are dropped.
     * - If true: missing endpoints are bucketed as:
     *      "__external__:pkg.name"  (or "__external__" if pkg is empty)
     *
     * Granularity mapping:
     * - CLASS   -> fqName
     * - PACKAGE -> packageName ("" allowed)
     * - MODULE  -> first package segment (best-effort; stable)
     */
    fun buildDependencyGraph(
        facts: FactIndex,
        granularity: Granularity,
        includeExternalBuckets: Boolean,
        scope: CompiledScope? = null,
    ): DirectedGraph {
        val classByFqn = facts.classes.associateBy { it.fqName }

        // Determine included project nodes (after optional scope filter).
        val includedProjectNodes = LinkedHashSet<String>()
        for (c in facts.classes) {
            if (scope != null && !classInScope(c, scope)) continue
            includedProjectNodes += nodeIdForClass(c, granularity)
        }

        val outgoing = LinkedHashMap<String, LinkedHashSet<String>>()
        var edges = 0

        fun addEdge(
            from: String,
            to: String,
        ) {
            if (from == to) return
            val s = outgoing.getOrPut(from) { LinkedHashSet() }
            if (s.add(to)) edges++
        }

        for (e in facts.edges) {
            val fromNode = nodeIdForType(e.from, granularity)
            val toNode = nodeIdForType(e.to, granularity)

            // Optional scope filter is applied based on "from" class (best practical semantics).
            if (scope != null) {
                val fromClass = classByFqn[e.from.fqName]
                if (fromClass == null || !classInScope(fromClass, scope)) continue
            }

            val fromInProject = fromNode in includedProjectNodes
            val toInProject = toNode in includedProjectNodes

            if (!fromInProject) {
                // if "from" isn't a project node, we never start edges from it (keeps graph project-anchored).
                continue
            }

            if (toInProject) {
                addEdge(fromNode, toNode)
                continue
            }

            if (!includeExternalBuckets) {
                // drop edges to external
                continue
            }

            val bucket = externalBucketFor(e.to, granularity)
            addEdge(fromNode, bucket)
        }

        // Ensure nodes includes all from/outgoing + targets
        val nodes = LinkedHashSet<String>()
        nodes.addAll(includedProjectNodes)
        nodes.addAll(outgoing.keys)
        for (targets in outgoing.values) nodes.addAll(targets)

        // Freeze sets/maps immutably
        val frozenOutgoing: Map<String, Set<String>> = outgoing.mapValues { (_, v) -> v.toSet() }

        return DirectedGraph(nodes = nodes.toSet(), outgoing = frozenOutgoing, edgeCount = edges)
    }

    private fun nodeIdForClass(
        c: ClassFact,
        granularity: Granularity,
    ): String =
        when (granularity) {
            Granularity.CLASS -> c.fqName
            Granularity.PACKAGE -> c.packageName
            Granularity.MODULE -> moduleIdForPackage(c.packageName)
        }

    private fun nodeIdForType(
        t: TypeRef,
        granularity: Granularity,
    ): String =
        when (granularity) {
            Granularity.CLASS -> t.fqName
            Granularity.PACKAGE -> t.packageName
            Granularity.MODULE -> moduleIdForPackage(t.packageName)
        }

    private fun moduleIdForPackage(pkg: String): String {
        val p = pkg.trim()
        if (p.isEmpty()) return ""
        return p.substringBefore('.')
    }

    private fun externalBucketFor(
        t: TypeRef,
        granularity: Granularity,
    ): String {
        val pkg =
            when (granularity) {
                Granularity.CLASS -> t.packageName // bucket by package, not class
                Granularity.PACKAGE -> t.packageName
                Granularity.MODULE -> moduleIdForPackage(t.packageName)
            }.trim()

        return if (pkg.isEmpty()) EXTERNAL_PREFIX else "$EXTERNAL_PREFIX:$pkg"
    }

    /* -------------------------------------------------------------------------
     * SCC / cycles (deterministic)
     * ---------------------------------------------------------------------- */

    /**
     * Tarjan SCC, deterministic ordering:
     * - visits nodes in sorted order
     * - visits successors in sorted order
     */
    fun stronglyConnectedComponents(g: DirectedGraph): List<Set<String>> {
        val nodes = g.nodes.toList().sorted()

        val index = HashMap<String, Int>(nodes.size)
        val lowlink = HashMap<String, Int>(nodes.size)
        val onStack = HashSet<String>(nodes.size)
        val stack = ArrayDeque<String>()
        var nextIndex = 0

        val sccs = mutableListOf<Set<String>>()

        fun strongConnect(v: String) {
            index[v] = nextIndex
            lowlink[v] = nextIndex
            nextIndex++

            stack.push(v)
            onStack.add(v)

            val succ = g.successors(v).toList().sorted()
            for (w in succ) {
                if (w !in index) {
                    strongConnect(w)
                    lowlink[v] = minOf(lowlink.getValue(v), lowlink.getValue(w))
                } else if (w in onStack) {
                    lowlink[v] = minOf(lowlink.getValue(v), index.getValue(w))
                }
            }

            if (lowlink.getValue(v) == index.getValue(v)) {
                val comp = LinkedHashSet<String>()
                while (true) {
                    val w = stack.pop()
                    onStack.remove(w)
                    comp.add(w)
                    if (w == v) break
                }
                sccs += comp.toSet()
            }
        }

        for (v in nodes) {
            if (v !in index) strongConnect(v)
        }

        // stable ordering of SCCs (by smallest node)
        return sccs.sortedBy { it.minOrNull().orEmpty() }
    }

    /**
     * Returns only SCCs that represent cycles:
     * - size > 1, or
     * - size == 1 with a self-loop
     */
    fun cyclicComponents(g: DirectedGraph): List<Set<String>> {
        val sccs = stronglyConnectedComponents(g)
        return sccs.filter { comp ->
            when {
                comp.size > 1 -> true
                comp.size == 1 -> {
                    val n = comp.first()
                    n in g.successors(n)
                }
                else -> false
            }
        }
    }

    /**
     * Best-effort cycle extraction: for each cyclic SCC, produce one representative cycle path.
     * - pick smallest node in SCC as start
     * - BFS parent tracking within SCC
     *
     * Finds up to [maxCycles] "representative" cycles using a bounded DFS back-edge sampler.
     *
     * IMPORTANT:
     * - This is NOT a full cycle enumeration algorithm (by design).
     * - It is safe for very large graphs: memory is O(V) and output is bounded.
     */
    internal fun <N> representativeCyclesBounded(
        nodes: Iterable<N>,
        outgoing: (N) -> Iterable<N>,
        maxCycles: Int = 3,
        maxCycleNodes: Int = 120,
    ): List<List<N>> {
        if (maxCycles <= 0) return emptyList()
        if (maxCycleNodes <= 0) return emptyList()

        // 0=unvisited, 1=visiting (on stack), 2=done
        val state = HashMap<N, Int>(16_384)
        val stack = ArrayList<N>(256)
        val indexInStack = HashMap<N, Int>(16_384)

        val cycles = ArrayList<List<N>>(minOf(maxCycles, 8))

        fun captureCycle(
            fromIndex: Int,
            closingNode: N,
        ) {
            if (cycles.size >= maxCycles) return

            // Build cycle path from stack[fromIndex..end) + closing node.
            // Bound the output length to avoid memory blowups.
            val raw = ArrayList<N>(minOf(maxCycleNodes + 1, (stack.size - fromIndex) + 1))
            var count = 0

            for (i in fromIndex until stack.size) {
                if (count >= maxCycleNodes) break
                raw.add(stack[i])
                count++
            }
            if (count < maxCycleNodes) raw.add(closingNode)

            cycles.add(raw)
        }

        fun dfs(u: N) {
            if (cycles.size >= maxCycles) return

            state[u] = 1
            indexInStack[u] = stack.size
            stack.add(u)

            for (v in outgoing(u)) {
                if (cycles.size >= maxCycles) break

                val sv = state[v] ?: 0
                when (sv) {
                    0 -> dfs(v)
                    1 -> {
                        // Back-edge to a node currently in stack => cycle found.
                        val start = indexInStack[v]
                        if (start != null) captureCycle(start, v)
                    }
                    else -> Unit
                }
            }

            stack.removeAt(stack.size - 1)
            indexInStack.remove(u)
            state[u] = 2
        }

        for (n in nodes) {
            if (cycles.size >= maxCycles) break
            if ((state[n] ?: 0) == 0) dfs(n)
        }

        return cycles
    }
    /* -------------------------------------------------------------------------
     * Reachability (transitive dependencies)
     * ---------------------------------------------------------------------- */

    /**
     * Returns all reachable nodes from [start], excluding [start] itself.
     * Deterministic: visits successors in sorted order.
     */
    fun reachable(
        g: DirectedGraph,
        start: String,
    ): Set<String> {
        if (start !in g.nodes) return emptySet()

        val seen = LinkedHashSet<String>()
        val q = ArrayDeque<String>()
        q.add(start)

        while (q.isNotEmpty()) {
            val v = q.removeFirst()
            for (w in g.successors(v).toList().sorted()) {
                if (w == start) continue
                if (seen.add(w)) q.add(w)
            }
        }
        return seen.toSet()
    }

    /* -------------------------------------------------------------------------
     * Metrics helpers
     * ---------------------------------------------------------------------- */

    fun fanOut(g: DirectedGraph): Map<String, Int> = g.nodes.associateWith { n -> g.successors(n).size }

    fun fanIn(g: DirectedGraph): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (n in g.nodes) counts[n] = 0
        for ((from, tos) in g.outgoing) {
            for (to in tos) {
                counts[to] = (counts[to] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * Dependency density = E / (N*(N-1)) for directed graphs without self edges.
     * Returns 0.0 when N < 2.
     */
    fun dependencyDensity(g: DirectedGraph): Double {
        val n = g.nodes.size
        if (n < 2) return 0.0
        val denom = n.toDouble() * (n - 1).toDouble()
        return g.edgeCount.toDouble() / denom
    }

    /* -------------------------------------------------------------------------
     * Edge helpers
     * ---------------------------------------------------------------------- */

    /**
     * Builds a map of role-to-role edges using engine-populated [FactIndex.classToRole].
     *
     * External bucket:
     * - if includeExternal = false: drop edges that touch an unassigned role
     * - if true: bucket missing role as "__external__"
     */
    fun buildRoleGraph(
        facts: FactIndex,
        includeExternal: Boolean,
        scope: CompiledScope? = null,
    ): DirectedGraph {
        val classByFqn = facts.classes.associateBy { it.fqName }

        val outgoing = LinkedHashMap<String, LinkedHashSet<String>>()
        var edges = 0

        fun addEdge(
            from: String,
            to: String,
        ) {
            if (from == to) return
            val s = outgoing.getOrPut(from) { LinkedHashSet() }
            if (s.add(to)) edges++
        }

        for (e in facts.edges) {
            // optional scope filter by "from" class
            if (scope != null) {
                val fromClass = classByFqn[e.from.fqName]
                if (fromClass == null || !classInScope(fromClass, scope)) continue
            }

            val fromRole = facts.classToRole[e.from.fqName]
            val toRole = facts.classToRole[e.to.fqName]

            if (fromRole == null) continue // anchor graph on known project roles only

            if (toRole != null) {
                addEdge(fromRole, toRole)
            } else if (includeExternal) {
                addEdge(fromRole, EXTERNAL_PREFIX)
            }
        }

        val nodes = LinkedHashSet<String>()
        nodes.addAll(outgoing.keys)
        for (targets in outgoing.values) nodes.addAll(targets)

        val frozenOutgoing: Map<String, Set<String>> = outgoing.mapValues { (_, v) -> v.toSet() }
        return DirectedGraph(nodes = nodes.toSet(), outgoing = frozenOutgoing, edgeCount = edges)
    }

    /**
     * Utility to extract the "from class" for an edge if it exists in facts.classes.
     */
    fun fromClassOfEdge(
        facts: FactIndex,
        e: DependencyEdge,
    ): ClassFact? = facts.classes.firstOrNull { it.fqName == e.from.fqName }

    /**
     * Utility to extract the "to class" for an edge if it exists in facts.classes.
     */
    fun toClassOfEdge(
        facts: FactIndex,
        e: DependencyEdge,
    ): ClassFact? = facts.classes.firstOrNull { it.fqName == e.to.fqName }
}
