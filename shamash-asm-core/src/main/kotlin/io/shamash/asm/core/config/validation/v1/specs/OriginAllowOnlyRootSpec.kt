/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.config.validation.v1.specs

import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleKey
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.validation.v1.OriginRootsParams
import io.shamash.asm.core.config.validation.v1.RuleSpec

class OriginAllowOnlyRootSpec : RuleSpec {
    override val key = RuleKey("origin", "allowOnlyRoot")

    override fun validate(rulePath: String, rule: RuleDef, config: ShamashAsmConfigV1): List<ValidationError> =
        OriginRootsParams.errors(rule.params, "$rulePath.params")
}
