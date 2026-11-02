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

class GraphMaxEdgeCountSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "graph", name = "maxEdgeCount", role = null)

    override fun validate(
        rulePath: String,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val params = Params.of(rule.params, "$rulePath.params")

        val allowed = setOf("maxEdges")
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

        val maxEdges =
            try {
                params.requireInt("maxEdges", min = 0)
            } catch (e: ParamError) {
                errors +=
                    ValidationError(
                        path = e.at,
                        message = e.message.removePrefix("${e.at} ").trim(),
                        severity = ValidationSeverity.ERROR,
                    )
                null
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
