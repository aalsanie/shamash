/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.facts

import io.shamash.psi.core.facts.model.v1.FactsIndex

data class FactsResult(
    val facts: FactsIndex,
    val errors: List<FactsError>,
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
}

data class FactsError(
    val fileId: String,
    val phase: String, // e.g. "uast:visitCallExpression", "psi:visitMethod", "computeFacts"
    val message: String,
    val throwableClass: String? = null,
)
