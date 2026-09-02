/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
 * Transitive restrictions follow paths through the role graph.
 * Role and package/path scope filters apply to the producing source class.
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

        // Unclassified targets have no role and cannot participate in role restrictions.
        val roleGraph =
            RuleUtil.buildRoleGraph(
                facts = facts,
                includeExternal = false,
                scope = scope,
            )

        val findings = ArrayList<Finding>()

        for ((fromRole, toRole) in params.forbiddenPairs) {
            if (!RuleUtil.roleAllowed(rule, scope, fromRole)) continue

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

                    // Anchor transitive findings to the first edge so the source location is actionable.
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

                // Keep the exported "mode" key for compatibility with existing report consumers.
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

    /** Returns the deterministic shortest path, including both endpoints, or null if unreachable. */
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
