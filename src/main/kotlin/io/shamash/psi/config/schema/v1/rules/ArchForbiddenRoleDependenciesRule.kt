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
package io.shamash.psi.config.schema.v1.rules

import com.intellij.psi.PsiFile
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.engine.EngineRule
import io.shamash.psi.engine.Finding
import io.shamash.psi.facts.model.v1.DependencyKind
import io.shamash.psi.facts.model.v1.FactsIndex
import io.shamash.psi.rules.RuleUtil

class ArchForbiddenRoleDependenciesRule : EngineRule {
    override val id: String = "arch.forbiddenRoleDependencies"

    override fun evaluate(
        file: PsiFile,
        facts: FactsIndex,
        rule: io.shamash.psi.config.schema.v1.model.Rule,
        config: ShamashPsiConfigV1,
    ): List<Finding> {
        val forbidden = RuleUtil.listOfMapsParam(rule, "forbidden")
        if (forbidden.isEmpty()) return emptyList()

        val kindsRaw = RuleUtil.stringListParam(rule, "kinds")
        val allowedKinds: Set<DependencyKind>? =
            if (kindsRaw.isEmpty()) {
                null
            } else {
                kindsRaw.mapNotNull { runCatching { DependencyKind.valueOf(it) }.getOrNull() }.toSet()
            }

        val sev = RuleUtil.severity(rule)
        val filePath = file.virtualFile?.path ?: file.name

        // Build map: fromRole -> set(toRoles)
        val forbMap = mutableMapOf<String, Set<String>>()
        for (m in forbidden) {
            val from = m["from"]?.toString() ?: continue
            val toList = (m["to"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
            if (toList.isNotEmpty()) forbMap[from] = toList
        }
        if (forbMap.isEmpty()) return emptyList()

        val out = mutableListOf<Finding>()

        for (d in facts.dependencies) {
            if (allowedKinds != null && !allowedKinds.contains(d.kind)) continue

            val fromRole = facts.classToRole[d.fromClassFqn] ?: continue

            // We depend on types; find the *role* of the target type if it’s one of our classes.
            // If d.toTypeFqn is external (JDK, libs), it has no role -> ignore.
            val toRole = facts.classToRole[d.toTypeFqn] ?: continue

            val forbiddenTargets = forbMap[fromRole] ?: continue
            if (!forbiddenTargets.contains(toRole)) continue

            out +=
                Finding(
                    ruleId = id,
                    message =
                        "Forbidden dependency: role '$fromRole' (${d.fromClassFqn}) " +
                            "depends on role '$toRole' (${d.toTypeFqn}) " +
                            "via ${d.kind}${d.detail?.let { " ($it)" } ?: ""}.",
                    filePath = filePath,
                    severity = sev,
                    classFqn = d.fromClassFqn,
                )
        }

        return out
    }
}
