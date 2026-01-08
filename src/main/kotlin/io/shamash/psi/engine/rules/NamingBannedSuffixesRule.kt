/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
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
package io.shamash.psi.engine.rules

import com.intellij.psi.PsiFile
import io.shamash.psi.config.schema.v1.model.RuleDef
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.config.validation.v1.params.Params
import io.shamash.psi.engine.EngineRule
import io.shamash.psi.engine.Finding
import io.shamash.psi.facts.model.v1.FactsIndex

/**
 * Bans class name suffixes.
 *
 * Params (v1):
 * - banned: required non-empty list of suffixes.
 * - applyToRoles: optional list of roleIds (if present, only classes in these roles are checked).
 * - caseSensitive: optional boolean.
 */
class NamingBannedSuffixesRule : EngineRule {
    override val id: String = "naming.bannedSuffixes"

    override fun evaluate(
        file: PsiFile,
        facts: FactsIndex,
        rule: RuleDef,
        config: ShamashPsiConfigV1,
    ): List<Finding> {
        val ruleInstanceId = RuleUtil.ruleInstanceId(rule, fallbackEngineRuleId = id)
        val p = Params.of(rule.params, "rules.${rule.type}.${rule.name}.params")

        val banned =
            p
                .requireStringList("banned", nonEmpty = true)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (banned.isEmpty()) return emptyList()

        val applyToRoles =
            p
                .optionalStringList("applyToRoles")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?.ifEmpty { null }

        val caseSensitive = p.optionalBoolean("caseSensitive") ?: false

        val matchSuffixes =
            if (caseSensitive) {
                banned
            } else {
                banned.map { it.lowercase() }
            }

        val sev = RuleUtil.severity(rule)
        val filePath = normalizePath(file.virtualFile?.path ?: file.name)

        val out = ArrayList<Finding>()
        for (c in RuleUtil.scopedClasses(facts, rule)) {
            val role = facts.classToRole[c.fqName]
            if (applyToRoles != null && (role == null || role !in applyToRoles)) continue

            val simple = c.simpleName
            val test = if (caseSensitive) simple else simple.lowercase()

            val hit = matchSuffixes.firstOrNull { sfx -> test.endsWith(sfx) } ?: continue

            out +=
                Finding(
                    ruleId = ruleInstanceId,
                    message = "Banned class name suffix '$hit' in '$simple'.",
                    filePath = filePath,
                    severity = sev,
                    classFqn = c.fqName,
                    data =
                        mapOf(
                            "className" to simple,
                            "bannedSuffix" to hit,
                            "caseSensitive" to caseSensitive.toString(),
                            "role" to (role ?: ""),
                        ),
                )
        }

        return out
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/')
}
