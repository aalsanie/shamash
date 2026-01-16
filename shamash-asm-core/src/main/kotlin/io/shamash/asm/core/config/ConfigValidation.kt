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
import java.io.Reader

object ConfigValidation {
    data class Result(
        val config: ShamashAsmConfigV1?,
        val errors: List<ValidationError>,
    ) {
        val ok: Boolean get() = errors.none { it.severity == ValidationSeverity.ERROR }
    }

    /**
     * Production entrypoint (backward compatible).
     *
     * Uses the default schema validator and attempts to initialize the engine rule registry
     * for unknown-rule executability checks when policy is WARN/ERROR.
     */
    fun loadAndValidateV1(
        reader: Reader,
        schemaValidator: SchemaValidator = SchemaValidatorNetworkNt,
    ): Result =
        loadAndValidateV1(
            reader = reader,
            schemaValidator = schemaValidator,
            engineRuleIdsProvider = null,
        )

    /**
     * Test-friendly entrypoint.
     *
     * @param engineRuleIdsProvider Optional provider for engine rule ids.
     * If null, the default engine registry will be used (DefaultRuleRegistry).
     */
    fun loadAndValidateV1(
        reader: Reader,
        schemaValidator: SchemaValidator = SchemaValidatorNetworkNt,
        engineRuleIdsProvider: (() -> Set<String>)? = null,
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
        // IMPORTANT:
        // - We only attempt engine registry initialization when policy is WARN/ERROR.
        // - If registry init fails under WARN/ERROR, we surface a warning/error instead of silently skipping.
        // - Engine rule ids may be either:
        //   - base ids: "type.name"
        //   - role-expanded ids: "type.name.role"
        //   We treat either as "implemented".
        val policy = normalizeUnknownPolicy(typed.project.validation.unknownRule)
        if (policy != UnknownRulePolicy.IGNORE && policy != UnknownRulePolicy.ignore) {
            val engineIdsResult = loadEngineRuleIds(engineRuleIdsProvider)

            when (engineIdsResult) {
                is EngineIdsResult.Unavailable -> {
                    val sev =
                        when (policy) {
                            UnknownRulePolicy.ERROR, UnknownRulePolicy.error -> ValidationSeverity.ERROR
                            UnknownRulePolicy.WARN, UnknownRulePolicy.warn -> ValidationSeverity.WARNING
                            else -> ValidationSeverity.WARNING
                        }

                    errors +=
                        ValidationError(
                            path = "project.validation.unknownRule",
                            message =
                                "Unknown-rule policy is set to '${policy.name}', but engine rule registry " +
                                    "could not be initialized to verify rule executability: ${engineIdsResult.reason}",
                            severity = sev,
                        )
                }

                is EngineIdsResult.Available -> {
                    val engineIds = engineIdsResult.ids
                    if (engineIds.isNotEmpty()) {
                        typed.rules.forEachIndexed { i, rule ->
                            if (!rule.enabled) return@forEachIndexed

                            val type = rule.type.trim()
                            val name = rule.name.trim()
                            if (type.isEmpty() || name.isEmpty()) return@forEachIndexed

                            val baseId = "$type.$name"
                            if (isImplemented(baseId, engineIds)) return@forEachIndexed

                            when (policy) {
                                UnknownRulePolicy.IGNORE, UnknownRulePolicy.ignore -> Unit
                                UnknownRulePolicy.WARN, UnknownRulePolicy.warn ->
                                    errors +=
                                        ValidationError(
                                            path = "rules[$i]",
                                            message =
                                                "Rule '$baseId' is configured but not implemented in the engine (rule will not run)",
                                            severity = ValidationSeverity.WARNING,
                                        )

                                UnknownRulePolicy.ERROR, UnknownRulePolicy.error ->
                                    errors +=
                                        ValidationError(
                                            path = "rules[$i]",
                                            message = "Rule '$baseId' is configured but not implemented in the engine",
                                            severity = ValidationSeverity.ERROR,
                                        )
                            }
                        }
                    }
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

    private sealed interface EngineIdsResult {
        data class Available(
            val ids: Set<String>,
        ) : EngineIdsResult

        data class Unavailable(
            val reason: String,
        ) : EngineIdsResult
    }

    private fun loadEngineRuleIds(engineRuleIdsProvider: (() -> Set<String>)?): EngineIdsResult =
        try {
            val ids =
                (
                    engineRuleIdsProvider?.invoke()
                        ?: DefaultRuleRegistry
                            .create()
                            .all()
                            .map { it.id }
                            .toSet()
                ).asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()

            EngineIdsResult.Available(ids)
        } catch (t: Throwable) {
            EngineIdsResult.Unavailable(t.message ?: t::class.java.simpleName)
        }

    /**
     * A rule is considered implemented if:
     * - the engine registry contains the base id exactly (type.name), OR
     * - the engine registry contains any role-expanded instance (type.name.<role>), OR
     * - the engine registry contains any id that starts with "type.name." (defensive)
     */
    private fun isImplemented(
        baseId: String,
        engineIds: Set<String>,
    ): Boolean {
        if (engineIds.contains(baseId)) return true
        val prefix = "$baseId."
        return engineIds.any { it.startsWith(prefix) }
    }
}
