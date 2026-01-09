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
package io.shamash.psi.config.validation.v1

import io.shamash.psi.config.ValidationError
import io.shamash.psi.config.ValidationSeverity
import io.shamash.psi.config.schema.v1.model.ExceptionMatch
import io.shamash.psi.config.schema.v1.model.Matcher
import io.shamash.psi.config.schema.v1.model.RoleId
import io.shamash.psi.config.schema.v1.model.RuleKey
import io.shamash.psi.config.schema.v1.model.RuleScope
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.config.schema.v1.model.UnknownRulePolicyV1
import io.shamash.psi.config.validation.v1.registry.RuleSpecRegistryV1
import java.util.LinkedHashSet
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Semantic validation for Shamash PSI config V1.
 *
 * Assumes config is already bound into schema v1 models (YAML binding is done by ConfigLoader).
 * Enforces invariants and semantics:
 * - role constraints (priority range)
 * - matcher sanity (recursive, regex compilation for regex matchers)
 * - rules uniqueness (wildcard vs specific role variants)
 * - rule scope sanity (known roles, regex compilation for package filters, non-empty globs)
 * - rule existence (RuleSpec registry) and rule param validation (via RuleSpec)
 * - optional engine executability (RuleKey set)
 * - exceptions sanity (required fields, match not empty, regex compilation, known roles)
 */
