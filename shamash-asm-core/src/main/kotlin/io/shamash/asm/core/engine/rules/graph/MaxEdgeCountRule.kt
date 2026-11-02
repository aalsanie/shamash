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

class MaxEdgeCountRule : Rule {
    override val id: String = "graph.maxEdgeCount"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val max = GraphThresholdParams.edges(rule.params, "rules.${rule.type}.${rule.name}.params")
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

        val count = g.edgeCount
        if (count <= max) return emptyList()

        val anchor =
            facts.classes
                .asSequence()
                .sortedBy { it.fqName }
                .firstOrNull { c ->
                    val roleId = facts.classToRole[c.fqName]
                    RuleUtil.roleAllowed(rule, scope, roleId) && RuleUtil.classInScope(c, scope)
                }

        return listOf(
            Finding(
                ruleId = RuleUtil.canonicalRuleId(rule),
                message = "Edge count ($count) exceeds max ($max) at granularity '${granularity.name.lowercase()}'.",
                filePath = anchor?.let { RuleUtil.filePathOf(it.location) } ?: "",
                severity = rule.severity,
                classFqn = anchor?.fqName,
                memberName = null,
                data =
                    buildMap {
                        put("max", max.toString())
                        put("count", count.toString())
                        put("nodes", g.nodes.size.toString())
                        put("granularity", granularity.name.lowercase())
                        put("includeExternal", includeExternal.toString())
                    },
            ),
        )
    }
}
