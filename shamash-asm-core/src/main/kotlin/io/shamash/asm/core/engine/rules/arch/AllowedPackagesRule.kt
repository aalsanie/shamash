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
package io.shamash.asm.core.engine.rules.arch

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.facts.query.FactIndex
import java.util.regex.Pattern

/**
 * arch.allowedPackages
 *
 * Params:
 * - allowPackages: [ "<regex>", ... ]  (non-empty)
 *
 * Semantics:
 * - For classes in-scope (roles + scope filters), their package MUST match at least one allowPackages regex.
 * - If not, a finding is emitted for that class.
 */
class AllowedPackagesRule : Rule {
    override val id: String = "arch.allowedPackages"

    override fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding> {
        val allow = compileAllowPackages(rule) ?: return emptyList()
        if (allow.isEmpty()) return emptyList()

        val scope = RuleUtil.compileScope(rule.scope)

        val out = ArrayList<Finding>()

        // deterministic iteration
        val classes = facts.classes.sortedBy { it.fqName }

        for (c in classes) {
            val roleId = facts.classToRole[c.fqName]
            if (!RuleUtil.roleAllowed(rule, scope, roleId)) continue
            if (!RuleUtil.classInScope(c, scope)) continue

            val pkg = c.packageName // can be "" (default package)
            if (matchesAny(allow, pkg)) continue

            out +=
                Finding(
                    ruleId = RuleUtil.canonicalRuleId(rule),
                    message =
                        buildString {
                            append("Package '")
                            append(if (pkg.isEmpty()) "<default>" else pkg)
                            append("' is not allowed for class '")
                            append(c.fqName)
                            append("'.")
                        },
                    filePath = RuleUtil.filePathOf(c.location),
                    severity = rule.severity,
                    classFqn = c.fqName,
                    memberName = null,
                    data =
                        mapOf(
                            "classInternalName" to c.type.internalName,
                            "packageName" to pkg,
                            "roleId" to (roleId ?: ""),
                            "allowPackagesCount" to allow.size.toString(),
                        ),
                )
        }

        return out
    }

    private fun compileAllowPackages(rule: RuleDef): List<Pattern>? {
        val p = Params.of(rule.params, path = "rules.${rule.type}.${rule.name}.params")
        val raw: List<String> =
            try {
                p.requireStringList("allowPackages", nonEmpty = true)
            } catch (_: ParamError) {
                // Config validator should catch; engine stays resilient.
                return null
            }

        return raw
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Pattern.compile(it) }
    }

    private fun matchesAny(
        patterns: List<Pattern>,
        packageName: String,
    ): Boolean {
        for (p in patterns) {
            if (p.matcher(packageName).find()) return true
        }
        return false
    }
}
