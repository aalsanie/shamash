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

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.shamash.psi.config.schema.v1.model.Rule
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.engine.EngineRule
import io.shamash.psi.engine.Finding
import io.shamash.psi.facts.model.v1.FactsIndex
import io.shamash.psi.rules.RuleUtil

class DeadcodeUnusedPrivateMembersRule : EngineRule {
    override val id: String = "deadcode.unusedPrivateMembers"

    override fun evaluate(
        file: PsiFile,
        facts: FactsIndex,
        rule: Rule,
        config: ShamashPsiConfigV1,
    ): List<Finding> {
        val includeMethods = RuleUtil.boolParam(rule, "includeMethods", true)
        val includeFields = RuleUtil.boolParam(rule, "includeFields", true)

        val sev = RuleUtil.severity(rule)
        val filePath = file.virtualFile?.path ?: file.name
        val project = file.project
        val scope = GlobalSearchScope.projectScope(project)

        val out = mutableListOf<Finding>()

        // We work directly on PSI for accuracy (facts don’t include reference usages).
        val psiClasses = PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java)

        for (cls in psiClasses) {
            val classFqn = cls.qualifiedName ?: continue

            if (includeFields) {
                for (field in cls.fields) {
                    if (!field.hasModifierProperty(PsiModifier.PRIVATE)) continue
                    if (field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL)) {
                        // constants can be used via inlining; still check refs
                    }

                    val refs = ReferencesSearch.search(field, scope).findFirst()
                    if (refs == null) {
                        out +=
                            Finding(
                                ruleId = id,
                                message = "Private field '${field.name}' appears unused.",
                                filePath = filePath,
                                severity = sev,
                                classFqn = classFqn,
                                memberName = field.name,
                            )
                    }
                }
            }

            if (includeMethods) {
                for (m in cls.methods) {
                    if (!m.hasModifierProperty(PsiModifier.PRIVATE)) continue
                    if (m.isConstructor) continue
                    if (m.name == "serialVersionUID") continue

                    // Ignore obvious entrypoints
                    if (m.name == "main" && m.hasModifierProperty(PsiModifier.STATIC)) continue

                    val refs = ReferencesSearch.search(m, scope).findFirst()
                    if (refs == null) {
                        out +=
                            Finding(
                                ruleId = id,
                                message = "Private method '${m.name}' appears unused.",
                                filePath = filePath,
                                severity = sev,
                                classFqn = classFqn,
                                memberName = m.name,
                            )
                    }
                }
            }
        }

        return out
    }
}
