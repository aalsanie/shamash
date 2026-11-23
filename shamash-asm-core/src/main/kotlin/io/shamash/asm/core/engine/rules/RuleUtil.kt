/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
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

internal object RuleUtil {
    fun canonicalRuleId(
        rule: RuleDef,
        role: String? = null,
    ): String {
        val t = rule.type.trim()
        val n = rule.name.trim()
        val r = role?.trim()?.takeIf { it.isNotEmpty() }
        return if (r == null) "$t.$n" else "$t.$n.$r"
    }

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

    fun roleAllowed(
        rule: RuleDef,
        scope: CompiledScope,
        roleId: String?,
    ): Boolean {
        val r = roleId?.trim()?.takeIf { it.isNotEmpty() }

        rule.roles?.let { allowed ->
            val set = allowed.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
            if (r == null || r !in set) return false
        }

        scope.includeRoles?.let { inc ->
            if (r == null || r !in inc) return false
        }
        scope.excludeRoles?.let { exc ->
            if (r != null && r in exc) return false
        }

        return true
    }

    fun ownerAllowed(
        owner: ClassFact,
        rule: RuleDef,
        scope: CompiledScope,
        classToRole: Map<String, String>,
    ): Boolean = classInScope(owner, scope) && roleAllowed(rule, scope, classToRole[owner.fqName])

    /** Checks package regexes and path globs. Call [roleAllowed] separately for role filtering. */
    fun classInScope(
        c: ClassFact,
        scope: CompiledScope,
    ): Boolean {
        val pkg = c.packageName
        if (scope.includePackages.isNotEmpty() && scope.includePackages.none { it.containsMatchIn(pkg) }) return false
        if (scope.excludePackages.isNotEmpty() && scope.excludePackages.any { it.containsMatchIn(pkg) }) return false

        if (scope.includeGlobs.isNotEmpty() || scope.excludeGlobs.isNotEmpty()) {
            val fp = GlobMatcher.normalizePath(filePathOf(c.location))
            if (scope.includeGlobs.isNotEmpty() && scope.includeGlobs.none { GlobMatcher.matches(it, fp) }) return false
            if (scope.excludeGlobs.isNotEmpty() && scope.excludeGlobs.any { GlobMatcher.matches(it, fp) }) return false
        }

        return true
    }

    /**
     * Stable file path string for findings.
     * - DIR_CLASS: normalized class file path
     * - JAR_ENTRY: normalized jar path + "!/<entry>"
     */
    fun filePathOf(location: SourceLocation?): String {
        if (location == null) return ""
        val loc = location.normalized()

        return when (loc.originKind) {
            OriginKind.DIR_CLASS -> {
                PathNormalizer.normalize(loc.originPath)
            }

            OriginKind.JAR_ENTRY -> {
                val jar = loc.containerPath ?: loc.originPath
                val entry = loc.entryPath ?: ""
                val jarN = PathNormalizer.normalize(jar)
                val entryN = entry.replace('\\', '/').trimStart('/')
                if (entryN.isEmpty()) jarN else "$jarN!/$entryN"
            }
        }
    }

    data class DirectedGraph(
        val nodes: Set<String>,
        val outgoing: Map<String, Set<String>>,
        val edgeCount: Int,
    ) {
        fun successors(node: String): Set<String> = outgoing[node] ?: emptySet()
    }

    const val EXTERNAL_PREFIX = "__external__"

    /**
     * Edges originate at included project nodes; scope filters apply to the source class.
     * External targets are dropped or grouped under `__external__` buckets.
     * MODULE granularity uses the first package segment, not a build-system module.
     */
    fun buildDependencyGraph(
        facts: FactIndex,
        granularity: Granularity,
        includeExternalBuckets: Boolean,
        scope: CompiledScope? = null,
        rule: RuleDef? = null,
    ): DirectedGraph {
        val filterSources = scope != null || rule != null
        val effectiveScope = scope ?: compileScope(rule?.scope)
        val selectedClasses =
            if (filterSources) {
                facts.classes.filter { c ->
                    if (rule == null) classInScope(c, effectiveScope) else ownerAllowed(c, rule, effectiveScope, facts.classToRole)
                }
            } else {
                facts.classes
            }
        val selectedClassNames =
            if (filterSources) selectedClasses.mapTo(HashSet(selectedClasses.size)) { it.fqName } else null

        val includedProjectNodes = LinkedHashSet<String>()
        for (c in selectedClasses) {
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

            if (selectedClassNames != null && e.from.fqName !in selectedClassNames) continue

            val fromInProject = fromNode in includedProjectNodes
            val toInProject = toNode in includedProjectNodes

            if (!fromInProject) {
                continue
            }

            if (toInProject) {
                addEdge(fromNode, toNode)
                continue
            }

            if (!includeExternalBuckets) {
                continue
            }

            val bucket = externalBucketFor(e.to, granularity)
            addEdge(fromNode, bucket)
        }

        val nodes = LinkedHashSet<String>()
        nodes.addAll(includedProjectNodes)
        nodes.addAll(outgoing.keys)
        for (targets in outgoing.values) nodes.addAll(targets)

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
                Granularity.CLASS -> t.packageName
                Granularity.PACKAGE -> t.packageName
                Granularity.MODULE -> moduleIdForPackage(t.packageName)
            }.trim()

