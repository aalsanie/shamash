/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.export.analysis

import io.shamash.asm.core.analysis.GraphAnalysisResult
import io.shamash.asm.core.analysis.HotspotsResult
import io.shamash.asm.core.analysis.ScoringResult

internal data class AnalysisGraphsDocument(
    val schemaId: String,
    val schemaVersion: Int,
    val toolName: String,
    val toolVersion: String,
    val generatedAtEpochMillis: Long,
    val projectName: String,
    val graphs: GraphAnalysisResult,
)

internal data class AnalysisHotspotsDocument(
    val schemaId: String,
    val schemaVersion: Int,
    val toolName: String,
    val toolVersion: String,
    val generatedAtEpochMillis: Long,
    val projectName: String,
    val hotspots: HotspotsResult,
)

internal data class AnalysisScoresDocument(
    val schemaId: String,
    val schemaVersion: Int,
    val toolName: String,
    val toolVersion: String,
    val generatedAtEpochMillis: Long,
    val projectName: String,
    val scoring: ScoringResult,
)
