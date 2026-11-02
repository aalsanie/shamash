/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.engine.rules.spi

import io.shamash.asm.core.engine.rules.RuleRegistry

/**
 * Providers are discovered through ServiceLoader (CLI) or the IntelliJ extension point.
 * Keep [id] stable: settings and CLI selectors persist it.
 */
interface AsmRuleRegistryProvider {
    val id: String

    val displayName: String

    fun create(): RuleRegistry
}
