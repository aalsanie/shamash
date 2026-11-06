/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.config.validation.v1.specs
import io.shamash.artifacts.params.ParamError
import io.shamash.artifacts.params.Params
import io.shamash.psi.core.config.ValidationError
import io.shamash.psi.core.config.ValidationSeverity
import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.schema.v1.model.RuleKey
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.config.validation.v1.RuleSpec

class PackagesRootPackageSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "packages", name = "rootPackage", role = null)

    private val allowedParamKeys: Set<String> = setOf("mode", "value")

    private enum class Mode { AUTO, EXPLICIT }

    // Strict-ish Java/Kotlin package format: segments of [A-Za-z_][A-Za-z0-9_]* separated by dots.
    private val pkgRegex = Regex("^[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)*$")

    override fun validate(
        rulePath: String,
        rule: RuleDef,
        config: ShamashPsiConfigV1,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val p = Params.of(rule.params, "$rulePath.params")

        fun err(
            at: String,
            message: String,
        ) {
            errors += ValidationError(at, message, ValidationSeverity.ERROR)
        }

        val unknown = p.unknownKeys(allowedParamKeys)
        if (unknown.isNotEmpty()) {
            err("$rulePath.params", "Unknown params: ${unknown.sorted().joinToString(", ")}")
        }

        val mode =
            try {
                p.optionalEnum<Mode>("mode") ?: Mode.AUTO
            } catch (e: ParamError) {
                err(e.at, e.message)
                return errors
            }

        val value =
            try {
                p.optionalString("value")?.trim()
            } catch (e: ParamError) {
                err(e.at, e.message)
                return errors
            }

        when (mode) {
            Mode.AUTO -> {
                if (value != null) {
                    err("$rulePath.params.value", "must be omitted when mode=AUTO")
                }
                val projectRoot =
                    config.project.rootPackage
                        ?.value
                        ?.trim()
                        .orEmpty()
                if (!pkgRegex.matches(projectRoot)) {
                    err("$rulePath.params.mode", "AUTO requires a configured project root package")
                }
            }

            Mode.EXPLICIT -> {
                if (value == null) {
                    err("$rulePath.params.value", "is required when mode=EXPLICIT")
                } else if (value.isEmpty()) {
                    err("$rulePath.params.value", "must be non-empty")
                } else if (!pkgRegex.matches(value)) {
                    err("$rulePath.params.value", "must be a valid Java/Kotlin package name")
                }
            }
        }

        return errors
    }
}
