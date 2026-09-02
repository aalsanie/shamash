/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
package io.shamash.psi.core.config.validation.v1
import io.shamash.psi.core.config.ValidationError
import io.shamash.psi.core.config.ValidationSeverity
import io.shamash.psi.core.config.schema.v1.model.ExceptionMatch
import io.shamash.psi.core.config.schema.v1.model.Matcher
import io.shamash.psi.core.config.schema.v1.model.RoleId
import io.shamash.psi.core.config.schema.v1.model.RuleKey
import io.shamash.psi.core.config.schema.v1.model.RuleScope
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.config.schema.v1.model.UnknownRulePolicyV1
import io.shamash.psi.core.config.validation.v1.registry.RuleSpecRegistryV1
import java.util.LinkedHashSet
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

object ConfigSemanticValidatorV1 {
    /**
     * When [executableRuleKeys] is provided, check runtime support as well as spec existence.
     * Unsupported enabled rules follow the configured unknown-rule policy.
     */
    fun validateSemantic(
        config: ShamashPsiConfigV1,
        executableRuleKeys: Set<RuleKey>? = null,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        if (config.version != 1) {
            errors += ValidationError("version", "Unsupported schema version: ${config.version}", ValidationSeverity.ERROR)
            return errors
        }

        config.roles.forEach { (roleId, role) ->
            val base = "roles.$roleId"

            if (role.priority < 0 || role.priority > 100) {
                errors += err("$base.priority", "priority must be between 0 and 100 (inclusive)")
            }

            validateMatcher(role.match, "$base.match", errors)
        }

        val seenWildcards = LinkedHashSet<Pair<String, String>>() // (type,name)
        val seenSpecific = LinkedHashSet<RuleKey>() // (type,name,role)

        config.rules.forEachIndexed { i, rule ->
            val base = "rules[$i]"
            val type = rule.type.trim()
            val name = rule.name.trim()

            if (type.isEmpty()) errors += err("$base.type", "type must be non-empty")
            if (name.isEmpty()) errors += err("$base.name", "name must be non-empty")

            if (rule.roles == null) {
                val key = type to name
                if (!seenWildcards.add(key)) {
                    errors += err(base, "Duplicate wildcard rule definition for '$type.$name' (roles: null)")
                }
            } else {
                if (rule.roles.isEmpty()) {
                    errors += err("$base.roles", "roles must be non-empty when provided; use null for wildcard")
                } else {
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

            validateScope(rule.scope, "$base.scope", config, errors)
        }

        config.rules.forEachIndexed { i, rule ->
            if (!rule.enabled) return@forEachIndexed

            val base = "rules[$i]"
            val type = rule.type.trim()
            val name = rule.name.trim()

            if (type.isEmpty() || name.isEmpty()) return@forEachIndexed

            val spec = RuleSpecRegistryV1.find(type, name)
            if (spec == null) {
                when (config.project.validation.unknownRule) {
                    UnknownRulePolicyV1.IGNORE -> {
                        Unit
                    }

                    UnknownRulePolicyV1.WARN -> {
                        errors +=
                            ValidationError(
                                base,
                                "Unknown rule '$type.$name' (no RuleSpec registered; rule will not run)",
                                ValidationSeverity.WARNING,
                            )
                    }

                    UnknownRulePolicyV1.ERROR -> {
                        errors +=
                            ValidationError(
                                base,
                                "Unknown rule '$type.$name' (no RuleSpec registered)",
                                ValidationSeverity.ERROR,
                            )
                    }
                }
                return@forEachIndexed
            }

            if (executableRuleKeys != null) {
                // Base ids cover role-specific instances: the engine expands authored roles at runtime.
                val baseKey = RuleKey(type = type, name = name, role = null)
                val isExecutable =
                    when (val roles = rule.roles) {
                        null -> {
                            executableRuleKeys.contains(baseKey) || executableRuleKeys.any { it.type == type && it.name == name }
                        }

                        else -> {
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
                        UnknownRulePolicyV1.IGNORE -> {
                            Unit
                        }

                        UnknownRulePolicyV1.WARN -> {
                            errors +=
                                ValidationError(
                                    base,
                                    "Rule '$type.$name' is registered but not implemented in engine (rule will not run)",
                                    ValidationSeverity.WARNING,
                                )
                        }

                        UnknownRulePolicyV1.ERROR -> {
                            errors +=
                                ValidationError(
                                    base,
                                    "Rule '$type.$name' is registered but not implemented in engine",
                                    ValidationSeverity.ERROR,
                                )
                        }
                    }
                }
            }

            errors += spec.validate(rulePath = base, rule = rule, config = config)
        }

        config.shamashExceptions.forEachIndexed { i, ex ->
            val base = "exceptions[$i]"

            if (ex.id.isBlank()) errors += err("$base.id", "id must be non-empty")
            if (ex.reason.isBlank()) errors += err("$base.reason", "reason must be non-empty")
            if (ex.suppress.isEmpty()) errors += err("$base.suppress", "suppress must contain at least one rule id")
            if (ex.suppress.any { it.isBlank() }) errors += err("$base.suppress", "suppress must not contain blank values")

            ex.suppress.forEachIndexed { j, rid ->
                val v = rid.trim()
                if (v == "*" || v.equals("all", ignoreCase = true)) {
                    errors += err("$base.suppress[$j]", "Wildcard suppress tokens are not supported; use explicit canonical ids")
                }
            }

            if (isExceptionMatchEmpty(ex.match)) {
                errors += err("$base.match", "Exception match must specify at least one matcher field")
            }

            ex.match.packageRegex?.let { compileRegex(it, "$base.match.packageRegex", errors) }
            ex.match.classNameRegex?.let { compileRegex(it, "$base.match.classNameRegex", errors) }
            ex.match.methodNameRegex?.let { compileRegex(it, "$base.match.methodNameRegex", errors) }
            ex.match.fieldNameRegex?.let { compileRegex(it, "$base.match.fieldNameRegex", errors) }

            ex.match.fileGlob?.let { g ->
                if (g.isBlank()) errors += err("$base.match.fileGlob", "fileGlob must be non-empty")
            }

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

        scope.includePackages?.forEachIndexed { i, rx -> compileRegex(rx, "$path.includePackages[$i]", errors) }
        scope.excludePackages?.forEachIndexed { i, rx -> compileRegex(rx, "$path.excludePackages[$i]", errors) }

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
