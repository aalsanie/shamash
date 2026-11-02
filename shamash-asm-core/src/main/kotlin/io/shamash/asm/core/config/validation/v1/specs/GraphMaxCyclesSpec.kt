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
import io.shamash.asm.core.config.validation.v1.GraphThresholdParams
import io.shamash.asm.core.config.validation.v1.RuleSpec

class GraphMaxCyclesSpec : RuleSpec {
    override val key: RuleKey = RuleKey("graph", "maxCycles")

    override fun validate(rulePath: String, rule: RuleDef, config: ShamashAsmConfigV1): List<ValidationError> =
        GraphThresholdParams.errors(rule.params, "$rulePath.params", "maxCycles")
}
