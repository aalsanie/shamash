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
package io.shamash.psi.core.engine.rules

import com.intellij.psi.PsiFile
import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.engine.EngineRule
import io.shamash.psi.core.facts.model.v1.FactsIndex
import io.shamash.psi.core.facts.model.v1.Visibility

/**
 * Enforces maximum number of methods per class by role.
 *
 * Params (v1):
 * - limits: required non-empty map { roleId: <non-negative int> }.
 * - countKinds: optional list of counting strategies:
 *    - DECLARED_METHODS (default)
 *    - PUBLIC_METHODS
 *    - PRIVATE_METHODS
 * - ignoreMethodNameRegex: optional list of regex strings; matching methods are ignored.
 */
class MetricsMaxMethodsByRoleRule : EngineRule {
    override val id: String = "metrics.maxMethodsByRole"

    private enum class CountKind { DECLARED_METHODS, PUBLIC_METHODS, PRIVATE_METHODS }

    override fun evaluate(
        file: PsiFile,
        facts: FactsIndex,
        rule: RuleDef,
        config: ShamashPsiConfigV1,
    ): List<Finding> {
        val ruleInstanceId = RuleUtil.ruleInstanceId(rule, fallbackEngineRuleId = id)
        val p = Params.of(rule.params, "rules.${rule.type}.${rule.name}.params")

        val limits = p.requireMap("limits")
        if (limits.isEmpty()) return emptyList()

        val countKinds =
            (p.optionalStringList("countKinds") ?: listOf(CountKind.DECLARED_METHODS.name))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { raw ->
                    runCatching { CountKind.valueOf(raw) }.getOrNull()
                }.ifEmpty { listOf(CountKind.DECLARED_METHODS) }
                .toSet()

        val ignoreRegexes =
            (p.optionalStringList("ignoreMethodNameRegex") ?: emptyList())
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { rx ->
                    try {
                        Regex(rx)
                    } catch (_: Throwable) {
                        throw ParamError(
                            "${p.currentPath}.ignoreMethodNameRegex",
                            "Invalid regex '$rx'",
                        )
                    }
                }

        // roleId -> max
        val roleToMax = LinkedHashMap<String, Int>(limits.size)
        for ((rawRole, rawMax) in limits) {
            val roleId = rawRole.trim()
            if (roleId.isEmpty()) continue

            val entryPath = "${p.currentPath}.limits.$rawRole"
            val v = Params.of(mapOf("v" to rawMax), entryPath).requireInt("v", min = 0)
            roleToMax[roleId] = v
        }
        if (roleToMax.isEmpty()) return emptyList()

        val sev = RuleUtil.severity(rule)
        val filePath = normalizePath(file.virtualFile?.path ?: file.name)

        // Index methods by class FQN (facts.methods includes only file-local methods already).
        val methodsByClass = facts.methods.groupBy { it.containingClassFqn }

        val out = ArrayList<Finding>()
        for ((clsFqn, methods) in methodsByClass.entries.sortedBy { it.key }) {
            val role = facts.classToRole[clsFqn] ?: continue
            val max = roleToMax[role] ?: continue

            val counted =
                methods
                    .asSequence()
                    .filter { !it.isConstructor }
                    .filter { m -> ignoreRegexes.none { rx -> rx.containsMatchIn(m.name) } }
                    .filter { m -> shouldCount(m.visibility, countKinds) }
                    .count()

            if (counted > max) {
                out +=
                    Finding(
                        ruleId = ruleInstanceId,
                        message = "Class '$clsFqn' (role '$role') has $counted methods, exceeding max=$max.",
                        filePath = filePath,
                        severity = sev,
                        classFqn = clsFqn,
                        data =
                            mapOf(
                                "role" to role,
                                "max" to max.toString(),
                                "actual" to counted.toString(),
                                "countKinds" to countKinds.joinToString(","),
                            ),
                    )
            }
        }

        return out
    }

    private fun shouldCount(
        visibility: Visibility,
        kinds: Set<CountKind>,
    ): Boolean {
        if (CountKind.DECLARED_METHODS in kinds) return true
        if (CountKind.PUBLIC_METHODS in kinds && visibility == Visibility.PUBLIC) return true
        if (CountKind.PRIVATE_METHODS in kinds && visibility == Visibility.PRIVATE) return true
        return false
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/')
}
