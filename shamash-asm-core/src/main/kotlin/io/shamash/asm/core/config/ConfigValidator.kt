/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.config

import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.validation.v1.ConfigSemanticValidatorV1
import io.shamash.asm.core.engine.rules.RuleRegistry

object ConfigValidator {
    fun validateSemantic(config: ShamashAsmConfigV1): List<ValidationError> = ConfigSemanticValidatorV1.validateSemantic(config)

    fun validateSemantic(
        config: ShamashAsmConfigV1,
        registry: RuleRegistry,
    ): List<ValidationError> = ConfigSemanticValidatorV1.validateSemantic(config, registry)
}
