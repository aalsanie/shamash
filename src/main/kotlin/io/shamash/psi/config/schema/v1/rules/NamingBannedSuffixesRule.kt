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
import io.shamash.psi.facts.model.v1.FactsIndex
import io.shamash.psi.rules.RuleUtil

class NamingBannedSuffixesRule : EngineRule {
    override val id: String = "naming.bannedSuffixes"

    override fun evaluate(
        file: PsiFile,
        facts: FactsIndex,
        rule: io.shamash.psi.config.schema.v1.model.Rule,
        config: ShamashPsiConfigV1,
    ): List<Finding> {
        val bannedRaw = RuleUtil.stringListParam(rule, "banned")
        if (bannedRaw.isEmpty()) return emptyList()

        val applyTo = (rule.params["applyTo"] as? String)?.lowercase() ?: "classes"
        val caseSensitive = RuleUtil.boolParam(rule, "caseSensitive", false)
        val applyToRoles = RuleUtil.stringListParam(rule, "applyToRoles").toSet().ifEmpty { null }

        val banned = if (caseSensitive) bannedRaw else bannedRaw.map { it.lowercase() }

        fun endsWithBanned(name: String): String? {
            val n = if (caseSensitive) name else name.lowercase()
            val idx = banned.indexOfFirst { suf -> n.endsWith(suf) }
            return if (idx >= 0) bannedRaw[idx] else null // return original suffix for messaging
        }

        val sev = RuleUtil.severity(rule)
        val filePath = file.virtualFile?.path ?: file.name
        val out = mutableListOf<Finding>()

        // class scope
        val scopedClasses =
            RuleUtil.scopedClasses(facts, rule).filter { c ->
                if (applyToRoles == null) true else applyToRoles.contains(facts.classToRole[c.fqName])
            }
        val scopedClassFqns = scopedClasses.map { it.fqName }.toSet()

        if (applyTo == "classes" || applyTo == "all") {
            for (c in scopedClasses) {
                val bad = endsWithBanned(c.simpleName) ?: continue
                out +=
                    Finding(
                        ruleId = id,
                        message = "Class name '${c.simpleName}' ends with banned suffix '$bad'.",
                        filePath = filePath,
                        severity = sev,
                        classFqn = c.fqName,
                        data =
                            mapOf(
                                "suffix" to bad,
                                "elementKind" to "class",
                                "applyTo" to applyTo,
                            ),
                    )
            }
        }

        if (applyTo == "methods" || applyTo == "all") {
            for (m in facts.methods) {
                if (!scopedClassFqns.contains(m.containingClassFqn)) continue
                val bad = endsWithBanned(m.name) ?: continue
                out +=
                    Finding(
                        ruleId = id,
                        message = "Method name '${m.name}' in ${m.containingClassFqn} ends with banned suffix '$bad'.",
                        filePath = filePath,
                        severity = sev,
                        classFqn = m.containingClassFqn,
                        memberName = m.name,
                        data =
                            mapOf(
                                "suffix" to bad,
                                "elementKind" to "method",
                                "applyTo" to applyTo,
                            ),
                    )
            }
        }

        if (applyTo == "fields" || applyTo == "all") {
            for (f in facts.fields) {
                if (!scopedClassFqns.contains(f.containingClassFqn)) continue
                val bad = endsWithBanned(f.name) ?: continue
                out +=
                    Finding(
                        ruleId = id,
                        message = "Field name '${f.name}' in ${f.containingClassFqn} ends with banned suffix '$bad'.",
                        filePath = filePath,
                        severity = sev,
                        classFqn = f.containingClassFqn,
                        memberName = f.name,
                        data =
                            mapOf(
                                "suffix" to bad,
                                "elementKind" to "field",
                                "applyTo" to applyTo,
                            ),
                    )
            }
        }

        return out
    }
}
