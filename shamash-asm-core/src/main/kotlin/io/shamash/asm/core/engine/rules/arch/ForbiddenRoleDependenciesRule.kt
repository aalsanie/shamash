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
 * - forbidden:
 *     <fromRole>: [<toRole>, <toRole>, ...]
 * - direction: "direct" | "transitive"   (optional, default "direct")
 *
 * Semantics:
 * - Build the role graph from facts.edges using engine-assigned classToRole.
 * - If direction=direct: fail when a configured forbidden role edge is observed.
 * - If direction=transitive: fail when a configured forbidden target role is reachable
 *   from the source role through one or more role dependencies.
 *
 * Scope semantics:
 * - role filters apply to the source role.
 * - package/glob scope is applied to the producing source class.
 */
class ForbiddenRoleDependenciesRule : Rule {
    override val id: String = "arch.forbiddenRoleDependencies"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val params = readParams(rule) ?: return emptyList()
        if (params.forbiddenPairs.isEmpty()) return emptyList()

        val scope = RuleUtil.compileScope(rule.scope)

        val classByFqn: Map<String, ClassFact> =
            facts.classes.associateBy { it.fqName }

        /*
         * External/unclassified targets cannot participate in configured
         * role-to-role restrictions because they do not have an assigned role.
         *
         * includeExternal is intentionally false here because it is not part of
         * the arch.forbiddenRoleDependencies V1 parameter contract.
         */
        val roleGraph =
            RuleUtil.buildRoleGraph(
                facts = facts,
                includeExternal = false,
                scope = scope,
            )

        val findings = ArrayList<Finding>()

        for ((fromRole, toRole) in params.forbiddenPairs) {
            if (!RuleUtil.roleAllowed(rule, scope, fromRole)) continue

            /*
             * The validator rejects self-dependencies, but retain this guard so
             * direct evaluator use remains defensive.
             */
            if (fromRole == toRole) continue

            when (params.direction) {
                Direction.DIRECT -> {
                    if (toRole !in roleGraph.successors(fromRole)) continue

                    val examples =
                        collectDirectExamples(
                            facts = facts,
                            classByFqn = classByFqn,
                            scope = scope,
                            fromRole = fromRole,
                            toRole = toRole,
                            limit = EXAMPLE_LIMIT,
                        )

                    findings +=
                        buildFinding(
                            rule = rule,
                            fromRole = fromRole,
                            toRole = toRole,
                            direction = Direction.DIRECT,
                            examples = examples,
                            path = null,
                            classByFqn = classByFqn,
                        )
                }

                Direction.TRANSITIVE -> {
                    val path =
                        shortestPath(
                            graph = roleGraph,
                            start = fromRole,
                            target = toRole,
                        ) ?: continue

                    if (path.size < 2) continue

                    /*
                     * Anchor the finding to evidence for the first edge in the
                     * discovered path. This gives the user an actionable source
                     * location even when the forbidden dependency is transitive.
                     */
                    val firstHop = path[1]

                    val examples =
                        collectDirectExamples(
                            facts = facts,
                            classByFqn = classByFqn,
                            scope = scope,
                            fromRole = fromRole,
                            toRole = firstHop,
                            limit = EXAMPLE_LIMIT,
                        )

                    findings +=
                        buildFinding(
                            rule = rule,
                            fromRole = fromRole,
                            toRole = toRole,
                            direction = Direction.TRANSITIVE,
                            examples = examples,
                            path = path,
                            classByFqn = classByFqn,
                        )
                }
            }
        }

        /*
         * Keep findings deterministic regardless of map/set iteration order in
         * the input facts.
         *
         * "mode" remains the finding-data key for output compatibility even
         * though the configuration parameter is now correctly named
         * "direction".
         */
        return findings.sortedWith(
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
        direction: Direction,
        examples: List<Pair<String, String>>,
        path: List<String>?,
        classByFqn: Map<String, ClassFact>,
    ): Finding {
        val anchorClassFqn = examples.firstOrNull()?.first
        val anchorClass = anchorClassFqn?.let(classByFqn::get)

        val message =
            when (direction) {
                Direction.DIRECT -> {
                    "Forbidden role dependency observed: '$fromRole' -> '$toRole'."
                }

                Direction.TRANSITIVE -> {
                    "Forbidden transitive role dependency observed: '$fromRole' -> '$toRole'."
                }
            }

        val data =
            buildMap {
                put("fromRole", fromRole)
                put("toRole", toRole)

                /*
                 * Preserve the existing exported finding field name.
                 * Changing this to "direction" would be a separate artifact
                 * compatibility decision.
                 */
                put("mode", direction.wire)

                if (path != null && path.isNotEmpty()) {
                    put("path", path.joinToString(" -> "))
                }

                if (examples.isNotEmpty()) {
                    put(
                        "examples",
                        examples.joinToString(",") { (fromClass, toClass) ->
                            "$fromClass->$toClass"
                        },
                    )

                    if (examples.size >= EXAMPLE_LIMIT) {
                        put("examplesTruncated", "true")
                    }
                }
            }

        return Finding(
            ruleId = RuleUtil.canonicalRuleId(rule),
            message = message,
            filePath = anchorClass?.let { RuleUtil.filePathOf(it.location) } ?: "",
            severity = rule.severity,
            classFqn = anchorClass?.fqName,
            memberName = null,
            data = data,
        )
    }

