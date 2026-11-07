/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.engine.rules.graph

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.ParamError
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.facts.query.FactIndex

/** Detects violations with a full SCC sweep; bounded cycle samples only explain the findings. */
class NoCyclesRule : Rule {
    override val id: String = "graph.noCycles"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        if (rule.params.isNotEmpty()) {
            throw ParamError("rules.${rule.type}.${rule.name}.params", "does not accept parameters")
        }
        val scope = RuleUtil.compileScope(rule.scope)

        val g =
            RuleUtil.buildDependencyGraph(
                facts = facts,
                granularity = config.analysis.graphs.granularity,
                includeExternalBuckets = config.analysis.graphs.includeExternalBuckets,
                scope = scope,
                rule = rule,
            )

        val cyclic = RuleUtil.cyclicComponents(g)
        if (cyclic.isEmpty()) return emptyList()

        val limit = 10

        val anchorClass =
            facts.classes
                .asSequence()
                .sortedBy { it.fqName }
                .firstOrNull { c ->
                    RuleUtil.ownerAllowed(c, rule, scope, facts.classToRole)
                }

        val anchorPath = anchorClass?.let { RuleUtil.filePathOf(it.location) } ?: ""

        val repCycles =
            RuleUtil.representativeCyclesBounded(
                nodes = g.nodes.sorted(),
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
                    message = "Cycle detected at granularity '${config.analysis.graphs.granularity.name.lowercase()}': $cycleStr",
                    filePath = anchorPath,
                    severity = rule.severity,
                    classFqn = anchorClass?.fqName,
                    memberName = null,
                    data =
                        buildMap {
                            put(
                                "granularity",
                                config.analysis.graphs.granularity.name
                                    .lowercase(),
                            )
                            put(
                                "includeExternal",
                                config.analysis.graphs.includeExternalBuckets
                                    .toString(),
                            )
                            put("cycleIndex", (idx + 1).toString())
                            put("cycleCount", cycleCount.toString()) // SCC count, from full sweep
                            put("cycle", cycleStr)
                            if (cycleCount > limit) put("cyclesTruncated", "true")
                        },
                )
        }

        // A sampling limit must not suppress a finding from the full SCC sweep.
        if (out.isEmpty()) {
            out +=
                Finding(
                    ruleId = RuleUtil.canonicalRuleId(rule),
                    message =
                        "Cycle detected at granularity " +
                            "'${config.analysis.graphs.granularity.name.lowercase()}', " +
                            "but cycle details were truncated.",
                    filePath = anchorPath,
                    severity = rule.severity,
                    classFqn = anchorClass?.fqName,
                    memberName = null,
                    data =
                        buildMap {
                            put(
                                "granularity",
                                config.analysis.graphs.granularity.name
                                    .lowercase(),
                            )
                            put(
                                "includeExternal",
                                config.analysis.graphs.includeExternalBuckets
                                    .toString(),
                            )
                            put("cycleCount", cycleCount.toString())
                            put("cyclesTruncated", "true")
                        },
                )
        }

        return out
    }
}
