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
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class ArchAllowedPackagesSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "arch", name = "allowedPackages", role = null)

    override fun validate(
        rulePath: String,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val p = Params.of(rule.params, "$rulePath.params")

        val allowedKeys = setOf("allowPackages")
        p.unknownKeys(allowedKeys).forEach { k ->
            errors +=
                ValidationError(
                    path = "${p.currentPath}.$k",
                    message = "Unknown param '$k' (allowed: ${allowedKeys.joinToString()})",
                    severity = ValidationSeverity.ERROR,
                )
        }

        val allowPackages: List<String> =
            try {
                p.requireStringList("allowPackages", nonEmpty = true)
            } catch (e: ParamError) {
                return errors +
                    ValidationError(
                        path = e.at,
                        message = e.message,
                        severity = ValidationSeverity.ERROR,
                    )
            }

        allowPackages.forEachIndexed { i, raw ->
            val at = "${p.currentPath}.allowPackages[$i]"
            val rx = raw.trim()

            if (rx.isEmpty()) {
                errors += ValidationError(at, "must be non-empty", ValidationSeverity.ERROR)
                return@forEachIndexed
            }

            try {
                Pattern.compile(rx)
            } catch (e: PatternSyntaxException) {
                errors += ValidationError(at, "Invalid regex: ${e.description}", ValidationSeverity.ERROR)
            }
        }

        rule.roles?.forEachIndexed { i, roleId ->
            val rid = roleId.trim()
            val at = "$rulePath.roles[$i]"
            if (rid.isEmpty()) {
                errors += ValidationError(at, "roleId must be non-empty", ValidationSeverity.ERROR)
            } else if (!config.roles.containsKey(rid)) {
                errors += ValidationError(at, "Unknown role '$rid' (not defined under roles)", ValidationSeverity.ERROR)
            }
        }

        return errors
    }
}
