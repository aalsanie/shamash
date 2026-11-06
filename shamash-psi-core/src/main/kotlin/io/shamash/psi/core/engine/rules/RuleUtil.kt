/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
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
        val scope = rule.scope
        val selectedRoles = rule.roles?.toSet()
        val includeRoles = scope?.includeRoles?.toSet()
        val excludeRoles = scope?.excludeRoles?.toSet()
        val includePackages = scope?.includePackages?.map(::Regex)
        val excludePackages = scope?.excludePackages?.map(::Regex)
        val includeGlobs = scope?.includeGlobs
        val excludeGlobs = scope?.excludeGlobs

        return facts.classes.filter { owner ->
            val role = facts.classToRole[owner.fqName]
            if (selectedRoles != null && role !in selectedRoles) return@filter false
            if (includeRoles != null && role !in includeRoles) return@filter false
            if (excludeRoles != null && role in excludeRoles) return@filter false
            if (includePackages != null && includePackages.none { it.containsMatchIn(owner.packageName) }) return@filter false
            if (excludePackages != null && excludePackages.any { it.containsMatchIn(owner.packageName) }) return@filter false
            if (includeGlobs != null && includeGlobs.none { GlobMatcher.matches(it, owner.filePath) }) return@filter false
            if (excludeGlobs != null && excludeGlobs.any { GlobMatcher.matches(it, owner.filePath) }) return@filter false
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