    private fun readParams(rule: RuleDef): ReadParams? {
        val params =
            Params.of(
                rule.params,
                path = "rules.${rule.type}.${rule.name}.params",
            )

        val forbidden =
            try {
                params.requireMap("forbidden")
            } catch (_: ParamError) {
                /*
                 * Semantic validation is responsible for reporting malformed
                 * configuration. The evaluator stays resilient if invoked
                 * independently.
                 */
                return null
            }

        val direction =
            try {
                params.optionalEnum<Direction>("direction") ?: Direction.DIRECT
            } catch (_: ParamError) {
                return null
            }

        val forbiddenPairs = ArrayList<Pair<String, String>>()

        for ((rawFromRole, rawTargets) in forbidden) {
            val fromRole = rawFromRole.trim()
            if (fromRole.isEmpty()) continue

            val targets = rawTargets as? List<*> ?: continue

            for (rawTarget in targets) {
                val toRole = (rawTarget as? String)?.trim() ?: continue
                if (toRole.isEmpty()) continue

                forbiddenPairs += fromRole to toRole
            }
        }

        return ReadParams(
            forbiddenPairs =
                forbiddenPairs
                    .distinct()
                    .sortedWith(
                        compareBy<Pair<String, String>>(
                            { it.first },
                            { it.second },
                        ),
                    ),
            direction = direction,
        )
    }

    /**
     * Deterministic shortest path in a directed graph using BFS.
     *
     * Returns [start, ..., target], or null when target is unreachable.
     */
    private fun shortestPath(
        graph: RuleUtil.DirectedGraph,
        start: String,
        target: String,
    ): List<String>? {
        if (start == target) return listOf(start)
        if (start !in graph.nodes || target !in graph.nodes) return null

        val queue = ArrayDeque<String>()
        val parent = HashMap<String, String?>(graph.nodes.size)

        queue.add(start)
        parent[start] = null

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()

            for (successor in graph.successors(current).sorted()) {
                if (successor in parent) continue

                parent[successor] = current

                if (successor == target) {
                    return reconstructPath(
                        parent = parent,
                        target = target,
                    )
                }

                queue.add(successor)
            }
        }

        return null
    }

    private fun reconstructPath(
        parent: Map<String, String?>,
        target: String,
    ): List<String> {
        val path = ArrayDeque<String>()
        var current: String? = target

        while (current != null) {
            path.addFirst(current)
            current = parent[current]
        }

        return path.toList()
    }

    /**
     * Collect deterministic class-level examples that produced a role edge.
     *
     * Returns pairs of:
     *
     *     fromClassFqn -> toClassFqn
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
                compareBy<DependencyEdge>(
                    { it.from.fqName },
                    { it.to.fqName },
                    { it.kind.name },
                    { it.detail ?: "" },
                ),
            )

        for (edge in edges) {
            val fromFqn = edge.from.fqName
            val toFqn = edge.to.fqName

            val actualFromRole = facts.classToRole[fromFqn] ?: continue
            val actualToRole = facts.classToRole[toFqn] ?: continue

            if (actualFromRole != fromRole || actualToRole != toRole) {
                continue
            }

            val fromClass = classByFqn[fromFqn] ?: continue
            if (!RuleUtil.classInScope(fromClass, scope)) continue

            if (examples.add(fromFqn to toFqn) && examples.size >= limit) {
                break
            }
        }

        return examples.toList()
    }

    private data class ReadParams(
        val forbiddenPairs: List<Pair<String, String>>,
        val direction: Direction,
    )

    private enum class Direction(
        val wire: String,
    ) {
        DIRECT("direct"),
        TRANSITIVE("transitive"),
    }

    private companion object {
        const val EXAMPLE_LIMIT = 10
    }
}
