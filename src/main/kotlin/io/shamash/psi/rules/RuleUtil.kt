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
package io.shamash.psi.rules

import io.shamash.psi.config.schema.v1.model.Rule
import io.shamash.psi.config.schema.v1.model.Severity
import io.shamash.psi.engine.FindingSeverity
import io.shamash.psi.facts.model.v1.ClassFact
import io.shamash.psi.facts.model.v1.FactsIndex
import io.shamash.psi.util.GlobMatcher

internal object RuleUtil {
    fun severity(rule: Rule): FindingSeverity =
        when (rule.severity) {
            Severity.ERROR -> FindingSeverity.ERROR
            Severity.WARNING -> FindingSeverity.WARNING
            Severity.INFO -> FindingSeverity.INFO
        }

    fun scopedClasses(
        facts: FactsIndex,
        rule: Rule,
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

    fun boolParam(
        rule: Rule,
        name: String,
        default: Boolean,
    ): Boolean = (rule.params[name] as? Boolean) ?: default

    fun stringListParam(
        rule: Rule,
        name: String,
    ): List<String> = (rule.params[name] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    fun mapParam(
        rule: Rule,
        name: String,
    ): Map<String, Any?> = (rule.params[name] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()

    fun listOfMapsParam(
        rule: Rule,
        name: String,
    ): List<Map<String, Any?>> =
        (rule.params[name] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.map { m ->
            m.entries.associate { it.key.toString() to it.value }
        } ?: emptyList()
}
