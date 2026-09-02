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

            val roleId = facts.classToRole[c.fqName]
            if (!RuleUtil.roleAllowed(rule, scope, roleId)) continue

            if (!RuleUtil.classInScope(c, scope)) continue

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
