/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.facts

import io.shamash.asm.core.facts.query.FactIndex

data class FactsResult(
    val facts: FactIndex,
    val errors: List<FactsError>,
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
}

data class FactsError(
    val originId: String,
    val phase: String, // e.g. "class:visit", "method:visitInsn", "computeFacts"
    val message: String,
    val throwableClass: String? = null,
)
