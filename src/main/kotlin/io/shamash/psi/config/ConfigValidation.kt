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

import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
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
                return Result(null, listOf(ValidationError("", "Failed to parse YAML: ${e.message}")))
            }

        val structural = schemaValidator.validate(raw)
        if (structural.isNotEmpty()) {
            return Result(null, structural)
        }

        val typed =
            try {
                ConfigLoader.bindV1(raw)
            } catch (e: Exception) {
                return Result(null, listOf(ValidationError("", "Failed to bind schema: ${e.message}")))
            }

        // also validate that enabled rules are executable by the engine
        val executableRuleIds: Set<String>? =
            try {
                // Adjust this import to your actual engine registry.
                // Example: io.shamash.psi.engine.registry.RuleRegistry.allIds()
                io.shamash.psi.engine.registry.RuleRegistry
                    .allIds()
            } catch (_: Throwable) {
                // If engine registry isn't available in some contexts, degrade gracefully.
                null
            }

        val semantic = ConfigValidator.validateSemantic(typed, executableRuleIds)
        return Result(typed, semantic)
    }
}
