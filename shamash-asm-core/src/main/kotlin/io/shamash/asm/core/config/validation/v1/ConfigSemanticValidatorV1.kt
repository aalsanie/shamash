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
package io.shamash.asm.core.config.validation.v1

import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.config.ValidationSeverity
import io.shamash.asm.core.config.schema.v1.model.AnalysisConfig
import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import io.shamash.asm.core.config.schema.v1.model.ExceptionMatch
import io.shamash.asm.core.config.schema.v1.model.ExportConfig
import io.shamash.asm.core.config.schema.v1.model.GlobSet
import io.shamash.asm.core.config.schema.v1.model.Matcher
import io.shamash.asm.core.config.schema.v1.model.ProjectConfig
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleKey
import io.shamash.asm.core.config.schema.v1.model.RuleScope
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.schema.v1.model.UnknownRulePolicy
import io.shamash.asm.core.config.validation.v1.registry.RuleSpecRegistryV1
import java.util.LinkedHashSet
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Semantic validation for Shamash ASM config V1.
 */
object ConfigSemanticValidatorV1 {
    fun validateSemantic(config: ShamashAsmConfigV1): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // ---- Version ----
        if (config.version != 1) {
            errors += err("version", "Unsupported schema version: ${config.version}")
            return errors
        }

        // ---- Project ----
        validateProject(config.project, errors)

        // ---- Roles ----
        config.roles.forEach { (roleId, role) ->
            val base = "roles.$roleId"
            if (roleId.isBlank()) {
                errors += err(base, "role id must be non-empty")
                return@forEach
            }

            if (role.priority < 0 || role.priority > 100) {
                errors += err("$base.priority", "priority must be between 0 and 100 (inclusive)")
            }

            validateMatcher(role.match, "$base.match", errors)
        }

        // ---- Analysis ----
        validateAnalysis(config.analysis, errors)

        // ---- Rules (uniqueness/scope/spec validation) ----
        val indexed = validateRules(config, errors)

        // ---- Cross-rule invariants (ERROR) ----
        errors += enforceMutuallyExclusiveRuleKinds(config, indexed)

        // ---- Exceptions ----
        validateExceptions(config, errors)

        // ---- Baseline / Export ----
        validateBaseline(config, errors)
        validateExport(config.export, errors)

