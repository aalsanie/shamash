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
package io.shamash.asm.core.config

import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.schema.v1.model.UnknownRulePolicy
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.asm.core.engine.rules.RuleRegistry
import java.io.Reader

object ConfigValidation {
    data class Result(
        val config: ShamashAsmConfigV1?,
        val errors: List<ValidationError>,
    ) {
        val ok: Boolean get() = errors.none { it.severity == ValidationSeverity.ERROR }
    }

    fun loadAndValidateV1(
        reader: Reader,
        schemaValidator: SchemaValidator = SchemaValidatorNetworkNt,
    ): Result {
        // 1) Parse YAML
        val raw =
            try {
                ConfigLoader.loadRaw(reader)
            } catch (e: Exception) {
                return Result(
                    config = null,
                    errors =
                        listOf(
                            ValidationError(
                                path = "",
                                message = "Failed to parse YAML: ${e.message ?: e::class.java.simpleName}",
                                severity = ValidationSeverity.ERROR,
                            ),
                        ),
                )
            }

        // 2) Structural schema validation
        val structural =
            try {
                schemaValidator.validate(raw)
            } catch (e: Exception) {
                return Result(
                    config = null,
                    errors =
                        listOf(
                            ValidationError(
                                path = "",
                                message = "Failed to validate schema: ${e.message ?: e::class.java.simpleName}",
                                severity = ValidationSeverity.ERROR,
                            ),
                        ),
                )
            }

        if (structural.isNotEmpty()) {
            return Result(config = null, errors = structural)
        }

        // 3) Bind to typed models
        val typed =
            try {
                ConfigLoader.bindV1(raw)
            } catch (e: Throwable) {
                val (path, msg) =
                    if (e is ConfigLoader.ConfigBindException) {
                        e.path to (e.message ?: "Bind error")
                    } else {
                        "" to (e.message ?: e::class.java.simpleName)
                    }

                return Result(
                    config = null,
                    errors =
                        listOf(
                            ValidationError(
                                path = path,
                                message = "Failed to bind schema: $msg",
                                severity = ValidationSeverity.ERROR,
                            ),
                        ),
                )
            }

        // 4) Semantic validation (config-only)
        val errors = ConfigValidator.validateSemantic(typed).toMutableList()

        // 5) Engine executability check (adds WARN/ERROR per unknownRule policy)
        //
        // NOTE(arch): This is a boundary leak (config -> engine). You requested it explicitly.
        // If/when we enforce strict layering, move this block to an orchestrator (plugin/CLI runner).
        val engineIds: Set<String>? =
            try {
                DefaultRuleRegistry
                    .create()
                    .all()
                    .map { it.id }
                    .filter { it.isNotEmpty() }
                    .toSet()
            } catch (_: Throwable) {
                // Degrade gracefully if registry cannot be initialized in some contexts.
                null
            }

        if (engineIds != null && engineIds.isNotEmpty()) {
            val policy = normalizeUnknownPolicy(typed.project.validation.unknownRule)

            typed.rules.forEachIndexed { i, rule ->
                if (!rule.enabled) return@forEachIndexed

                val type = rule.type.trim()
                val name = rule.name.trim()
                if (type.isEmpty() || name.isEmpty()) return@forEachIndexed

                // Engine registry is keyed by rule "kind" (type.name) not role-expanded instances.
                val baseId = "$type.$name"
                if (engineIds.contains(baseId)) return@forEachIndexed

                when (policy) {
                    UnknownRulePolicy.IGNORE, UnknownRulePolicy.ignore -> Unit
                    UnknownRulePolicy.WARN, UnknownRulePolicy.warn ->
                        errors +=
                            ValidationError(
                                path = "rules[$i]",
                                message = "Rule '$baseId' is registered but not implemented in engine (rule will not run)",
                                severity = ValidationSeverity.WARNING,
                            )
                    UnknownRulePolicy.ERROR, UnknownRulePolicy.error ->
                        errors +=
                            ValidationError(
                                path = "rules[$i]",
                                message = "Rule '$baseId' is registered but not implemented in engine",
                                severity = ValidationSeverity.ERROR,
                            )
                }
            }
        }

        return Result(config = typed, errors = errors)
    }

    private fun normalizeUnknownPolicy(p: UnknownRulePolicy): UnknownRulePolicy =
        when (p) {
            UnknownRulePolicy.ERROR, UnknownRulePolicy.error -> UnknownRulePolicy.ERROR
            UnknownRulePolicy.WARN, UnknownRulePolicy.warn -> UnknownRulePolicy.WARN
            UnknownRulePolicy.IGNORE, UnknownRulePolicy.ignore -> UnknownRulePolicy.IGNORE
        }
}
