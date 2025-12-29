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

class MetricsMaxMethodsByRoleRule : EngineRule {
    override val id: String = "metrics.maxMethodsByRole"

    override fun evaluate(
        file: PsiFile,
        facts: FactsIndex,
        rule: Rule,
        config: ShamashPsiConfigV1,
    ): List<Finding> {
        val role = rule.params["role"]?.toString() ?: return emptyList()
        val max = (rule.params["max"] as? Number)?.toInt() ?: return emptyList()
        val countConstructors = RuleUtil.boolParam(rule, "countConstructors", false)

        val sev = RuleUtil.severity(rule)
        val filePath = file.virtualFile?.path ?: file.name

        val classesInRole = facts.roles[role] ?: emptySet()
        if (classesInRole.isEmpty()) return emptyList()

        // Count methods per class for better localization.
        val methodsByClass =
            facts.methods
                .filter { classesInRole.contains(it.containingClassFqn) }
                .filter { countConstructors || !it.isConstructor }
                .groupBy { it.containingClassFqn }
                .mapValues { it.value.size }

        val out = mutableListOf<Finding>()

        for ((cls, count) in methodsByClass) {
            if (count > max) {
                out +=
                    Finding(
                        ruleId = id,
                        message = "Class '$cls' (role '$role') has $count methods, exceeding max=$max.",
                        filePath = filePath,
                        severity = sev,
                        classFqn = cls,
                    )
            }
        }

        return out
    }
}
