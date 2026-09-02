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
package io.shamash.asm.core.config.validation.v1.specs

import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.config.ValidationSeverity
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleKey
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.validation.v1.RuleSpec

class GraphMaxDependencyDensitySpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "graph", name = "maxDependencyDensity", role = null)

    override fun validate(
        rulePath: String,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val params = Params.of(rule.params, "$rulePath.params")

        val allowed = setOf("maxDensity")
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

        val maxDensity =
            try {
                params.requireDouble("maxDensity", min = 0.0)
            } catch (e: ParamError) {
                errors +=
                    ValidationError(
                        path = e.at,
                        message = e.message.removePrefix("${e.at} ").trim(),
                        severity = ValidationSeverity.ERROR,
                    )
                null
            }

        if (maxDensity != null && maxDensity > 1.0) {
            errors +=
                ValidationError(
                    path = "$rulePath.params.maxDensity",
                    message = "maxDensity is > 1.0. This is allowed, but verify this matches your engine's density scale.",
                    severity = ValidationSeverity.WARNING,
                )
        }

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