        return errors
    }

    /**
     * Returns indexed rules so cross-rule checks can point to stable paths.
     */
    private fun validateRules(
        config: ShamashAsmConfigV1,
        errors: MutableList<ValidationError>,
    ): List<IndexedRule> {
        val seenWildcards = LinkedHashSet<Pair<String, String>>() // (type,name)
        val seenSpecific = LinkedHashSet<RuleKey>() // (type,name,role)

        val indexed = ArrayList<IndexedRule>(config.rules.size)

        // ---- Basic sanity + uniqueness + scope ----
        config.rules.forEachIndexed { i, rule ->
            val base = "rules[$i]"
            val type = rule.type.trim()
            val name = rule.name.trim()

            indexed += IndexedRule(index = i, path = base, type = type, name = name, rule = rule)

            if (type.isEmpty()) errors += err("$base.type", "type must be non-empty")
            if (name.isEmpty()) errors += err("$base.name", "name must be non-empty")

            if (type.isNotEmpty() && name.isNotEmpty()) {
                if (rule.roles == null) {
                    val key = type to name
                    if (!seenWildcards.add(key)) {
                        errors += err(base, "Duplicate wildcard rule definition for '$type.$name' (roles: null)")
                    }
                } else {
                    if (rule.roles.isEmpty()) {
                        errors += err("$base.roles", "roles must be non-empty when provided; use null for wildcard")
                    } else {
                        val local = LinkedHashSet<String>()
                        rule.roles.forEachIndexed { rIdx, roleId ->
                            val rid = roleId.trim()
                            if (rid.isEmpty()) {
                                errors += err("$base.roles[$rIdx]", "roleId must be non-empty")
                                return@forEachIndexed
                            }
                            if (!local.add(rid)) {
                                errors += err("$base.roles[$rIdx]", "duplicate role '$rid' in roles list")
                            }
                            if (!config.roles.containsKey(rid)) {
                                errors += err("$base.roles[$rIdx]", "Unknown role '$rid' (not defined under roles)")
                            }

                            val rk = RuleKey(type = type, name = name, role = rid)
                            if (!seenSpecific.add(rk)) {
                                errors += err(base, "Duplicate specific rule definition for '${rk.canonicalId()}'")
                            }
                        }
                    }
                }
            }

            validateScope(rule.scope, "$base.scope", config, errors)
        }

        // ---- Specs (only for enabled rules) ----
        config.rules.forEachIndexed { i, rule ->
            if (!rule.enabled) return@forEachIndexed

            val base = "rules[$i]"
            val type = rule.type.trim()
            val name = rule.name.trim()
            if (type.isEmpty() || name.isEmpty()) return@forEachIndexed

            val spec = RuleSpecRegistryV1.find(type, name)
            if (spec == null) {
                when (config.project.validation.unknownRule) {
                    UnknownRulePolicy.IGNORE, UnknownRulePolicy.ignore -> Unit
                    UnknownRulePolicy.WARN, UnknownRulePolicy.warn ->
                        errors +=
                            ValidationError(
                                path = base,
                                message = "Unknown rule '$type.$name' (no RuleSpec registered; rule will not run)",
                                severity = ValidationSeverity.WARNING,
                            )
                    UnknownRulePolicy.ERROR, UnknownRulePolicy.error ->
                        errors +=
                            ValidationError(
                                path = base,
                                message = "Unknown rule '$type.$name' (no RuleSpec registered)",
                                severity = ValidationSeverity.ERROR,
                            )
                }
                return@forEachIndexed
            }

            // Keep validating other rules regardless of spec failures so user gets full picture.
            errors += spec.validate(rulePath = base, rule = rule, config = config)
        }

        return indexed
    }

    /**
     * Hard invariant: disallow both "allowedX" and "forbiddenX" for the same effective role target.
     *
     * This is an ERROR because:
     * - It prevents ambiguous configs.
     * - It keeps engine enforcement simple and predictable.
     */
    private fun enforceMutuallyExclusiveRuleKinds(
        config: ShamashAsmConfigV1,
        rules: List<IndexedRule>,
    ): List<ValidationError> {
        val out = mutableListOf<ValidationError>()

        // Pairs we disallow. (type, allowName, forbidName)
        val pairs =
            listOf(
                Triple("arch", "allowedRoleDependencies", "forbiddenRoleDependencies"),
                Triple("arch", "allowedPackages", "forbiddenPackages"),
            )

        for ((type, allowName, forbidName) in pairs) {
            val allowRules = rules.filter { it.rule.enabled && it.type == type && it.name == allowName }
            val forbidRules = rules.filter { it.rule.enabled && it.type == type && it.name == forbidName }

            if (allowRules.isEmpty() || forbidRules.isEmpty()) continue

            // Compute per-rule effective role targets.
            // wildcard roles=null => all roles in config
            val allRoles = config.roles.keys

            data class Targeted(
                val rule: IndexedRule,
                val roles: Set<String>,
            )

            fun targets(rs: List<IndexedRule>): List<Targeted> =
                rs.map { r ->
                    val tr =
                        if (r.rule.roles == null) {
                            allRoles.toSet()
                        } else {
                            r.rule.roles
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .toSet()
                        }
                    Targeted(r, tr)
                }

            val allowTargets = targets(allowRules)
            val forbidTargets = targets(forbidRules)

            // If roles set is empty (should already be caught by semantic validation), skip it here.
            for (a in allowTargets) {
                if (a.roles.isEmpty()) continue
                for (f in forbidTargets) {
                    if (f.roles.isEmpty()) continue

                    val overlap = a.roles.intersect(f.roles)
                    if (overlap.isEmpty()) continue

                    overlap.forEach { roleId ->
                        out +=
                            ValidationError(
                                path = a.rule.path,
                                message =
                                    "Invalid rule combination for role '$roleId': " +
                                        "'$type.$allowName' and '$type.$forbidName' cannot both be enabled for the same role target. " +
                                        "Remove one of them. Conflicts with ${f.rule.path}.",
                                severity = ValidationSeverity.ERROR,
                            )
                    }
                }
            }
        }

        return out
    }

    private data class IndexedRule(
        val index: Int,
        val path: String,
        val type: String,
        val name: String,
        val rule: RuleDef,
    )

    private fun validateProject(
        project: ProjectConfig,
        errors: MutableList<ValidationError>,
    ) {
        val roots = project.bytecode.roots
        if (roots.isEmpty()) {
            errors += err("project.bytecode.roots", "must contain at least one root path")
        } else {
            roots.forEachIndexed { i, r ->
                if (r.isBlank()) errors += err("project.bytecode.roots[$i]", "root path must be non-empty")
            }
        }

        validateGlobSet(project.bytecode.outputsGlobs, "project.bytecode.outputsGlobs", errors)
        validateGlobSet(project.bytecode.jarGlobs, "project.bytecode.jarGlobs", errors)

        project.scan.maxClasses?.let { if (it <= 0) errors += err("project.scan.maxClasses", "must be > 0") }
        project.scan.maxJarBytes?.let { if (it <= 0) errors += err("project.scan.maxJarBytes", "must be > 0") }
        project.scan.maxClassBytes?.let { if (it <= 0) errors += err("project.scan.maxClassBytes", "must be > 0") }
    }

    private fun validateGlobSet(
        globs: GlobSet,
        path: String,
        errors: MutableList<ValidationError>,
    ) {
        if (globs.include.isEmpty()) {
            errors += err("$path.include", "must contain at least one glob")
        } else {
            globs.include.forEachIndexed { i, g ->
                if (g.isBlank()) errors += err("$path.include[$i]", "glob must be non-empty")
            }
        }

        globs.exclude.forEachIndexed { i, g ->
            if (g.isBlank()) errors += err("$path.exclude[$i]", "glob must be non-empty")
        }
    }

    private fun validateAnalysis(
        analysis: AnalysisConfig,
        errors: MutableList<ValidationError>,
    ) {
        if (analysis.hotspots.topN <= 0) {
            errors += err("analysis.hotspots.topN", "topN must be > 0")
        }

        fun validateThresholds(
            t: io.shamash.asm.core.config.schema.v1.model.ScoreThresholds?,
            path: String,
        ) {
            if (t == null) return
            if (t.warning.isNaN() || t.error.isNaN()) {
                errors += err(path, "thresholds must not be NaN")
                return
            }
            if (t.warning < 0.0 || t.error < 0.0) errors += err(path, "thresholds must be >= 0")
            if (t.warning >= t.error) errors += err(path, "warning must be < error")
        }

        validateThresholds(analysis.scoring.godClass.thresholds, "analysis.scoring.godClass.thresholds")
        validateThresholds(analysis.scoring.overall.thresholds, "analysis.scoring.overall.thresholds")

        fun validateWeights(
            weights: Any?,
            path: String,
        ) {
            when (weights) {
                is io.shamash.asm.core.config.schema.v1.model.GodClassWeights -> {
                    listOf(
                        "methods" to weights.methods,
                        "fields" to weights.fields,
                        "fanOut" to weights.fanOut,
                        "fanIn" to weights.fanIn,
                        "packageSpread" to weights.packageSpread,
                    ).forEach { (k, v) ->
                        if (!v.isFinite()) errors += err("$path.$k", "must be finite")
                        if (v < 0.0) errors += err("$path.$k", "must be >= 0")
                    }
                }
                is io.shamash.asm.core.config.schema.v1.model.OverallWeights -> {
                    listOf(
                        "cycles" to weights.cycles,
                        "dependencyDensity" to weights.dependencyDensity,
                        "layeringViolations" to weights.layeringViolations,
                        "godClassPrevalence" to weights.godClassPrevalence,
                        "externalCoupling" to weights.externalCoupling,
                    ).forEach { (k, v) ->
                        if (!v.isFinite()) errors += err("$path.$k", "must be finite")
                        if (v < 0.0) errors += err("$path.$k", "must be >= 0")
                    }
                }
                null -> Unit
                else -> Unit
            }
        }

        validateWeights(analysis.scoring.godClass.weights, "analysis.scoring.godClass.weights")
        validateWeights(analysis.scoring.overall.weights, "analysis.scoring.overall.weights")
    }

    private fun validateScope(
        scope: RuleScope?,
        path: String,
        config: ShamashAsmConfigV1,
        errors: MutableList<ValidationError>,
    ) {
        if (scope == null) return

        scope.includeRoles?.forEachIndexed { i, role ->
            if (role.isBlank()) {
                errors += err("$path.includeRoles[$i]", "role must be non-empty")
            } else if (!config.roles.containsKey(role)) {
                errors +=
                    err("$path.includeRoles[$i]", "Unknown role '$role' (not defined under roles)")
            }
        }
        scope.excludeRoles?.forEachIndexed { i, role ->
            if (role.isBlank()) {
                errors += err("$path.excludeRoles[$i]", "role must be non-empty")
            } else if (!config.roles.containsKey(role)) {
                errors +=
                    err("$path.excludeRoles[$i]", "Unknown role '$role' (not defined under roles)")
            }
        }

        scope.includePackages?.forEachIndexed { i, rx -> compileRegex(rx, "$path.includePackages[$i]", errors) }
        scope.excludePackages?.forEachIndexed { i, rx -> compileRegex(rx, "$path.excludePackages[$i]", errors) }

        scope.includeGlobs?.forEachIndexed { i, g ->
            if (g.isBlank()) errors += err("$path.includeGlobs[$i]", "glob must be non-empty")
        }
        scope.excludeGlobs?.forEachIndexed { i, g ->
            if (g.isBlank()) errors += err("$path.excludeGlobs[$i]", "glob must be non-empty")
        }
    }

    private fun validateExceptions(
        config: ShamashAsmConfigV1,
        errors: MutableList<ValidationError>,
    ) {
        config.exceptions.forEachIndexed { i, ex ->
            val base = "exceptions[$i]"
            if (ex.id.isBlank()) errors += err("$base.id", "id must be non-empty")
            if (ex.reason.isBlank()) errors += err("$base.reason", "reason must be non-empty")

            validateExceptionMatch(ex.match, "$base.match", config, errors)
        }
    }

    private fun validateExceptionMatch(
        m: ExceptionMatch,
        path: String,
        config: ShamashAsmConfigV1,
        errors: MutableList<ValidationError>,
    ) {
        if (isExceptionMatchEmpty(m)) {
            errors += err(path, "Exception match must specify at least one matcher field")
            return
        }

        if ((m.ruleType == null) != (m.ruleName == null)) {
            errors += err(path, "ruleType and ruleName must be provided together")
        }

        m.roles?.forEachIndexed { i, roleId ->
            val rid = roleId.trim()
            if (rid.isEmpty()) {
                errors += err("$path.roles[$i]", "roleId must be non-empty")
            } else if (!config.roles.containsKey(rid)) {
                errors += err("$path.roles[$i]", "Unknown role '$rid' (not defined under roles)")
            }
        }

        m.classInternalName?.let { if (it.isBlank()) errors += err("$path.classInternalName", "must be non-empty") }
        m.classNameRegex?.let { compileRegex(it, "$path.classNameRegex", errors) }
        m.packageRegex?.let { compileRegex(it, "$path.packageRegex", errors) }
        m.originPathRegex?.let { compileRegex(it, "$path.originPathRegex", errors) }

        m.glob?.let { if (it.isBlank()) errors += err("$path.glob", "glob must be non-empty") }
    }

    private fun isExceptionMatchEmpty(m: ExceptionMatch): Boolean {
        val rolesEmpty = m.roles.isNullOrEmpty()
        return m.ruleId == null &&
            m.ruleType == null &&
            m.ruleName == null &&
            rolesEmpty &&
            m.classInternalName == null &&
            m.classNameRegex == null &&
            m.packageRegex == null &&
            m.originPathRegex == null &&
            m.glob == null
    }

    private fun validateBaseline(
        config: ShamashAsmConfigV1,
        errors: MutableList<ValidationError>,
    ) {
        val mode = config.baseline.mode
        if (mode != BaselineMode.NONE && config.baseline.path.isBlank()) {
            errors += err("baseline.path", "path must be non-empty when baseline.mode is $mode")
        }
    }

    private fun validateExport(
        export: ExportConfig,
        errors: MutableList<ValidationError>,
    ) {
        if (!export.enabled) return

        if (export.outputDir.isBlank()) errors += err("export.outputDir", "outputDir must be non-empty when export.enabled is true")
        if (export.formats.isEmpty()) errors += err("export.formats", "formats must be non-empty when export.enabled is true")
        if (export.formats.size != export.formats.distinct().size) errors += err("export.formats", "formats must not contain duplicates")
    }

    private fun validateMatcher(
        m: Matcher,
        path: String,
        errors: MutableList<ValidationError>,
    ) {
        when (m) {
            is Matcher.AnyOf -> {
                if (m.anyOf.isEmpty()) errors += err("$path.anyOf", "anyOf must be non-empty")
                m.anyOf.forEachIndexed { i, it -> validateMatcher(it, "$path.anyOf[$i]", errors) }
            }
            is Matcher.AllOf -> {
                if (m.allOf.isEmpty()) errors += err("$path.allOf", "allOf must be non-empty")
                m.allOf.forEachIndexed { i, it -> validateMatcher(it, "$path.allOf[$i]", errors) }
            }
            is Matcher.Not -> validateMatcher(m.not, "$path.not", errors)

            is Matcher.PackageRegex -> compileRegex(m.packageRegex, "$path.packageRegex", errors)
            is Matcher.PackageContainsSegment ->
                if (m.packageContainsSegment.isBlank()) {
                    errors +=
                        err("$path.packageContainsSegment", "must be non-empty")
                }
            is Matcher.ClassNameEndsWith -> if (m.classNameEndsWith.isBlank()) errors += err("$path.classNameEndsWith", "must be non-empty")

            is Matcher.Annotation -> if (m.annotation.isBlank()) errors += err("$path.annotation", "must be non-empty")
            is Matcher.AnnotationPrefix -> if (m.annotationPrefix.isBlank()) errors += err("$path.annotationPrefix", "must be non-empty")
        }
    }

    private fun compileRegex(
        rx: String,
        path: String,
        errors: MutableList<ValidationError>,
    ) {
        if (rx.isBlank()) {
            errors += err(path, "regex must be non-empty")
            return
        }
        try {
            Pattern.compile(rx)
        } catch (e: PatternSyntaxException) {
            errors += err(path, "Invalid regex: ${e.description}")
        }
    }

    private fun err(
        path: String,
        msg: String,
    ): ValidationError = ValidationError(path = path, message = msg, severity = ValidationSeverity.ERROR)
}
