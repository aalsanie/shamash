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
package io.shamash.asm.core.engine.rules.arch

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.query.FactIndex
import java.util.ArrayDeque
import java.util.LinkedHashSet

/**
 * arch.forbiddenRoleDependencies
 *
 * Params:
 * - forbid: [ "fromRole->toRole", ... ]   (non-empty)
 * - mode: "direct" | "transitive"         (optional, default "direct")
 * - includeExternal: boolean              (optional, default false)
 *
 * Semantics:
 * - Build role graph from facts.edges using engine-assigned classToRole.
 * - If mode=direct: fail when an observed role edge (from -> to) exists and is forbidden.
 * - If mode=transitive: fail when to is reachable from (path length >= 1) and (from -> to) is forbidden.
 *
 * Scope semantics:
 * - role filters apply to the "fromRole" (source role).
 * - package/glob scope is applied to the producing "from-class" (via RuleUtil.buildRoleGraph(..., scope)).
 */
class ForbiddenRoleDependenciesRule : Rule {
    override val id: String = "arch.forbiddenRoleDependencies"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val params = readParams(rule) ?: return emptyList()
        if (params.forbidPairs.isEmpty()) return emptyList()

        val scope = RuleUtil.compileScope(rule.scope)

        // Useful for anchoring file paths / examples.
        val classByFqn: Map<String, ClassFact> = facts.classes.associateBy { it.fqName }

        // Graph is already scope-filtered by producing class (from-class).
        val roleGraph =
            RuleUtil.buildRoleGraph(
                facts = facts,
                includeExternal = params.includeExternal,
                scope = scope,
            )

        val out = ArrayList<Finding>()

