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
 * metrics.maxFanIn
 *
 * Validates config params only (engine semantics are elsewhere).
 *
 * Params:
 * - max (required int, >= 0)
 * - includeExternal (optional boolean; default handled by engine)
 */
class MetricsMaxFanInSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "metrics", name = "maxFanIn", role = null)

    override fun validate(
        rulePath: String,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val paramsPath = "$rulePath.params"
        val p = Params.of(rule.params, path = paramsPath)

        val allowed = setOf("max", "includeExternal")
        val unknown = p.unknownKeys(allowed)
        unknown.forEach { k ->
            errors +=
                ValidationError(
                    path = "$paramsPath.$k",
                    message = "Unknown param '$k'. Allowed params: ${allowed.joinToString()}",
                    severity = ValidationSeverity.ERROR,
                )
        }

        try {
            // required
            p.requireInt("max", min = 0)

            // optional
            p.optionalBoolean("includeExternal")
        } catch (e: ParamError) {
            errors +=
                ValidationError(
                    path = e.at,
                    message = e.message ?: "Invalid param",
                    severity = ValidationSeverity.ERROR,
                )
        }

        return errors
    }
}
