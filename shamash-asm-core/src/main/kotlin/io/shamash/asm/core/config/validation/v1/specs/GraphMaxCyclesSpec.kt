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
package io.shamash.asm.core.config.validation.v1.specs

import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.config.ValidationSeverity
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleKey
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.validation.v1.RuleSpec

/**
 * graph.maxCycles
 *
 * Contract (non-opinionated):
 * - Params:
 *   - maxCycles: Int (>= 0)
 */
class GraphMaxCyclesSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "graph", name = "maxCycles", role = null)

    override fun validate(
        rulePath: String,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        val params = Params.of(rule.params, "$rulePath.params")

        val allowed = setOf("maxCycles")
        val unknown = params.unknownKeys(allowed)
        if (unknown.isNotEmpty()) {
            unknown.sorted().forEach { k ->
                errors +=
                    ValidationError(
                        path = "$rulePath.params.$k",
                        message = "Unknown param '$k' for '${key.canonicalId()}'. Allowed: ${allowed.joinToString()}",
                        severity = ValidationSeverity.ERROR,
                    )
            }
        }

        // Only validate known fields if unknown-keys didn't already explode the whole thing.
        // (We still try to validate maxCycles so the user sees all issues.)
        try {
            params.requireInt("maxCycles", min = 0)
        } catch (e: ParamError) {
            errors +=
                ValidationError(
                    path = e.at,
                    message = e.message.removePrefix("${e.at} ").trim(),
                    severity = ValidationSeverity.ERROR,
                )
        }

        // If graphs are disabled, rule has no effect (warning, not error).
        if (!config.analysis.graphs.enabled) {
            errors +=
                ValidationError(
                    path = rulePath,
                    message = "Rule '${key.canonicalId()}' is enabled but analysis.graphs.enabled is false; rule will have no effect.",
                    severity = ValidationSeverity.WARNING,
                )
        }

        return errors
    }
}
