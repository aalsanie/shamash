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
package io.shamash.psi.config

import io.shamash.psi.config.schema.v1.model.ExceptionMatch
import io.shamash.psi.config.schema.v1.model.Matcher
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.config.schema.v1.model.UnknownRuleIdPolicyV1
import io.shamash.psi.config.schema.v1.registry.RuleSpecRegistryV1
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

object ConfigValidator {
    // These are reserved "match everything" tokens in exception suppress lists.
    private val WILDCARD_SUPPRESS: Set<String> = setOf("*", "all")

    /**
     * Semantic validation that also supports checking whether rules are actually executable.
     *
     * @param executableRuleIds optional set of ruleIds that are runnable in the engine.
     *        If provided, enabled rules not in this set will be warned/errored (per unknownRuleId policy),
     *        even if they exist in the schema registry.
     */
    fun validateSemantic(
        config: ShamashPsiConfigV1,
        executableRuleIds: Set<String>? = null,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // Version check
        if (config.version != 1) {
            errors += ValidationError("version", "Unsupported schema version: ${config.version}", ValidationSeverity.ERROR)
            return errors
        }

        // Validate roles: compile regex in matchers (fail fast, but do not execute)
        config.roles.forEach { (roleName, roleDef) ->
            validateMatcher(roleDef.match, "roles.$roleName.match", errors)
        }

        // Validate rules: scope compilation + dynamic param validation per RuleSpec
        config.rules.forEach { (ruleId, ruleDef) ->
            val rulePath = "rules.$ruleId"

            // Scope checks
            ruleDef.scope?.includePackages?.forEachIndexed { i, rx -> compileRegex(rx, "$rulePath.scope.includePackages[$i]", errors) }
            ruleDef.scope?.excludePackages?.forEachIndexed { i, rx -> compileRegex(rx, "$rulePath.scope.excludePackages[$i]", errors) }

            // Globs: keep semantic checks minimal (non-empty).
            ruleDef.scope?.includeGlobs?.forEachIndexed { i, g ->
                if (g.isBlank()) errors += err("$rulePath.scope.includeGlobs[$i]", "glob must be non-empty")
            }
            ruleDef.scope?.excludeGlobs?.forEachIndexed { i, g ->
                if (g.isBlank()) errors += err("$rulePath.scope.excludeGlobs[$i]", "glob must be non-empty")
            }

            if (!ruleDef.enabled) return@forEach

            val spec = RuleSpecRegistryV1.find(ruleId)
            if (spec == null) {
                // Unknown to schema/spec registry
                when (config.project.validation.unknownRuleId) {
                    UnknownRuleIdPolicyV1.IGNORE -> Unit
                    UnknownRuleIdPolicyV1.WARN ->
                        errors += ValidationError(rulePath, "Unknown ruleId '$ruleId' (rule will not run)", ValidationSeverity.WARNING)
                    UnknownRuleIdPolicyV1.ERROR ->
                        errors += ValidationError(rulePath, "Unknown ruleId '$ruleId'", ValidationSeverity.ERROR)
                }
                return@forEach
            }

            // Known to schema, but possibly not implemented in engine
            if (executableRuleIds != null && !executableRuleIds.contains(ruleId)) {
                when (config.project.validation.unknownRuleId) {
                    UnknownRuleIdPolicyV1.IGNORE -> Unit
                    UnknownRuleIdPolicyV1.WARN ->
                        errors +=
                            ValidationError(
                                rulePath,
                                "RuleId '$ruleId' is defined in schema but not implemented in engine (rule will not run)",
                                ValidationSeverity.WARNING,
                            )
                    UnknownRuleIdPolicyV1.ERROR ->
                        errors +=
                            ValidationError(
                                rulePath,
                                "RuleId '$ruleId' is defined in schema but not implemented in engine",
                                ValidationSeverity.ERROR,
                            )
                }
                // still run spec validation so user sees param issues too
            }

            errors += spec.validate(rulePath, ruleDef, config)
        }

        // Validate exceptions
        config.shamashExceptions.forEachIndexed { i, ex ->
            val base = "exceptions[$i]"

            if (ex.id.isBlank()) errors += err("$base.id", "id must be non-empty")
            if (ex.reason.isBlank()) errors += err("$base.reason", "reason must be non-empty")
            if (ex.suppress.isEmpty()) errors += err("$base.suppress", "suppress must contain at least one ruleId")
            if (ex.suppress.any { it.isBlank() }) errors += err("$base.suppress", "suppress must not contain blank values")

            val knownSpecRuleIds = RuleSpecRegistryV1.allIds()
            val knownExecutableRuleIds = executableRuleIds

            ex.suppress.forEachIndexed { j, rid ->
                // Allow reserved wildcard tokens.
                if (rid in WILDCARD_SUPPRESS) return@forEachIndexed

                val specKnown = knownSpecRuleIds.contains(rid)
                val execKnown = knownExecutableRuleIds?.contains(rid) ?: true // if not provided, skip exec check

                if (!specKnown || !execKnown) {
                    val why =
                        when {
                            !specKnown -> "Unknown ruleId '$rid' in exception suppress list"
                            else -> "RuleId '$rid' in exception suppress list is not implemented in engine"
                        }

                    when (config.project.validation.unknownRuleId) {
                        UnknownRuleIdPolicyV1.IGNORE -> Unit
                        UnknownRuleIdPolicyV1.WARN ->
                            errors += ValidationError("$base.suppress[$j]", why, ValidationSeverity.WARNING)
                        UnknownRuleIdPolicyV1.ERROR ->
                            errors += ValidationError("$base.suppress[$j]", why, ValidationSeverity.ERROR)
                    }
                }
            }

            // expiresOn: optional YYYY-MM-DD
            ex.expiresOn?.let {
                try {
                    LocalDate.parse(it)
                } catch (_: DateTimeParseException) {
                    errors += err("$base.expiresOn", "Invalid date, expected YYYY-MM-DD")
                }
            }

            // match must have at least one field (schema should enforce, but we keep semantic check)
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
