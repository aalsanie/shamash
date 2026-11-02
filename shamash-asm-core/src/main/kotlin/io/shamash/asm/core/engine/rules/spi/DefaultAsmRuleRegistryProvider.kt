/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.engine.rules.spi

import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.asm.core.engine.rules.RuleRegistry

class DefaultAsmRuleRegistryProvider : AsmRuleRegistryProvider {
    override val id: String = "default"
    override val displayName: String = "Built-in rules"

    override fun create(): RuleRegistry = DefaultRuleRegistry.create()
}