object ConfigSemanticValidatorV1 {
    /**
     * Validate config semantics.
     *
     * @param executableRuleKeys optional set of rule keys that are runnable in the engine.
     * If provided, enabled rules not in this set will be WARN/ERROR per unknownRule policy,
     * even if a RuleSpec exists for (type,name).
     *
     * NOTE: If you don't have RuleKey-based executability yet, pass null and only spec existence is checked.
     */
    fun validateSemantic(
        config: ShamashPsiConfigV1,
        executableRuleKeys: Set<RuleKey>? = null,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // ---- Version ----
        if (config.version != 1) {
            errors += ValidationError("version", "Unsupported schema version: ${config.version}", ValidationSeverity.ERROR)
            return errors
        }

        // ---- Roles ----
        config.roles.forEach { (roleId, role) ->
            val base = "roles.$roleId"

            // Contract: semantic validator enforces 0..100
            if (role.priority < 0 || role.priority > 100) {
                errors += err("$base.priority", "priority must be between 0 and 100 (inclusive)")
            }

            validateMatcher(role.match, "$base.match", errors)
        }

        // ---- Rules: uniqueness + basic sanity ----
        // Contract:
        // - Only one wildcard allowed per (type,name) where roles == null
        // - Only one specific allowed per (type,name,role)
        // - No duplicate roles within the same RuleDef
        val seenWildcards = LinkedHashSet<Pair<String, String>>() // (type,name)
        val seenSpecific = LinkedHashSet<RuleKey>() // (type,name,role)

        config.rules.forEachIndexed { i, rule ->
            val base = "rules[$i]"
            val type = rule.type.trim()
            val name = rule.name.trim()

            if (type.isEmpty()) errors += err("$base.type", "type must be non-empty")
            if (name.isEmpty()) errors += err("$base.name", "name must be non-empty")

            // roles == null => wildcard
            if (rule.roles == null) {
                val key = type to name
                if (!seenWildcards.add(key)) {
                    errors += err(base, "Duplicate wildcard rule definition for '$type.$name' (roles: null)")
                }
            } else {
                if (rule.roles.isEmpty()) {
                    errors += err("$base.roles", "roles must be non-empty when provided; use null for wildcard")
                } else {
                    // ensure all roles exist + no duplicates in same rule
                    val local = LinkedHashSet<RoleId>()
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

            // scope sanity (regex/globs + role existence)
            validateScope(rule.scope, "$base.scope", config, errors)
        }

        // ---- Rules: spec existence + param validation ----
        config.rules.forEachIndexed { i, rule ->
            if (!rule.enabled) return@forEachIndexed

            val base = "rules[$i]"
            val type = rule.type.trim()
            val name = rule.name.trim()

            // Skip cascading garbage if these are empty (already reported above)
            if (type.isEmpty() || name.isEmpty()) return@forEachIndexed

            val spec = RuleSpecRegistryV1.find(type, name)
            if (spec == null) {
                // Unknown (type,name)
                when (config.project.validation.unknownRule) {
                    UnknownRulePolicyV1.IGNORE -> Unit
                    UnknownRulePolicyV1.WARN ->
                        errors +=
                            ValidationError(
                                base,
                                "Unknown rule '$type.$name' (no RuleSpec registered; rule will not run)",
                                ValidationSeverity.WARNING,
                            )
                    UnknownRulePolicyV1.ERROR ->
                        errors +=
                            ValidationError(
                                base,
                                "Unknown rule '$type.$name' (no RuleSpec registered)",
                                ValidationSeverity.ERROR,
                            )
                }
                return@forEachIndexed
            }

            // Executability check (optional)
            if (executableRuleKeys != null) {
                // Engine registry exposes only base rule ids (type.name). The engine itself expands
                // authored role lists into concrete instances (type.name.role) at runtime.
                //
                // Therefore, a rule is executable if the base (type,name) is implemented — regardless
                // of whether roles == null (wildcard) or roles != null (role-specific instances).
                //
                // If, in the future, the engine registry starts exposing role-specific ids too,
                // this logic still holds.
                val baseKey = RuleKey(type = type, name = name, role = null)
                val isExecutable =
                    when (val roles = rule.roles) {
                        null -> executableRuleKeys.contains(baseKey) || executableRuleKeys.any { it.type == type && it.name == name }
                        else -> {
                            // Prefer base key, but also accept explicit role keys if the engine ever exposes them.
                            executableRuleKeys.contains(baseKey) ||
                                roles
                                    .asSequence()
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .any { r -> executableRuleKeys.contains(RuleKey(type = type, name = name, role = r)) }
                        }
                    }

                if (!isExecutable) {
                    when (config.project.validation.unknownRule) {
                        UnknownRulePolicyV1.IGNORE -> Unit
                        UnknownRulePolicyV1.WARN ->
                            errors +=
                                ValidationError(
                                    base,
                                    "Rule '$type.$name' is registered but not implemented in engine (rule will not run)",
                                    ValidationSeverity.WARNING,
                                )
                        UnknownRulePolicyV1.ERROR ->
                            errors +=
                                ValidationError(
                                    base,
                                    "Rule '$type.$name' is registered but not implemented in engine",
                                    ValidationSeverity.ERROR,
                                )
                    }
                    // NOTE: still run spec validation so user sees param issues too
                }
            }

            errors += spec.validate(rulePath = base, rule = rule, config = config)
        }

        // ---- Exceptions ----
        config.shamashExceptions.forEachIndexed { i, ex ->
            val base = "exceptions[$i]"

            if (ex.id.isBlank()) errors += err("$base.id", "id must be non-empty")
            if (ex.reason.isBlank()) errors += err("$base.reason", "reason must be non-empty")
            if (ex.suppress.isEmpty()) errors += err("$base.suppress", "suppress must contain at least one rule id")
            if (ex.suppress.any { it.isBlank() }) errors += err("$base.suppress", "suppress must not contain blank values")

            // No magic suppress tokens in v1 (break compatibility on purpose).
            ex.suppress.forEachIndexed { j, rid ->
                val v = rid.trim()
                if (v == "*" || v.equals("all", ignoreCase = true)) {
                    errors += err("$base.suppress[$j]", "Wildcard suppress tokens are not supported; use explicit canonical ids")
                }
            }

            if (isExceptionMatchEmpty(ex.match)) {
                errors += err("$base.match", "Exception match must specify at least one matcher field")
            }

            // compile regex fields
            ex.match.packageRegex?.let { compileRegex(it, "$base.match.packageRegex", errors) }
            ex.match.classNameRegex?.let { compileRegex(it, "$base.match.classNameRegex", errors) }
            ex.match.methodNameRegex?.let { compileRegex(it, "$base.match.methodNameRegex", errors) }
            ex.match.fieldNameRegex?.let { compileRegex(it, "$base.match.fieldNameRegex", errors) }

            // fileGlob semantic check
            ex.match.fileGlob?.let { g ->
                if (g.isBlank()) errors += err("$base.match.fileGlob", "fileGlob must be non-empty")
            }

            // role existence
            ex.match.role?.let { role ->
                if (!config.roles.containsKey(role)) {
                    errors += err("$base.match.role", "Unknown role '$role' (not defined under roles)")
                }
            }
        }

        return errors
    }

    private fun validateScope(
        scope: RuleScope?,
        path: String,
        config: ShamashPsiConfigV1,
        errors: MutableList<ValidationError>,
    ) {
        if (scope == null) return

        // role lists must reference known roles
        scope.includeRoles?.forEachIndexed { i, role ->
            if (!config.roles.containsKey(role)) {
                errors += err("$path.includeRoles[$i]", "Unknown role '$role' (not defined under roles)")
            }
        }
        scope.excludeRoles?.forEachIndexed { i, role ->
            if (!config.roles.containsKey(role)) {
                errors += err("$path.excludeRoles[$i]", "Unknown role '$role' (not defined under roles)")
            }
        }

        // NOTE (v1 contract): includePackages/excludePackages are treated as regex filters.
        // If you later want true "package patterns", change this in v2 and rename fields accordingly.
        scope.includePackages?.forEachIndexed { i, rx -> compileRegex(rx, "$path.includePackages[$i]", errors) }
        scope.excludePackages?.forEachIndexed { i, rx -> compileRegex(rx, "$path.excludePackages[$i]", errors) }

        // globs: minimal semantic check
        scope.includeGlobs?.forEachIndexed { i, g ->
            if (g.isBlank()) errors += err("$path.includeGlobs[$i]", "glob must be non-empty")
        }
        scope.excludeGlobs?.forEachIndexed { i, g ->
            if (g.isBlank()) errors += err("$path.excludeGlobs[$i]", "glob must be non-empty")
        }
    }

    private fun validateMatcher(
        m: Matcher,
        path: String,
        errors: MutableList<ValidationError>,
    ) {
        when (m) {
            is Matcher.AnyOf -> m.anyOf.forEachIndexed { i, it -> validateMatcher(it, "$path.anyOf[$i]", errors) }
            is Matcher.AllOf -> m.allOf.forEachIndexed { i, it -> validateMatcher(it, "$path.allOf[$i]", errors) }
            is Matcher.Not -> validateMatcher(m.not, "$path.not", errors)

            is Matcher.PackageRegex -> compileRegex(m.packageRegex, "$path.packageRegex", errors)
            is Matcher.ClassNameRegex -> compileRegex(m.classNameRegex, "$path.classNameRegex", errors)

            else -> Unit
        }
    }

    private fun isExceptionMatchEmpty(m: ExceptionMatch): Boolean =
        m.fileGlob == null &&
            m.packageRegex == null &&
            m.classNameRegex == null &&
            m.methodNameRegex == null &&
            m.fieldNameRegex == null &&
            m.hasAnnotation == null &&
            m.hasAnnotationPrefix == null &&
            m.role == null

    private fun compileRegex(
        rx: String,
        path: String,
        errors: MutableList<ValidationError>,
    ) {
        try {
            Pattern.compile(rx)
        } catch (e: PatternSyntaxException) {
            errors += err(path, "Invalid regex: ${e.description}")
        }
    }

    private fun err(
        path: String,
        msg: String,
    ): ValidationError = ValidationError(path, msg, ValidationSeverity.ERROR)
}
