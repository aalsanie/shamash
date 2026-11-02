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

class ArchAllowedRoleDependenciesSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "arch", name = "allowedRoleDependencies", role = null)

    override fun validate(
        rulePath: String,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val p = Params.of(rule.params, "$rulePath.params")

        val allowedKeys = setOf("allow")
        val unknown = p.unknownKeys(allowedKeys)
        unknown.forEach { k ->
            errors +=
                ValidationError(
                    path = "${p.currentPath}.$k",
                    message = "Unknown param '$k' (allowed: ${allowedKeys.joinToString()})",
                    severity = ValidationSeverity.ERROR,
                )
        }

        val allow: List<String> =
            try {
                p.requireStringList("allow", nonEmpty = true).map { it.trim() }
            } catch (e: ParamError) {
                return errors +
                    ValidationError(
                        path = e.at,
                        message = e.message,
                        severity = ValidationSeverity.ERROR,
                    )
            }

        allow.forEachIndexed { i, edge ->
            val at = "${p.currentPath}.allow[$i]"
            if (edge.isBlank()) {
                errors += ValidationError(at, "must be non-empty", ValidationSeverity.ERROR)
                return@forEachIndexed
            }

            val parts = edge.split("->")
            if (parts.size != 2) {
                errors += ValidationError(at, "must be in form 'fromRole->toRole'", ValidationSeverity.ERROR)
                return@forEachIndexed
            }

            val from = parts[0].trim()
            val to = parts[1].trim()
            if (from.isEmpty() || to.isEmpty()) {
                errors += ValidationError(at, "must be in form 'fromRole->toRole'", ValidationSeverity.ERROR)
                return@forEachIndexed
            }

            if (!config.roles.containsKey(from)) {
                errors += ValidationError(at, "Unknown role '$from' (not defined under roles)", ValidationSeverity.ERROR)
            }
            if (!config.roles.containsKey(to)) {
                errors += ValidationError(at, "Unknown role '$to' (not defined under roles)", ValidationSeverity.ERROR)
            }
        }

        return errors
    }
}
