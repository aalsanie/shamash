/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.engine

import io.shamash.artifacts.contract.Finding

data class EngineResult(
    val findings: List<Finding>,
    val errors: List<EngineError>,
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
}

data class EngineError(
    val fileId: String,
    val phase: String, // e.g. "facts:toUElementOfType", "roleIndex:getOrBuild", "rule:params", "rule:crash", "suppress:exceptions"
    val message: String,
    val throwableClass: String? = null,
    val ruleId: String? = null,
)
