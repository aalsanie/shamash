/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.engine.rules

import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.validation.v1.RuleSpec
import io.shamash.asm.core.config.validation.v1.registry.RuleSpecRegistryV1

/** Keys are canonical [Rule.id] values (`type.name`); the engine expands role-specific instances. */
interface RuleRegistry {
    /** Deterministic iteration order (sorted by id). */
    fun all(): List<Rule>

    /** Lookup by canonical base id (e.g., "arch.forbiddenPackages"). */
    fun byId(ruleId: String): Rule?

    fun resolve(rule: RuleDef): Rule? = byId("${rule.type.trim()}.${rule.name.trim()}")

    /** Custom registries can provide parameter validation alongside executable rules. */
    fun findSpec(
        type: String,
        name: String,
    ): RuleSpec? = RuleSpecRegistryV1.find(type, name)
}
