/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.artifacts.report.schema.v1

data class ToolMetadata(
    val name: String,
    val version: String,
    val schemaVersion: String,
    val generatedAtEpochMillis: Long,
)
