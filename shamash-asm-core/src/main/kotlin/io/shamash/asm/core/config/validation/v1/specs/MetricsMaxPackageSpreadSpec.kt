/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
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