        // Deterministic: iterate forbid list sorted.
        val forbidPairs =
            params.forbidPairs
                .distinct()
                .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))

        for ((fromRole, toRole) in forbidPairs) {
            if (!RuleUtil.roleAllowed(rule, scope, fromRole)) continue
            if (fromRole == toRole) continue // forbidding self-deps is almost always noise; ignore deterministically

            when (params.mode) {
                Mode.DIRECT -> {
                    if (toRole in roleGraph.successors(fromRole)) {
                        val examples =
                            collectDirectExamples(
                                facts = facts,
                                classByFqn = classByFqn,
                                scope = scope,
                                fromRole = fromRole,
                                toRole = toRole,
                                limit = 10,
                            )
                        out += buildFinding(rule, fromRole, toRole, Mode.DIRECT, examples, null, classByFqn)
                    }
                }

                Mode.TRANSITIVE -> {
                    val path = shortestPath(roleGraph, fromRole, toRole)
                    if (path != null && path.size >= 2) {
                        val examples =
                            collectDirectExamples(
                                facts = facts,
                                classByFqn = classByFqn,
                                scope = scope,
                                fromRole = fromRole,
                                toRole = path.getOrNull(1) ?: toRole, // first hop examples help explain
                                limit = 10,
                            )
                        out += buildFinding(rule, fromRole, toRole, Mode.TRANSITIVE, examples, path, classByFqn)
                    }
                }
            }
        }

        // Deterministic: stable sort by (fromRole, toRole, mode)
        return out.sortedWith(
            compareBy<Finding>(
                { it.data["fromRole"].orEmpty() },
                { it.data["toRole"].orEmpty() },
                { it.data["mode"].orEmpty() },
            ),
        )
    }

    private fun buildFinding(
        rule: RuleDef,
        fromRole: String,
        toRole: String,
        mode: Mode,
        examples: List<Pair<String, String>>,
        path: List<String>?,
        classByFqn: Map<String, ClassFact>,
    ): Finding {
        val anchorClassFqn = examples.firstOrNull()?.first
        val anchorClass = anchorClassFqn?.let { classByFqn[it] }
        val filePath = anchorClass?.let { RuleUtil.filePathOf(it.location) } ?: ""

        val msg =
            when (mode) {
                Mode.DIRECT ->
                    "Forbidden role dependency observed: '$fromRole' -> '$toRole'."
                Mode.TRANSITIVE ->
                    "Forbidden transitive role dependency observed: '$fromRole' -> '$toRole'."
            }

        val data =
            buildMap {
                put("fromRole", fromRole)
                put("toRole", toRole)
                put("mode", mode.wire)
                if (path != null && path.isNotEmpty()) put("path", path.joinToString(" -> "))
                if (examples.isNotEmpty()) {
                    put("examples", examples.joinToString(",") { (a, b) -> "$a->$b" })
                    if (examples.size >= 10) put("examplesTruncated", "true")
                }
            }

        return Finding(
            ruleId = RuleUtil.canonicalRuleId(rule),
            message = msg,
            filePath = filePath,
            severity = rule.severity,
            classFqn = anchorClass?.fqName,
            memberName = null,
            data = data,
        )
    }

    private data class ReadParams(
        val forbidPairs: List<Pair<String, String>>,
        val mode: Mode,
        val includeExternal: Boolean,
    )

    private enum class Mode(
        val wire: String,
    ) {
        DIRECT("direct"),
        TRANSITIVE("transitive"),
        ;

        companion object {
            fun parse(s: String?): Mode {
                val v = s?.trim()?.lowercase()
                return when (v) {
                    "transitive" -> TRANSITIVE
                    "direct", null, "" -> DIRECT
                    else -> DIRECT
                }
            }
        }
    }

    private fun readParams(rule: RuleDef): ReadParams? {
        val p = Params.of(rule.params, path = "rules.${rule.type}.${rule.name}.params")

        val forbid: List<String> =
            try {
                p.requireStringList("forbid", nonEmpty = true)
            } catch (_: ParamError) {
                return null
            }

        val mode = Mode.parse(runCatching { p.optionalString("mode") }.getOrNull())
        val includeExternal = runCatching { p.optionalBoolean("includeExternal") }.getOrNull() ?: false

        val pairs = ArrayList<Pair<String, String>>(forbid.size)
        for (edge in forbid) {
            val s = edge.trim()
            if (s.isEmpty()) continue
            val parts = s.split("->")
            if (parts.size != 2) continue
            val from = parts[0].trim()
            val to = parts[1].trim()
            if (from.isEmpty() || to.isEmpty()) continue
            pairs += from to to
        }

        return ReadParams(
            forbidPairs = pairs,
            mode = mode,
            includeExternal = includeExternal,
        )
    }

    /**
     * Deterministic shortest path in a directed graph using BFS.
     * Returns list [start, ..., target] or null if unreachable.
     */
    private fun shortestPath(
        g: RuleUtil.DirectedGraph,
        start: String,
        target: String,
    ): List<String>? {
        if (start == target) return listOf(start)
        if (start !in g.nodes || target !in g.nodes) return null

        val q = ArrayDeque<String>()
        val parent = HashMap<String, String?>(g.nodes.size)

        q.add(start)
        parent[start] = null

        while (q.isNotEmpty()) {
            val v = q.removeFirst()
            for (w in g.successors(v).toList().sorted()) {
                if (w !in parent) {
                    parent[w] = v
                    if (w == target) {
                        return reconstructPath(parent, target)
                    }
                    q.add(w)
                }
            }
        }

        return null
    }

    private fun reconstructPath(
        parent: Map<String, String?>,
        target: String,
    ): List<String> {
        val out = ArrayDeque<String>()
        var cur: String? = target
        while (cur != null) {
            out.addFirst(cur)
            cur = parent[cur]
        }
        return out.toList()
    }

    /**
     * Collect deterministic examples of class edges that produced a role->role edge.
     * Returns pairs (fromClassFqn -> toClassFqn).
     */
    private fun collectDirectExamples(
        facts: FactIndex,
        classByFqn: Map<String, ClassFact>,
        scope: RuleUtil.CompiledScope,
        fromRole: String,
        toRole: String,
        limit: Int,
    ): List<Pair<String, String>> {
        val examples = LinkedHashSet<Pair<String, String>>()

        val edges =
            facts.edges.sortedWith(
                compareBy<DependencyEdge>({ it.from.fqName }, { it.to.fqName }, { it.kind.name }, { it.detail ?: "" }),
            )

        for (e in edges) {
            val fromFqn = e.from.fqName
            val toFqn = e.to.fqName

            val rFrom = facts.classToRole[fromFqn] ?: continue
            val rTo = facts.classToRole[toFqn] ?: continue

            if (rFrom != fromRole || rTo != toRole) continue

            val fromClass = classByFqn[fromFqn] ?: continue
            if (!RuleUtil.classInScope(fromClass, scope)) continue

            if (examples.add(fromFqn to toFqn) && examples.size >= limit) break
        }

        return examples.toList()
    }
}
