/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.fixes

import io.shamash.artifacts.contract.Finding

interface FixProvider {
    /**
     * Lightweight predicate. Must never throw.
     */
    fun supports(f: Finding): Boolean

    fun fixesFor(
        f: Finding,
        ctx: FixContext,
    ): List<ShamashFix>
}
