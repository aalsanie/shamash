/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.engine.rules.api

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.facts.model.Visibility
import io.shamash.asm.core.facts.query.FactIndex

class MaxPublicTypesRule : Rule {
    override val id: String = "api.maxPublicTypes"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val max = readMax(rule) ?: return emptyList()

        val scope = RuleUtil.compileScope(rule.scope)

        val publicClassesInScope =
            facts.classes
                .asSequence()
                .sortedBy { it.fqName }
                .filter { it.visibility == Visibility.PUBLIC }
                .filter { c ->
                    val roleId = facts.classToRole[c.fqName]
                    RuleUtil.roleAllowed(rule, scope, roleId) && RuleUtil.classInScope(c, scope)
                }.toList()

        val count = publicClassesInScope.size
        if (count <= max) return emptyList()

        // Bound examples to keep findings small on large projects.
        val examplesLimit = 25
        val examples = publicClassesInScope.take(examplesLimit).map { it.fqName }

        val anchor = publicClassesInScope.firstOrNull()

        return listOf(
            Finding(
                ruleId = RuleUtil.canonicalRuleId(rule),
                message = "Public API types count ($count) exceeds max ($max).",
                filePath = anchor?.let { RuleUtil.filePathOf(it.location) } ?: "",
                severity = rule.severity,
                classFqn = anchor?.fqName,
                memberName = null,
                data =
                    buildMap {
                        put("max", max.toString())
                        put("count", count.toString())
                        put("examples", examples.joinToString(","))
                        if (count > examplesLimit) put("examplesTruncated", "true")
                    },
            ),
        )
    }

    private fun readMax(rule: RuleDef): Int? {
        val p = Params.of(rule.params, path = "rules.${rule.type}.${rule.name}.params")
        return try {
            val v = p.requireInt("max")
            if (v < 0) null else v
        } catch (_: ParamError) {
            null
        }
    }
}
