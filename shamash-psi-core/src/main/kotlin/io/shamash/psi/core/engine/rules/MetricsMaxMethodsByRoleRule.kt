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
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.config.validation.v1.MethodCountKinds
import io.shamash.psi.core.config.validation.v1.MethodCountKinds.Kind
import io.shamash.psi.core.engine.EngineRule
import io.shamash.psi.core.facts.model.v1.FactsIndex
import io.shamash.psi.core.facts.model.v1.Visibility

class MetricsMaxMethodsByRoleRule : EngineRule {
    override val id: String = "metrics.maxMethodsByRole"

    override fun evaluate(
        file: PsiFile,
        facts: FactsIndex,
        rule: RuleDef,
        config: ShamashPsiConfigV1,
    ): List<Finding> {
        val ruleInstanceId = RuleUtil.ruleInstanceId(rule, fallbackEngineRuleId = id)
        val p = Params.of(rule.params, "rules.${rule.type}.${rule.name}.params")

        val limits = p.requireMap("limits")
        if (limits.isEmpty()) throw ParamError("${p.currentPath}.limits", "must not be empty")

        val countKinds = MethodCountKinds.decode(rule.params, p.currentPath)

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

        val roleToMax = LinkedHashMap<String, Int>(limits.size)
        for ((rawRole, rawMax) in limits) {
            val roleId = rawRole.trim()
            if (roleId.isEmpty()) throw ParamError("${p.currentPath}.limits", "role name must be non-empty")

            val entryPath = "${p.currentPath}.limits.$rawRole"
            val v = Params.of(mapOf("v" to rawMax), entryPath).requireInt("v", min = 0)
            roleToMax[roleId] = v
        }
        if (roleToMax.isEmpty()) throw ParamError("${p.currentPath}.limits", "must contain a role")

        val sev = RuleUtil.severity(rule)
        val filePath = normalizePath(file.virtualFile?.path ?: file.name)

        val eligibleOwners = RuleUtil.scopedClasses(facts, rule).mapTo(HashSet()) { it.fqName }
        val methodsByClass = facts.methods.groupBy { it.containingClassFqn }

        val out = ArrayList<Finding>()
        for ((clsFqn, methods) in methodsByClass.entries.sortedBy { it.key }) {
            if (clsFqn !in eligibleOwners) continue
            val role = facts.classToRole[clsFqn] ?: continue
            val max = roleToMax[role] ?: continue

            val counted =
                methods
                    .asSequence()
                    .filter { !it.isConstructor }
                    .filter { m -> ignoreRegexes.none { rx -> rx.containsMatchIn(m.name) } }
                    .filter { m -> shouldCount(m.visibility, countKinds) }
                    .distinctBy { it.signature }
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
                                "countKinds" to countKinds.map(MethodCountKinds::wireName).sorted().joinToString(","),
                            ),
                    )
            }
        }

        return out
    }

    private fun shouldCount(
        visibility: Visibility,
        kinds: Set<Kind>,
    ): Boolean =
        Kind.DECLARED in kinds ||
            (Kind.PUBLIC in kinds && visibility == Visibility.PUBLIC) ||
            (Kind.PRIVATE in kinds && visibility == Visibility.PRIVATE)

    private fun normalizePath(path: String): String = path.replace('\\', '/')
}
