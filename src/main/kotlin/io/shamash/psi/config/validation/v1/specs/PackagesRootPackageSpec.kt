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
package io.shamash.psi.config.validation.v1.specs

import io.shamash.psi.config.ValidationError
import io.shamash.psi.config.ValidationSeverity
import io.shamash.psi.config.schema.v1.model.RuleDef
import io.shamash.psi.config.schema.v1.model.RuleKey
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.config.validation.v1.RuleSpec
import io.shamash.psi.config.validation.v1.params.ParamError
import io.shamash.psi.config.validation.v1.params.Params

/**
 * Spec for `packages.rootPackage`.
 *
 * Params:
 * - `mode`: optional enum `{ AUTO, EXPLICIT }` (case-insensitive). Defaults to `AUTO`.
 * - `value`: required when `mode=EXPLICIT`. Must look like a Java/Kotlin package name.
 */
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

        // Unknown params (typos)
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
                // value may be present but must be non-blank if provided.
                if (value != null && value.isEmpty()) {
                    err("$rulePath.params.value", "must be non-empty if present")
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
