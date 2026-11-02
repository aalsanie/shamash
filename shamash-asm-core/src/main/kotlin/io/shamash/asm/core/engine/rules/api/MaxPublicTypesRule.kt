/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.engine.rules.api

import io.shamash.artifacts.contract.Finding
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.validation.v1.ApiPublicTypesParams
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.facts.model.Visibility
import org.objectweb.asm.Opcodes
import io.shamash.asm.core.facts.query.FactIndex

class MaxPublicTypesRule : Rule {
    override val id: String = "api.maxPublicTypes"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val params = ApiPublicTypesParams.decode(rule.params, "rules.${rule.type}.${rule.name}.params")

        val scope = RuleUtil.compileScope(rule.scope)

        val publicClassesInScope =
            facts.classes
                .asSequence()
                .sortedBy { it.fqName }
                .filter { c ->
                    c.visibility == Visibility.PUBLIC &&
                        (params.includeNested || !c.isNestedType) &&
                        (params.includeAnnotations || (c.access and Opcodes.ACC_ANNOTATION) == 0) &&
                        (params.includeEnums || !c.isEnum) &&
                        (params.includeInterfaces || !c.isInterface || (c.access and Opcodes.ACC_ANNOTATION) != 0)
                }
                .filter { c ->
                    RuleUtil.ownerAllowed(c, rule, scope, facts.classToRole)
                }.toList()

        val count = publicClassesInScope.size
        if (count <= params.max) return emptyList()

        // Bound examples to keep findings small on large projects.
        val examplesLimit = 25
        val examples = publicClassesInScope.take(examplesLimit).map { it.fqName }

        val anchor = publicClassesInScope.firstOrNull()

        return listOf(
            Finding(
                ruleId = RuleUtil.canonicalRuleId(rule),
                message = "Public API types count ($count) exceeds max (${params.max}).",
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

}
