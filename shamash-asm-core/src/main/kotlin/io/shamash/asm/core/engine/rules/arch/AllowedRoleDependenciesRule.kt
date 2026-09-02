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

/**
 * Same-role edges are allowed; external or unclassified endpoints are ignored.
 * Role and package/path scope filters apply to the source of each edge.
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

        val classByFqn: Map<String, ClassFact> = facts.classes.associateBy { it.fqName }

        val roleAdj = buildObservedRoleAdjacency(facts, classByFqn, scope)

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

        return parsed
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
    }

    private fun buildObservedRoleAdjacency(
        facts: FactIndex,
        classByFqn: Map<String, ClassFact>,
        scope: RuleUtil.CompiledScope,
    ): Map<String, Set<String>> {
        val adj = LinkedHashMap<String, LinkedHashSet<String>>()

        val edges =
            facts.edges.sortedWith(
                compareBy<DependencyEdge>({ it.from.fqName }, { it.to.fqName }, { it.kind.name }, { it.detail ?: "" }),
            )

        for (e in edges) {
            val fromFqn = e.from.fqName
            val toFqn = e.to.fqName

            val fromRole = facts.classToRole[fromFqn] ?: continue
            val toRole = facts.classToRole[toFqn] ?: continue

            val fromClass = classByFqn[fromFqn] ?: continue
            if (!RuleUtil.classInScope(fromClass, scope)) continue

            adj.getOrPut(fromRole) { LinkedHashSet() }.add(toRole)
        }

        return adj.mapValues { (_, v) -> v.toSet() }
    }

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
