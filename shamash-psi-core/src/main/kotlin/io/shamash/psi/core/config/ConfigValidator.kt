/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.config

import io.shamash.psi.core.config.schema.v1.model.RuleKey
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.config.validation.v1.ConfigSemanticValidatorV1

/** Compatibility entrypoint delegating semantic checks to the validation layer. */
object ConfigValidator {
    fun validateSemantic(
        config: ShamashPsiConfigV1,
        executableRuleKeys: Set<RuleKey>? = null,
    ): List<ValidationError> =
        ConfigSemanticValidatorV1
            .validateSemantic(config = config, executableRuleKeys = executableRuleKeys)
}
