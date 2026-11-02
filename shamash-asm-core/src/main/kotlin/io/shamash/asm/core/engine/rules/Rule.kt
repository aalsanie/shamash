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

/**
 * Return deterministic findings for identical inputs. The engine applies exceptions and baselines.
 * Malformed parameters should yield no findings; the engine reports unexpected execution errors.
 */
interface Rule {
    /** Canonical base id (`type.name`), without the engine's role suffix. */
    val id: String

    fun evaluate(
        facts: FactIndex,
        rule: RuleDef,
        config: ShamashAsmConfigV1,
    ): List<Finding>
}
