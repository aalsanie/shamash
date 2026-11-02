/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.engine

import kotlin.math.max

data class EngineStats(
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val facts: FactsStats = FactsStats(),
    val rules: RulesStats = RulesStats(),
    val findings: FindingsStats = FindingsStats(),
    val baseline: BaselineStats = BaselineStats(),
    val export: ExportStats = ExportStats(),
) {
    val durationMillis: Long get() = max(0L, finishedAtEpochMillis - startedAtEpochMillis)

    data class FactsStats(
        val classes: Int = 0,
        val methods: Int = 0,
        val fields: Int = 0,
        val edges: Int = 0,
        val jars: Int = 0,
    )

    data class RulesStats(
        val configured: Int = 0,
        val executed: Int = 0,
        val skipped: Int = 0,
        val notFound: Int = 0,
        val failed: Int = 0,
    )

    data class FindingsStats(
        val total: Int = 0,
        val bySeverity: Map<String, Int> = emptyMap(),
        val suppressed: Int = 0,
        val baselineMatched: Int = 0,
        val baselineNew: Int = 0,
    )

    data class BaselineStats(
        val enabled: Boolean = false,
        val loaded: Boolean = false,
        val written: Boolean = false,
        val matchedCount: Int = 0,
        val newCount: Int = 0,
        val path: String? = null,
    )

    data class ExportStats(
        val enabled: Boolean = false,
        val written: Boolean = false,
        val outputDir: String? = null,
        val formats: List<String> = emptyList(),
    )

    companion object {
        fun nowStarted(nowEpochMillis: Long): EngineStats =
            EngineStats(
                startedAtEpochMillis = nowEpochMillis,
                finishedAtEpochMillis = nowEpochMillis,
            )
    }
}
