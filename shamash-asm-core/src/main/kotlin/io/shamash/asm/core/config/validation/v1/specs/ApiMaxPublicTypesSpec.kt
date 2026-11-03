/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.config.validation.v1.specs

import io.shamash.artifacts.params.ParamError
import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.config.ValidationSeverity
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleKey
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.validation.v1.ApiPublicTypesParams
import io.shamash.asm.core.config.validation.v1.RuleSpec

class ApiMaxPublicTypesSpec : RuleSpec {
    override val key: RuleKey = RuleKey(type = "api", name = "maxPublicTypes", role = null)

    override fun validate(
        rulePath: String,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<ValidationError> {
        val path = "$rulePath.params"
        return try {
            ApiPublicTypesParams.decode(rule.params, path)
            emptyList()
        } catch (e: ParamError) {
            listOf(ValidationError(e.at, e.message, ValidationSeverity.ERROR))
        }
    }
}
