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
import io.shamash.psi.config.validation.v1.params.ParamError
import io.shamash.psi.config.validation.v1.params.Params
import io.shamash.psi.engine.EngineRule
import io.shamash.psi.engine.Finding
import io.shamash.psi.facts.model.v1.FactsIndex
import java.util.LinkedHashMap

/**
 * Enforces that each role's classes live under an expected package regex.
 *
 * Params (v1):
 * - expected: required non-empty map { roleId: { packageRegex: "..." } }
 */
class PackagesRolePlacementRule : EngineRule {
    override val id: String = "packages.rolePlacement"

    override fun evaluate(
        file: PsiFile,
        facts: FactsIndex,
        rule: RuleDef,
        config: ShamashPsiConfigV1,
    ): List<Finding> {
        val ruleInstanceId = RuleUtil.ruleInstanceId(rule, fallbackEngineRuleId = id)
        val p = Params.of(rule.params, "rules.${rule.type}.${rule.name}.params")

        val expected = p.requireMap("expected")
        if (expected.isEmpty()) return emptyList()

        // roleId -> compiled regex
        val roleToRegex = LinkedHashMap<String, Regex>(expected.size)
        for ((rawRole, rawEntry) in expected) {
            val roleId = rawRole.trim()
            if (roleId.isEmpty()) continue

            val entryPath = "${p.currentPath}.expected.$rawRole"
            val entry = rawEntry as? Map<*, *> ?: throw ParamError(entryPath, "must be an object/map")

            val norm = LinkedHashMap<String, Any?>(entry.size)
            for ((k, v) in entry) {
                if (k == null) throw ParamError(entryPath, "map key must not be null")
                norm[k.toString()] = v
            }
            val ep = Params.of(norm, entryPath)
            val pkgRegex = ep.requireString("packageRegex").trim()
            if (pkgRegex.isEmpty()) throw ParamError("$entryPath.packageRegex", "must be non-empty")

            roleToRegex[roleId] = Regex(pkgRegex)
        }

        if (roleToRegex.isEmpty()) return emptyList()

        val sev = RuleUtil.severity(rule)
        val filePath = normalizePath(file.virtualFile?.path ?: file.name)

        val out = ArrayList<Finding>()
        for (c in RuleUtil.scopedClasses(facts, rule)) {
            val role = facts.classToRole[c.fqName] ?: continue
            val rx = roleToRegex[role] ?: continue

            val pkg = c.packageName
            if (!rx.containsMatchIn(pkg)) {
                out +=
                    Finding(
                        ruleId = ruleInstanceId,
                        message =
                            "Role '$role' class '${c.fqName}' is in package " +
                                "'$pkg' which does not match expected packageRegex='${rx.pattern}'.",
                        filePath = filePath,
                        severity = sev,
                        classFqn = c.fqName,
                        data =
                            mapOf(
                                "role" to role,
                                "packageName" to pkg,
                                "packageRegex" to rx.pattern,
                            ),
                    )
            }
        }

        return out
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/')
}
