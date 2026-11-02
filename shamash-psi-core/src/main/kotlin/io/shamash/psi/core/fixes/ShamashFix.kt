/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.fixes

interface ShamashFix {
    val id: String
    val title: String

    /**
     * Whether the fix can run now (finding might be stale).
     */
    fun isApplicable(): Boolean

    /**
     * Apply the fix. Implementation must use write actions where needed.
     */
    fun apply()
}
