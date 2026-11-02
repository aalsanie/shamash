/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.engine.rules

import io.shamash.artifacts.contract.Finding
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.facts.query.FactIndex
import java.nio.file.Path

data class RuleExecutionContext(val projectBasePath: Path?)

interface ContextAwareRule : Rule {
    fun evaluate(facts: FactIndex, rule: RuleDef, config: ShamashAsmConfigV1, context: RuleExecutionContext): List<Finding>
}
