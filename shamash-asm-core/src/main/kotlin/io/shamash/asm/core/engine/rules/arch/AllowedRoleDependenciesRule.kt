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

/**
 * arch.allowedRoleDependencies
 *
 * Params:
 * - allow: [ "fromRole->toRole", ... ]  (non-empty)
 *
 * Semantics:
 * - Build role graph from facts.edges using engine-assigned classToRole.
 * - For every observed role edge (fromRole -> toRole), if it is not explicitly allowed, emit a finding.
 * - Dependencies within the same role are always allowed (fromRole == toRole).
 * - External/unclassified targets are ignored (they don't have roles; handled by other rules).
 *
 * Scope semantics:
 * - role filters are applied on the *fromRole* (source role).
 * - include/exclude package/glob scope is applied on the *from-class* that produced the edge.
 */
class AllowedRoleDependenciesRule : Rule {
    override val id: String = "arch.allowedRoleDependencies"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val allowEdges = readAllowEdges(rule) ?: return emptyList()
        if (allowEdges.isEmpty()) return emptyList()

        val scope = RuleUtil.compileScope(rule.scope)

        // Map classFqn -> ClassFact for anchoring file paths / scope evaluation.
        val classByFqn: Map<String, ClassFact> = facts.classes.associateBy { it.fqName }

        // Build role adjacency from edges (project-only; ignore externals).
        val roleAdj = buildObservedRoleAdjacency(facts, classByFqn, scope)

        // Allowed adjacency for fast lookup.
        val allowedAdj: Map<String, Set<String>> =
            allowEdges
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, tos) -> tos.toSet() }

        val violations = mutableListOf<RoleEdgeViolation>()
        for ((fromRole, tos) in roleAdj) {
            if (!RuleUtil.roleAllowed(rule, scope, fromRole)) continue
            for (toRole in tos.sorted()) {
                if (fromRole == toRole) continue
                val allowedTos = allowedAdj[fromRole].orEmpty()
                if (toRole !in allowedTos) {
                    violations += RoleEdgeViolation(fromRole, toRole)
                }
            }
        }

        if (violations.isEmpty()) return emptyList()

        // Deterministic: sort by (fromRole, toRole)
        violations.sortWith(compareBy({ it.fromRole }, { it.toRole }))

        val out = ArrayList<Finding>(violations.size)
        for (v in violations) {
            val examples =
                collectExamples(
                    facts = facts,
                    classByFqn = classByFqn,
                    scope = scope,
                    fromRole = v.fromRole,
                    toRole = v.toRole,
                    limit = 10,
                )

            val anchorClass = examples.firstOrNull()?.first?.let { classByFqn[it] }
            val filePath = anchorClass?.let { RuleUtil.filePathOf(it.location) } ?: ""

            out +=
                Finding(
                    ruleId = RuleUtil.canonicalRuleId(rule),
                    message = "Disallowed role dependency observed: '${v.fromRole}' -> '${v.toRole}' (not in allow list).",
                    filePath = filePath,
                    severity = rule.severity,
                    classFqn = anchorClass?.fqName,
                    memberName = null,
                    data =
                        buildMap {
                            put("fromRole", v.fromRole)
                            put("toRole", v.toRole)
                            put("allowCount", allowEdges.size.toString())
                            if (examples.isNotEmpty()) {
                                put("examples", examples.joinToString(",") { (a, b) -> "$a->$b" })
                                if (examples.size >= 10) put("examplesTruncated", "true")
                            }
                        },
                )
        }

        return out
    }

    private fun readAllowEdges(rule: RuleDef): List<Pair<String, String>>? {
        val p = Params.of(rule.params, path = "rules.${rule.type}.${rule.name}.params")
        val allow: List<String> =
            try {
                p.requireStringList("allow", nonEmpty = true).map { it.trim() }
            } catch (_: ParamError) {
                // validator should catch; engine stays resilient
                return null
            }

        val parsed = ArrayList<Pair<String, String>>(allow.size)
        for (edge in allow) {
            if (edge.isBlank()) continue
            val parts = edge.split("->")
            if (parts.size != 2) continue
            val from = parts[0].trim()
            val to = parts[1].trim()
            if (from.isEmpty() || to.isEmpty()) continue
            parsed += from to to
        }

        // Deterministic: unique + sort
        return parsed
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
    }

    /**
     * Observed role adjacency from facts edges, filtered by scope using the "from" class.
     * Only includes edges where both endpoints have roles.
     */
    private fun buildObservedRoleAdjacency(
        facts: FactIndex,
        classByFqn: Map<String, ClassFact>,
        scope: RuleUtil.CompiledScope,
    ): Map<String, Set<String>> {
        val adj = LinkedHashMap<String, LinkedHashSet<String>>()

        // Deterministic iteration over edges:
        val edges =
            facts.edges.sortedWith(
                compareBy<DependencyEdge>({ it.from.fqName }, { it.to.fqName }, { it.kind.name }, { it.detail ?: "" }),
            )

        for (e in edges) {
            val fromFqn = e.from.fqName
            val toFqn = e.to.fqName

            val fromRole = facts.classToRole[fromFqn] ?: continue
            val toRole = facts.classToRole[toFqn] ?: continue

            // Apply package/glob scope using the producing class (from class).
            val fromClass = classByFqn[fromFqn] ?: continue
            if (!RuleUtil.classInScope(fromClass, scope)) continue

            adj.getOrPut(fromRole) { LinkedHashSet() }.add(toRole)
        }

        return adj.mapValues { (_, v) -> v.toSet() }
    }

    /**
     * Collect deterministic examples (fromClassFqn -> toClassFqn) that produced a role edge.
     * Scope filters are applied using fromClass (same as adjacency).
     */
    private fun collectExamples(
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
            val a = e.from.fqName
            val b = e.to.fqName

            val ra = facts.classToRole[a] ?: continue
            val rb = facts.classToRole[b] ?: continue
            if (ra != fromRole || rb != toRole) continue

            val fromClass = classByFqn[a] ?: continue
            if (!RuleUtil.classInScope(fromClass, scope)) continue

            if (examples.add(a to b) && examples.size >= limit) break
        }

        return examples.toList()
    }

    private data class RoleEdgeViolation(
        val fromRole: String,
        val toRole: String,
    )
}
