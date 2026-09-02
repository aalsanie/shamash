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
package io.shamash.psi.core.config

import io.shamash.psi.core.config.schema.v1.model.RuleKey
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.engine.registry.RuleRegistry
import java.io.Reader

object ConfigValidation {
    data class Result(
        val config: ShamashPsiConfigV1?,
        val errors: List<ValidationError>,
    ) {
        val ok: Boolean get() = errors.none { it.severity == ValidationSeverity.ERROR }
    }

    fun loadAndValidateV1(
        reader: Reader,
        schemaValidator: SchemaValidator = SchemaValidatorNetworkNt,
    ): Result {
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

        val structural = schemaValidator.validate(raw)
        if (structural.isNotEmpty()) {
            return Result(config = null, errors = structural)
        }

        val typed =
            try {
                ConfigLoader.bindV1(raw)
            } catch (e: Throwable) {
                val (path, msg) =
                    if (e is ConfigLoader.ConfigBindException) {
                        e.path to (e.message)
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

        val executableRuleKeys: Set<RuleKey>? =
            try {
                // TODO: migrate engine ruleRegistry outside of config layer. config should never depends on engine
                val ids: Set<String> = RuleRegistry.allIds()
                parseRuleKeysOrNull(ids)
            } catch (_: Throwable) {
                null
            }

        val semantic = ConfigValidator.validateSemantic(typed, executableRuleKeys)
        return Result(config = typed, errors = semantic)
    }

    /** Accepts `type.name` and `type.name.role`; ignores internal ids outside that format. */
    private fun parseRuleKeysOrNull(ids: Set<String>): Set<RuleKey> {
        val out = LinkedHashSet<RuleKey>(ids.size)
        ids.forEach { id ->
            val trimmed = id.trim()
            if (trimmed.isEmpty()) return@forEach

            val parts = trimmed.split('.')
            val rk: RuleKey? =
                when (parts.size) {
                    2 -> {
                        val type = parts[0]
                        val name = parts[1]
                        if (type.isBlank() || name.isBlank()) null else RuleKey(type = type, name = name, role = null)
                    }

                    3 -> {
                        val type = parts[0]
                        val name = parts[1]
                        val role = parts[2]
                        if (type.isBlank() || name.isBlank() || role.isBlank()) null else RuleKey(type = type, name = name, role = role)
                    }

                    else -> {
                        null
                    }
                }

            if (rk != null) out += rk
        }
        return out
    }
}