        return if (pkg.isEmpty()) EXTERNAL_PREFIX else "$EXTERNAL_PREFIX:$pkg"
    }

    /** Tarjan SCC traversal with sorted nodes/successors; components are ordered by smallest node. */
    fun stronglyConnectedComponents(g: DirectedGraph): List<Set<String>> {
        data class Frame(
            val node: String,
            val successors: Iterator<String>,
        )

        val nodes = g.nodes.sorted()
        val index = HashMap<String, Int>(nodes.size)
        val lowlink = HashMap<String, Int>(nodes.size)
        val onStack = HashSet<String>(nodes.size)
        val stack = ArrayDeque<String>()
        val frames = ArrayDeque<Frame>()
        val components = ArrayList<Set<String>>()
        var nextIndex = 0

        fun enter(node: String) {
            index[node] = nextIndex
            lowlink[node] = nextIndex++
            stack.push(node)
            onStack.add(node)
            frames.addLast(Frame(node, g.successors(node).sorted().iterator()))
        }

        for (node in nodes) {
            if (node in index) continue
            enter(node)
            while (frames.isNotEmpty()) {
                val frame = frames.peekLast()
                if (frame.successors.hasNext()) {
                    val next = frame.successors.next()
                    if (next !in index) {
                        enter(next)
                    } else if (next in onStack) {
                        lowlink[frame.node] = minOf(lowlink.getValue(frame.node), index.getValue(next))
                    }
                } else {
                    frames.removeLast()
                    if (lowlink.getValue(frame.node) == index.getValue(frame.node)) {
                        val component = LinkedHashSet<String>()
                        do {
                            val member = stack.pop()
                            onStack.remove(member)
                            component.add(member)
                        } while (member != frame.node)
                        components.add(component)
                    }
                    if (frames.isNotEmpty()) {
                        val parent = frames.peekLast().node
                        lowlink[parent] = minOf(lowlink.getValue(parent), lowlink.getValue(frame.node))
                    }
                }
            }
        }

        return components.sortedBy { it.minOrNull().orEmpty() }
    }

    fun cyclicComponents(g: DirectedGraph): List<Set<String>> {
        val sccs = stronglyConnectedComponents(g)
        return sccs.filter { comp ->
            when {
                comp.size > 1 -> {
                    true
                }

                comp.size == 1 -> {
                    val n = comp.first()
                    n in g.successors(n)
                }

                else -> {
                    false
                }
            }
        }
    }

    /**
     * Samples DFS back-edge cycles, bounded by [maxCycles] and [maxCycleNodes].
     * Only complete, closed paths are returned; cycles exceeding the length bound are omitted.
     */
    internal fun <N> representativeCyclesBounded(
        nodes: Iterable<N>,
        outgoing: (N) -> Iterable<N>,
        maxCycles: Int = 3,
        maxCycleNodes: Int = 120,
    ): List<List<N>> {
        if (maxCycles <= 0 || maxCycleNodes < 2) return emptyList()

        data class Frame<N>(
            val node: N,
            val successors: Iterator<N>,
        )

        val state = HashMap<N, Int>()
        val indexInPath = HashMap<N, Int>()
        val path = ArrayList<N>()
        val frames = ArrayDeque<Frame<N>>()
        val cycles = ArrayList<List<N>>(minOf(maxCycles, 8))

        fun enter(node: N) {
            state[node] = 1
            indexInPath[node] = path.size
            path.add(node)
            frames.addLast(Frame(node, outgoing(node).iterator()))
        }

        for (node in nodes) {
            if (cycles.size >= maxCycles) break
            if (node in state) continue
            enter(node)
            while (frames.isNotEmpty() && cycles.size < maxCycles) {
                val frame = frames.peekLast()
                if (frame.successors.hasNext()) {
                    val next = frame.successors.next()
                    when (state[next] ?: 0) {
                        0 -> {
                            enter(next)
                        }

                        1 -> {
                            val start = indexInPath.getValue(next)
                            val length = path.size - start + 1
                            if (length <= maxCycleNodes) {
                                val cycle = ArrayList<N>(length)
                                for (i in start until path.size) cycle.add(path[i])
                                cycle.add(next)
                                cycles.add(cycle)
                            }
                        }
                    }
                } else {
                    frames.removeLast()
                    val last = path.removeAt(path.lastIndex)
                    indexInPath.remove(last)
                    state[last] = 2
                }
            }
        }

        return cycles
    }

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

    /**
     * Edges originate at assigned project roles; scope filters apply to the source class.
     * Unassigned targets are dropped or grouped as `__external__`.
     */
    fun buildRoleGraph(
        facts: FactIndex,
        includeExternal: Boolean,
        scope: CompiledScope? = null,
    ): DirectedGraph = buildRoleGraphInternal(facts, includeExternal, scope, null)

    fun buildRoleGraph(
        facts: FactIndex,
        includeExternal: Boolean,
        scope: CompiledScope?,
        rule: RuleDef,
    ): DirectedGraph = buildRoleGraphInternal(facts, includeExternal, scope, rule)

    private fun buildRoleGraphInternal(
        facts: FactIndex,
        includeExternal: Boolean,
        scope: CompiledScope?,
        rule: RuleDef?,
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
            if (scope != null) {
                val fromClass = classByFqn[e.from.fqName]
                if (fromClass == null) continue
                if (rule == null && !classInScope(fromClass, scope)) continue
                if (rule != null && !ownerAllowed(fromClass, rule, scope, facts.classToRole)) continue
            }

            val fromRole = facts.classToRole[e.from.fqName]
            val toRole = facts.classToRole[e.to.fqName]

            if (fromRole == null) continue

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

    fun fromClassOfEdge(
        facts: FactIndex,
        e: DependencyEdge,
    ): ClassFact? = facts.classes.firstOrNull { it.fqName == e.from.fqName }

    fun toClassOfEdge(
        facts: FactIndex,
        e: DependencyEdge,
    ): ClassFact? = facts.classes.firstOrNull { it.fqName == e.to.fqName }
}
