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
 * metrics.maxPackageSpread
 *
 * Params:
 * - max: Int (>= 0) [required]
 * - includeExternal: Boolean [optional]
 */
class MetricsMaxPackageSpreadSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "metrics", name = "maxPackageSpread")

    override fun validate(
        rulePath: String,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        val p = Params.of(rule.params, path = "$rulePath.params")

        val allowed = setOf("max", "includeExternal")
        p.unknownKeys(allowed).forEach { k ->
            errors +=
                ValidationError(
                    path = "${p.currentPath}.$k",
                    message = "Unknown param '$k' (allowed: ${allowed.joinToString()})",
                    severity = ValidationSeverity.ERROR,
                )
        }

        // Validate required/optional params independently so user gets all errors in one run.
        errors += validateParam { p.requireInt("max", min = 0) }
        errors += validateParam { p.optionalBoolean("includeExternal") }

        return errors
    }

    private inline fun validateParam(block: () -> Unit): List<ValidationError> =
        try {
            block()
            emptyList()
        } catch (e: ParamError) {
            listOf(
                ValidationError(
                    path = e.at,
                    message = e.message,
                    severity = ValidationSeverity.ERROR,
                ),
            )
        }
}
