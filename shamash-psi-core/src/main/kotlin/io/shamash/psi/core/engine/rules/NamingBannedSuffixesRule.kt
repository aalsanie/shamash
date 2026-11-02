/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.engine.rules

import com.intellij.psi.PsiFile
import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.Params
import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.engine.EngineRule
import io.shamash.psi.core.facts.model.v1.FactsIndex

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
