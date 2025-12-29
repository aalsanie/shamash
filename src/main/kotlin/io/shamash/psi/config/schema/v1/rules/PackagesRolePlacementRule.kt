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
import io.shamash.psi.config.schema.v1.model.Rule
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.engine.EngineRule
import io.shamash.psi.engine.Finding
import io.shamash.psi.facts.model.v1.FactsIndex
import io.shamash.psi.rules.RuleUtil

class PackagesRolePlacementRule : EngineRule {
    override val id: String = "packages.rolePlacement"

    override fun evaluate(
        file: PsiFile,
        facts: FactsIndex,
        rule: Rule,
        config: ShamashPsiConfigV1,
    ): List<Finding> {
        val placementAny = RuleUtil.mapParam(rule, "placement")
        if (placementAny.isEmpty()) return emptyList()

        val mode = (rule.params["mode"] as? String)?.lowercase() ?: "any"
        val placement: Map<String, List<Regex>> =
            placementAny.mapValues { (_, v) ->
                val list = (v as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                list.map { Regex(it) }
            }

        val sev = RuleUtil.severity(rule)
        val filePath = file.virtualFile?.path ?: file.name
        val out = mutableListOf<Finding>()

        for (c in RuleUtil.scopedClasses(facts, rule)) {
            val role = facts.classToRole[c.fqName] ?: continue
            val allowed = placement[role] ?: continue

            val ok =
                when (mode) {
                    "all" -> allowed.all { it.containsMatchIn(c.packageName) }
                    else -> allowed.any { it.containsMatchIn(c.packageName) }
                }

            if (!ok) {
                out +=
                    Finding(
                        ruleId = id,
                        message =
                            "Class '${c.fqName}' has role '$role' " +
                                "but is in package '${c.packageName}' " +
                                "which violates placement rules.",
                        filePath = filePath,
                        severity = sev,
                        classFqn = c.fqName,
                    )
            }
        }

        return out
    }
}
