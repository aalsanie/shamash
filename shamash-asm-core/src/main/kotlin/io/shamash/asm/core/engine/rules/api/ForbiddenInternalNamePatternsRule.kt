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
import java.util.regex.Pattern

/** Matches JVM internal names (slash-separated) of public classes only. */
class ForbiddenInternalNamePatternsRule : Rule {
    override val id: String = "api.forbiddenInternalNamePatterns"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val patterns = compileForbidPatterns(rule) ?: return emptyList()
        if (patterns.isEmpty()) return emptyList()

        val scope = RuleUtil.compileScope(rule.scope)

        val out = ArrayList<Finding>()

        val classes = facts.classes.sortedBy { it.fqName }

        for (c in classes) {
            if (c.visibility != Visibility.PUBLIC) continue

            if (!RuleUtil.ownerAllowed(c, rule, scope, facts.classToRole)) continue

            val internalName = c.type.internalName
            val matched = firstMatchingPattern(patterns, internalName) ?: continue

            out +=
                Finding(
                    ruleId = RuleUtil.canonicalRuleId(rule),
                    message = "Forbidden internal name pattern matched public API type: '$internalName' (matched: $matched)",
                    filePath = RuleUtil.filePathOf(c.location),
                    severity = rule.severity,
                    classFqn = c.fqName,
                    memberName = null,
                    data =
                        mapOf(
                            "classInternalName" to internalName,
                            "matchedPattern" to matched,
                        ),
                )
        }

        return out
    }

    private fun compileForbidPatterns(rule: RuleDef): List<Pattern>? {
        val p = Params.of(rule.params, path = "rules.${rule.type}.${rule.name}.params")

        val forbid: List<String> =
            try {
                p.requireStringList("forbid", nonEmpty = true)
            } catch (_: ParamError) {
                return null
            }

        return forbid
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Pattern.compile(it) }
    }

    private fun firstMatchingPattern(
        patterns: List<Pattern>,
        internalName: String,
    ): String? {
        for (p in patterns) {
            if (p.matcher(internalName).find()) return p.pattern()
        }
        return null
    }
}
