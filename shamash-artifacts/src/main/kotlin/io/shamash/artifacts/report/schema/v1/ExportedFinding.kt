/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.artifacts.report.schema.v1

import io.shamash.artifacts.contract.FindingSeverity

/**
 * [filePath] must be project-relative with forward slashes; [fingerprint] is SHA-256 hex.
 * Exporters must preserve the report builder's deterministic ordering.
 */
data class ExportedFinding(
    val ruleId: String,
    val message: String,
    val severity: FindingSeverity,
    val filePath: String,
    val classFqn: String?,
    val memberName: String?,
    val fingerprint: String,
)
