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

import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.util.glob.GlobMatcher
import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.schema.v1.model.Severity
import io.shamash.psi.core.facts.model.v1.ClassFact
import io.shamash.psi.core.facts.model.v1.FactsIndex

internal object RuleUtil {
    fun severity(rule: RuleDef): FindingSeverity =
        when (rule.severity) {
            Severity.ERROR -> FindingSeverity.ERROR
            Severity.WARNING -> FindingSeverity.WARNING
            Severity.INFO -> FindingSeverity.INFO
        }

    fun scopedClasses(
        facts: FactsIndex,
        rule: RuleDef,
    ): List<ClassFact> {
        val scope = rule.scope ?: return facts.classes

        val includeRoles = scope.includeRoles?.toSet()
        val excludeRoles = scope.excludeRoles?.toSet()

        val includePkg = scope.includePackages?.map { Regex(it) }
        val excludePkg = scope.excludePackages?.map { Regex(it) }

        val includeGlobs = scope.includeGlobs
        val excludeGlobs = scope.excludeGlobs

        return facts.classes.filter { c ->
            val role = facts.classToRole[c.fqName]

            if (includeRoles != null && (role == null || !includeRoles.contains(role))) return@filter false
            if (excludeRoles != null && role != null && excludeRoles.contains(role)) return@filter false

            if (includePkg != null && includePkg.none { it.containsMatchIn(c.packageName) }) return@filter false
            if (excludePkg != null && excludePkg.any { it.containsMatchIn(c.packageName) }) return@filter false

            if (includeGlobs != null) {
                val ok = includeGlobs.any { g -> GlobMatcher.matches(g, c.filePath) }
                if (!ok) return@filter false
            }
            if (excludeGlobs != null) {
                val bad = excludeGlobs.any { g -> GlobMatcher.matches(g, c.filePath) }
                if (bad) return@filter false
            }

            true
        }
    }

    fun ruleInstanceId(
        rule: RuleDef,
        fallbackEngineRuleId: String,
    ): String {
        val type = rule.type.trim()
        val name = rule.name.trim()
        return if (type.isNotEmpty() && name.isNotEmpty()) "$type.$name" else fallbackEngineRuleId
    }
}
