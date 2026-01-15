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
package io.shamash.asm.core.engine.rules.graph

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.facts.query.FactIndex

/**
 * graph.noCycles
 *
 * Params:
 * - granularity: "class" | "package" | "module" (optional, default "package")
 * - includeExternal: boolean (optional, default false)
 * - maxCyclesReported: int (optional, default 10)
 *
 * Semantics:
 * - Build dependency graph at requested granularity.
 * - If any cycle exists, emit findings up to maxCyclesReported.
 * - Each finding includes a representative cycle path for debugging.
 *
 * Notes:
 * - Cycle existence is determined using a full sweep (cyclicComponents).
 * - Representative cycle extraction is bounded (memory-safe) via representativeCyclesBounded.
 */
class NoCyclesRule : Rule {
    override val id: String = "graph.noCycles"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val params = readParams(rule) ?: return emptyList()
        val scope = RuleUtil.compileScope(rule.scope)

        val g =
            RuleUtil.buildDependencyGraph(
                facts = facts,
                granularity = params.granularity,
                includeExternalBuckets = params.includeExternal,
                scope = scope,
            )

        // Full sweep: determines truth of violation.
        val cyclic = RuleUtil.cyclicComponents(g)
        if (cyclic.isEmpty()) return emptyList()

        val limit = params.maxCyclesReported
        if (limit <= 0) return emptyList()

        // Anchor: stable class in-scope so findings have a deterministic filePath/classFqn.
        val anchorClass =
            facts.classes
                .asSequence()
                .sortedBy { it.fqName }
                .firstOrNull { c ->
                    val roleId = facts.classToRole[c.fqName]
                    RuleUtil.roleAllowed(rule, scope, roleId) && RuleUtil.classInScope(c, scope)
                }

        val anchorPath = anchorClass?.let { RuleUtil.filePathOf(it.location) } ?: ""

        // Memory-safe cycle examples (do NOT attempt to enumerate all cycles).
        // Important: outgoing is a MAP, so we must provide a lambda.
        val repCycles =
            RuleUtil.representativeCyclesBounded(
                nodes = g.nodes.sorted(), // stable traversal order
                outgoing = { n -> g.outgoing[n].orEmpty() },
                maxCycles = limit,
                maxCycleNodes = 120,
            )

        val cycleCount = cyclic.size
        val cyclesToReport = repCycles.take(limit)

        val out = ArrayList<Finding>(minOf(cyclesToReport.size, limit))
        for ((idx, cycle) in cyclesToReport.withIndex()) {
            val cycleStr = cycle.joinToString(" -> ")
            out +=
                Finding(
                    ruleId = RuleUtil.canonicalRuleId(rule),
                    message = "Cycle detected at granularity '${params.granularity.name.lowercase()}': $cycleStr",
                    filePath = anchorPath,
                    severity = rule.severity,
                    classFqn = anchorClass?.fqName,
                    memberName = null,
                    data =
                        buildMap {
                            put("granularity", params.granularity.name.lowercase())
                            put("includeExternal", params.includeExternal.toString())
                            put("cycleIndex", (idx + 1).toString())
                            put("cycleCount", cycleCount.toString()) // SCC count, from full sweep
                            put("cycle", cycleStr)
                            if (cycleCount > limit) put("cyclesTruncated", "true")
                        },
                )
        }

        // If repCycles ended up empty (edge-case: very constrained bounds), still emit *one* generic finding.
        if (out.isEmpty()) {
            out +=
                Finding(
                    ruleId = RuleUtil.canonicalRuleId(rule),
                    message = "Cycle detected at granularity '${params.granularity.name.lowercase()}', but cycle details were truncated.",
                    filePath = anchorPath,
                    severity = rule.severity,
                    classFqn = anchorClass?.fqName,
                    memberName = null,
                    data =
                        buildMap {
                            put("granularity", params.granularity.name.lowercase())
                            put("includeExternal", params.includeExternal.toString())
                            put("cycleCount", cycleCount.toString())
                            put("cyclesTruncated", "true")
                        },
                )
        }

        return out
    }

    private data class ReadParams(
        val granularity: Granularity,
        val includeExternal: Boolean,
        val maxCyclesReported: Int,
    )

    private fun readParams(rule: RuleDef): ReadParams? {
        val p = Params.of(rule.params, path = "rules.${rule.type}.${rule.name}.params")

        val granularity = parseGranularity(runCatching { p.optionalString("granularity") }.getOrNull())
        val includeExternal = runCatching { p.optionalBoolean("includeExternal") }.getOrNull() ?: false

        val maxCyclesReported =
            try {
                val v = runCatching { p.optionalInt("maxCyclesReported") }.getOrNull()
                when {
                    v == null -> 10
                    v < 0 -> 0
                    else -> v
                }
            } catch (_: ParamError) {
                10
            }

        return ReadParams(
            granularity = granularity,
            includeExternal = includeExternal,
            maxCyclesReported = maxCyclesReported,
        )
    }

    private fun parseGranularity(s: String?): Granularity {
        val v = s?.trim()?.lowercase()
        return when (v) {
            "class" -> Granularity.CLASS
            "module" -> Granularity.MODULE
            "package", null, "" -> Granularity.PACKAGE
            else -> Granularity.PACKAGE
        }
    }
}
