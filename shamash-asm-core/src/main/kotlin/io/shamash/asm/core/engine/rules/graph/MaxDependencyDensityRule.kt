/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.engine.rules.graph

import io.shamash.artifacts.contract.Finding
import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.validation.v1.GraphThresholdParams
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.facts.query.FactIndex
import kotlin.math.roundToInt

class MaxDependencyDensityRule : Rule {
    override val id: String = "graph.maxDependencyDensity"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val max = GraphThresholdParams.density(rule.params, "rules.${rule.type}.${rule.name}.params")
        val granularity = Granularity.PACKAGE
        val includeExternal = false

        val scope = RuleUtil.compileScope(rule.scope)

        val g =
            RuleUtil.buildDependencyGraph(
                facts = facts,
                granularity = granularity,
                includeExternalBuckets = includeExternal,
                scope = scope,
                rule = rule,
            )

        val density = RuleUtil.dependencyDensity(g)
        if (density <= max) return emptyList()

        val anchor =
            facts.classes
                .asSequence()
                .sortedBy { it.fqName }
                .first { c ->
                    val roleId = facts.classToRole[c.fqName]
                    RuleUtil.roleAllowed(rule, scope, roleId) && RuleUtil.classInScope(c, scope)
                }

        val densityPct = (density * 100.0 * 100.0).roundToInt() / 100.0

        return listOf(
            Finding(
                ruleId = RuleUtil.canonicalRuleId(rule),
                message =
                    "Dependency density ($densityPct%) exceeds max " +
                        "(${max * 100.0}%) at granularity '${granularity.name.lowercase()}'.",
                filePath = RuleUtil.filePathOf(anchor.location),
                severity = rule.severity,
                classFqn = anchor.fqName,
                memberName = null,
                data =
                    buildMap {
                        put("max", max.toString())
                        put("density", density.toString())
                        put("nodes", g.nodes.size.toString())
                        put("edges", g.edgeCount.toString())
                        put("granularity", granularity.name.lowercase())
                        put("includeExternal", includeExternal.toString())
                    },
            ),
        )
    }
}
